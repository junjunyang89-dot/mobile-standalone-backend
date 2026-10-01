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
 * 事故沉淀：每次炸完记一条，按类型归类，标哪些以后能做成检查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentPlaybookService {

    private final JdbcTemplate jdbcTemplate;

    private static final String SELECT_COLS = """
            id, title, category, symptom, root_cause AS rootCause, resolution,
            fingerprints, playbook, artifact, tool_status AS toolStatus, tool_note AS toolNote,
            created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt
            """;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS incident_playbooks (
                        id VARCHAR(64) PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        category VARCHAR(16) NOT NULL DEFAULT 'OTHER',
                        symptom TEXT DEFAULT NULL,
                        root_cause TEXT DEFAULT NULL,
                        resolution TEXT DEFAULT NULL,
                        fingerprints TEXT DEFAULT NULL COMMENT '下次靠这些报错认出来，一行一条',
                        playbook TEXT DEFAULT NULL COMMENT '标准排查步骤',
                        artifact VARCHAR(128) DEFAULT NULL COMMENT '如 appuser-api',
                        tool_status VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/SCRIPT/TOOL',
                        tool_note TEXT DEFAULT NULL,
                        created_by VARCHAR(64) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_inc_cat (category),
                        INDEX idx_inc_tool (tool_status),
                        INDEX idx_inc_updated (updated_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            seedAppuserIfEmpty();
            log.info("[incident-playbooks] 表已就绪");
        } catch (Exception e) {
            log.warn("[incident-playbooks] 建表失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> list(String category, String toolStatus, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS)
                .append(" FROM incident_playbooks WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            sql.append(" AND category = ?");
            args.add(IncidentPlaybookRules.normalizeCategory(category));
        }
        if (toolStatus != null && !toolStatus.isBlank() && !"ALL".equalsIgnoreCase(toolStatus)) {
            sql.append(" AND tool_status = ?");
            args.add(IncidentPlaybookRules.normalizeToolStatus(toolStatus));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (title LIKE ? OR symptom LIKE ? OR root_cause LIKE ?");
            sql.append(" OR fingerprints LIKE ? OR artifact LIKE ? OR playbook LIKE ?)");
            for (int i = 0; i < 6; i++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY updated_at DESC LIMIT 200");
        List<Map<String, Object>> rows;
        try {
            rows = decorate(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
        } catch (Exception e) {
            log.debug("[incident-playbooks] 列表失败: {}", e.getMessage());
            rows = List.of();
        }
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        Map<String, Long> toolCounts = new LinkedHashMap<>();
        for (String c : IncidentPlaybookRules.CATEGORIES) {
            categoryCounts.put(c, 0L);
        }
        for (String t : IncidentPlaybookRules.TOOL_STATUSES) {
            toolCounts.put(t, 0L);
        }
        try {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "SELECT category, tool_status AS toolStatus FROM incident_playbooks")) {
                String c = IncidentPlaybookRules.normalizeCategory(str(row.get("category")));
                String t = IncidentPlaybookRules.normalizeToolStatus(str(row.get("toolStatus")));
                categoryCounts.merge(c, 1L, Long::sum);
                toolCounts.merge(t, 1L, Long::sum);
            }
        } catch (Exception ignored) {
            // 归纳数字失败不挡列表
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("incidents", rows);
        out.put("categoryCounts", categoryCounts);
        out.put("toolCounts", toolCounts);
        return out;
    }

    public Map<String, Object> get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = decorate(jdbcTemplate.queryForList(
                    "SELECT " + SELECT_COLS + " FROM incident_playbooks WHERE id = ?", id.trim()));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    public String create(Map<String, Object> body, String createdBy) {
        String id = "inc-" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO incident_playbooks
                    (id, title, category, symptom, root_cause, resolution, fingerprints,
                     playbook, artifact, tool_status, tool_note, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                IncidentPlaybookRules.requireTitle(str(body.get("title"))),
                IncidentPlaybookRules.normalizeCategory(str(body.get("category"))),
                str(body.get("symptom")),
                str(body.get("rootCause")),
                str(body.get("resolution")),
                IncidentPlaybookRules.fingerprintsText(body.get("fingerprints")),
                str(body.get("playbook")),
                blankToNull(str(body.get("artifact"))),
                IncidentPlaybookRules.normalizeToolStatus(str(body.get("toolStatus"))),
                str(body.get("toolNote")),
                createdBy);
        return id;
    }

    public boolean update(String id, Map<String, Object> body) {
        Map<String, Object> existing = get(id);
        if (existing == null) {
            return false;
        }
        return jdbcTemplate.update("""
                UPDATE incident_playbooks
                   SET title = ?, category = ?, symptom = ?, root_cause = ?, resolution = ?,
                       fingerprints = ?, playbook = ?, artifact = ?, tool_status = ?,
                       tool_note = ?, updated_at = NOW()
                 WHERE id = ?
                """,
                body.containsKey("title")
                        ? IncidentPlaybookRules.requireTitle(str(body.get("title")))
                        : str(existing.get("title")),
                IncidentPlaybookRules.normalizeCategory(
                        str(body.containsKey("category") ? body.get("category") : existing.get("category"))),
                body.containsKey("symptom") ? str(body.get("symptom")) : str(existing.get("symptom")),
                body.containsKey("rootCause") ? str(body.get("rootCause")) : str(existing.get("rootCause")),
                body.containsKey("resolution") ? str(body.get("resolution")) : str(existing.get("resolution")),
                IncidentPlaybookRules.fingerprintsText(
                        body.containsKey("fingerprints") ? body.get("fingerprints") : existing.get("fingerprints")),
                body.containsKey("playbook") ? str(body.get("playbook")) : str(existing.get("playbook")),
                blankToNull(str(body.containsKey("artifact") ? body.get("artifact") : existing.get("artifact"))),
                IncidentPlaybookRules.normalizeToolStatus(
                        str(body.containsKey("toolStatus") ? body.get("toolStatus") : existing.get("toolStatus"))),
                body.containsKey("toolNote") ? str(body.get("toolNote")) : str(existing.get("toolNote")),
                id) > 0;
    }

    public boolean delete(String id) {
        try {
            return jdbcTemplate.update("DELETE FROM incident_playbooks WHERE id = ?", id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void seedAppuserIfEmpty() {
        try {
            Integer n = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM incident_playbooks", Integer.class);
            if (n != null && n > 0) {
                return;
            }
            Map<String, Object> seed = new LinkedHashMap<>();
            seed.put("title", "appuser-api：Git 源码和编进包的 API 不是同一份");
            seed.put("category", "DEPENDENCY");
            seed.put("artifact", "appuser-api");
            seed.put("toolStatus", "TOOL");
            seed.put("toolNote", """
                    平台「事故沉淀」页可 SSH 到构建机看 .m2 里该 SNAPSHOT jar 的时间。
                    过旧就清这一版缓存，下次构建加 -U。Jenkins 若不是默认那台，配 BUILD_SSH_HOST。""");
            seed.put("fingerprints", List.of(
                    "does not override",
                    "cannot find symbol",
                    "Method has too many Body parameters"));
            seed.put("symptom", """
                    appuser-api 是公共 Feign 包，20 多个项目从 Nexus 引用。
                    出问题的不是某一份业务代码，而是 Git 里的 API 和实际编进包 / 拉到的 Nexus 包不是同一份。
                    源项目 appuser：只编 appuser-service，Maven 去 Nexus 拉旧 API。
                    引用项目（community / decoration）：自己按新接口写了 @Override 或新参数，拉到的是旧 jar。""");
            seed.put("rootCause", """
                    Nexus 上的 SNAPSHOT 可能是开发本机 deploy 的，不一定是当前 master。
                    Jenkins .m2 里同名目录还在，默认不重新拉，08:47 的包 ≠ 后来新上传的包。""");
            seed.put("resolution", """
                    1. appuser 在仓库根目录打：mvn clean package -pl appuser-service -am（不要只 cd service）。
                    2. API 只从 appuser 的 master CI 发布：mvn clean deploy -pl appuser-api -am。禁止本机随手 deploy 覆盖 SNAPSHOT。
                    3. 引用项目命令加 -U；仍旧则删 .m2 里对应 SNAPSHOT 再打。
                    4. 改了 Feign/DTO 升小版本，不要反复覆盖同一个 SNAPSHOT。""");
            seed.put("playbook", """
                    遇到 does not override / cannot find symbol / too many Body parameters：
                    1. 缺的是 com.steamcn.appuser.* → API 包不对；启动期 Feign Body parameters → 接口缺注解，多半旧 jar。
                    2. 本仓库有没有 appuser-api 模块？有 → 必须 -am；没有 → 看 Nexus + 缓存。
                    3. grep pom 里的 appuser-api 版本。
                    4. ls -l Jenkins .m2 里该版本 jar 的时间，不要只看文件夹名。
                    5. jar tf / javap 对照报错方法（如 getUserCommentReportCount、securityCode）。""");
            create(seed, "系统预置");
            log.info("[incident-playbooks] 已预置 appuser-api 事故");
        } catch (Exception e) {
            log.debug("[incident-playbooks] 预置跳过: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> decorate(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> vo = new LinkedHashMap<>(row);
            String cat = IncidentPlaybookRules.normalizeCategory(str(row.get("category")));
            String tool = IncidentPlaybookRules.normalizeToolStatus(str(row.get("toolStatus")));
            vo.put("category", cat);
            vo.put("categoryLabel", IncidentPlaybookRules.categoryLabel(cat));
            vo.put("toolStatus", tool);
            vo.put("toolStatusLabel", IncidentPlaybookRules.toolStatusLabel(tool));
            vo.put("fingerprints", IncidentPlaybookRules.parseFingerprints(row.get("fingerprints")));
            out.add(vo);
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
