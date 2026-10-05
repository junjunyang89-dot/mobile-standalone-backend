package com.mobile.standalone.service.redis;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 只够查问题用的 RESP2 客户端：一条连接、同步收发、可以批量发。
 * <p>
 * 没用 Lettuce / Jedis：引进来会带上 Spring 的 Redis 自动配置（启动时去连 localhost），
 * 而这里只需要十来条命令；值也要按原始字节拿回来，自己判断是文本、JSON 还是 Java 序列化。
 * <p>
 * 回包类型：简单字符串 → String，整数 → Long，批量字符串 → byte[]（不存在为 null），
 * 数组 → List。顶层的错误回包抛 {@link RespError}；批量发送里某一条出错，那一条位置放 RespError 对象。
 */
public final class RespClient implements AutoCloseable {

    /** 单个值最多收这么大，超了说明调用方没先看长度，直接断开比把内存吃光好 */
    static final int MAX_BULK = 64 * 1024 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    RespClient(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    public static RespClient open(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.setTcpNoDelay(true);
            return new RespClient(s, new BufferedInputStream(s.getInputStream()), s.getOutputStream());
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 连不上时关闭失败无所谓
            }
            throw e;
        }
    }

    /** Redis 回的错误，比如 WRONGTYPE、NOAUTH、MOVED 3999 10.0.0.2:6379 */
    public static final class RespError extends RuntimeException {
        public RespError(String message) {
            super(message);
        }

        /** 集群里 key 不在这个节点上：MOVED slot host:port */
        public String movedTo() {
            String m = getMessage();
            if (m == null || !m.startsWith("MOVED ")) {
                return null;
            }
            String[] parts = m.split(" ");
            return parts.length >= 3 ? parts[2] : null;
        }
    }

    /** 参数可以是 String（按 UTF-8）、byte[]、或数字 */
    public Object call(Object... args) throws IOException {
        write(args);
        out.flush();
        return read(true);
    }

    public List<Object> pipeline(List<Object[]> commands) throws IOException {
        for (Object[] c : commands) {
            write(c);
        }
        out.flush();
        List<Object> replies = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            replies.add(read(false));
        }
        return replies;
    }

    private void write(Object[] args) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(('*' + Integer.toString(args.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (Object a : args) {
            byte[] b = a instanceof byte[] bytes ? bytes : String.valueOf(a).getBytes(StandardCharsets.UTF_8);
            buf.write(('$' + Integer.toString(b.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            buf.write(b);
            buf.write('\r');
            buf.write('\n');
        }
        buf.writeTo(out);
    }

    private Object read(boolean top) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException("Redis 断开了连接");
        }
        String line = readLine();
        switch (type) {
            case '+':
                return line;
            case '-':
                RespError err = new RespError(line);
                if (top) {
                    throw err;
                }
                return err;
            case ':':
                return Long.parseLong(line);
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    return null;
                }
                if (len > MAX_BULK) {
                    throw new IOException("单个值 " + len + " 字节，超过上限");
                }
                byte[] b = in.readNBytes(len);
                if (b.length < len) {
                    throw new EOFException("值没收完 Redis 就断开了");
                }
                readLine();
                return b;
            }
            case '*': {
                int n = Integer.parseInt(line);
                if (n < 0) {
                    return null;
                }
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(read(false));
                }
                return list;
            }
            default:
                throw new IOException("看不懂的回包类型：" + (char) type);
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(32);
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new EOFException("Redis 断开了连接");
            }
            if (prev == '\r' && c == '\n') {
                byte[] b = buf.toByteArray();
                return new String(b, 0, b.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(c);
            prev = c;
        }
    }

    public static String str(Object reply) {
        if (reply == null) {
            return null;
        }
        if (reply instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return String.valueOf(reply);
    }

    public static long num(Object reply) {
        if (reply instanceof Long l) {
            return l;
        }
        String s = str(reply);
        return s == null ? 0 : Long.parseLong(s.trim());
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 关不掉也没什么可做的
        }
    }
}
