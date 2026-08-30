package com.mobile.standalone.service;

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
 * 测出来的 Bug，挂在需求单上。
 * <p>
 * 字段是照着 wiki 那张表定的（所属模块 / 问题描述 / 截图 / 开发 / 测试 /
 * 是否修复 / 是否验证 / 备注）——<b>列对不上的话，搬过去还得逐条重填</b>，
 * 那就等于没在平台记。
 * <p>
 * 关联用例是<b>可选</b>的：有些 bug 是顺手点出来的，不属于任何一条用例。
 * 强制要求选用例，人就会随便挑一条填进去，那比不填更坏——
 * 以后按用例查 bug 时全是错的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementBugService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS requirement_bugs (
                        id VARCHAR(64) PRIMARY KEY,
                        requirement_no VARCHAR(64) NOT NULL,
                        app_version VARCHAR(128) DEFAULT NULL,
                        seq INT DEFAULT 0 COMMENT 'wiki 表里的序号',
                        module VARCHAR(255) DEFAULT NULL COMMENT '所属模块',
                        description TEXT NOT NULL COMMENT '问题描述',
                        images TEXT DEFAULT NULL COMMENT '截图相对路径，JSON 数组',
                        developer VARCHAR(64) DEFAULT NULL COMMENT '开发',
                        tester VARCHAR(64) DEFAULT NULL COMMENT '测试',
                        fixed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否修复',
                        verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否验证',
                        note VARCHAR(1024) DEFAULT NULL COMMENT '备注',
                        case_id VARCHAR(64) DEFAULT NULL COMMENT '哪条用例测出来的，可空',
                        bug_no INT DEFAULT NULL COMMENT '全局展示编号',
                        priority VARCHAR(16) DEFAULT 'MEDIUM' COMMENT 'CRITICAL/HIGH/MEDIUM/LOW',
                        status VARCHAR(16) DEFAULT 'OPEN' COMMENT 'OPEN/FIXING/TO_VERIFY/CLOSED',
                        environment VARCHAR(16) DEFAULT 'TEST' COMMENT 'TEST/PROD',
                        project_code VARCHAR(128) DEFAULT NULL COMMENT '归属项目/服务',
                        lang VARCHAR(8) DEFAULT NULL COMMENT 'zh/en，海外多语言分组',
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_req (requirement_no),
                        INDEX idx_case (case_id),
                        INDEX idx_bug_no (bug_no)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            widenColumn("bug_no", "INT DEFAULT NULL COMMENT '全局展示编号'");
            widenColumn("priority", "VARCHAR(16) DEFAULT 'MEDIUM'");
            widenColumn("status", "VARCHAR(16) DEFAULT 'OPEN'");
            widenColumn("environment", "VARCHAR(16) DEFAULT 'TEST'");
            widenColumn("project_code", "VARCHAR(128) DEFAULT NULL");
            widenColumn("lang", "VARCHAR(8) DEFAULT NULL");
            backfillBugMeta();
            log.info("[bugs] 表已就绪");
        } catch (Exception e) {
            log.warn("[bugs] 建表失败: {}", e.getMessage());
        }
    }

    private void widenColumn(String col, String ddl) {
        try {
            jdbcTemplate.execute("ALTER TABLE requirement_bugs ADD COLUMN " + col + " " + ddl);
        } catch (Exception ignored) {
            /* 列已存在 */
        }
    }

    /** 老数据补全局编号与状态 */
    private void backfillBugMeta() {
        try {
            jdbcTemplate.update("""
                    UPDATE requirement_bugs
                       SET status = CASE
                           WHEN verified = 1 THEN 'CLOSED'
                           WHEN fixed = 1 THEN 'TO_VERIFY'
                           ELSE COALESCE(NULLIF(status, ''), 'OPEN')
                       END
                     WHERE status IS NULL OR status = ''
                    """);
            Integer maxNo = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(bug_no), 0) FROM requirement_bugs", Integer.class);
            int next = maxNo == null ? 1 : maxNo + 1;
            List<Map<String, Object>> missing = jdbcTemplate.queryForList(
                    "SELECT id FROM requirement_bugs WHERE bug_no IS NULL ORDER BY created_at, id");
            for (Map<String, Object> row : missing) {
                jdbcTemplate.update("UPDATE requirement_bugs SET bug_no = ? WHERE id = ?", next++, row.get("id"));
            }
        } catch (Exception e) {
            log.debug("[bugs] 回填元数据跳过: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listByRequirement(String requirementNo) {
        if (requirementNo == null || requirementNo.isBlank()) {
            return List.of();
        }
        try {
            return decorateRows(jdbcTemplate.queryForList("""
                    SELECT id, requirement_no AS requirementNo, app_version AS appVersion, seq,
                           module, description, images, developer, tester,
                           fixed, verified, note, case_id AS caseId,
                           bug_no AS bugNo, priority, status, environment,
                           project_code AS projectCode, lang,
                           created_at AS createdAt, updated_at AS updatedAt
                      FROM requirement_bugs
                     WHERE requirement_no = ?
                     ORDER BY seq, created_at
                    """, requirementNo.trim()));
        } catch (Exception e) {
            log.debug("[bugs] 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** Bug 管理页：带筛选的全量列表 */
    public Map<String, Object> listForManage(
            String environment,
            String status,
            String priority,
            String assignee,
            String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT b.id, b.requirement_no AS requirementNo, b.app_version AS appVersion, b.seq,
                       b.module, b.description, b.images, b.developer, b.tester,
                       b.fixed, b.verified, b.note, b.case_id AS caseId,
                       b.bug_no AS bugNo, b.priority, b.status, b.environment,
                       b.project_code AS projectCode, b.lang,
                       b.created_at AS createdAt, b.updated_at AS updatedAt,
                       c.title AS caseTitle, c.server_service AS caseProject,
                       c.sides AS caseSides, c.app_id AS caseAppId
                  FROM requirement_bugs b
                  LEFT JOIN ai_test_cases c ON b.case_id IS NOT NULL AND b.case_id <> ''
                     AND c.id = CAST(b.case_id AS UNSIGNED)
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (environment != null && !environment.isBlank()) {
            sql.append(" AND b.environment = ?");
            args.add(environment.trim().toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append(" AND b.status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" AND b.priority = ?");
            args.add(priority.trim().toUpperCase(Locale.ROOT));
        }
        if (assignee != null && !assignee.isBlank()) {
            sql.append(" AND b.developer = ?");
            args.add(assignee.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (b.description LIKE ? OR b.module LIKE ? OR b.requirement_no LIKE ?");
            sql.append(" OR CAST(b.bug_no AS CHAR) LIKE ? OR b.developer LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY b.created_at DESC, b.bug_no DESC");

        List<Map<String, Object>> bugs;
        try {
            bugs = decorateRows(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
        } catch (Exception e) {
            log.debug("[bugs] manage 查询失败: {}", e.getMessage());
            bugs = List.of();
        }

        Set<String> assignees = new LinkedHashSet<>();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        statusCounts.put("ALL", (long) bugs.size());
        for (String st : List.of("OPEN", "FIXING", "TO_VERIFY", "CLOSED")) {
            statusCounts.put(st, 0L);
        }
        for (Map<String, Object> b : bugs) {
            if (b.get("developer") != null && !String.valueOf(b.get("developer")).isBlank()) {
                assignees.add(String.valueOf(b.get("developer")));
            }
            String st = String.valueOf(b.get("status"));
            statusCounts.merge(st, 1L, Long::sum);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bugs", bugs);
        out.put("assignees", new ArrayList<>(assignees));
        out.put("statusCounts", statusCounts);
        return out;
    }

    public Map<String, Object> getById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = decorateRows(jdbcTemplate.queryForList("""
                    SELECT id, requirement_no AS requirementNo, app_version AS appVersion, seq,
                           module, description, images, developer, tester,
                           fixed, verified, note, case_id AS caseId,
                           bug_no AS bugNo, priority, status, environment,
                           project_code AS projectCode, lang,
                           created_at AS createdAt, updated_at AS updatedAt
                      FROM requirement_bugs
                     WHERE id = ?
                    """, id));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 每个需求单下从 1 开始编号，和 wiki 表里的序号一致 */
    private int nextSeq(String requirementNo) {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) FROM requirement_bugs WHERE requirement_no = ?",
                    Integer.class, requirementNo);
            return (max == null ? 0 : max) + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private int nextBugNo() {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(bug_no), 0) FROM requirement_bugs", Integer.class);
            return (max == null ? 0 : max) + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    public String create(Map<String, Object> body) {
        String reqNo = str(body.get("requirementNo"));
        String desc = str(body.get("description"));
        if (reqNo == null || reqNo.isBlank()) {
            throw new IllegalArgumentException("要挂到哪个需求单？");
        }
        if (desc == null || desc.isBlank()) {
            throw new IllegalArgumentException("问题描述必填 —— 只有截图没有描述，过两周谁也想不起来是什么问题");
        }
        enrichFromCase(body);
        StatusFields sf = resolveStatusFields(body, null);
        String id = "bug-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO requirement_bugs
                    (id, requirement_no, app_version, seq, module, description, images,
                     developer, tester, fixed, verified, note, case_id,
                     bug_no, priority, status, environment, project_code, lang)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, reqNo.trim(), str(body.get("appVersion")), nextSeq(reqNo.trim()),
                str(body.get("module")), desc.trim(), str(body.get("images")),
                str(body.get("developer")), str(body.get("tester")),
                sf.fixed(), sf.verified(), str(body.get("note")), str(body.get("caseId")),
                nextBugNo(), normalizePriority(body.get("priority")), sf.status(),
                normalizeEnvironment(body.get("environment")),
                str(body.get("projectCode")), str(body.get("lang")));
        log.info("[bugs] {} 新增一条 bug", reqNo);
        return id;
    }

    public boolean update(String id, Map<String, Object> body) {
        try {
            Map<String, Object> existing = getById(id);
            if (existing == null) {
                return false;
            }
            StatusFields sf = resolveStatusFields(body, existing);
            String priority = body.containsKey("priority")
                    ? normalizePriority(body.get("priority"))
                    : normalizePriority(existing.get("priority"));
            return jdbcTemplate.update("""
                    UPDATE requirement_bugs
                       SET module = ?, description = ?, images = ?, developer = ?, tester = ?,
                           fixed = ?, verified = ?, note = ?, priority = ?, status = ?
                     WHERE id = ?
                    """,
                    body.containsKey("module") ? str(body.get("module")) : str(existing.get("module")),
                    body.containsKey("description") ? str(body.get("description")) : str(existing.get("description")),
                    body.containsKey("images") ? str(body.get("images")) : str(existing.get("images")),
                    body.containsKey("developer") ? str(body.get("developer")) : str(existing.get("developer")),
                    body.containsKey("tester") ? str(body.get("tester")) : str(existing.get("tester")),
                    sf.fixed(), sf.verified(),
                    body.containsKey("note") ? str(body.get("note")) : str(existing.get("note")),
                    priority, sf.status(), id) > 0;
        } catch (Exception e) {
            log.warn("[bugs] 更新失败 {}: {}", id, e.getMessage());
            return false;
        }
    }

    public boolean delete(String id) {
        try {
            return jdbcTemplate.update("DELETE FROM requirement_bugs WHERE id = ?", id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 给需求单卡片用的一行统计：几条、修了几条、验了几条 */
    public Map<String, Object> summaryOf(String requirementNo) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = listByRequirement(requirementNo);
        out.put("total", rows.size());
        out.put("fixed", rows.stream().filter(r -> num(r.get("fixed")) == 1).count());
        out.put("verified", rows.stream().filter(r -> num(r.get("verified")) == 1).count());
        out.put("fixedNotVerified", rows.stream()
                .filter(r -> num(r.get("fixed")) == 1 && num(r.get("verified")) == 0).count());
        return out;
    }

    public String toWikiHtml(String requirementNo) {
        List<Map<String, Object>> rows = listByRequirement(requirementNo);
        StringBuilder sb = new StringBuilder();
        sb.append("<table><tbody><tr>")
                .append("<th>序号</th><th>所属模块</th><th>问题描述</th><th>截图</th>")
                .append("<th>开发</th><th>测试</th><th>是否修复</th><th>是否验证</th><th>备注</th>")
                .append("</tr>");
        for (Map<String, Object> r : rows) {
            sb.append("<tr>")
                    .append(td(r.get("seq")))
                    .append(td(r.get("module")))
                    .append(td(r.get("description")))
                    .append(td(imageNames(str(r.get("images")))))
                    .append(td(r.get("developer")))
                    .append(td(r.get("tester")))
                    .append(td(num(r.get("fixed")) == 1 ? "是" : ""))
                    .append(td(num(r.get("verified")) == 1 ? "是" : ""))
                    .append(td(r.get("note")))
                    .append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    public String exportManageHtml(List<Map<String, Object>> bugs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table><tbody><tr>")
                .append("<th>编号</th><th>需求单</th><th>模块</th><th>概要</th><th>优先级</th>")
                .append("<th>状态</th><th>负责人</th><th>测试</th><th>发现时间</th>")
                .append("</tr>");
        for (Map<String, Object> r : bugs) {
            sb.append("<tr>")
                    .append(td(r.get("bugCode")))
                    .append(td(r.get("requirementNo")))
                    .append(td(r.get("module")))
                    .append(td(r.get("description")))
                    .append(td(r.get("priority")))
                    .append(td(r.get("statusLabel")))
                    .append(td(r.get("developer")))
                    .append(td(r.get("tester")))
                    .append(td(r.get("createdAt")))
                    .append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private void enrichFromCase(Map<String, Object> body) {
        String caseId = str(body.get("caseId"));
        if (caseId == null || caseId.isBlank()) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT title, module, priority, app_version AS appVersion,
                           server_service AS serverService, sides, app_id AS appId,
                           requirement_no AS requirementNo
                      FROM ai_test_cases WHERE id = ?
                    """, Long.parseLong(caseId.trim()));
            if (rows.isEmpty()) {
                return;
            }
            Map<String, Object> c = rows.get(0);
            if (body.get("module") == null || String.valueOf(body.get("module")).isBlank()) {
                body.put("module", c.get("module"));
            }
            if (body.get("appVersion") == null) {
                body.put("appVersion", c.get("appVersion"));
            }
            if (body.get("projectCode") == null || String.valueOf(body.get("projectCode")).isBlank()) {
                body.put("projectCode", c.get("serverService"));
            }
            if (body.get("priority") == null || String.valueOf(body.get("priority")).isBlank()) {
                body.put("priority", mapCasePriority(c.get("priority")));
            }
            if (body.get("lang") == null) {
                body.put("lang", langOfTitle(str(c.get("title"))));
            }
            if (body.get("environment") == null) {
                body.put("environment", "TEST");
            }
        } catch (Exception e) {
            log.debug("[bugs] 从用例补全跳过: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> decorateRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            String status = normalizeStatus(m.get("status"), num(m.get("fixed")), num(m.get("verified")));
            m.put("status", status);
            m.put("statusLabel", statusLabel(status));
            m.put("priority", normalizePriority(m.get("priority")));
            m.put("bugCode", formatBugCode(m.get("bugNo")));
            if (m.get("projectCode") == null || String.valueOf(m.get("projectCode")).isBlank()) {
                Object cp = m.get("caseProject");
                if (cp != null && !String.valueOf(cp).isBlank()) {
                    m.put("projectCode", cp);
                }
            }
            out.add(m);
        }
        return out;
    }

    private record StatusFields(String status, int fixed, int verified) {
    }

    private StatusFields resolveStatusFields(Map<String, Object> body, Map<String, Object> existing) {
        int fixed = existing != null ? num(existing.get("fixed")) : 0;
        int verified = existing != null ? num(existing.get("verified")) : 0;
        String status = existing != null ? normalizeStatus(existing.get("status"), fixed, verified) : "OPEN";

        if (body.containsKey("status") && body.get("status") != null) {
            status = normalizeStatus(body.get("status"), fixed, verified);
            return switch (status) {
                case "CLOSED" -> new StatusFields(status, 1, 1);
                case "TO_VERIFY" -> new StatusFields(status, 1, 0);
                case "FIXING" -> new StatusFields(status, 0, 0);
                default -> new StatusFields("OPEN", 0, 0);
            };
        }
        if (body.containsKey("fixed") || body.containsKey("verified")) {
            fixed = body.containsKey("fixed") ? bool(body.get("fixed")) : fixed;
            verified = body.containsKey("verified") ? bool(body.get("verified")) : verified;
            status = normalizeStatus(null, fixed, verified);
        }
        return new StatusFields(status, fixed, verified);
    }

    private static String normalizeStatus(Object status, int fixed, int verified) {
        if (verified == 1) {
            return "CLOSED";
        }
        if (status != null && !String.valueOf(status).isBlank()) {
            String s = String.valueOf(status).trim().toUpperCase(Locale.ROOT);
            if (Set.of("OPEN", "FIXING", "TO_VERIFY", "CLOSED").contains(s)) {
                if ("CLOSED".equals(s) && verified == 0 && fixed == 1) {
                    return "TO_VERIFY";
                }
                return s;
            }
        }
        if (fixed == 1) {
            return "TO_VERIFY";
        }
        return "OPEN";
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "FIXING" -> "修复中";
            case "TO_VERIFY" -> "待验证";
            case "CLOSED" -> "已关闭";
            default -> "待修复";
        };
    }

    private static String normalizePriority(Object p) {
        if (p == null || String.valueOf(p).isBlank()) {
            return "MEDIUM";
        }
        String s = String.valueOf(p).trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("P0") || "CRITICAL".equals(s)) {
            return "CRITICAL";
        }
        if (s.startsWith("P1") || "HIGH".equals(s)) {
            return "HIGH";
        }
        if (s.startsWith("P2") || "MEDIUM".equals(s)) {
            return "MEDIUM";
        }
        if (s.startsWith("P3") || "LOW".equals(s)) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private static String mapCasePriority(Object p) {
        return normalizePriority(p);
    }

    private static String normalizeEnvironment(Object env) {
        if (env == null || String.valueOf(env).isBlank()) {
            return "TEST";
        }
        String s = String.valueOf(env).trim().toUpperCase(Locale.ROOT);
        return "PROD".equals(s) || "PRODUCTION".equals(s) ? "PROD" : "TEST";
    }

    private static String langOfTitle(String title) {
        if (title == null || title.isBlank()) {
            return "zh";
        }
        int cjk = 0;
        int latin = 0;
        for (char ch : title.toCharArray()) {
            if (ch >= 0x4e00 && ch <= 0x9fff) {
                cjk++;
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                latin++;
            }
        }
        return cjk >= latin ? "zh" : "en";
    }

    private static String formatBugCode(Object bugNo) {
        if (bugNo == null) {
            return "BUG-?";
        }
        try {
            int n = Integer.parseInt(String.valueOf(bugNo));
            return "BUG-" + n;
        } catch (NumberFormatException e) {
            return "BUG-" + bugNo;
        }
    }

    private static String imageNames(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String p : imagesJson.replaceAll("[\\[\\]\"]", "").split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                names.add(t.substring(t.lastIndexOf('/') + 1));
            }
        }
        return names.isEmpty() ? "" : names.size() + " 张：" + String.join("、", names);
    }

    private static String td(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return "<td>" + s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br/>") + "</td>";
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private static int bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equals(String.valueOf(o)) || "1".equals(String.valueOf(o))
                ? 1 : 0;
    }

    private static int num(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
