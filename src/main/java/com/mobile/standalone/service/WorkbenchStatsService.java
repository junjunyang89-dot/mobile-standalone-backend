package com.mobile.standalone.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工作台看板统计：实时聚合现有表，不落快照表。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkbenchStatsService {

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> getStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeElementCount", countSafe(
                "SELECT COUNT(*) FROM ui_elements WHERE status = 'ACTIVE' AND (enabled IS NULL OR enabled = 1)"));
        out.put("totalElementCount", countSafe("SELECT COUNT(*) FROM ui_elements"));
        out.put("enabledFlowCount", countSafe(
                "SELECT COUNT(*) FROM ui_flows WHERE enabled = 1 AND (is_component = 0 OR is_component IS NULL)"));
        out.put("regressionFlowCount", countSafe(
                "SELECT COUNT(*) FROM ui_flows WHERE enabled = 1 AND (is_component = 0 OR is_component IS NULL) "
                        + "AND catalog IN ('ONLINE','SHARED')"));

        Map<String, Object> task7d = taskStatsLastDays(7);
        out.put("taskPassRate7d", task7d.get("passRate"));
        out.put("tasksFinished7d", task7d.get("finished"));
        out.put("tasksPassed7d", task7d.get("passed"));
        out.put("tasksFailed7d", task7d.get("failed"));
        out.put("tasksRunning", countSafe("SELECT COUNT(*) FROM tasks WHERE status = 'RUNNING'"));
        out.put("tasksTotal", countSafe("SELECT COUNT(*) FROM tasks"));

        Map<String, Object> reg = regressionStats();
        out.put("latestRegressionPassRate", reg.get("latestPassRate"));
        out.put("regressionPassRate7d", reg.get("passRate7d"));
        out.put("regressionRuns7d", reg.get("runs7d"));
        out.put("regressionWeekDays", reg.get("weekDays"));
        out.put("recentRegressionRuns", recentRegressionRuns(40));

        // 缺陷系统未接入：先占位，前端可显示「—」
        out.put("p0BugCount", null);
        out.put("p0BugAvailable", false);

        out.put("recentFailedTasks", recentFailedTasks(5));
        return out;
    }

    private Map<String, Object> taskStatsLastDays(int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        Integer finished = countSafe(
                "SELECT COUNT(*) FROM tasks WHERE status IN ('COMPLETED','FAILED','STOPPED') "
                        + "AND updated_at >= DATE_SUB(NOW(), INTERVAL ? DAY)",
                days);
        Integer passed = countSafe(
                "SELECT COUNT(*) FROM tasks WHERE status = 'COMPLETED' "
                        + "AND updated_at >= DATE_SUB(NOW(), INTERVAL ? DAY)",
                days);
        Integer failed = countSafe(
                "SELECT COUNT(*) FROM tasks WHERE status IN ('FAILED','STOPPED') "
                        + "AND updated_at >= DATE_SUB(NOW(), INTERVAL ? DAY)",
                days);
        m.put("finished", finished);
        m.put("passed", passed);
        m.put("failed", failed);
        m.put("passRate", finished > 0 ? Math.round(passed * 100f / finished) : null);
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

        Integer runs7d = countSafe(
                "SELECT COUNT(*) FROM online_regression_runs "
                        + "WHERE status IN ('COMPLETED','FAILED','STOPPED') "
                        + "AND COALESCE(finished_at, created_at) >= DATE_SUB(NOW(), INTERVAL 7 DAY)");
        Integer avg7d = null;
        try {
            Double avg = jdbcTemplate.query(
                    "SELECT AVG(pass_rate) FROM online_regression_runs "
                            + "WHERE status IN ('COMPLETED','FAILED','STOPPED') AND pass_rate IS NOT NULL "
                            + "AND COALESCE(finished_at, created_at) >= DATE_SUB(NOW(), INTERVAL 7 DAY)",
                    rs -> rs.next() ? rs.getObject(1, Double.class) : null);
            if (avg != null) {
                avg7d = (int) Math.round(avg);
            }
        } catch (Exception e) {
            log.debug("regression avg 7d: {}", e.getMessage());
        }
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

        for (Map<String, Object> run : runs) {
            String runId = (String) run.get("id");
            run.put("jobCount", countSafe("SELECT COUNT(*) FROM online_regression_jobs WHERE run_id = ?", runId));
            run.put("features", featuresForRun(runId));
            run.put("appsText", appsText((String) run.get("selectedApps")));
        }
        return runs;
    }

    private List<Map<String, Object>> featuresForRun(String runId) {
        try {
            // 按 job 展开：每端每步一条。旧逻辑按 stepId 合并会把 iOS/Android 揉成一条，统计像「没进 iOS」
            List<Map<String, Object>> raw = jdbcTemplate.query(
                    "SELECT s.step_id AS stepId, s.step_label AS stepLabel, s.sort_order AS sortOrder, "
                            + "s.status AS status, s.error_message AS errorMessage, s.task_id AS taskId, "
                            + "j.endpoint AS endpoint, j.device_name AS deviceName, "
                            + "j.device_platform AS devicePlatform "
                            + "FROM online_regression_steps s "
                            + "INNER JOIN online_regression_jobs j ON j.id = s.job_id "
                            + "WHERE j.run_id = ? "
                            + "ORDER BY CASE LOWER(IFNULL(j.endpoint,'')) "
                            + "  WHEN 'ios' THEN 0 WHEN 'android' THEN 1 ELSE 2 END, "
                            + "s.sort_order ASC, s.step_label ASC",
                    (rs, i) -> {
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
                        return row;
                    },
                    runId);
            return raw != null ? raw : List.of();
        } catch (Exception e) {
            log.warn("featuresForRun {}: {}", runId, e.getMessage());
            return List.of();
        }
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

    private int countSafe(String sql, Object... args) {
        try {
            Integer n = args.length == 0
                    ? jdbcTemplate.queryForObject(sql, Integer.class)
                    : jdbcTemplate.queryForObject(sql, Integer.class, args);
            return n != null ? n : 0;
        } catch (Exception e) {
            log.warn("workbench count failed: {} — {}", sql, e.getMessage());
            return 0;
        }
    }
}
