package com.mobile.standalone.service.apitest;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 需求单 ↔ 接口 的关联。
 * <p>
 * 关联<b>不靠人填</b>：你们的分支名里已经带着单号（{@code fch/20260804/APP-8928}），
 * 跑一次接口对比分析就能把变更的接口自动挂到那个单子上。
 * 让人填的话，一个版本几十个单子、每个几个接口，填两周就没人填了——
 * 而填了一半比没填更糟：你会以为没列出来的接口就是没改。
 * <p>
 * 单独一张表而不是往 {@code api_endpoints} 上加一列：一个接口会被多个版本的多个单子
 * 先后改到，加一列只能记住最后一次，而「9.3 的 APP-8928 当时改了哪些接口」
 * 恰恰是上线出问题时最想翻的东西。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementEndpointService {

    private final JdbcTemplate jdbcTemplate;
    private final ApiEndpointService apiEndpointService;
    private final com.mobile.standalone.service.capture.PacketCaptureService packetCaptureService;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS requirement_endpoints (
                        id VARCHAR(64) PRIMARY KEY,
                        requirement_no VARCHAR(64) NOT NULL,
                        project VARCHAR(64) DEFAULT NULL,
                        endpoint_id VARCHAR(64) DEFAULT NULL,
                        http_method VARCHAR(16) NOT NULL,
                        path VARCHAR(512) NOT NULL,
                        summary VARCHAR(512) DEFAULT NULL,
                        change_flag VARCHAR(32) DEFAULT NULL,
                        change_note VARCHAR(1024) DEFAULT NULL,
                        source_branch VARCHAR(255) DEFAULT NULL,
                        source VARCHAR(16) NOT NULL DEFAULT 'SYNC',
                        linked_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_req_ep (requirement_no, http_method, path(300)),
                        INDEX idx_req (requirement_no)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            ensureColumn("source", "VARCHAR(16) NOT NULL DEFAULT 'SYNC' "
                    + "COMMENT 'SYNC＝对比分析关联，MANUAL＝人工补的'");
            ensureColumn("lifecycle", "VARCHAR(16) NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED'");
            log.info("[req-endpoints] 表已就绪");
        } catch (Exception e) {
            log.warn("[req-endpoints] 建表失败: {}", e.getMessage());
        }
    }

    private void ensureColumn(String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'requirement_endpoints' AND COLUMN_NAME = ?",
                    Integer.class, column);
            if (n != null && n == 0) {
                jdbcTemplate.execute("ALTER TABLE requirement_endpoints ADD COLUMN " + column + " " + definition);
                log.info("[req-endpoints] 已补充 {} 列", column);
            }
        } catch (Exception e) {
            log.debug("[req-endpoints] {} 列检查跳过: {}", column, e.getMessage());
        }
    }

    /**
     * 人工补一个接口。
     * <p>
     * 对比分析只能认出<b>这个分支改过的</b>接口。而一个需求往往还会调到一批没改的接口
     * （「获取更新日志」这种），那些同样属于这条链路，出问题时一样要看——
     * 但分析永远不会把它们列出来。所以要能手填。
     * <p>
     * 标成 {@code MANUAL} 和分析来的区分开：一个是「这个分支确实改了它」（事实），
     * 一个是「我记得这个需求会调它」（判断）。混在一起，你就分不清哪些是验证过的。
     */
    public boolean addManual(String requirementNo, String project, String httpMethod,
                             String path, String summary) {
        if (requirementNo == null || requirementNo.isBlank()
                || path == null || path.isBlank()) {
            return false;
        }
        String m = httpMethod == null || httpMethod.isBlank()
                ? "GET" : httpMethod.trim().toUpperCase(java.util.Locale.ROOT);
        String p = path.trim();
        String resolved = RequirementEndpointProjectResolver.resolve(project, p);
        if (resolved == null || resolved.isBlank()) {
            return false;
        }
        String epId = adopt(resolved, m, p, summary);
        try {
            jdbcTemplate.update("""
                    INSERT INTO requirement_endpoints
                        (id, requirement_no, project, endpoint_id, http_method, path, summary, source)
                    VALUES (?,?,?,?,?,?,?, 'MANUAL')
                    ON DUPLICATE KEY UPDATE summary = VALUES(summary),
                        project = COALESCE(VALUES(project), project),
                        endpoint_id = COALESCE(VALUES(endpoint_id), endpoint_id)
                    """,
                    "re-" + java.util.UUID.randomUUID(), requirementNo.trim(), resolved, epId, m, p,
                    summary == null || summary.isBlank() ? null : summary.trim());
            return true;
        } catch (Exception e) {
            log.warn("[req-endpoints] 手工关联失败 {} {}: {}", m, p, e.getMessage());
            return false;
        }
    }

    /**
     * 把接口收进<b>接口清单</b>并归到项目，返回清单里的 id。
     * <p>
     * 少了这一步，抓包和手填挂上来的接口就只是需求单下面的几行字：
     * 它们不在清单里，「去生成用例」点过去什么都没有，覆盖率也永远是 0%——
     * 而页面上还理直气壮地写着「4 个接口没有启用的用例」，
     * 让人以为是自己没点，其实是根本生成不了。
     * <p>
     * 没给项目就挂不进去（{@link ApiEndpointService#ensureEndpointFor} 会返回 null）：
     * 清单是按项目分的，无主的接口进去了也查不出来。
     */
    private String adopt(String project, String httpMethod, String path, String summary) {
        String resolved = RequirementEndpointProjectResolver.resolve(project, path);
        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        return apiEndpointService.ensureEndpointFor(resolved, httpMethod, path, null, summary);
    }

    /**
     * 抓包抓到的接口挂上去。
     * <p>
     * 标成 {@code CAPTURE}，和另外两种分开——三者的证据强度是不一样的：
     * <ul>
     *   <li><b>CAPTURE</b>：这个操作<b>确实</b>调了它。最硬</li>
     *   <li><b>SYNC</b>：这个分支<b>确实改</b>了它。硬，但不代表这个需求会走到</li>
     *   <li><b>MANUAL</b>：我<b>记得</b>它会调。是判断，可能记错</li>
     * </ul>
     * 混成一种颜色，你就没法回答「这条链路是验过的还是想当然的」。
     * <p>
     * 已经有的不降级：分析或手填过的再被抓到，只更新时间，不把 source 改掉——
     * 抓包证明它被调用，不否定它被改过。
     */
    public int linkCaptured(String requirementNo, String project, List<Link> links) {
        if (requirementNo == null || requirementNo.isBlank() || links == null || links.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Link l : links) {
            if (l.httpMethod() == null || l.path() == null || l.path().isBlank()) {
                continue;
            }
            String resolved = RequirementEndpointProjectResolver.resolve(project, l.path());
            if (resolved == null || resolved.isBlank()) {
                continue;
            }
            try {
                String epId = adopt(resolved, l.httpMethod(), l.path(), l.summary());
                applyCaptureDetail(l, epId);
                apiEndpointService.markCaptured(epId);
                // captured_at 每次都刷新，source 保持不变。
                // 两者分工：source 记「它是怎么进来的」，captured_at 记「它被实际调用过」。
                // 合成一个字段的话，SYNC 接口被抓到就得改 source，那就丢掉了
                // 「这个接口是本次改动的」这个事实——而差集恰恰要靠它。
                jdbcTemplate.update("""
                        INSERT INTO requirement_endpoints
                            (id, requirement_no, project, endpoint_id, http_method, path, summary, source, captured_at)
                        VALUES (?,?,?,?,?,?,?, 'CAPTURE', NOW())
                        ON DUPLICATE KEY UPDATE linked_at = NOW(),
                            captured_at = NOW(),
                            project = COALESCE(VALUES(project), project),
                            endpoint_id = COALESCE(VALUES(endpoint_id), endpoint_id)
                        """,
                        "re-" + java.util.UUID.randomUUID(), requirementNo.trim(), resolved, epId,
                        l.httpMethod(), l.path(), l.summary());
                n++;
            } catch (Exception e) {
                log.debug("[req-endpoints] 抓包关联失败 {} {}: {}", l.httpMethod(), l.path(), e.getMessage());
            }
        }
        if (n > 0) {
            log.info("[req-endpoints] {} 从抓包关联了 {} 个接口", requirementNo, n);
        }
        return n;
    }

    /** 从抓包详情文件解析响应体字段样本，写入接口清单 */
    private void applyCaptureDetail(Link l, String endpointId) {
        if (endpointId == null || endpointId.isBlank() || l.captureSeq() == null) {
            return;
        }
        String sessionId = l.captureSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "last";
        }
        try {
            JsonNode detail = packetCaptureService.body(sessionId, l.captureSeq());
            if (detail == null) {
                return;
            }
            String reqBody = textOrNull(detail.path("reqBody"));
            String respBody = textOrNull(detail.path("respBody"));
            apiEndpointService.applyCaptureDetail(endpointId, reqBody, respBody);
        } catch (Exception e) {
            log.debug("[req-endpoints] 抓包样本写入失败 {} seq={}: {}",
                    endpointId, l.captureSeq(), e.getMessage());
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String t = n.asText(null);
        return t == null || t.isBlank() ? null : t;
    }

    /**
     * 把这一批接口挂到某个需求单上。
     * <p>
     * 同一条重复挂时更新变更说明而不是插第二条——同一个单子下一个接口只该有一行。
     * <b>不删旧的</b>：同一个单子可能分几次改（提测后又修一轮），
     * 每次只清空重建的话，前一轮改过的接口就从记录里消失了。
     *
     * @return 实际写入的条数
     */
    public int link(String requirementNo, String project, String branch, List<Link> links) {
        if (requirementNo == null || requirementNo.isBlank() || links == null || links.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Link l : links) {
            if (l.httpMethod() == null || l.path() == null || l.path().isBlank()) {
                continue;
            }
            try {
                jdbcTemplate.update("""
                        INSERT INTO requirement_endpoints
                            (id, requirement_no, project, endpoint_id, http_method, path,
                             summary, change_flag, change_note, source_branch)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        ON DUPLICATE KEY UPDATE
                            endpoint_id = VALUES(endpoint_id), summary = VALUES(summary),
                            change_flag = VALUES(change_flag), change_note = VALUES(change_note),
                            source_branch = VALUES(source_branch), linked_at = NOW(),
                            source = 'SYNC'
                        """,
                        "re-" + java.util.UUID.randomUUID(), requirementNo.trim(), project,
                        l.endpointId(), l.httpMethod(), l.path(), l.summary(),
                        l.changeFlag(), l.changeNote(), branch);
                n++;
            } catch (Exception e) {
                log.debug("[req-endpoints] 单条关联失败 {} {}: {}", l.httpMethod(), l.path(), e.getMessage());
            }
        }
        if (n > 0) {
            log.info("[req-endpoints] {} 关联了 {} 个接口（来自分支 {}）", requirementNo, n, branch);
        }
        return n;
    }

    /** 一条关联的完整信息，生成用例时要用到项目和 endpoint_id */
    public record LinkRow(String id, String requirementNo, String project, String endpointId,
                          String httpMethod, String path, String summary, String sourceBranch) {
    }

    public LinkRow get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            var rows = jdbcTemplate.queryForList("""
                    SELECT id, requirement_no, project, endpoint_id, http_method, path,
                           summary, source_branch
                      FROM requirement_endpoints WHERE id = ?
                    """, id.trim());
            if (rows.isEmpty()) {
                return null;
            }
            var r = rows.get(0);
            return new LinkRow(str(r.get("id")), str(r.get("requirement_no")), str(r.get("project")),
                    str(r.get("endpoint_id")), str(r.get("http_method")), str(r.get("path")),
                    str(r.get("summary")), str(r.get("source_branch")));
        } catch (Exception e) {
            log.warn("[req-endpoints] 查询单条失败 {}: {}", id, e.getMessage());
            return null;
        }
    }

    /** 补上 endpoint_id。老数据挂的时候还没有项目字段，收进清单之后要回写 */
    public void bindEndpoint(String id, String endpointId) {
        try {
            jdbcTemplate.update(
                    "UPDATE requirement_endpoints SET endpoint_id = ? WHERE id = ?", endpointId, id);
        } catch (Exception e) {
            log.warn("[req-endpoints] 回写 endpoint_id 失败 {}: {}", id, e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 按需求单汇总：测试环境的树就是按单号和分支长的。
     * <p>
     * 测试环境的集合<b>不按项目分</b>——一个单子可能同时改 H5 和三个后端服务，
     * 按项目切开的话，「这个单测完了没有」就没有一个地方能回答。
     */
    public List<Map<String, Object>> summaryByRequirement() {
        try {
            return jdbcTemplate.queryForList("""
                    SELECT requirement_no AS requirementNo,
                           MAX(source_branch) AS sourceBranch,
                           COUNT(*) AS total,
                           SUM(CASE WHEN verified = 1 THEN 1 ELSE 0 END) AS verified,
                           GROUP_CONCAT(DISTINCT source) AS sources
                      FROM requirement_endpoints
                     WHERE (lifecycle IS NULL OR lifecycle = '' OR lifecycle = 'ACTIVE')
                     GROUP BY requirement_no
                     ORDER BY requirement_no DESC
                    """);
        } catch (Exception e) {
            log.debug("[req-endpoints] 按单号汇总失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 按项目 + 需求单汇总，供接口清单测试环境树使用 */
    public List<Map<String, Object>> summaryByProjectAndRequirement() {
        try {
            return jdbcTemplate.queryForList("""
                    SELECT project, requirement_no AS requirementNo,
                           MAX(source_branch) AS sourceBranch,
                           COUNT(*) AS total,
                           SUM(CASE WHEN verified = 1 THEN 1 ELSE 0 END) AS verified
                      FROM requirement_endpoints
                     WHERE (lifecycle IS NULL OR lifecycle = '' OR lifecycle = 'ACTIVE')
                       AND project IS NOT NULL AND project <> ''
                     GROUP BY project, requirement_no
                     ORDER BY requirement_no DESC
                    """);
        } catch (Exception e) {
            log.debug("[req-endpoints] 按项目+单号汇总失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 这个需求改了哪些接口 */
    public List<Map<String, Object>> listByRequirement(String requirementNo) {
        if (requirementNo == null || requirementNo.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, requirement_no AS requirementNo, project, endpoint_id AS endpointId,
                           http_method AS httpMethod, path, summary,
                           change_flag AS changeFlag, change_note AS changeNote,
                           source_branch AS sourceBranch, source, linked_at AS linkedAt
                    FROM requirement_endpoints
                    WHERE requirement_no = ?
                      AND (lifecycle IS NULL OR lifecycle = '' OR lifecycle = 'ACTIVE')
                    ORDER BY source, path
                    """, requirementNo.trim());
            for (Map<String, Object> row : rows) {
                backfillRowProject(row);
            }
            return rows;
        } catch (Exception e) {
            log.debug("[req-endpoints] 查询跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /** 老数据缺 project 时按路径补全并回写库 */
    private void backfillRowProject(Map<String, Object> row) {
        String project = str(row.get("project"));
        if (project != null && !project.isBlank()) {
            return;
        }
        String path = str(row.get("path"));
        String inferred = RequirementEndpointProjectResolver.inferFromPath(path);
        if (inferred == null || inferred.isBlank()) {
            return;
        }
        String id = str(row.get("id"));
        String method = str(row.get("httpMethod"));
        String summary = str(row.get("summary"));
        String epId = adopt(inferred, method, path, summary);
        try {
            jdbcTemplate.update(
                    "UPDATE requirement_endpoints SET project = ?, "
                            + "endpoint_id = COALESCE(?, endpoint_id) WHERE id = ?",
                    inferred, epId, id);
            row.put("project", inferred);
            if (epId != null) {
                row.put("endpointId", epId);
            }
            log.info("[req-endpoints] 按路径补全项目 {} → {}", path, inferred);
        } catch (Exception e) {
            log.warn("[req-endpoints] 补全项目失败 {}: {}", id, e.getMessage());
        }
    }

    /** 显式指定归属项目（补全抓包时漏选的） */
    public boolean assignProject(String id, String projectCode) {
        LinkRow row = get(id);
        if (row == null) {
            return false;
        }
        String resolved = RequirementEndpointProjectResolver.resolve(projectCode, row.path());
        if (resolved == null || resolved.isBlank()) {
            return false;
        }
        String epId = adopt(resolved, row.httpMethod(), row.path(), row.summary());
        try {
            return jdbcTemplate.update(
                    "UPDATE requirement_endpoints SET project = ?, "
                            + "endpoint_id = COALESCE(?, endpoint_id) WHERE id = ?",
                    resolved, epId, id.trim()) > 0;
        } catch (Exception e) {
            log.warn("[req-endpoints] 指定项目失败 {}: {}", id, e.getMessage());
            return false;
        }
    }

    /** 一批需求各自关联了几个接口。清单上「这个单子动了几个接口」用它 */
    public List<Map<String, Object>> countByRequirements() {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT requirement_no AS requirementNo, COUNT(1) AS total "
                            + "FROM requirement_endpoints "
                            + "WHERE lifecycle IS NULL OR lifecycle = '' OR lifecycle = 'ACTIVE' "
                            + "GROUP BY requirement_no");
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 人工解掉一条错挂的关联 */
    public boolean unlink(String id) {
        try {
            return jdbcTemplate.update("DELETE FROM requirement_endpoints WHERE id = ?", id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 上线后归档：记录保留，测试视图默认不再展示 */
    public int archiveByRequirement(String requirementNo) {
        if (requirementNo == null || requirementNo.isBlank()) {
            return 0;
        }
        try {
            return jdbcTemplate.update(
                    "UPDATE requirement_endpoints SET lifecycle = 'ARCHIVED' WHERE requirement_no = ?",
                    requirementNo.trim());
        } catch (Exception e) {
            log.warn("[req-endpoints] 归档失败 {}: {}", requirementNo, e.getMessage());
            return 0;
        }
    }

    public record Link(String endpointId, String httpMethod, String path, String summary,
                       String changeFlag, String changeNote, Integer captureSeq,
                       String captureSessionId) {
        public Link(String endpointId, String httpMethod, String path, String summary,
                    String changeFlag, String changeNote) {
            this(endpointId, httpMethod, path, summary, changeFlag, changeNote, null, null);
        }
    }
}
