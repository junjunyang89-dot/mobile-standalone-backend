package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.entity.KnowledgeHandbook;
import com.mobile.standalone.handbook.HandbookCategories;
import com.mobile.standalone.mapper.KnowledgeHandbookMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识手册：分类短记（现象 / 原因 / 处理）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeHandbookService {

    /** 业务相关分类：保存时项目必填 */
    private static final Set<String> PROJECT_REQUIRED = Set.of("business", "checklist");

    private final KnowledgeHandbookMapper handbookMapper;
    private final ApiProjectService apiProjectService;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_handbook (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        category VARCHAR(32) NOT NULL,
                        project VARCHAR(64) DEFAULT NULL,
                        title VARCHAR(255) NOT NULL,
                        symptom TEXT NOT NULL,
                        cause TEXT NOT NULL,
                        fix TEXT NOT NULL,
                        tags VARCHAR(512) DEFAULT NULL,
                        image_url VARCHAR(512) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_category (category),
                        INDEX idx_project (project),
                        INDEX idx_updated (updated_at),
                        INDEX idx_title (title)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            ensureImageColumn();
            ensureProjectColumn();
            log.info("[knowledge-handbook] 表已就绪 knowledge_handbook");
            migrateLegacyCategories();
        } catch (Exception e) {
            log.warn("[knowledge-handbook] 建表失败（可手动执行 schema_knowledge_handbook.sql）: {}", e.getMessage());
        }
    }

    private void ensureImageColumn() {
        ensureColumn("image_url",
                "ALTER TABLE knowledge_handbook ADD COLUMN image_url VARCHAR(512) DEFAULT NULL AFTER tags");
    }

    private void ensureProjectColumn() {
        ensureColumn("project",
                "ALTER TABLE knowledge_handbook ADD COLUMN project VARCHAR(64) DEFAULT NULL AFTER category");
        try {
            jdbcTemplate.execute("CREATE INDEX idx_project ON knowledge_handbook (project)");
        } catch (Exception ignored) {
            // 已存在则忽略
        }
    }

    private void ensureColumn(String column, String alterSql) {
        try {
            Integer cnt = jdbcTemplate.queryForObject("""
                    SELECT COUNT(1) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'knowledge_handbook'
                      AND COLUMN_NAME = ?
                    """, Integer.class, column);
            if (cnt == null || cnt == 0) {
                jdbcTemplate.execute(alterSql);
                log.info("[knowledge-handbook] 已添加列 {}", column);
            }
        } catch (Exception e) {
            log.warn("[knowledge-handbook] 检查/添加 {} 跳过: {}", column, e.getMessage());
        }
    }

    /** 旧 12 类 code → 新叶子 code */
    private void migrateLegacyCategories() {
        try {
            for (Map.Entry<String, String> e : HandbookCategories.LEGACY_ALIASES.entrySet()) {
                int n = jdbcTemplate.update(
                        "UPDATE knowledge_handbook SET category = ? WHERE category = ?",
                        e.getValue(),
                        e.getKey()
                );
                if (n > 0) {
                    log.info("[knowledge-handbook] 迁移分类 {} → {}，{} 条", e.getKey(), e.getValue(), n);
                }
            }
        } catch (Exception e) {
            log.warn("[knowledge-handbook] 分类迁移跳过: {}", e.getMessage());
        }
    }

    public List<KnowledgeHandbook> list(String category, String project, String q, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        String cat = HandbookCategories.normalize(category);
        return handbookMapper.search(cat, blankToNull(project), blankToNull(q), lim);
    }

    public KnowledgeHandbook get(Long id) {
        return handbookMapper.findById(id);
    }

    @Transactional
    public KnowledgeHandbook create(KnowledgeHandbook req) {
        KnowledgeHandbook row = validateAndCopy(req, null);
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        handbookMapper.insert(row);
        return row;
    }

    @Transactional
    public KnowledgeHandbook update(Long id, KnowledgeHandbook req) {
        KnowledgeHandbook existing = handbookMapper.findById(id);
        if (existing == null) {
            throw new IllegalArgumentException("记录不存在: " + id);
        }
        KnowledgeHandbook row = validateAndCopy(req, id);
        row.setCreatedAt(existing.getCreatedAt());
        row.setUpdatedAt(LocalDateTime.now());
        handbookMapper.update(row);
        return handbookMapper.findById(id);
    }

    @Transactional
    public void delete(Long id) {
        if (handbookMapper.findById(id) == null) {
            throw new IllegalArgumentException("记录不存在: " + id);
        }
        handbookMapper.deleteById(id);
    }

    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", handbookMapper.countAll());
        m.put("categories", HandbookCategories.LABELS);
        m.put("groups", HandbookCategories.groupsForMeta());
        m.put("projectRequiredCategories", PROJECT_REQUIRED);
        return m;
    }

    public static boolean projectRequired(String category) {
        String cat = HandbookCategories.normalize(category);
        return cat != null && PROJECT_REQUIRED.contains(cat);
    }

    private KnowledgeHandbook validateAndCopy(KnowledgeHandbook req, Long id) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String cat = HandbookCategories.normalize(req.getCategory());
        if (cat == null) {
            throw new IllegalArgumentException("category 无效，须为: " + String.join(",", HandbookCategories.CODES));
        }
        requireText(req.getTitle(), "title");
        requireText(req.getSymptom(), "symptom（现象）");
        requireText(req.getCause(), "cause（原因）");
        requireText(req.getFix(), "fix（处理）");

        String project = blankToNull(req.getProject());
        if (projectRequired(cat)) {
            if (project == null) {
                throw new IllegalArgumentException("业务相关短记请选择归属项目（来自项目管理）");
            }
        }
        if (project != null) {
            ApiProject p = apiProjectService.get(project);
            if (p == null) {
                throw new IllegalArgumentException("项目不存在: " + project + "（请先在项目管理中创建）");
            }
            if (Boolean.FALSE.equals(p.getEnabled())) {
                throw new IllegalArgumentException("项目已停用: " + project);
            }
        }

        KnowledgeHandbook row = new KnowledgeHandbook();
        row.setId(id);
        row.setCategory(cat);
        row.setProject(project);
        row.setTitle(req.getTitle().trim());
        row.setSymptom(req.getSymptom().trim());
        row.setCause(req.getCause().trim());
        row.setFix(req.getFix().trim());
        row.setTags(blankToNull(req.getTags()));
        row.setImageUrl(normalizeImageUrl(req.getImageUrl()));
        return row;
    }

    private static String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        String v = imageUrl.trim();
        if (v.startsWith("http://") || v.startsWith("https://")) {
            return v;
        }
        if (v.startsWith("/")) {
            v = v.substring(1);
        }
        if (!v.startsWith("handbook-images/")) {
            throw new IllegalArgumentException("imageUrl 非法，须为 handbook-images/ 下相对路径");
        }
        if (v.contains("..")) {
            throw new IllegalArgumentException("imageUrl 非法");
        }
        return v;
    }

    private static void requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
