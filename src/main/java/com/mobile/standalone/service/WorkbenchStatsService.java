package com.mobile.standalone.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工作台看板统计：实时聚合现有表，不落快照表。
 *
 * <p><b>这个接口曾经要跑 60+ 条串行查询。</b>表本身都很小（回归批次 27 行、步骤 80 行）、索引也齐，
 * 单条 SQL 都是毫秒级——慢的从来不是 SQL，是<b>往返次数 × 网络延迟</b>。
 * 数据库在公网地址上，每条查询都是一次公网往返；按 100ms RTT 算，60 条就是 6 秒起步，
 * 网络一抖就直接顶到超时。
 *
 * <p>所以这里有三条硬规矩：
 * <ul>
 *   <li><b>不许在循环里查库。</b>批次列表按 runId 批量取，不是每个批次查两次。</li>
 *   <li><b>看板默认只取少量批次。</b>卡片上根本放不下 30 个批次，取回来也是白取。</li>
 *   <li><b>结果短暂缓存。</b>看板是给人扫一眼的，30 秒内的数据完全够用，
 *       没必要每次刷新都把这一整套查询重打一遍。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkbenchStatsService {

    private final JdbcTemplate jdbcTemplate;

    /** 看板默认取几个回归批次。卡片上放不下更多，取回来也是白取 */
    @Value("${app.workbench.recent-runs:8}")
    private int recentRunsLimit;

    /** 结果缓存秒数，0＝关闭。看板是给人扫一眼的，30 秒内的数据完全够用 */
    @Value("${app.workbench.cache-seconds:30}")
    private int cacheSeconds;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public Map<String, Object> getStats() {
        int ttl = Math.max(0, cacheSeconds);
        if (ttl > 0) {
            Map<String, Object> hit = cached;
            if (hit != null && System.currentTimeMillis() - cachedAt < ttl * 1000L) {
                return hit;
            }
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> result = computeStats();
        long cost = System.currentTimeMillis() - t0;
        // 这条日志是必须的：这个接口慢的时候，没有它只能靠猜是哪一段慢
        if (cost > 1500) {
            log.warn("[workbench] 统计耗时 {}ms —— 数据库往返太慢，确认后端与 MySQL 是否同内网", cost);
        } else {
            log.info("[workbench] 统计耗时 {}ms", cost);
        }
        cached = result;
        cachedAt = System.currentTimeMillis();
        return result;
    }

    private Map<String, Object> computeStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        // 同一张表的多个 count 一次问完。每条 count 单独发，省的是 SQL 时间（本来就毫秒级），
        // 花的是一次公网往返（几十上百毫秒）——这笔账在跨网数据库上是反的
        out.putAll(elementCounts());
        out.putAll(flowCounts());
        out.putAll(taskCounts(7));

        Map<String, Object> reg = regressionStats();
        out.put("latestRegressionPassRate", reg.get("latestPassRate"));
        out.put("regressionPassRate7d", reg.get("passRate7d"));
        out.put("regressionRuns7d", reg.get("runs7d"));
        out.put("regressionWeekDays", reg.get("weekDays"));
        out.put("recentRegressionRuns", recentRegressionRuns(recentRunsLimit));

        // 缺陷系统未接入：先占位，前端可显示「—」
        out.put("p0BugCount", null);
        out.put("p0BugAvailable", false);

        out.put("recentFailedTasks", recentFailedTasks(5));
        return out;
    }

    /** 元素库两个数一次问完 */
    private Map<String, Object> elementCounts() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeElementCount", 0);
        m.put("totalElementCount", 0);
        try {
            jdbcTemplate.query(
                    "SELECT COUNT(*) AS total,"
                            + " COALESCE(SUM(CASE WHEN status = 'ACTIVE'"
                            + "   AND (enabled IS NULL OR enabled = 1) THEN 1 ELSE 0 END), 0) AS active"
                            + " FROM ui_elements",
                    rs -> {
                        if (rs.next()) {
                            m.put("totalElementCount", rs.getInt("total"));
                            m.put("activeElementCount", rs.getInt("active"));
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.warn("elementCounts: {}", e.getMessage());
        }
        return m;
    }

    /** 流程两个数一次问完 */
    private Map<String, Object> flowCounts() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabledFlowCount", 0);
        m.put("regressionFlowCount", 0);
        try {
            jdbcTemplate.query(
                    "SELECT COUNT(*) AS enabled_cnt,"
                            + " COALESCE(SUM(CASE WHEN catalog IN ('ONLINE','SHARED') THEN 1 ELSE 0 END), 0) AS reg_cnt"
                            + " FROM ui_flows"
                            + " WHERE enabled = 1 AND (is_component = 0 OR is_component IS NULL)",
                    rs -> {
                        if (rs.next()) {
                            m.put("enabledFlowCount", rs.getInt("enabled_cnt"));
                            m.put("regressionFlowCount", rs.getInt("reg_cnt"));
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.warn("flowCounts: {}", e.getMessage());
        }
        return m;
    }

    /**
     * 任务的 5 个数（近 N 天完成/通过/失败 + 当前运行中 + 总数）一次问完。
     * <p>
     * <b>一律排除试跑。</b>工作台是质量看板，而试跑是「我改完步骤跑一下看通不通」——
     * 调试挂两次算进通过率，这个数从第一天起就是不可信的，而不可信的数会被一直拿去汇报。
     */
    private Map<String, Object> taskCounts(int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskPassRate7d", null);
        m.put("tasksFinished7d", 0);
        m.put("tasksPassed7d", 0);
        m.put("tasksFailed7d", 0);
        m.put("tasksRunning", 0);
        m.put("tasksTotal", 0);
        try {
            jdbcTemplate.query(
                    "SELECT COUNT(*) AS total,"
                            + " COALESCE(SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END), 0) AS running,"
                            + " COALESCE(SUM(CASE WHEN status IN ('COMPLETED','FAILED','STOPPED')"
                            + "   AND updated_at >= DATE_SUB(NOW(), INTERVAL ? DAY) THEN 1 ELSE 0 END), 0) AS finished,"
                            + " COALESCE(SUM(CASE WHEN status = 'COMPLETED'"
                            + "   AND updated_at >= DATE_SUB(NOW(), INTERVAL ? DAY) THEN 1 ELSE 0 END), 0) AS passed,"
                            + " COALESCE(SUM(CASE WHEN status IN ('FAILED','STOPPED')"
                            + "   AND updated_at >= DATE_SUB(NOW(), INTERVAL ? DAY) THEN 1 ELSE 0 END), 0) AS failed"
                            + " FROM tasks WHERE (run_track IS NULL OR run_track <> 'DEBUG')",
                    rs -> {
                        if (rs.next()) {
                            int finished = rs.getInt("finished");
                            int passed = rs.getInt("passed");
                            m.put("tasksTotal", rs.getInt("total"));
                            m.put("tasksRunning", rs.getInt("running"));
                            m.put("tasksFinished7d", finished);
                            m.put("tasksPassed7d", passed);
                            m.put("tasksFailed7d", rs.getInt("failed"));
                            m.put("taskPassRate7d", finished > 0 ? Math.round(passed * 100f / finished) : null);
                        }
                        return null;
                    },
                    days, days, days);
        } catch (Exception e) {
            log.warn("taskCounts: {}", e.getMessage());
        }
        return m;
    }

    private Map<String, Object> regressionStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        Integer latest = null;
        try {
            latest = jdbcTemplate.query(
                    "SELECT pass_rate FROM online_regression_runs "
                            + "WHERE status IN ('COMPLETED','FAILED','STOPPED') "
                            + "ORDER BY COALESCE(finished_at, created_at) DESC LIMIT 1",
                    rs -> rs.next() ? rs.getObject(1, Integer.class) : null);
        } catch (Exception e) {
            log.debug("latest regression pass_rate: {}", e.getMessage());
        }
        m.put("latestPassRate", latest);

        // 批次数和平均通过率是同一批行上的两个聚合，一条查询出完
        int[] runs = {0};
        Integer[] avgBox = {null};
        try {
            jdbcTemplate.query(
                    "SELECT COUNT(*) AS cnt, AVG(pass_rate) AS avg_rate FROM online_regression_runs "
                            + "WHERE status IN ('COMPLETED','FAILED','STOPPED') "
                            + "AND COALESCE(finished_at, created_at) >= DATE_SUB(NOW(), INTERVAL 7 DAY)",
                    rs -> {
                        if (rs.next()) {
                            runs[0] = rs.getInt("cnt");
                            Object raw = rs.getObject("avg_rate");
                            if (raw != null) {
                                avgBox[0] = (int) Math.round(rs.getDouble("avg_rate"));
                            }
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.debug("regression 7d: {}", e.getMessage());
        }
        Integer runs7d = runs[0];
        Integer avg7d = avgBox[0];
        m.put("runs7d", runs7d);
        m.put("passRate7d", avg7d);
        m.put("weekDays", regressionWeekDays());
        return m;
    }

    /**
     * 近 7 个自然日的回归批次汇总（含今天往前共 7 天，无数据日也返回）。
     */
    private List<Map<String, Object>> regressionWeekDays() {
        LocalDate today = LocalDate.now();
        Map<LocalDate, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("date", d.toString());
            cell.put("dateText", d.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)));
            cell.put("weekday", d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.CHINA));
            cell.put("runCount", 0);
            cell.put("passRate", null);
            cell.put("allPassed", null);
            byDay.put(d, cell);
        }
        try {
            jdbcTemplate.query(
                    "SELECT DATE(COALESCE(finished_at, created_at)) AS d, "
                            + "COUNT(*) AS cnt, AVG(pass_rate) AS avg_rate, "
                            + "SUM(CASE WHEN pass_rate IS NOT NULL AND pass_rate < 100 THEN 1 ELSE 0 END) AS fail_batches "
                            + "FROM online_regression_runs "
                            + "WHERE status IN ('COMPLETED','FAILED','STOPPED') "
                            + "AND COALESCE(finished_at, created_at) >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) "
                            + "GROUP BY DATE(COALESCE(finished_at, created_at))",
                    rs -> {
                        while (rs.next()) {
                            java.sql.Date sqlDate = rs.getDate("d");
                            if (sqlDate == null) continue;
                            LocalDate d = sqlDate.toLocalDate();
                            Map<String, Object> cell = byDay.get(d);
                            if (cell == null) continue;
                            int cnt = rs.getInt("cnt");
                            cell.put("runCount", cnt);
                            Double avg = rs.getObject("avg_rate") != null ? rs.getDouble("avg_rate") : null;
                            Integer rate = avg != null ? (int) Math.round(avg) : null;
                            cell.put("passRate", rate);
                            int failBatches = rs.getInt("fail_batches");
                            cell.put("allPassed", cnt > 0 && failBatches == 0 && rate != null && rate >= 100);
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.debug("regressionWeekDays: {}", e.getMessage());
        }
        return new ArrayList<>(byDay.values());
    }

    /**
     * 最近回归批次：日期 + 回归了哪些功能用例 + 每用例状态（多端取最差）。
     */
    private List<Map<String, Object>> recentRegressionRuns(int limit) {
        int n = Math.min(Math.max(limit, 1), 30);
        List<Map<String, Object>> runs;
        try {
            runs = jdbcTemplate.query(
                    "SELECT id, status, pass_rate, selected_apps, started_at, finished_at, created_at "
                            + "FROM online_regression_runs "
                            + "ORDER BY COALESCE(finished_at, created_at) DESC LIMIT ?",
                    (rs, i) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("status", rs.getString("status"));
                        row.put("passRate", rs.getObject("pass_rate") != null ? rs.getInt("pass_rate") : null);
                        row.put("selectedApps", rs.getString("selected_apps"));
                        LocalDateTime finished = rs.getTimestamp("finished_at") != null
                                ? rs.getTimestamp("finished_at").toLocalDateTime() : null;
                        LocalDateTime started = rs.getTimestamp("started_at") != null
                                ? rs.getTimestamp("started_at").toLocalDateTime() : null;
                        LocalDateTime created = rs.getTimestamp("created_at") != null
                                ? rs.getTimestamp("created_at").toLocalDateTime() : null;
                        LocalDateTime when = finished != null ? finished : (started != null ? started : created);
                        row.put("finishedAt", finished != null ? finished.toString() : null);
                        row.put("startedAt", started != null ? started.toString() : null);
                        row.put("createdAt", created != null ? created.toString() : null);
                        if (when != null) {
                            row.put("date", when.toLocalDate().toString());
                            row.put("dateText", when.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)));
                            row.put("dayText", when.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)));
                        } else {
                            row.put("date", null);
                            row.put("dateText", "-");
                            row.put("dayText", "-");
                        }
                        return row;
                    },
                    n);
        } catch (Exception e) {
            log.warn("recentRegressionRuns list: {}", e.getMessage());
            return List.of();
        }
        if (runs == null || runs.isEmpty()) return List.of();

        // 原来这里是「每个批次再查两次库」——27 个批次就是 54 次公网往返，
        // 是这个接口从几百毫秒变成十几秒的主要原因。改成两条批量查询，与批次数无关
        List<String> runIds = new ArrayList<>();
        for (Map<String, Object> run : runs) {
            String id = (String) run.get("id");
            if (id != null) runIds.add(id);
        }
        Map<String, Integer> jobCounts = jobCountsOf(runIds);
        Map<String, List<Map<String, Object>>> featuresByRun = featuresOf(runIds);

        for (Map<String, Object> run : runs) {
            String runId = (String) run.get("id");
            run.put("jobCount", jobCounts.getOrDefault(runId, 0));
            run.put("features", featuresByRun.getOrDefault(runId, List.of()));
            run.put("appsText", appsText((String) run.get("selectedApps")));
        }
        return runs;
    }

    /** 一次查完所有批次的 job 数 */
    private Map<String, Integer> jobCountsOf(List<String> runIds) {
        Map<String, Integer> out = new HashMap<>();
        if (runIds.isEmpty()) return out;
        try {
            jdbcTemplate.query(
                    "SELECT run_id, COUNT(*) AS cnt FROM online_regression_jobs"
                            + " WHERE run_id IN (" + placeholders(runIds.size()) + ") GROUP BY run_id",
                    rs -> {
                        while (rs.next()) {
                            out.put(rs.getString("run_id"), rs.getInt("cnt"));
                        }
                        return null;
                    },
                    runIds.toArray());
        } catch (Exception e) {
            log.warn("jobCountsOf: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 一次查完所有批次的步骤明细，再在内存里按批次分组。
     * <p>
     * ORDER BY 保持原样（先 iOS 后 Android、再按序号）：不同批次的行会交错出来，
     * 但分组时每个批次内部的相对顺序不变，前端看到的顺序和以前一致。
     */
    private Map<String, List<Map<String, Object>>> featuresOf(List<String> runIds) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        if (runIds.isEmpty()) return out;
        try {
            jdbcTemplate.query(
                    "SELECT j.run_id AS runId, s.step_id AS stepId, s.step_label AS stepLabel, "
                            + "s.sort_order AS sortOrder, s.status AS status, s.error_message AS errorMessage, "
                            + "s.task_id AS taskId, j.endpoint AS endpoint, j.device_name AS deviceName, "
                            + "j.device_platform AS devicePlatform "
                            + "FROM online_regression_steps s "
                            + "INNER JOIN online_regression_jobs j ON j.id = s.job_id "
                            + "WHERE j.run_id IN (" + placeholders(runIds.size()) + ") "
                            + "ORDER BY CASE LOWER(IFNULL(j.endpoint,'')) "
                            + "  WHEN 'ios' THEN 0 WHEN 'android' THEN 1 ELSE 2 END, "
                            + "s.sort_order ASC, s.step_label ASC",
                    rs -> {
                        while (rs.next()) {
                            String runId = rs.getString("runId");
                            Map<String, Object> row = new LinkedHashMap<>();
                            String stepId = rs.getString("stepId");
                            String label = rs.getString("stepLabel");
                            row.put("stepId", stepId);
                            row.put("label", label != null ? label : stepId);
                            row.put("sortOrder", rs.getObject("sortOrder") != null ? rs.getInt("sortOrder") : 0);
                            row.put("status", normalizeStepStatus(rs.getString("status")));
                            String err = rs.getString("errorMessage");
                            row.put("errorMessage", err != null && !err.isBlank() ? err.trim() : null);
                            row.put("taskId", rs.getString("taskId"));
                            String endpoint = rs.getString("endpoint");
                            row.put("endpoint", endpoint != null ? endpoint.toLowerCase(Locale.ROOT) : "");
                            row.put("deviceName", rs.getString("deviceName"));
                            row.put("devicePlatform", rs.getString("devicePlatform"));
                            String ep = (String) row.get("endpoint");
                            String dn = row.get("deviceName") != null ? String.valueOf(row.get("deviceName")) : "";
                            row.put("featureKey", (stepId != null ? stepId : "") + "|" + ep + "|" + dn);
                            out.computeIfAbsent(runId, k -> new ArrayList<>()).add(row);
                        }
                        return null;
                    },
                    runIds.toArray());
        } catch (Exception e) {
            log.warn("featuresOf: {}", e.getMessage());
        }
        return out;
    }

    private static String placeholders(int n) {
        return "?" + ",?".repeat(Math.max(0, n - 1));
    }

    private static String normalizeStepStatus(String status) {
        if (status == null || status.isBlank()) return "pending";
        return status.trim().toLowerCase(Locale.ROOT);
    }

    private static String appsText(String selectedAppsJson) {
        if (selectedAppsJson == null || selectedAppsJson.isBlank()) return "";
        String s = selectedAppsJson;
        List<String> labels = new ArrayList<>();
        if (s.contains("DOMESTIC_ESPORTS")) labels.add("国内电竞");
        if (s.contains("OVERSEAS_POGPOG")) labels.add("海外 PogPog");
        if (labels.isEmpty()) {
            // 粗解析 JSON 数组字面量
            s = s.replace("[", "").replace("]", "").replace("\"", "").trim();
            if (!s.isEmpty()) {
                for (String part : s.split(",")) {
                    String t = part.trim();
                    if (!t.isEmpty()) labels.add(t);
                }
            }
        }
        return String.join("、", labels);
    }

    private List<Map<String, Object>> recentFailedTasks(int limit) {
        try {
            return jdbcTemplate.query(
                    "SELECT id, name, status, task_type AS taskType, updated_at AS updatedAt "
                            + "FROM tasks WHERE status IN ('FAILED','STOPPED') "
                            // 同上：调试挂掉的不该出现在看板的「最近失败」里
                            + "AND (run_track IS NULL OR run_track <> 'DEBUG') "
                            + "ORDER BY updated_at DESC LIMIT ?",
                    (rs, i) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("name", rs.getString("name"));
                        row.put("status", rs.getString("status"));
                        row.put("taskType", rs.getString("taskType"));
                        row.put("updatedAt", rs.getTimestamp("updatedAt") != null
                                ? rs.getTimestamp("updatedAt").toLocalDateTime().toString() : null);
                        return row;
                    },
                    limit);
        } catch (Exception e) {
            log.warn("recentFailedTasks: {}", e.getMessage());
            return List.of();
        }
    }

}
