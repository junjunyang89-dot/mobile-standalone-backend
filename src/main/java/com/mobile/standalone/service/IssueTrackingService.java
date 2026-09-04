package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 线上/测试问题持续跟踪。
 * <p>
 * 与「Bug 管理」不同：不挂需求单，专门记录别人反馈过来、需要持续跟进的零散问题，
 * 防止「说好了要验、开发说修了、忘了上线没」这类事项漏掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueTrackingService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    private static final List<String> STATUSES = List.of(
            "TRACKING", "VERIFYING", "FIXED", "DEPLOYED", "CLOSED");

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS issue_tracking (
                        id VARCHAR(64) PRIMARY KEY,
                        issue_no INT DEFAULT NULL COMMENT '展示编号',
                        title VARCHAR(512) NOT NULL COMMENT '问题标题',
                        environment VARCHAR(16) NOT NULL DEFAULT 'TEST' COMMENT 'TEST/PROD',
                        source VARCHAR(128) DEFAULT NULL COMMENT '反馈来源',
                        module VARCHAR(255) DEFAULT NULL COMMENT '所属模块',
                        description TEXT DEFAULT NULL COMMENT '问题描述',
                        reproduction_steps TEXT DEFAULT NULL COMMENT '复现步骤',
                        test_status TEXT DEFAULT NULL COMMENT '测试情况',
                        verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已验证',
                        deployed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已上线',
                        developers VARCHAR(255) DEFAULT NULL COMMENT '涉及的开发',
                        origin_note TEXT DEFAULT NULL COMMENT '问题怎么产生的',
                        dev_fixed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '开发是否修复',
                        tracking_tester VARCHAR(64) DEFAULT NULL COMMENT '跟踪的测试',
                        status VARCHAR(16) NOT NULL DEFAULT 'TRACKING' COMMENT 'TRACKING/VERIFYING/FIXED/DEPLOYED/CLOSED',
                        priority VARCHAR(16) DEFAULT 'MEDIUM' COMMENT 'CRITICAL/HIGH/MEDIUM/LOW',
                        note TEXT DEFAULT NULL COMMENT '备注',
                        project_type VARCHAR(16) NOT NULL DEFAULT 'APP' COMMENT 'APP/PVP/WEB',
                        images TEXT DEFAULT NULL COMMENT '截图 URL JSON 数组',
                        related_apis TEXT DEFAULT NULL COMMENT '涉及的接口 JSON 数组',
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_env (environment),
                        INDEX idx_status (status),
                        INDEX idx_tester (tracking_tester),
                        INDEX idx_issue_no (issue_no)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            ensureColumn("project_type",
                    "VARCHAR(16) NOT NULL DEFAULT 'APP' COMMENT 'APP/PVP/WEB'");
            ensureColumn("images", "TEXT DEFAULT NULL COMMENT '截图 URL JSON 数组'");
            ensureColumn("related_apis", "TEXT DEFAULT NULL COMMENT '涉及的接口 JSON 数组'");
            ensureIndex("idx_project", "(project_type)");
            backfillIssueNo();
            log.info("[issue-tracking] 表已就绪");
        } catch (Exception e) {
            log.warn("[issue-tracking] 建表失败: {}", e.getMessage());
        }
    }

    private void ensureColumn(String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, "issue_tracking", column);
            if (n != null && n == 0) {
                jdbcTemplate.execute("ALTER TABLE issue_tracking ADD COLUMN " + column + " " + definition);
                log.info("[issue-tracking] 已补充 {} 列", column);
            }
        } catch (Exception e) {
            log.debug("[issue-tracking] {} 列检查跳过: {}", column, e.getMessage());
        }
    }

    private void ensureIndex(String name, String columns) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                    Integer.class, "issue_tracking", name);
            if (n != null && n == 0) {
                jdbcTemplate.execute("ALTER TABLE issue_tracking ADD INDEX " + name + " " + columns);
            }
        } catch (Exception e) {
            log.debug("[issue-tracking] 索引 {} 检查跳过: {}", name, e.getMessage());
        }
    }

    private void backfillIssueNo() {
        try {
            Integer maxNo = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(issue_no), 0) FROM issue_tracking", Integer.class);
            int next = maxNo == null ? 1 : maxNo + 1;
            List<Map<String, Object>> missing = jdbcTemplate.queryForList(
                    "SELECT id FROM issue_tracking WHERE issue_no IS NULL ORDER BY created_at, id");
            for (Map<String, Object> row : missing) {
                jdbcTemplate.update("UPDATE issue_tracking SET issue_no = ? WHERE id = ?", next++, row.get("id"));
            }
        } catch (Exception e) {
            log.debug("[issue-tracking] 回填编号跳过: {}", e.getMessage());
        }
    }

    public Map<String, Object> list(
            String environment,
            String status,
            String priority,
            String trackingTester,
            String projectType,
            String developer,
            String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, issue_no AS issueNo, title, environment, source, module,
                       description, reproduction_steps AS reproductionSteps,
                       test_status AS testStatus, verified, deployed,
                       developers, origin_note AS originNote, dev_fixed AS devFixed,
                       tracking_tester AS trackingTester, status, priority, note,
                       project_type AS projectType, images, related_apis AS relatedApis,
                       created_at AS createdAt, updated_at AS updatedAt
                  FROM issue_tracking
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (environment != null && !environment.isBlank()) {
            sql.append(" AND environment = ?");
            args.add(environment.trim().toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" AND priority = ?");
            args.add(priority.trim().toUpperCase(Locale.ROOT));
        }
        if (trackingTester != null && !trackingTester.isBlank()) {
            sql.append(" AND tracking_tester = ?");
            args.add(trackingTester.trim());
        }
        if (projectType != null && !projectType.isBlank() && !"ALL".equalsIgnoreCase(projectType)) {
            sql.append(" AND project_type = ?");
            args.add(normalizeProjectType(projectType));
        }
        if (developer != null && !developer.isBlank()) {
            sql.append(" AND developers LIKE ?");
            args.add("%" + developer.trim() + "%");
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (title LIKE ? OR description LIKE ? OR module LIKE ?");
            sql.append(" OR developers LIKE ? OR CAST(issue_no AS CHAR) LIKE ? OR related_apis LIKE ?)");
            for (int i = 0; i < 6; i++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY updated_at DESC, issue_no DESC");

        List<Map<String, Object>> issues;
        try {
            issues = decorateRows(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
        } catch (Exception e) {
            log.debug("[issue-tracking] 查询失败: {}", e.getMessage());
            issues = List.of();
        }

        Set<String> testers = new LinkedHashSet<>();
        Set<String> developers = new LinkedHashSet<>();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        statusCounts.put("ALL", (long) issues.size());
        for (String st : STATUSES) {
            statusCounts.put(st, 0L);
        }
        for (Map<String, Object> row : issues) {
            Object tester = row.get("trackingTester");
            if (tester != null && !String.valueOf(tester).isBlank()) {
                testers.add(String.valueOf(tester));
            }
            for (String name : splitNames(row.get("developers"))) {
                developers.add(name);
            }
            String st = String.valueOf(row.get("status"));
            statusCounts.merge(st, 1L, Long::sum);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issues", issues);
        out.put("testers", new ArrayList<>(testers));
        out.put("developers", new ArrayList<>(developers));
        out.put("statusCounts", statusCounts);
        return out;
    }

    public Map<String, Object> getById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = decorateRows(jdbcTemplate.queryForList("""
                    SELECT id, issue_no AS issueNo, title, environment, source, module,
                           description, reproduction_steps AS reproductionSteps,
                           test_status AS testStatus, verified, deployed,
                           developers, origin_note AS originNote, dev_fixed AS devFixed,
                           tracking_tester AS trackingTester, status, priority, note,
                           project_type AS projectType, images, related_apis AS relatedApis,
                           created_at AS createdAt, updated_at AS updatedAt
                      FROM issue_tracking
                     WHERE id = ?
                    """, id));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    public String create(Map<String, Object> body) {
        String title = str(body.get("title"));
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("问题标题必填");
        }
        StatusFields sf = resolveStatusFields(body, null);
        String id = "issue-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO issue_tracking
                    (id, issue_no, title, environment, source, module, description,
                     reproduction_steps, test_status, verified, deployed, developers,
                     origin_note, dev_fixed, tracking_tester, status, priority, note,
                     project_type, images, related_apis)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, nextIssueNo(), title.trim(),
                normalizeEnvironment(body.get("environment")),
                str(body.get("source")), str(body.get("module")), str(body.get("description")),
                str(body.get("reproductionSteps")), str(body.get("testStatus")),
                sf.verified(), sf.deployed(), str(body.get("developers")),
                str(body.get("originNote")), sf.devFixed(), str(body.get("trackingTester")),
                sf.status(), normalizePriority(body.get("priority")), str(body.get("note")),
                normalizeProjectType(body.get("projectType")), imagesToJson(body.get("images")),
                imagesToJson(firstPresent(body, "relatedApis", "related_apis")));
        log.info("[issue-tracking] 新增 {}", title);
        return id;
    }

    public boolean update(String id, Map<String, Object> body) {
        try {
            Map<String, Object> existing = getById(id);
            if (existing == null) {
                return false;
            }
            StatusFields sf = resolveStatusFields(body, existing);
            return jdbcTemplate.update("""
                    UPDATE issue_tracking
                       SET title = ?, environment = ?, source = ?, module = ?,
                           description = ?, reproduction_steps = ?, test_status = ?,
                           verified = ?, deployed = ?, developers = ?, origin_note = ?,
                           dev_fixed = ?, tracking_tester = ?, status = ?, priority = ?, note = ?,
                           project_type = ?, images = ?, related_apis = ?
                     WHERE id = ?
                    """,
                    body.containsKey("title") ? str(body.get("title")) : str(existing.get("title")),
                    body.containsKey("environment")
                            ? normalizeEnvironment(body.get("environment"))
                            : normalizeEnvironment(existing.get("environment")),
                    body.containsKey("source") ? str(body.get("source")) : str(existing.get("source")),
                    body.containsKey("module") ? str(body.get("module")) : str(existing.get("module")),
                    body.containsKey("description") ? str(body.get("description")) : str(existing.get("description")),
                    body.containsKey("reproductionSteps")
                            ? str(body.get("reproductionSteps"))
                            : str(existing.get("reproductionSteps")),
                    body.containsKey("testStatus") ? str(body.get("testStatus")) : str(existing.get("testStatus")),
                    sf.verified(), sf.deployed(),
                    body.containsKey("developers") ? str(body.get("developers")) : str(existing.get("developers")),
                    body.containsKey("originNote") ? str(body.get("originNote")) : str(existing.get("originNote")),
                    sf.devFixed(),
                    body.containsKey("trackingTester")
                            ? str(body.get("trackingTester"))
                            : str(existing.get("trackingTester")),
                    sf.status(),
                    body.containsKey("priority")
                            ? normalizePriority(body.get("priority"))
                            : normalizePriority(existing.get("priority")),
                    body.containsKey("note") ? str(body.get("note")) : str(existing.get("note")),
                    body.containsKey("projectType")
                            ? normalizeProjectType(body.get("projectType"))
                            : normalizeProjectType(existing.get("projectType")),
                    body.containsKey("images")
                            ? imagesToJson(body.get("images"))
                            : imagesToJson(existing.get("images")),
                    body.containsKey("relatedApis") || body.containsKey("related_apis")
                            ? imagesToJson(firstPresent(body, "relatedApis", "related_apis"))
                            : imagesToJson(existing.get("relatedApis")),
                    id) > 0;
        } catch (Exception e) {
            log.warn("[issue-tracking] 更新失败 {}: {}", id, e.getMessage());
            return false;
        }
    }

    public boolean delete(String id) {
        try {
            return jdbcTemplate.update("DELETE FROM issue_tracking WHERE id = ?", id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int nextIssueNo() {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(issue_no), 0) FROM issue_tracking", Integer.class);
            return (max == null ? 0 : max) + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private List<Map<String, Object>> decorateRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            int verified = num(m.get("verified"));
            int devFixed = num(m.get("devFixed"));
            int deployed = num(m.get("deployed"));
            String status = normalizeStatus(m.get("status"), verified, devFixed, deployed);
            m.put("status", status);
            m.put("statusLabel", statusLabel(status));
            m.put("priority", normalizePriority(m.get("priority")));
            m.put("issueCode", formatIssueCode(m.get("issueNo")));
            m.put("environmentLabel", "PROD".equals(m.get("environment")) ? "线上" : "测试");
            String projectType = normalizeProjectType(m.get("projectType"));
            m.put("projectType", projectType);
            m.put("projectTypeLabel", projectType);
            m.put("images", parseImages(m.get("images")));
            m.put("relatedApis", parseImages(m.get("relatedApis")));
            out.add(m);
        }
        return out;
    }

    private record StatusFields(String status, int verified, int devFixed, int deployed) {
    }

    private StatusFields resolveStatusFields(Map<String, Object> body, Map<String, Object> existing) {
        int verified = existing != null ? num(existing.get("verified")) : 0;
        int devFixed = existing != null ? num(existing.get("devFixed")) : 0;
        int deployed = existing != null ? num(existing.get("deployed")) : 0;
        String status = existing != null
                ? normalizeStatus(existing.get("status"), verified, devFixed, deployed)
                : "TRACKING";

        if (body.containsKey("verified")) {
            verified = bool(body.get("verified"));
        }
        if (body.containsKey("devFixed")) {
            devFixed = bool(body.get("devFixed"));
        }
        if (body.containsKey("deployed")) {
            deployed = bool(body.get("deployed"));
        }
        if (body.containsKey("status")) {
            status = normalizeStatus(body.get("status"), verified, devFixed, deployed);
        } else {
            status = inferStatus(status, verified, devFixed, deployed);
        }
        return new StatusFields(status, verified, devFixed, deployed);
    }

    private String inferStatus(String current, int verified, int devFixed, int deployed) {
        if (verified == 1 && deployed == 1) {
            return "CLOSED";
        }
        if (verified == 1) {
            return "VERIFYING".equals(current) || "FIXED".equals(current) ? current : "VERIFYING";
        }
        if (deployed == 1) {
            return "DEPLOYED";
        }
        if (devFixed == 1) {
            return "FIXED";
        }
        return current == null || current.isBlank() ? "TRACKING" : current;
    }

    private String normalizeStatus(Object raw, int verified, int devFixed, int deployed) {
        String s = raw == null ? "" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            s = inferStatus("TRACKING", verified, devFixed, deployed);
        }
        return s;
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "VERIFYING" -> "待验证";
            case "FIXED" -> "已修复";
            case "DEPLOYED" -> "已上线";
            case "CLOSED" -> "已关闭";
            default -> "跟踪中";
        };
    }

    private String formatIssueCode(Object issueNo) {
        if (issueNo == null) {
            return "ISS-?";
        }
        return "ISS-" + issueNo;
    }

    private String normalizeEnvironment(Object v) {
        if (v == null) {
            return "TEST";
        }
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        return "PROD".equals(s) ? "PROD" : "TEST";
    }

    private String normalizeProjectType(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return "APP";
        }
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "PVP", "WEB" -> s;
            default -> "APP";
        };
    }

    private String normalizePriority(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return "MEDIUM";
        }
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "CRITICAL", "HIGH", "LOW" -> s;
            default -> "MEDIUM";
        };
    }

    private List<String> parseImages(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "[]".equals(s)) {
            return List.of();
        }
        try {
            JsonNode n = JSON.readTree(s);
            if (n.isArray()) {
                List<String> out = new ArrayList<>();
                for (JsonNode e : n) {
                    String u = e.asText();
                    if (u != null && !u.isBlank()) {
                        out.add(u.trim());
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            /* 非 JSON 就当没有图 */
        }
        return List.of();
    }

    private String imagesToJson(Object raw) {
        List<String> list = parseImages(raw);
        if (list.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> splitNames(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : String.valueOf(raw).split("[,，、]")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    private static Object firstPresent(Map<String, Object> body, String... keys) {
        for (String k : keys) {
            if (body.containsKey(k)) {
                return body.get(k);
            }
        }
        return null;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static int num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return "1".equals(String.valueOf(v)) || "true".equalsIgnoreCase(String.valueOf(v)) ? 1 : 0;
    }

    private static int bool(Object v) {
        return num(v);
    }
}
