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
 * 测完容易忘的业务规则。挂在模块上找回，需求单只是来源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeNoteService {

    private final JdbcTemplate jdbcTemplate;

    private static final String SELECT_COLS = """
            id, title, body, images, module_id AS moduleId, module_label AS moduleLabel,
            requirement_no AS requirementNo, status, created_by AS createdBy,
            created_at AS createdAt, updated_at AS updatedAt
            """;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_notes (
                        id VARCHAR(64) PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        body TEXT DEFAULT NULL,
                        images TEXT DEFAULT NULL COMMENT '截图 URL JSON 数组',
                        module_id VARCHAR(64) NOT NULL,
                        module_label VARCHAR(255) DEFAULT NULL COMMENT '写入时的模块路径快照',
                        requirement_no VARCHAR(64) DEFAULT NULL,
                        status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/STALE',
                        created_by VARCHAR(64) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_kn_module (module_id),
                        INDEX idx_kn_req (requirement_no),
                        INDEX idx_kn_status (status),
                        INDEX idx_kn_updated (updated_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[knowledge-notes] 表已就绪");
        } catch (Exception e) {
            log.warn("[knowledge-notes] 建表失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> list(String moduleId, String requirementNo, String status, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS)
                .append(" FROM knowledge_notes WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (moduleId != null && !moduleId.isBlank()) {
            sql.append(" AND module_id = ?");
            args.add(moduleId.trim());
        }
        if (requirementNo != null && !requirementNo.isBlank()) {
            sql.append(" AND requirement_no = ?");
            args.add(requirementNo.trim().toUpperCase());
        }
        String st = status == null ? "" : status.trim();
        if (!st.isEmpty() && !"ALL".equalsIgnoreCase(st)) {
            sql.append(" AND status = ?");
            args.add(KnowledgeNoteRules.normalizeStatus(st));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (title LIKE ? OR body LIKE ? OR module_label LIKE ? OR requirement_no LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT 200");
        try {
            return decorate(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
        } catch (Exception e) {
            log.debug("[knowledge-notes] 列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = decorate(jdbcTemplate.queryForList(
                    "SELECT " + SELECT_COLS + " FROM knowledge_notes WHERE id = ?", id.trim()));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    public String create(Map<String, Object> body, String createdBy) {
        String title = KnowledgeNoteRules.requireTitle(str(body.get("title")));
        String moduleId = KnowledgeNoteRules.requireModuleId(str(body.get("moduleId")));
        String label = resolveModuleLabel(moduleId);
        if (label.isEmpty()) {
            throw new IllegalArgumentException("模块不存在，请从需求登记用的那棵模块树里选");
        }
        String req = KnowledgeNoteRules.optionalRequirementNo(str(body.get("requirementNo")));
        String status = KnowledgeNoteRules.normalizeStatus(str(body.get("status")));
        String images = KnowledgeNoteRules.imagesJson(body.get("images"));
        String noteBody = str(body.get("body"));
        String id = "kn-" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO knowledge_notes
                    (id, title, body, images, module_id, module_label, requirement_no, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, title, noteBody, images, moduleId, label, req, status, createdBy);
        return id;
    }

    public boolean update(String id, Map<String, Object> body) {
        Map<String, Object> existing = get(id);
        if (existing == null) {
            return false;
        }
        String title = body.containsKey("title")
                ? KnowledgeNoteRules.requireTitle(str(body.get("title")))
                : String.valueOf(existing.get("title"));
        String moduleId = body.containsKey("moduleId")
                ? KnowledgeNoteRules.requireModuleId(str(body.get("moduleId")))
                : str(existing.get("moduleId"));
        String label = resolveModuleLabel(moduleId);
        if (label.isEmpty()) {
            label = str(existing.get("moduleLabel"));
            if (body.containsKey("moduleId") && (label == null || label.isBlank())) {
                throw new IllegalArgumentException("模块不存在，请从需求登记用的那棵模块树里选");
            }
        }
        String req = body.containsKey("requirementNo")
                ? KnowledgeNoteRules.optionalRequirementNo(str(body.get("requirementNo")))
                : str(existing.get("requirementNo"));
        String status = body.containsKey("status")
                ? KnowledgeNoteRules.normalizeStatus(str(body.get("status")))
                : KnowledgeNoteRules.normalizeStatus(str(existing.get("status")));
        String images = body.containsKey("images")
                ? KnowledgeNoteRules.imagesJson(body.get("images"))
                : KnowledgeNoteRules.imagesJson(existing.get("images"));
        String noteBody = body.containsKey("body") ? str(body.get("body")) : str(existing.get("body"));
        return jdbcTemplate.update("""
                UPDATE knowledge_notes
                   SET title = ?, body = ?, images = ?, module_id = ?, module_label = ?,
                       requirement_no = ?, status = ?, updated_at = NOW()
                 WHERE id = ?
                """, title, noteBody, images, moduleId, label, req, status, id) > 0;
    }

    public boolean delete(String id) {
        try {
            return jdbcTemplate.update("DELETE FROM knowledge_notes WHERE id = ?", id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveModuleLabel(String moduleId) {
        return KnowledgeNoteRules.moduleLabel(moduleId, this::findModule);
    }

    private KnowledgeNoteRules.ModuleRef findModule(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            List<KnowledgeNoteRules.ModuleRef> rows = jdbcTemplate.query(
                    "SELECT id, parent_id AS parentId, name FROM project_modules WHERE id = ?",
                    (rs, n) -> new KnowledgeNoteRules.ModuleRef(
                            rs.getString("id"), rs.getString("parentId"), rs.getString("name")),
                    id.trim());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("[knowledge-notes] 读模块失败: {}", e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> decorate(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> vo = new LinkedHashMap<>(row);
            vo.put("images", KnowledgeNoteRules.parseImages(row.get("images")));
            String st = KnowledgeNoteRules.normalizeStatus(str(row.get("status")));
            vo.put("status", st);
            vo.put("statusLabel", "STALE".equals(st) ? "已过时" : "仍有效");
            out.add(vo);
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
