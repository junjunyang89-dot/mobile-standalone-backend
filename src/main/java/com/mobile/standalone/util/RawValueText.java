package com.mobile.standalone.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 把 Redis 值、Kafka 消息这类原始字节翻成人能看的样子。
 * <p>
 * 缓存里的值常见四种：纯文本、JSON、Java 序列化（RedisTemplate 默认的 JdkSerializationRedisSerializer）、
 * gzip 压缩过的 JSON。按 UTF-8 硬转的话后两种是一屏乱码，人会以为缓存坏了。
 * <p>
 * JSON 在这边格式化而不是交给前端 JSON.parse：长整型 id 过了浏览器会丢精度，
 * 看到的 id 和库里对不上，查问题时反而误导。
 */
public final class RawValueText {

    public static final String TEXT = "TEXT";
    public static final String JSON = "JSON";
    public static final String JAVA_SERIALIZED = "JAVA_SERIALIZED";
    public static final String BINARY = "BINARY";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private static final int GZIP_LIMIT = 4 * 1024 * 1024;
    private static final int HEX_PREVIEW = 512;

    private RawValueText() {
    }

    /**
     * @param format    TEXT / JSON / JAVA_SERIALIZED / BINARY
     * @param text      给人看的内容；JSON 是格式化后的，二进制是十六进制预览
     * @param bytes     原始长度（字节）
     * @param truncated 只拿了前面一截
     * @param gzip      值是 gzip 压缩过的，text 是解压后的内容
     * @param editable  能不能当文本改回去——二进制、序列化、压缩过的改了就写坏了
     */
    public record View(String format, String text, long bytes, boolean truncated, boolean gzip, boolean editable) {
    }

    public static View of(byte[] raw) {
        return of(raw, raw == null ? 0 : raw.length, false);
    }

    /**
     * @param totalBytes 值的真实长度；raw 可能只是前一截
     */
    public static View of(byte[] raw, long totalBytes, boolean truncated) {
        if (raw == null) {
            return null;
        }
        if (!truncated && isGzip(raw)) {
            byte[] plain = gunzip(raw);
            if (plain != null) {
                View inner = of(plain, plain.length, plain.length >= GZIP_LIMIT);
                return new View(inner.format(), inner.text(), totalBytes, inner.truncated(), true, false);
            }
        }
        if (isJavaSerialized(raw)) {
            return new View(JAVA_SERIALIZED, javaStrings(raw), totalBytes, truncated, false, false);
        }
        String s = strictUtf8(raw, truncated);
        if (s == null || looksBinary(s)) {
            return new View(BINARY, hex(raw), totalBytes, truncated || raw.length > HEX_PREVIEW, false, false);
        }
        if (!truncated) {
            String pretty = prettyJson(s);
            if (pretty != null) {
                return new View(JSON, pretty, totalBytes, false, false, true);
            }
        }
        return new View(TEXT, s, totalBytes, truncated, false, !truncated);
    }

    static boolean isGzip(byte[] b) {
        return b.length > 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b;
    }

    static boolean isJavaSerialized(byte[] b) {
        return b.length > 2 && (b[0] & 0xff) == 0xac && (b[1] & 0xff) == 0xed;
    }

    private static byte[] gunzip(byte[] b) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            return in.readNBytes(GZIP_LIMIT);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 截断的值末尾可能正好切在一个多字节字符中间，按严格解码会被整段判成二进制，
     * 所以截断时容忍末尾最多 3 个字节解不出来。
     */
    static String strictUtf8(byte[] b, boolean truncated) {
        int len = b.length;
        for (int cut = 0; cut <= (truncated ? Math.min(3, len) : 0); cut++) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(b, 0, len - cut))
                        .toString();
            } catch (CharacterCodingException ignored) {
                // 再少一个字节试试
            }
        }
        return null;
    }

    /** 控制字符（除了换行、制表）占比超过 5% 就不当文本了 */
    static boolean looksBinary(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int ctrl = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                ctrl++;
            }
        }
        return ctrl * 20 > s.length();
    }

    static String prettyJson(String s) {
        String t = s.trim();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(t);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Java 序列化的对象没法不带类就还原，但类名和字符串字段都是明文躺在里面的——
     * 把连续的可打印片段抠出来，人基本能认出是哪个对象、关键字段是什么。
     */
    static String javaStrings(byte[] b) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < b.length) {
            int start = i;
            while (i < b.length && printable(b[i])) {
                i++;
            }
            if (i - start >= 3) {
                String run = new String(b, start, i - start, StandardCharsets.UTF_8).replace("\uFFFD", "");
                if (run.length() >= 3) {
                    parts.add(run);
                }
            }
            if (i == start) {
                i++;
            }
        }
        StringBuilder sb = new StringBuilder("Java 序列化对象，以下是里面能认出的明文片段：\n");
        for (String p : parts) {
            sb.append(p).append('\n');
        }
        return sb.toString();
    }

    private static boolean printable(byte x) {
        int c = x & 0xff;
        return (c >= 0x20 && c < 0x7f) || c >= 0x80;
    }

    static String hex(byte[] b) {
        int n = Math.min(b.length, HEX_PREVIEW);
        StringBuilder sb = new StringBuilder(n * 3 + n / 16);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", b[i] & 0xff));
            sb.append((i + 1) % 16 == 0 ? '\n' : ' ');
        }
        return sb.toString().trim();
    }
}
