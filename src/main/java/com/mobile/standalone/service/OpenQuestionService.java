package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 生成用例时挑出来的「待确认」项。
 * <p>
 * 落库而不是弹一次窗就算了：弹窗关掉就没了，人得当场复制到别处，
 * 否则下次打开这一版根本不知道还有几个问题没问产品。
 * 而<b>需要人当场记下来的东西，迟早会有一次忘了记</b>。
 * <p>
 * 存在版本 + 集合上，打开那一版时顶部直接显示「这一版还有 N 项没确认」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenQuestionService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS ai_open_questions (
                        id VARCHAR(64) PRIMARY KEY,
                        category VARCHAR(64) DEFAULT NULL,
                        app_version VARCHAR(32) DEFAULT NULL,
                        page_name VARCHAR(255) DEFAULT NULL,
                        batch_id VARCHAR(64) DEFAULT NULL,
                        title VARCHAR(512) NOT NULL,
                        kind VARCHAR(32) DEFAULT NULL,
                        detail TEXT NULL,
                        question TEXT NULL,
                        impact TEXT NULL,
                        resolved TINYINT(1) NOT NULL DEFAULT 0,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_cat_ver (category, app_version, resolved)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[open-questions] 表已就绪");
        } catch (Exception e) {
            log.warn("[open-questions] 建表失败: {}", e.getMessage());
        }
    }

    /**
     * 存这一批的待确认项。
     * <p>
     * 同一批重复生成时先清掉这一批的旧记录，不然重跑两次就变两份一样的问题。
     * 只清「同 category + 同版本 + 同页面」的，不碰别的需求。
     */
    public int save(String category, String appVersion, String pageName, String batchId,
                    List<JsonNode> questions) {
        if (questions == null || questions.isEmpty()) {
            return 0;
        }
        try {
            jdbcTemplate.update(
                    "DELETE FROM ai_open_questions WHERE category <=> ? AND app_version <=> ? "
                            + "AND page_name <=> ? AND resolved = 0",
                    category, appVersion, pageName);
            int n = 0;
            for (JsonNode q : questions) {
                jdbcTemplate.update("""
                        INSERT INTO ai_open_questions
                            (id, category, app_version, page_name, batch_id, title, kind, detail, question, impact)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """,
                        "oq-" + UUID.randomUUID(), category, appVersion, pageName, batchId,
                        text(q, "title"), text(q, "kind"), text(q, "detail"),
                        text(q, "question"), text(q, "impact"));
                n++;
            }
            return n;
        } catch (Exception e) {
            // 存不下来不该让整次生成失败——用例已经生成好了，那才是主产物
            log.warn("[open-questions] 保存失败（不影响用例）: {}", e.getMessage());
            return 0;
        }
    }

    /** 某个集合 / 某一版还没确认的问题 */
    public List<Map<String, Object>> list(String category, String appVersion, boolean includeResolved) {
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, category, app_version AS appVersion, page_name AS pageName, "
                            + "title, kind, detail, question, impact, resolved, created_at AS createdAt "
                            + "FROM ai_open_questions WHERE 1=1");
            java.util.List<Object> args = new java.util.ArrayList<>();
            if (category != null && !category.isBlank()) {
                sql.append(" AND category = ?");
                args.add(category.trim());
            }
            if (appVersion != null && !appVersion.isBlank()) {
                sql.append(" AND app_version = ?");
                args.add(appVersion.trim());
            }
            if (!includeResolved) {
                sql.append(" AND resolved = 0");
            }
            sql.append(" ORDER BY resolved, created_at DESC");
            return jdbcTemplate.queryForList(sql.toString(), args.toArray());
        } catch (Exception e) {
            log.debug("[open-questions] 查询跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 直接删掉。
     * <p>
     * 和「已确认」是两件事：确认过的问题<b>问过、有结论</b>，留着下个版本还有人翻；
     * 而模型挑出来的东西总有几条根本不成立（看错了、或者本来就有定义），
     * 那种留着只会让下次打开时又要重新判断一遍「这条是不是已经看过了」。
     */
    public boolean delete(String id) {
        try {
            return jdbcTemplate.update("DELETE FROM ai_open_questions WHERE id = ?", id) > 0;
        } catch (Exception e) {
            log.warn("[open-questions] 删除失败: {}", e.getMessage());
            return false;
        }
    }

    /** 确认完了就标掉。不删——问过什么、结论是什么，下个版本还会有人想翻 */
    public boolean resolve(String id, boolean resolved) {
        try {
            return jdbcTemplate.update(
                    "UPDATE ai_open_questions SET resolved = ? WHERE id = ?",
                    resolved ? 1 : 0, id) > 0;
        } catch (Exception e) {
            log.warn("[open-questions] 更新失败: {}", e.getMessage());
            return false;
        }
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
