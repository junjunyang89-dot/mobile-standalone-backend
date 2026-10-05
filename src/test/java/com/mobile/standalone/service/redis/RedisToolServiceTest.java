package com.mobile.standalone.service.redis;

import com.mobile.standalone.entity.RedisConnection;
import com.mobile.standalone.util.RawValueText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 起一个只会十来条命令的假 Redis，把扫描分页、看值、改值、生产只读整条链路跑一遍。
 */
class RedisToolServiceTest {

    private FakeRedis redis;
    private RedisToolService tool;

    @BeforeEach
    void setUp() throws IOException {
        redis = new FakeRedis();
        redis.strings.put("user:info:1", "{\"id\":1,\"name\":\"a\"}");
        redis.strings.put("user:info:2", "plain");
        redis.strings.put("user:info:3", "x");
        redis.strings.put("order:9", "y");
        redis.hashes.put("user:hash:1", new TreeMap<>(Map.of("nick", "zs", "age", "18")));

        RedisConnectionService conns = mock(RedisConnectionService.class);
        when(conns.get("test")).thenReturn(conn("TEST"));
        when(conns.get("prod")).thenReturn(conn("PROD"));
        tool = new RedisToolService(conns);
    }

    @AfterEach
    void tearDown() throws IOException {
        redis.close();
    }

    private RedisConnection conn(String env) {
        RedisConnection c = new RedisConnection();
        c.setId(env.toLowerCase());
        c.setName(env);
        c.setEnvironment(env);
        c.setHost("127.0.0.1");
        c.setPort(redis.port());
        c.setPassword("pw");
        c.setDbIndex(0);
        return c;
    }

    @Test
    @DisplayName("按前缀扫：假服务每次只回 2 个，要翻页把同前缀的都找齐，带上类型和 TTL")
    @SuppressWarnings("unchecked")
    void 扫描() throws Exception {
        Map<String, Object> r = tool.scan("test", "user:info:*", null, 100);
        List<Map<String, Object>> keys = (List<Map<String, Object>>) r.get("keys");
        assertThat(keys).extracting(k -> k.get("key")).containsExactlyInAnyOrder("user:info:1", "user:info:2", "user:info:3");
        assertThat(keys).allSatisfy(k -> {
            assertThat(k.get("type")).isEqualTo("string");
            assertThat(k.get("ttlMs")).isEqualTo(-1L);
        });
        assertThat(r.get("cursor")).isNull();
        assertThat(redis.authed).isTrue();
    }

    @Test
    @DisplayName("一页够数就停，给出下一页游标，接着扫不重复")
    @SuppressWarnings("unchecked")
    void 分页() throws Exception {
        Map<String, Object> p1 = tool.scan("test", "*", null, 2);
        assertThat(p1.get("cursor")).isNotNull();
        Map<String, Object> p2 = tool.scan("test", "*", (String) p1.get("cursor"), 100);
        List<Object> all = new ArrayList<>();
        ((List<Map<String, Object>>) p1.get("keys")).forEach(k -> all.add(k.get("key")));
        ((List<Map<String, Object>>) p2.get("keys")).forEach(k -> all.add(k.get("key")));
        assertThat(all).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("精确 key 不走 SCAN；不存在的 key 返回空列表")
    @SuppressWarnings("unchecked")
    void 精确查() throws Exception {
        Map<String, Object> r = tool.scan("test", "order:9", null, null);
        assertThat((List<Map<String, Object>>) r.get("keys")).extracting(k -> k.get("key")).containsExactly("order:9");
        assertThat(redis.scanCalls).isZero();
        assertThat((List<?>) tool.scan("test", "order:404", null, null).get("keys")).isEmpty();
    }

    @Test
    @DisplayName("看值：string 识别 JSON，hash 列出 field；生产连接标成只读")
    @SuppressWarnings("unchecked")
    void 看值() throws Exception {
        Map<String, Object> s = tool.inspect("test", "user:info:1");
        assertThat(s.get("type")).isEqualTo("string");
        assertThat(((RawValueText.View) s.get("value")).format()).isEqualTo(RawValueText.JSON);
        assertThat(s.get("readOnly")).isEqualTo(false);

        Map<String, Object> h = tool.inspect("prod", "user:hash:1");
        assertThat(h.get("size")).isEqualTo(2L);
        assertThat((List<Map<String, Object>>) h.get("items")).extracting(i -> i.get("field")).containsExactly("age", "nick");
        assertThat(h.get("readOnly")).isEqualTo(true);

        assertThat(tool.inspect("test", "nope").get("exists")).isEqualTo(false);
    }

    @Test
    @DisplayName("测试环境能改、能删；生产环境一律拒")
    void 写操作() throws Exception {
        tool.setString("test", "k:new", "v1", 60L);
        assertThat(redis.strings.get("k:new")).isEqualTo("v1");
        assertThat(redis.lastSet).containsSubsequence("EX", "60");

        tool.hashSet("test", "user:hash:1", "nick", "ls");
        assertThat(redis.hashes.get("user:hash:1")).containsEntry("nick", "ls");

        assertThat(tool.delete("test", List.of("k:new", "order:9")).get("deleted")).isEqualTo(2L);
        assertThat(redis.strings).doesNotContainKeys("k:new", "order:9");

        assertThatThrownBy(() -> tool.setString("prod", "k", "v", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只能查不能改");
        assertThatThrownBy(() -> tool.delete("prod", List.of("user:info:1"))).isInstanceOf(IllegalStateException.class);
        assertThat(redis.strings).containsKey("user:info:1");
    }

    @Test
    @DisplayName("连通性检查回版本号；密码错时说人话")
    void 检查连接() {
        Map<String, Object> ok = tool.probe("test");
        assertThat(ok.get("ok")).isEqualTo(true);
        assertThat(ok.get("version")).isEqualTo("7.0.0");

        RedisConnection bad = conn("TEST");
        bad.setPassword("wrong");
        Map<String, Object> no = tool.probe(bad);
        assertThat(no.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(no.get("message"))).contains("密码不对");
    }

    /** 每次 SCAN 只回 2 个 key，逼调用方翻页 */
    static final class FakeRedis implements AutoCloseable {
        final Map<String, String> strings = new ConcurrentHashMap<>();
        final Map<String, TreeMap<String, String>> hashes = new ConcurrentHashMap<>();
        volatile boolean authed;
        volatile int scanCalls;
        volatile List<String> lastSet = List.of();
        private final ServerSocket server;

        FakeRedis() throws IOException {
            server = new ServerSocket(0);
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        Thread h = new Thread(() -> serve(s));
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve(Socket s) {
            try (s) {
                InputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                while (true) {
                    List<String> cmd = readCommand(in);
                    if (cmd == null) {
                        return;
                    }
                    out.write(handle(cmd).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException ignored) {
                // 客户端关了
            }
        }

        private String handle(List<String> c) {
            String op = c.get(0).toUpperCase();
            String key = c.size() > 1 ? c.get(1) : null;
            switch (op) {
                case "AUTH":
                    if (!"pw".equals(c.get(c.size() - 1))) {
                        return "-WRONGPASS invalid username-password pair\r\n";
                    }
                    authed = true;
                    return "+OK\r\n";
                case "PING":
                    return "+PONG\r\n";
                case "INFO":
                    return bulk("cluster".equals(key) ? "# Cluster\r\ncluster_enabled:0\r\n"
                            : "# Server\r\nredis_version:7.0.0\r\nredis_mode:standalone\r\n");
                case "DBSIZE":
                    return ":" + allKeys().size() + "\r\n";
                case "SCAN": {
                    scanCalls++;
                    int cur = Integer.parseInt(key);
                    String glob = c.get(3);
                    List<String> all = allKeys();
                    int end = Math.min(cur + 2, all.size());
                    StringBuilder sb = new StringBuilder();
                    List<String> hit = new ArrayList<>();
                    for (int i = cur; i < end; i++) {
                        if (all.get(i).matches(glob.replace("*", ".*").replace("?", "."))) {
                            hit.add(all.get(i));
                        }
                    }
                    sb.append("*2\r\n").append(bulk(String.valueOf(end >= all.size() ? 0 : end)));
                    sb.append("*").append(hit.size()).append("\r\n");
                    hit.forEach(h -> sb.append(bulk(h)));
                    return sb.toString();
                }
                case "TYPE":
                    return "+" + (strings.containsKey(key) ? "string" : hashes.containsKey(key) ? "hash" : "none") + "\r\n";
                case "PTTL":
                    return strings.containsKey(key) || hashes.containsKey(key) ? ":-1\r\n" : ":-2\r\n";
                case "MEMORY":
                    return "-ERR unknown command 'MEMORY'\r\n";
                case "STRLEN":
                    return ":" + strings.getOrDefault(key, "").getBytes(StandardCharsets.UTF_8).length + "\r\n";
                case "GET":
                    return strings.containsKey(key) ? bulk(strings.get(key)) : "$-1\r\n";
                case "HLEN":
                    return ":" + hashes.getOrDefault(key, new TreeMap<>()).size() + "\r\n";
                case "HSCAN": {
                    TreeMap<String, String> h = hashes.getOrDefault(key, new TreeMap<>());
                    StringBuilder sb = new StringBuilder("*2\r\n").append(bulk("0")).append("*").append(h.size() * 2).append("\r\n");
                    h.forEach((f, v) -> sb.append(bulk(f)).append(bulk(v)));
                    return sb.toString();
                }
                case "SET":
                    strings.put(key, c.get(2));
                    lastSet = c;
                    return "+OK\r\n";
                case "HSET":
                    hashes.computeIfAbsent(key, k -> new TreeMap<>()).put(c.get(2), c.get(3));
                    return ":1\r\n";
                case "DEL":
                    return ":" + (strings.remove(key) != null || hashes.remove(key) != null ? 1 : 0) + "\r\n";
                default:
                    return "-ERR unknown command '" + op + "'\r\n";
            }
        }

        private List<String> allKeys() {
            List<String> all = new ArrayList<>(strings.keySet());
            all.addAll(hashes.keySet());
            all.sort(String::compareTo);
            return all;
        }

        private static String bulk(String s) {
            return "$" + s.getBytes(StandardCharsets.UTF_8).length + "\r\n" + s + "\r\n";
        }

        private static List<String> readCommand(InputStream in) throws IOException {
            String head = line(in);
            if (head == null) {
                return null;
            }
            int n = Integer.parseInt(head.substring(1));
            List<String> args = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int len = Integer.parseInt(line(in).substring(1));
                byte[] b = in.readNBytes(len);
                line(in);
                args.add(new String(b, StandardCharsets.UTF_8));
            }
            return args;
        }

        private static String line(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int prev = -1;
            while (true) {
                int c = in.read();
                if (c < 0) {
                    return null;
                }
                if (prev == '\r' && c == '\n') {
                    byte[] b = buf.toByteArray();
                    return new String(b, 0, b.length - 1, StandardCharsets.UTF_8);
                }
                buf.write(c);
                prev = c;
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
