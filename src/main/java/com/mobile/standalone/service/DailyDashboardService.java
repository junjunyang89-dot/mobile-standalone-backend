package com.mobile.standalone.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 首页按天汇总：选一天，看 UI 自动化、接口自动化、性能测试各跑了多少用例，以及当天新增了多少用例。
 * <p>
 * 和 {@link WorkbenchStatsService} 一样每条查询都是一次跨网往返，所以：同类数字一条 SQL 问完，
 * 不在循环里查库，结果按日期缓存几十秒。
 * <p>
 * 口径：
 * <ul>
 *   <li>UI 回归批次按 {@code COALESCE(finished_at, created_at)} 归日，和原来的周历一致；
 *       用例数是批次里每台设备上每个流程的结果（同一流程跑两台设备算两条）。</li>
 *   <li>UI 单独执行只算 {@code run_track = AUTOMATION} 的流程任务：REGRESSION 的已经算在批次里，
 *       DEBUG 是试跑，不进统计。</li>
 *   <li>接口按 {@code api_test_runs.trigger_type} 分定时 / 手动，按 environment 分环境。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyDashboardService {

    private static final long CACHE_MS = 20_000;
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    static final List<String> PERF_TYPES = List.of(
            "PERFORMANCE_BASELINE_TEST", "SCENARIO_PERFORMANCE_TEST", "PERFORMANCE_QUALITY_TEST",
            "PERFORMANCE_MONITORING_TEST", "POWER_CONSUMPTION_BASELINE_TEST", "POWER_CONSUMPTION_QUALITY_TEST");

    private static final String PERF_IN = "('" + String.join("','", PERF_TYPES) + "')";

    /** 单独执行的流程任务，环境写在 config JSON 里 */
    private static final String SINGLE_ENV = "CASE WHEN REPLACE(t.config, ' ', '') LIKE '%\"environment\":\"TEST\"%' THEN 'TEST'"
            + " WHEN REPLACE(t.config, ' ', '') LIKE '%\"environment\":\"PROD\"%' THEN 'PROD' ELSE '' END";

    private final JdbcTemplate jdbcTemplate;

    private final Map<LocalDate, Object[]> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public Map<String, Object> day(LocalDate date) {
        LocalDate d = date == null ? LocalDate.now() : date;
        Object[] hit = cache.get(d);
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < CACHE_MS) {
            return (Map<String, Object>) hit[1];
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = compute(d);
        long cost = System.currentTimeMillis() - t0;
        if (cost > 1500) {
            log.warn("[daily] {} 统计耗时 {}ms", d, cost);
        }
        if (cache.size() > 60) {
            cache.clear();
        }
        cache.put(d, new Object[]{System.currentTimeMillis(), out});
        return out;
    }

    private Map<String, Object> compute(LocalDate d) {
        Timestamp from = Timestamp.valueOf(d.atStartOfDay());
        Timestamp to = Timestamp.valueOf(d.plusDays(1).atStartOfDay());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", d.toString());
        out.put("week", week(d));
        out.put("ui", ui(from, to));
        out.put("api", api(from, to));
        out.put("perf", perf(from, to));
        out.put("added", added(from, to));
        return out;
    }

    // ---------- 周历 ----------

    private List<Map<String, Object>> week(LocalDate d) {
        LocalDate monday = d.with(DayOfWeek.MONDAY);
        Timestamp from = Timestamp.valueOf(monday.atStartOfDay());
        Timestamp to = Timestamp.valueOf(monday.plusDays(7).atStartOfDay());

        Map<String, long[]> ui = perDay("""
                SELECT DATE(COALESCE(r.finished_at, r.created_at)) AS d,
                       SUM(s.status IN ('SUCCESS','FAILED','SKIPPED')) AS n, SUM(s.status = 'FAILED') AS f
                  FROM online_regression_runs r
                  JOIN online_regression_jobs j ON j.run_id = r.id
                  JOIN online_regression_steps s ON s.job_id = j.id
                 WHERE COALESCE(r.finished_at, r.created_at) >= ? AND COALESCE(r.finished_at, r.created_at) < ?
                 GROUP BY d
                """, from, to);
        Map<String, long[]> single = perDay("""
                SELECT DATE(t.created_at) AS d, COUNT(*) AS n, SUM(t.status = 'FAILED') AS f
                  FROM tasks t
                 WHERE t.flow_id IS NOT NULL AND t.run_track = 'AUTOMATION'
                   AND t.created_at >= ? AND t.created_at < ?
                 GROUP BY d
                """, from, to);
        Map<String, long[]> api = perDay("""
                SELECT DATE(created_at) AS d, COALESCE(SUM(total), 0) AS n, COALESCE(SUM(failed), 0) AS f
                  FROM api_test_runs
                 WHERE created_at >= ? AND created_at < ?
                 GROUP BY d
                """, from, to);
        Map<String, long[]> perf = perDay("SELECT DATE(t.created_at) AS d, COUNT(*) AS n, SUM(t.status = 'FAILED') AS f"
                + " FROM tasks t WHERE t.task_type IN " + PERF_IN
                + " AND t.created_at >= ? AND t.created_at < ? GROUP BY d", from, to);
        Map<String, long[]> added = perDay("""
                SELECT d, SUM(n) AS n, 0 AS f FROM (
                    SELECT DATE(created_at) AS d, COUNT(*) AS n FROM api_test_cases
                     WHERE created_at >= ? AND created_at < ? GROUP BY DATE(created_at)
                    UNION ALL
                    SELECT DATE(created_at), COUNT(*) FROM ui_flows
                     WHERE created_at >= ? AND created_at < ? AND (is_component IS NULL OR is_component = 0)
                     GROUP BY DATE(created_at)
                ) x GROUP BY d
                """, from, to, from, to);
        Map<String, long[]> addedFunc = perDay("""
                SELECT DATE(created_at) AS d, COUNT(*) AS n, 0 AS f FROM ai_test_cases
                 WHERE created_at >= ? AND created_at < ? GROUP BY d
                """, from, to);

        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            String k = day.toString();
            long[] u = sum(ui.get(k), single.get(k));
            long[] a = api.getOrDefault(k, new long[2]);
            long[] p = perf.getOrDefault(k, new long[2]);
            long[] n = sum(added.get(k), addedFunc.get(k));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", k);
            m.put("weekday", WEEKDAYS[i]);
            m.put("dateText", day.getMonthValue() + "月" + day.getDayOfMonth() + "日");
            m.put("uiCases", u[0]);
            m.put("apiCases", a[0]);
            m.put("perfRuns", p[0]);
            m.put("added", n[0]);
            m.put("failed", u[1] + a[1] + p[1]);
            m.put("future", day.isAfter(LocalDate.now()));
            days.add(m);
        }
        return days;
    }

    private static long[] sum(long[] a, long[] b) {
        long[] out = new long[2];
        for (long[] x : new long[][]{a, b}) {
            if (x != null) {
                out[0] += x[0];
                out[1] += x[1];
            }
        }
        return out;
    }

    /** 每行 d, n, f → {日期: [n, f]}；表不存在等错误只记日志，当天算 0 */
    private Map<String, long[]> perDay(String sql, Object... args) {
        Map<String, long[]> out = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                out.put(String.valueOf(rs.getDate("d")), new long[]{rs.getLong("n"), rs.getLong("f")});
            }, args);
        } catch (Exception e) {
            log.warn("[daily] 周历查询失败: {}", e.getMessage());
        }
        return out;
    }

    // ---------- UI 自动化 ----------

    private Map<String, Object> ui(Timestamp from, Timestamp to) {
        List<Map<String, Object>> rows = new ArrayList<>(rows("""
                SELECT COALESCE(NULLIF(r.environment, ''), 'PROD') AS env,
                       COALESCE(NULLIF(r.track, ''), 'REGRESSION') AS track,
                       COUNT(DISTINCT r.id) AS batches,
                       COALESCE(SUM(s.status = 'SUCCESS'), 0) AS passed,
                       COALESCE(SUM(s.status = 'FAILED'), 0) AS failed,
                       COALESCE(SUM(s.status = 'SKIPPED'), 0) AS skipped
                  FROM online_regression_runs r
                  LEFT JOIN online_regression_jobs j ON j.run_id = r.id
                  LEFT JOIN online_regression_steps s ON s.job_id = j.id
                 WHERE COALESCE(r.finished_at, r.created_at) >= ? AND COALESCE(r.finished_at, r.created_at) < ?
                 GROUP BY env, track
                 ORDER BY track, env
                """, from, to));
        List<Map<String, Object>> singles = rows("SELECT " + SINGLE_ENV + " AS env, 'SINGLE' AS track,"
                + " COUNT(*) AS batches,"
                + " COALESCE(SUM(t.status = 'COMPLETED'), 0) AS passed,"
                + " COALESCE(SUM(t.status = 'FAILED'), 0) AS failed,"
                + " COALESCE(SUM(t.status IN ('SKIPPED','BLOCKED','STOPPED')), 0) AS skipped"
                + " FROM tasks t WHERE t.flow_id IS NOT NULL AND t.run_track = 'AUTOMATION'"
                + " AND t.created_at >= ? AND t.created_at < ? GROUP BY env", from, to);
        rows.addAll(singles);
        for (Map<String, Object> r : rows) {
            r.put("cases", num(r.get("passed")) + num(r.get("failed")) + num(r.get("skipped")));
        }

        List<Map<String, Object>> failures = new ArrayList<>(rows("""
                SELECT s.step_label AS label, s.error_message AS error, j.app_name AS app,
                       j.device_name AS device, j.endpoint AS endpoint,
                       COALESCE(NULLIF(r.environment, ''), 'PROD') AS env,
                       COALESCE(NULLIF(r.track, ''), 'REGRESSION') AS track, r.id AS runId
                  FROM online_regression_runs r
                  JOIN online_regression_jobs j ON j.run_id = r.id
                  JOIN online_regression_steps s ON s.job_id = j.id
                 WHERE COALESCE(r.finished_at, r.created_at) >= ? AND COALESCE(r.finished_at, r.created_at) < ?
                   AND s.status = 'FAILED'
                 ORDER BY s.finished_at DESC
                 LIMIT 50
                """, from, to));
        failures.addAll(rows("SELECT t.name AS label, NULL AS error, NULL AS app, NULL AS device, NULL AS endpoint,"
                + SINGLE_ENV + " AS env, 'SINGLE' AS track, t.id AS taskId"
                + " FROM tasks t WHERE t.flow_id IS NOT NULL AND t.run_track = 'AUTOMATION' AND t.status = 'FAILED'"
                + " AND t.created_at >= ? AND t.created_at < ? ORDER BY t.updated_at DESC LIMIT 20", from, to));

        Map<String, Object> out = totals(rows, "batches");
        out.put("rows", rows);
        out.put("failures", failures);
        return out;
    }

    // ---------- 接口自动化 ----------

    private Map<String, Object> api(Timestamp from, Timestamp to) {
        List<Map<String, Object>> rows = rows("""
                SELECT COALESCE(NULLIF(trigger_type, ''), 'MANUAL') AS trig, environment AS env,
                       COUNT(*) AS runs, COALESCE(SUM(total), 0) AS cases,
                       COALESCE(SUM(passed), 0) AS passed, COALESCE(SUM(failed), 0) AS failed,
                       COALESCE(SUM(blocked), 0) AS blocked, COALESCE(SUM(skipped), 0) AS skipped
                  FROM api_test_runs
                 WHERE created_at >= ? AND created_at < ?
                 GROUP BY trig, env
                 ORDER BY trig DESC, env DESC
                """, from, to);
        List<Map<String, Object>> kinds = rows("""
                SELECT COALESCE(NULLIF(rc.case_kind, ''), 'UNCLASSIFIED') AS kind, COUNT(*) AS cases,
                       COALESCE(SUM(rc.status = 'PASSED'), 0) AS passed, COALESCE(SUM(rc.status = 'FAILED'), 0) AS failed
                  FROM api_test_run_cases rc
                 WHERE rc.run_id IN (SELECT id FROM api_test_runs WHERE created_at >= ? AND created_at < ?)
                 GROUP BY kind
                """, from, to);
        List<Map<String, Object>> recent = rows("""
                SELECT r.id, r.created_at AS createdAt, COALESCE(NULLIF(r.trigger_type, ''), 'MANUAL') AS trig,
                       r.environment AS env, c.name AS collection, r.total, r.passed, r.failed, r.blocked,
                       r.duration_ms AS durationMs, r.created_by AS createdBy
                  FROM api_test_runs r
                  LEFT JOIN api_test_collections c ON c.id = r.collection_id
                 WHERE r.created_at >= ? AND r.created_at < ?
                 ORDER BY r.created_at DESC
                 LIMIT 20
                """, from, to);
        Map<String, Object> out = totals(rows, "runs");
        out.put("blocked", rows.stream().mapToLong(r -> num(r.get("blocked"))).sum());
        out.put("rows", rows);
        out.put("kinds", kinds);
        out.put("recent", recent);
        return out;
    }

    // ---------- 性能 ----------

    private Map<String, Object> perf(Timestamp from, Timestamp to) {
        List<Map<String, Object>> rows = rows("SELECT t.task_type AS type, COALESCE(NULLIF(d.environment, ''), '') AS env,"
                + " COUNT(*) AS runs, COALESCE(SUM(t.status = 'COMPLETED'), 0) AS passed,"
                + " COALESCE(SUM(t.status = 'FAILED'), 0) AS failed,"
                + " COALESCE(SUM(t.status IN ('RUNNING','PENDING')), 0) AS running"
                + " FROM tasks t LEFT JOIN devices d ON d.id = t.device_id"
                + " WHERE t.task_type IN " + PERF_IN + " AND t.created_at >= ? AND t.created_at < ?"
                + " GROUP BY t.task_type, env ORDER BY runs DESC", from, to);
        for (Map<String, Object> r : rows) {
            r.put("cases", r.get("runs"));
        }
        Map<String, Object> out = totals(rows, "runs");
        out.put("rows", rows);
        return out;
    }

    // ---------- 用例增加 ----------

    private Map<String, Object> added(Timestamp from, Timestamp to) {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Long> apiKind = new LinkedHashMap<>();
        Map<String, Long> apiSource = new LinkedHashMap<>();
        Map<String, Long> apiEnv = new LinkedHashMap<>();
        long apiTotal = 0;
        for (Map<String, Object> r : rows("""
                SELECT COALESCE(NULLIF(case_kind, ''), 'UNCLASSIFIED') AS kind, created_by AS createdBy,
                       COALESCE(NULLIF(environment, ''), 'ANY') AS env, COUNT(*) AS n
                  FROM api_test_cases
                 WHERE created_at >= ? AND created_at < ?
                 GROUP BY kind, created_by, env
                """, from, to)) {
            long n = num(r.get("n"));
            apiTotal += n;
            apiKind.merge(String.valueOf(r.get("kind")), n, Long::sum);
            apiSource.merge(caseSource((String) r.get("createdBy")), n, Long::sum);
            apiEnv.merge(String.valueOf(r.get("env")), n, Long::sum);
        }
        out.put("api", Map.of("total", apiTotal, "byKind", apiKind, "bySource", apiSource, "byEnv", apiEnv));

        Map<String, Long> uiCatalog = new LinkedHashMap<>();
        long uiTotal = 0;
        for (Map<String, Object> r : rows("""
                SELECT COALESCE(NULLIF(catalog, ''), 'AUTOMATION') AS catalog, COUNT(*) AS n
                  FROM ui_flows
                 WHERE created_at >= ? AND created_at < ? AND (is_component IS NULL OR is_component = 0)
                 GROUP BY catalog
                """, from, to)) {
            long n = num(r.get("n"));
            uiTotal += n;
            uiCatalog.merge(String.valueOf(r.get("catalog")), n, Long::sum);
        }
        out.put("ui", Map.of("total", uiTotal, "byCatalog", uiCatalog));

        Map<String, Long> funcSource = new LinkedHashMap<>();
        long funcTotal = 0;
        for (Map<String, Object> r : rows("""
                SELECT COALESCE(NULLIF(source, ''), 'MANUAL') AS source, COUNT(*) AS n
                  FROM ai_test_cases
                 WHERE created_at >= ? AND created_at < ?
                 GROUP BY source
                """, from, to)) {
            long n = num(r.get("n"));
            funcTotal += n;
            funcSource.merge(String.valueOf(r.get("source")), n, Long::sum);
        }
        out.put("func", Map.of("total", funcTotal, "bySource", funcSource));
        out.put("total", apiTotal + uiTotal + funcTotal);
        return out;
    }

    /** api_test_cases 没有来源字段，生成器各自往 created_by 里写固定标记 */
    static String caseSource(String createdBy) {
        String s = createdBy == null ? "" : createdBy.trim();
        if (s.equals("AI 生成")) return "AI";
        if (s.equals("源码生成")) return "SOURCE";
        if (s.equals("抓包")) return "CAPTURE";
        if (s.equals("涉及接口") || s.equals("接口清单")) return "ENDPOINT";
        if (s.startsWith("seed-")) return "SEED";
        return "MANUAL";
    }

    // ---------- 工具 ----------

    private List<Map<String, Object>> rows(String sql, Object... args) {
        try {
            return new ArrayList<>(jdbcTemplate.queryForList(sql, args));
        } catch (Exception e) {
            log.warn("[daily] 查询失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 各行的 cases/passed/failed/skipped 加总，外加 count 列的总数 */
    private static Map<String, Object> totals(List<Map<String, Object>> rows, String countKey) {
        long count = 0, cases = 0, passed = 0, failed = 0, skipped = 0;
        for (Map<String, Object> r : rows) {
            count += num(r.get(countKey));
            cases += num(r.get("cases"));
            passed += num(r.get("passed"));
            failed += num(r.get("failed"));
            skipped += num(r.get("skipped"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(countKey, count);
        out.put("cases", cases);
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("skipped", skipped);
        out.put("passRate", passed + failed == 0 ? null : Math.round(passed * 1000.0 / (passed + failed)) / 10.0);
        return out;
    }

    private static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof Boolean b) return b ? 1 : 0;
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
