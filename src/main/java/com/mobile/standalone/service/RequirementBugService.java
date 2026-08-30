package com.mobile.standalone.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_req (requirement_no),
                        INDEX idx_case (case_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[bugs] 表已就绪");
        } catch (Exception e) {
            log.warn("[bugs] 建表失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listByRequirement(String requirementNo) {
        if (requirementNo == null || requirementNo.isBlank()) {
            return List.of();
        }
        try {
            return jdbcTemplate.queryForList("""
                    SELECT id, requirement_no AS requirementNo, app_version AS appVersion, seq,
                           module, description, images, developer, tester,
                           fixed, verified, note, case_id AS caseId,
                           created_at AS createdAt, updated_at AS updatedAt
                      FROM requirement_bugs
                     WHERE requirement_no = ?
                     ORDER BY seq, created_at
                    """, requirementNo.trim());
        } catch (Exception e) {
            log.debug("[bugs] 查询失败: {}", e.getMessage());
            return List.of();
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

    public String create(Map<String, Object> body) {
        String reqNo = str(body.get("requirementNo"));
        String desc = str(body.get("description"));
        if (reqNo == null || reqNo.isBlank()) {
            throw new IllegalArgumentException("要挂到哪个需求单？");
        }
        if (desc == null || desc.isBlank()) {
            throw new IllegalArgumentException("问题描述必填 —— 只有截图没有描述，过两周谁也想不起来是什么问题");
        }
        String id = "bug-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO requirement_bugs
                    (id, requirement_no, app_version, seq, module, description, images,
                     developer, tester, fixed, verified, note, case_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, reqNo.trim(), str(body.get("appVersion")), nextSeq(reqNo.trim()),
                str(body.get("module")), desc.trim(), str(body.get("images")),
                str(body.get("developer")), str(body.get("tester")),
                bool(body.get("fixed")), bool(body.get("verified")),
                str(body.get("note")), str(body.get("caseId")));
        log.info("[bugs] {} 新增一条 bug", reqNo);
        return id;
    }

    public boolean update(String id, Map<String, Object> body) {
        try {
            return jdbcTemplate.update("""
                    UPDATE requirement_bugs
                       SET module = ?, description = ?, images = ?, developer = ?, tester = ?,
                           fixed = ?, verified = ?, note = ?
                     WHERE id = ?
                    """,
                    str(body.get("module")), str(body.get("description")), str(body.get("images")),
                    str(body.get("developer")), str(body.get("tester")),
                    bool(body.get("fixed")), bool(body.get("verified")),
                    str(body.get("note")), id) > 0;
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
        // 「修了但还没验」是最容易漏的一档：开发说改好了，而没人回去点一遍
        out.put("fixedNotVerified", rows.stream()
                .filter(r -> num(r.get("fixed")) == 1 && num(r.get("verified")) == 0).count());
        return out;
    }

    /**
     * 导成 wiki 那张表能直接粘的 HTML。
     * <p>
     * Confluence 粘 HTML 表格会保留表结构，这一步省掉的是「一条条重敲九列」。
     * <b>截图粘不过去</b>——图片走的是本机地址，wiki 服务器访问不到，
     * 所以这里只列文件名，图还得手动拖。说清楚比让人贴完才发现全是裂图强。
     */
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
        return o == null ? null : String.valueOf(o);
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
