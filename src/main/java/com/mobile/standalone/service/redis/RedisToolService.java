package com.mobile.standalone.service.redis;

import com.mobile.standalone.auth.CurrentUser;
import com.mobile.standalone.entity.RedisConnection;
import com.mobile.standalone.util.TestEnvironment;
import com.mobile.standalone.util.RawValueText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 测试工具「Redis 查询」：按 key 模式扫、看单个 key 的值、在测试环境改值/删 key/调过期。
 * <p>
 * 几条硬规矩：
 * <ul>
 *   <li>只用 SCAN，不用 KEYS——KEYS 在大库上会把 Redis 卡住几秒，测试环境也是大家共用的</li>
 *   <li>生产环境的连接只读，写操作在服务端直接拒掉，不靠前端藏按钮</li>
 *   <li>集合类型只取前 {@value #ITEM_LIMIT} 条，大 value 只取前 {@value #STRING_LIMIT} 字节</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisToolService {

    static final int TIMEOUT_MS = 5000;
    static final int ITEM_LIMIT = 300;
    static final int STRING_LIMIT = 512 * 1024;
    static final int ELEMENT_LIMIT = 64 * 1024;
    static final int SCAN_COUNT = 1000;
    static final int MAX_ROUNDS = 60;
    static final long SCAN_BUDGET_MS = 4000;
    static final int DELETE_LIMIT = 200;

    private final RedisConnectionService connections;

    // ==================== 连接 ====================

    public Map<String, Object> probe(RedisConnection c) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        try (Session s = Session.open(c)) {
            s.main.call("PING");
            Map<String, String> server = parseInfo(RespClient.str(s.main.call("INFO", "server")));
            Map<String, String> keyspace = parseInfo(RespClient.str(s.main.call("INFO", "keyspace")));
            m.put("ok", true);
            m.put("version", server.get("redis_version"));
            m.put("mode", s.cluster ? "cluster" : server.getOrDefault("redis_mode", "standalone"));
            m.put("dbSize", RespClient.num(s.main.call("DBSIZE")));
            m.put("keyspace", keyspace);
            m.put("message", "连上了");
        } catch (Exception e) {
            m.put("ok", false);
            m.put("message", explain(e, c));
        }
        m.put("elapsedMs", System.currentTimeMillis() - t0);
        return m;
    }

    public Map<String, Object> probe(String id) {
        return probe(require(id));
    }

    // ==================== 查 ====================

    /**
     * @param cursor 上一页返回的游标；空表示从头扫。格式「节点序号:游标」，集群要挨个主节点扫
     */
    public Map<String, Object> scan(String id, String pattern, String cursor, Integer limit) throws IOException {
        RedisConnection c = require(id);
        String p = pattern == null || pattern.isBlank() ? "*" : pattern.trim();
        int want = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        Map<String, Object> out = new LinkedHashMap<>();
        try (Session s = Session.open(c)) {
            if (!hasGlob(p)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                Map<String, Object> row = s.onKey(p, cl -> {
                    List<Object> r = cl.pipeline(List.of(new Object[]{"TYPE", p}, new Object[]{"PTTL", p}));
                    return keyRow(p, r.get(0), r.get(1));
                });
                if (!"none".equals(row.get("type"))) {
                    rows.add(row);
                }
                out.put("keys", rows);
                out.put("cursor", null);
                out.put("exact", true);
                out.put("cluster", s.cluster);
                return out;
            }

            List<String> nodes = s.cluster ? s.masters() : List.of("");
            int node = 0;
            String cur = "0";
            if (cursor != null && !cursor.isBlank()) {
                int colon = cursor.indexOf(':');
                node = colon > 0 ? Integer.parseInt(cursor.substring(0, colon)) : 0;
                cur = colon > 0 ? cursor.substring(colon + 1) : cursor;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            long deadline = System.currentTimeMillis() + SCAN_BUDGET_MS;
            int rounds = 0;
            boolean done = false;
            while (rows.size() < want && rounds < MAX_ROUNDS && System.currentTimeMillis() < deadline) {
                if (node >= nodes.size()) {
                    done = true;
                    break;
                }
                RespClient cl = nodes.get(node).isEmpty() ? s.main : s.node(nodes.get(node));
                List<?> r = (List<?>) cl.call("SCAN", cur, "MATCH", p, "COUNT", SCAN_COUNT);
                rounds++;
                cur = RespClient.str(r.get(0));
                List<?> keys = (List<?>) r.get(1);
                if (!keys.isEmpty()) {
                    rows.addAll(describe(cl, keys));
                }
                if ("0".equals(cur)) {
                    node++;
                    if (node >= nodes.size()) {
                        done = true;
                        break;
                    }
                }
            }
            out.put("keys", rows);
            out.put("cursor", done ? null : node + ":" + cur);
            out.put("exact", false);
            out.put("cluster", s.cluster);
            out.put("nodes", nodes.size());
            out.put("rounds", rounds);
            return out;
        }
    }

    private static List<Map<String, Object>> describe(RespClient cl, List<?> keys) throws IOException {
        List<Object[]> cmds = new ArrayList<>(keys.size() * 2);
        for (Object k : keys) {
            cmds.add(new Object[]{"TYPE", k});
            cmds.add(new Object[]{"PTTL", k});
        }
        List<Object> r = cl.pipeline(cmds);
        List<Map<String, Object>> rows = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            rows.add(keyRow(RespClient.str(keys.get(i)), r.get(i * 2), r.get(i * 2 + 1)));
        }
        return rows;
    }

    private static Map<String, Object> keyRow(String key, Object type, Object pttl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("type", type instanceof RespClient.RespError ? "unknown" : RespClient.str(type));
        m.put("ttlMs", pttl instanceof Long l ? l : null);
        return m;
    }

    public Map<String, Object> inspect(String id, String key) throws IOException {
        RedisConnection c = require(id);
        String k = requireKey(key);
        try (Session s = Session.open(c)) {
            Map<String, Object> m = s.onKey(k, cl -> inspectOn(cl, k));
            m.put("readOnly", !TestEnvironment.isTest(c.getEnvironment()));
            return m;
        }
    }

    private static Map<String, Object> inspectOn(RespClient cl, String key) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        String type = RespClient.str(cl.call("TYPE", key));
        m.put("type", type);
        if ("none".equals(type)) {
            m.put("exists", false);
            return m;
        }
        m.put("exists", true);
        m.put("ttlMs", RespClient.num(cl.call("PTTL", key)));
        try {
            Object mem = cl.call("MEMORY", "USAGE", key, "SAMPLES", "0");
            m.put("memoryBytes", mem instanceof Long l ? l : null);
        } catch (RespClient.RespError ignored) {
            // 老版本或 ACL 没开 MEMORY 命令，不影响看值
        }
        List<Map<String, Object>> items = new ArrayList<>();
        long size;
        switch (type) {
            case "string" -> {
                size = RespClient.num(cl.call("STRLEN", key));
                byte[] raw = size > STRING_LIMIT
                        ? (byte[]) cl.call("GETRANGE", key, 0, STRING_LIMIT - 1)
                        : (byte[]) cl.call("GET", key);
                m.put("value", RawValueText.of(raw, size, size > STRING_LIMIT));
            }
            case "hash" -> {
                size = RespClient.num(cl.call("HLEN", key));
                for (List<?> pair : scanPairs(cl, "HSCAN", key)) {
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("field", RespClient.str(pair.get(0)));
                    it.put("value", element(pair.get(1)));
                    items.add(it);
                }
            }
            case "list" -> {
                size = RespClient.num(cl.call("LLEN", key));
                List<?> r = (List<?>) cl.call("LRANGE", key, 0, ITEM_LIMIT - 1);
                for (int i = 0; i < r.size(); i++) {
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("index", i);
                    it.put("value", element(r.get(i)));
                    items.add(it);
                }
            }
            case "set" -> {
                size = RespClient.num(cl.call("SCARD", key));
                for (List<?> one : scanPairs(cl, "SSCAN", key)) {
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("value", element(one.get(0)));
                    items.add(it);
                }
            }
            case "zset" -> {
                size = RespClient.num(cl.call("ZCARD", key));
                List<?> r = (List<?>) cl.call("ZRANGE", key, 0, ITEM_LIMIT - 1, "WITHSCORES");
                for (int i = 0; i + 1 < r.size(); i += 2) {
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("value", element(r.get(i)));
                    it.put("score", RespClient.str(r.get(i + 1)));
                    items.add(it);
                }
            }
            case "stream" -> {
                size = RespClient.num(cl.call("XLEN", key));
                List<?> r = (List<?>) cl.call("XREVRANGE", key, "+", "-", "COUNT", 100);
                for (Object e : r) {
                    List<?> entry = (List<?>) e;
                    List<?> fv = (List<?>) entry.get(1);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i + 1 < fv.size(); i += 2) {
                        sb.append(RespClient.str(fv.get(i))).append(": ").append(RespClient.str(fv.get(i + 1))).append('\n');
                    }
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("id", RespClient.str(entry.get(0)));
                    it.put("value", element(sb.toString().trim().getBytes(StandardCharsets.UTF_8)));
                    items.add(it);
                }
            }
            default -> {
                size = 0;
                m.put("unsupported", "这个类型（" + type + "）是模块扩展的，这里看不了");
            }
        }
        m.put("size", size);
        m.put("items", items);
        m.put("itemsTruncated", !"string".equals(type) && items.size() < size);
        return m;
    }

    /** HSCAN 回 field/value 成对，SSCAN 回单个成员；都只取前 ITEM_LIMIT 条 */
    private static List<List<?>> scanPairs(RespClient cl, String cmd, String key) throws IOException {
        int step = "HSCAN".equals(cmd) ? 2 : 1;
        List<List<?>> out = new ArrayList<>();
        String cur = "0";
        int rounds = 0;
        do {
            List<?> r = (List<?>) cl.call(cmd, key, cur, "COUNT", 200);
            cur = RespClient.str(r.get(0));
            List<?> flat = (List<?>) r.get(1);
            for (int i = 0; i + step - 1 < flat.size() && out.size() < ITEM_LIMIT; i += step) {
                out.add(flat.subList(i, i + step));
            }
            rounds++;
        } while (!"0".equals(cur) && out.size() < ITEM_LIMIT && rounds < 50);
        return out;
    }

    private static RawValueText.View element(Object reply) {
        byte[] b = reply instanceof byte[] bytes ? bytes : String.valueOf(reply).getBytes(StandardCharsets.UTF_8);
        return b.length > ELEMENT_LIMIT
                ? RawValueText.of(Arrays.copyOf(b, ELEMENT_LIMIT), b.length, true)
                : RawValueText.of(b);
    }

    // ==================== 改（只限测试环境） ====================

    public Map<String, Object> setString(String id, String key, String value, Long ttlSeconds) throws IOException {
        RedisConnection c = requireWritable(id);
        String k = requireKey(key);
        try (Session s = Session.open(c)) {
            s.onKey(k, cl -> ttlSeconds != null && ttlSeconds > 0
                    ? cl.call("SET", k, value == null ? "" : value, "EX", ttlSeconds)
                    : cl.call("SET", k, value == null ? "" : value));
        }
        audit(c, "SET", k);
        return Map.of("ok", true);
    }

    public Map<String, Object> hashSet(String id, String key, String field, String value) throws IOException {
        RedisConnection c = requireWritable(id);
        String k = requireKey(key);
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("field 不能为空");
        }
        try (Session s = Session.open(c)) {
            s.onKey(k, cl -> cl.call("HSET", k, field, value == null ? "" : value));
        }
        audit(c, "HSET " + field, k);
        return Map.of("ok", true);
    }

    public Map<String, Object> hashDelete(String id, String key, String field) throws IOException {
        RedisConnection c = requireWritable(id);
        String k = requireKey(key);
        long n;
        try (Session s = Session.open(c)) {
            n = RespClient.num(s.onKey(k, cl -> cl.call("HDEL", k, field)));
        }
        audit(c, "HDEL " + field, k);
        return Map.of("ok", true, "deleted", n);
    }

    /** ttlSeconds ≤ 0 表示去掉过期时间（永不过期） */
    public Map<String, Object> expire(String id, String key, Long ttlSeconds) throws IOException {
        RedisConnection c = requireWritable(id);
        String k = requireKey(key);
        long r;
        try (Session s = Session.open(c)) {
            r = RespClient.num(s.onKey(k, cl -> ttlSeconds == null || ttlSeconds <= 0
                    ? cl.call("PERSIST", k)
                    : cl.call("EXPIRE", k, ttlSeconds)));
        }
        audit(c, ttlSeconds == null || ttlSeconds <= 0 ? "PERSIST" : "EXPIRE " + ttlSeconds, k);
        return Map.of("ok", r == 1);
    }

    /** 逐个删：集群里多个 key 不在一个槽，一条 DEL 带多个 key 会被拒 */
    public Map<String, Object> delete(String id, List<String> keys) throws IOException {
        RedisConnection c = requireWritable(id);
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("没选要删的 key");
        }
        if (keys.size() > DELETE_LIMIT) {
            throw new IllegalArgumentException("一次最多删 " + DELETE_LIMIT + " 个");
        }
        long n = 0;
        try (Session s = Session.open(c)) {
            for (String key : keys) {
                String k = requireKey(key);
                n += RespClient.num(s.onKey(k, cl -> cl.call("DEL", k)));
            }
        }
        audit(c, "DEL x" + keys.size(), String.join(",", keys.subList(0, Math.min(5, keys.size()))));
        return Map.of("ok", true, "deleted", n);
    }

    // ==================== 内部 ====================

    private RedisConnection require(String id) {
        RedisConnection c = id == null ? null : connections.get(id);
        if (c == null) {
            throw new IllegalArgumentException("连接不存在，可能被删了，刷新一下");
        }
        return c;
    }

    private RedisConnection requireWritable(String id) {
        RedisConnection c = require(id);
        if (!TestEnvironment.isTest(c.getEnvironment())) {
            throw new IllegalStateException("生产环境的 Redis 只能查不能改");
        }
        return c;
    }

    private static String requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        return key;
    }

    private static void audit(RedisConnection c, String op, String key) {
        log.info("[redis-tool] {} 在 {}({}) 执行 {} key={}", CurrentUser.nameOr("未知"), c.getName(),
                c.getEnvironment(), op, key);
    }

    static boolean hasGlob(String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0 || pattern.indexOf('[') >= 0;
    }

    static Map<String, String> parseInfo(String info) {
        Map<String, String> m = new LinkedHashMap<>();
        if (info == null) {
            return m;
        }
        for (String line : info.split("\r?\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int i = line.indexOf(':');
            if (i > 0) {
                m.put(line.substring(0, i), line.substring(i + 1).trim());
            }
        }
        return m;
    }

    /**
     * CLUSTER NODES 里活着的主节点地址，排好序——游标里存的是序号，两次请求顺序必须一致。
     * 节点地址为空（只配了 hostname 的老版本）时用连接上配的地址。
     */
    static List<String> parseMasters(String nodes, String fallbackHost) {
        TreeSet<String> out = new TreeSet<>();
        if (nodes == null) {
            return new ArrayList<>();
        }
        for (String line : nodes.split("\r?\n")) {
            String[] f = line.trim().split(" ");
            if (f.length < 3) {
                continue;
            }
            String flags = f[2];
            if (!flags.contains("master") || flags.contains("fail") || flags.contains("noaddr")) {
                continue;
            }
            String addr = f[1];
            int at = addr.indexOf('@');
            if (at >= 0) {
                addr = addr.substring(0, at);
            }
            int comma = addr.indexOf(',');
            if (comma >= 0) {
                addr = addr.substring(0, comma);
            }
            if (addr.startsWith(":")) {
                addr = fallbackHost + addr;
            }
            if (!addr.isEmpty() && !addr.endsWith(":0")) {
                out.add(addr);
            }
        }
        return new ArrayList<>(out);
    }

    public String explain(String id, Exception e) {
        RedisConnection c = id == null ? null : connections.get(id);
        return c == null ? String.valueOf(e.getMessage()) : explain(e, c);
    }

    /** 报给人看的原因：连不上要说清是网络、密码还是库号的问题 */
    static String explain(Exception e, RedisConnection c) {
        String where = c.getHost() + ":" + c.getPort();
        if (e instanceof SocketTimeoutException) {
            return "连 " + where + " 超时：多半是网络不通或没加白名单，先确认这台服务器能访问到 Redis";
        }
        if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
            return "连不上 " + where + "：端口没开或地址不对";
        }
        if (e instanceof UnknownHostException) {
            return "解析不了地址 " + c.getHost();
        }
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String low = msg.toLowerCase(Locale.ROOT);
        if (low.startsWith("noauth")) {
            return "这个 Redis 要密码，连接里没配";
        }
        if (low.startsWith("wrongpass") || low.contains("invalid password") || low.contains("invalid username-password")) {
            return "用户名或密码不对";
        }
        if (low.contains("without any password configured")) {
            return "这个 Redis 没设密码，把连接里的密码清掉";
        }
        if (low.contains("db index is out of range")) {
            return "库号 " + c.getDbIndex() + " 超出范围";
        }
        if (low.contains("select is not allowed in cluster mode")) {
            return "集群模式只有 0 号库，把库号改成 0";
        }
        return msg;
    }

    @FunctionalInterface
    interface KeyOp<T> {
        T run(RespClient c) throws IOException;
    }

    /**
     * 一次请求用的连接。集群里 key 不在当前节点会回 MOVED，按它给的地址再连一次重试；
     * 扫描时要连每个主节点。用完全部关掉——这是查问题的工具，不值得为它常驻连接池。
     */
    static final class Session implements AutoCloseable {
        final RedisConnection conn;
        final RespClient main;
        final Map<String, RespClient> nodes = new LinkedHashMap<>();
        final boolean cluster;

        private Session(RedisConnection conn, RespClient main, boolean cluster) {
            this.conn = conn;
            this.main = main;
            this.cluster = cluster;
        }

        static Session open(RedisConnection c) throws IOException {
            RespClient cl = connect(c, c.getHost(), c.getPort() == null ? 6379 : c.getPort());
            try {
                boolean cluster = false;
                try {
                    String info = RespClient.str(cl.call("INFO", "cluster"));
                    cluster = "1".equals(parseInfo(info).get("cluster_enabled"));
                } catch (RespClient.RespError ignored) {
                    // 有的云 Redis 代理禁了 INFO 的部分段，当单机用
                }
                if (!cluster && c.getDbIndex() != null && c.getDbIndex() > 0) {
                    cl.call("SELECT", c.getDbIndex());
                }
                return new Session(c, cl, cluster);
            } catch (IOException | RuntimeException e) {
                cl.close();
                throw e;
            }
        }

        private static RespClient connect(RedisConnection c, String host, int port) throws IOException {
            RespClient cl = RespClient.open(host, port, TIMEOUT_MS);
            try {
                if (c.getPassword() != null && !c.getPassword().isEmpty()) {
                    if (c.getUsername() != null && !c.getUsername().isBlank()) {
                        cl.call("AUTH", c.getUsername(), c.getPassword());
                    } else {
                        cl.call("AUTH", c.getPassword());
                    }
                }
                return cl;
            } catch (IOException | RuntimeException e) {
                cl.close();
                throw e;
            }
        }

        RespClient node(String addr) throws IOException {
            RespClient cl = nodes.get(addr);
            if (cl == null) {
                int colon = addr.lastIndexOf(':');
                cl = connect(conn, addr.substring(0, colon), Integer.parseInt(addr.substring(colon + 1)));
                nodes.put(addr, cl);
            }
            return cl;
        }

        List<String> masters() throws IOException {
            List<String> m = parseMasters(RespClient.str(main.call("CLUSTER", "NODES")), conn.getHost());
            if (m.isEmpty()) {
                throw new IOException("集群里没找到可用的主节点");
            }
            return m;
        }

        <T> T onKey(String key, KeyOp<T> op) throws IOException {
            try {
                return op.run(main);
            } catch (RespClient.RespError e) {
                String moved = e.movedTo();
                if (moved == null) {
                    throw e;
                }
                return op.run(node(moved));
            }
        }

        @Override
        public void close() {
            main.close();
            nodes.values().forEach(RespClient::close);
        }
    }
}
