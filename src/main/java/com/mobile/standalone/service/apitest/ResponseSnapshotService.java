package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.capture.CaptureStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 接口响应基线：从一场抓包提升成基线，之后拿新抓的一场和它比结构。
 * <p>
 * 换皮打不穿的回归基准。前端每期全改，但接口返回结构基本不变，所以比的是
 * 「接口 → 响应结构」，跟页面长什么样、选择器叫什么完全无关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseSnapshotService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SAMPLE_MAX = 20000;

    /**
     * 路径里要剥掉的易变参数。签名协议的 a/r/t/s 每次都不一样，
     * 不剥的话同一个接口在基线里会出现无数份、对不上号。
     */
    private static final java.util.Set<String> VOLATILE_QUERY =
            java.util.Set.of("a", "r", "t", "s", "_", "timestamp", "nonce");

    private final CaptureStore captureStore;
    private final JdbcTemplate jdbcTemplate;

    /** 一个接口的响应记录，从抓包流水里抽出来的 */
    public record Endpoint(String method, String path, Integer status, String body) {
    }

    // ---------------------------------------------------------------- 建基线

    /**
     * 把一场抓包提升成基线。
     * <p>
     * 同一个接口在一场抓包里可能出现多次（重试、轮询）——取<b>最后一次成功的</b>，
     * 因为页面最终呈现的是它。
     */
    @Transactional
    public Map<String, Object> promote(String sessionId, String name, String requirementNo, String note) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("基线名称不能为空");
        }
        Map<String, Endpoint> endpoints = readEndpoints(sessionId);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("这场抓包没有可用的响应（会话不存在或一条都没抓到）");
        }
        Map<String, Object> session = captureStore.get(sessionId);
        String project = session == null ? null : str(session.get("project"));

        String id = "rs-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO response_snapshots (id, name, requirement_no, project, source_session, entry_count, note)
                VALUES (?,?,?,?,?,?,?)
                """, id, name.trim(), blank(requirementNo), project, sessionId, endpoints.size(), blank(note));

        for (Endpoint e : endpoints.values()) {
            jdbcTemplate.update("""
                    INSERT INTO response_snapshot_entries
                        (snapshot_id, http_method, path, status, shape, sample_body)
                    VALUES (?,?,?,?,?,?)
                    """, id, e.method(), e.path(), e.status(),
                    ResponseShape.of(e.body()), truncate(e.body()));
        }
        log.info("[snapshot] 基线 {} 建成，{} 个接口，来自会话 {}", name, endpoints.size(), sessionId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name.trim());
        out.put("entryCount", endpoints.size());
        return out;
    }

    // ---------------------------------------------------------------- 对比

    /**
     * 拿一场新抓包和某条基线比结构。
     *
     * @return 每个接口一条结果：一致 / 结构漂移（含逐字段差异）/ 基线有但这次没抓到 /
     *         这次多出来的接口
     */
    public Map<String, Object> diffAgainst(String snapshotId, String sessionId) {
        List<Map<String, Object>> baseline = jdbcTemplate.queryForList(
                "SELECT http_method, path, status, sample_body FROM response_snapshot_entries WHERE snapshot_id = ?",
                snapshotId);
        if (baseline.isEmpty()) {
            throw new IllegalArgumentException("基线不存在或为空: " + snapshotId);
        }
        Map<String, Map<String, Object>> baseByKey = new LinkedHashMap<>();
        for (Map<String, Object> r : baseline) {
            baseByKey.put(key(str(r.get("http_method")), str(r.get("path"))), r);
        }
        Map<String, Endpoint> current = readEndpoints(sessionId);

        List<Map<String, Object>> drifted = new ArrayList<>();
        List<Map<String, Object>> consistent = new ArrayList<>();
        List<String> onlyInBaseline = new ArrayList<>();
        List<String> onlyInCurrent = new ArrayList<>();

        for (Map.Entry<String, Endpoint> e : current.entrySet()) {
            Map<String, Object> base = baseByKey.remove(e.getKey());
            if (base == null) {
                onlyInCurrent.add(e.getValue().method() + " " + e.getValue().path());
                continue;
            }
            List<ResponseShape.Diff> diffs = ResponseShape.diff(
                    str(base.get("sample_body")), e.getValue().body());
            if (diffs.isEmpty()) {
                consistent.add(Map.of("method", e.getValue().method(), "path", e.getValue().path()));
            } else {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("method", e.getValue().method());
                d.put("path", e.getValue().path());
                d.put("diffs", diffs.stream().map(x -> Map.of(
                        "path", x.path(), "kind", x.kind(),
                        "baseline", x.baseline(), "current", x.current())).toList());
                drifted.add(d);
            }
        }
        // 基线里剩下的都是这次没抓到的
        baseByKey.forEach((k, v) -> onlyInBaseline.add(str(v.get("http_method")) + " " + str(v.get("path"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", snapshotId);
        out.put("sessionId", sessionId);
        out.put("driftedCount", drifted.size());
        out.put("consistentCount", consistent.size());
        out.put("drifted", drifted);
        out.put("consistent", consistent);
        // 基线有、这次没抓到：可能漏测，也可能这次流程没走到，人来判断
        out.put("onlyInBaseline", onlyInBaseline);
        // 这次多出来的：新接口，或走到了基线没覆盖的路径
        out.put("onlyInCurrent", onlyInCurrent);
        out.put("hint", buildHint(drifted.size(), onlyInBaseline.size()));
        return out;
    }

    static String buildHint(int drifted, int missing) {
        if (drifted == 0 && missing == 0) {
            return "所有接口结构与基线一致，没有回归";
        }
        StringBuilder sb = new StringBuilder();
        if (drifted > 0) {
            sb.append(drifted).append(" 个接口结构变了——少字段/改类型/数组变长短都在这里，逐条看 diffs。");
        }
        if (missing > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(missing).append(" 个基线里的接口这次没抓到，确认是漏测还是这次流程没走到。");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 列表

    public List<Map<String, Object>> list(String requirementNo) {
        if (requirementNo != null && !requirementNo.isBlank()) {
            return jdbcTemplate.queryForList(
                    "SELECT * FROM response_snapshots WHERE requirement_no = ? ORDER BY created_at DESC",
                    requirementNo.trim());
        }
        return jdbcTemplate.queryForList("SELECT * FROM response_snapshots ORDER BY created_at DESC LIMIT 100");
    }

    @Transactional
    public boolean delete(String snapshotId) {
        return jdbcTemplate.update("DELETE FROM response_snapshots WHERE id = ?", snapshotId) > 0;
    }

    // ---------------------------------------------------------------- 读抓包

    /**
     * 从一场抓包的流水里抽出每个接口的响应。key = method + 规约后的 path，
     * 同一接口出现多次时后者覆盖前者（取最后一次）。
     */
    private Map<String, Endpoint> readEndpoints(String sessionId) {
        Path dir = captureStore.dirOf(sessionId);
        Path flows = dir.resolve("flows.jsonl");
        Path details = Path.of(flows.toAbsolutePath() + ".details");
        Map<String, Endpoint> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(flows)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(flows, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode row = JSON.readTree(line);
                String method = row.path("method").asText("");
                String path = row.path("path").asText("");
                String query = row.path("query").asText("");
                int status = row.path("status").asInt(0);
                if (method.isEmpty() || path.isEmpty()) continue;
                // 只快照成功的 JSON 接口：错误响应结构本就不稳定，纳进基线只会制造噪音
                if (status < 200 || status >= 300) continue;

                String body = readBody(details, row.path("seq").asInt(-1));
                if (body == null) continue;

                String stablePath = normalizePath(path, query);
                out.put(key(method, stablePath), new Endpoint(method, stablePath, status, body));
            }
        } catch (Exception e) {
            log.warn("[snapshot] 读抓包会话 {} 失败: {}", sessionId, e.getMessage());
        }
        return out;
    }

    private String readBody(Path detailsDir, int seq) {
        if (seq < 0) return null;
        Path f = detailsDir.resolve(seq + ".json");
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonNode detail = JSON.readTree(Files.readString(f, StandardCharsets.UTF_8));
            JsonNode body = detail.get("respBody");
            return body == null || body.isNull() ? null : body.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 规约路径：把易变的签名/时间戳参数剥掉，其余按 key 排序拼回去。
     * <p>
     * {@code /api/userTask/listByCategory?a=30000&r=123456&t=...&s=...&category=task_1}
     * → {@code /api/userTask/listByCategory?category=task_1}
     * 这样同一个接口每次都归到同一个 key 上，才对得上号。
     */
    static String normalizePath(String path, String query) {
        if (query == null || query.isBlank()) {
            return path;
        }
        Map<String, String> kept = new TreeMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (VOLATILE_QUERY.contains(k)) continue;
            kept.put(k, eq < 0 ? "" : pair.substring(eq + 1));
        }
        if (kept.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : kept.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String key(String method, String path) {
        return method.toUpperCase() + " " + path;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= SAMPLE_MAX ? s : s.substring(0, SAMPLE_MAX);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
