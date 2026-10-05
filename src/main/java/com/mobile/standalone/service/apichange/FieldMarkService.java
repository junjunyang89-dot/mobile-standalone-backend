package com.mobile.standalone.service.apichange;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对比报告里字段级的两种人工标记。
 * <ul>
 *   <li>TAG 业务标签：这个字段给前端哪块用（「胜率展示模块」）。跟着 项目+接口+字段 走，下一份报告还在。</li>
 *   <li>CONFIRM 已确认：这一处变更有人看过了。跟着 change_sig（哪两个分支之间、怎么变的）走——
 *       同一分支重新分析不会丢，但变化本身又变了（比如类型改成别的）就得重新确认。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FieldMarkService {

    public static final String TAG = "TAG";
    public static final String CONFIRM = "CONFIRM";
    private static final Set<String> SIDES = Set.of("REQUEST", "RESPONSE");
    private static final int MAX_ITEMS = 500;

    private final JdbcTemplate jdbc;

    public record Item(String method, String path, String side, String field, String changeSig) {
    }

    @PostConstruct
    public void ensureTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS api_field_marks (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        mark_key CHAR(40) NOT NULL,
                        kind VARCHAR(16) NOT NULL,
                        project VARCHAR(64) NOT NULL DEFAULT '',
                        http_method VARCHAR(16) NOT NULL,
                        path VARCHAR(512) NOT NULL,
                        side VARCHAR(16) NOT NULL,
                        field VARCHAR(255) NOT NULL,
                        change_sig VARCHAR(512) NULL,
                        biz_tag VARCHAR(128) NULL,
                        updated_by VARCHAR(64) NULL,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY udx_mark_key (mark_key),
                        INDEX idx_project_kind (project, kind)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        } catch (Exception e) {
            log.warn("[api-field-marks] 建表失败: {}", e.getMessage());
        }
    }

    /** @param refs {@code base..head}；为空时不返回确认记录 */
    public Map<String, Object> list(String project, String refs) {
        String p = nz(project);
        List<Map<String, Object>> tags = jdbc.queryForList("""
                SELECT http_method AS method, path, side, field, biz_tag AS tag, updated_by AS updatedBy, updated_at AS updatedAt
                FROM api_field_marks WHERE project = ? AND kind = 'TAG'
                """, p);
        List<Map<String, Object>> confirms = refs == null || refs.isBlank() ? List.of() : jdbc.queryForList("""
                SELECT http_method AS method, path, side, field, change_sig AS changeSig, updated_by AS updatedBy, updated_at AS updatedAt
                FROM api_field_marks WHERE project = ? AND kind = 'CONFIRM' AND change_sig LIKE ?
                """, p, likePrefix(refs.trim() + "|"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tags", tags);
        out.put("confirms", confirms);
        out.put("tagNames", jdbc.queryForList("""
                SELECT biz_tag FROM api_field_marks WHERE project = ? AND kind = 'TAG' AND biz_tag IS NOT NULL
                GROUP BY biz_tag ORDER BY MAX(updated_at) DESC LIMIT 100
                """, String.class, p));
        return out;
    }

    /** tag 为空 = 去掉标签 */
    @Transactional
    public int setTag(String project, List<Item> items, String tag, String user) {
        String t = tag == null ? "" : tag.trim();
        if (t.length() > 128) {
            throw new IllegalArgumentException("标签最多 128 个字");
        }
        int n = 0;
        for (Item it : checked(items, false)) {
            String key = keyOf(TAG, project, it);
            if (t.isEmpty()) {
                n += jdbc.update("DELETE FROM api_field_marks WHERE mark_key = ?", key);
            } else {
                n += upsert(key, TAG, project, it, null, t, user);
            }
        }
        return n;
    }

    @Transactional
    public int setConfirmed(String project, List<Item> items, boolean confirmed, String user) {
        int n = 0;
        for (Item it : checked(items, true)) {
            String key = keyOf(CONFIRM, project, it);
            n += confirmed
                    ? upsert(key, CONFIRM, project, it, it.changeSig().trim(), null, user)
                    : jdbc.update("DELETE FROM api_field_marks WHERE mark_key = ?", key);
        }
        return n;
    }

    private int upsert(String key, String kind, String project, Item it, String sig, String tag, String user) {
        return jdbc.update("""
                INSERT INTO api_field_marks (mark_key, kind, project, http_method, path, side, field, change_sig, biz_tag, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE biz_tag = VALUES(biz_tag), updated_by = VALUES(updated_by), updated_at = NOW()
                """, key, kind, nz(project), method(it), it.path().trim(), side(it), it.field().trim(), sig, tag, user);
    }

    static List<Item> checked(List<Item> items, boolean needSig) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("没选字段");
        }
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("一次最多 " + MAX_ITEMS + " 个字段");
        }
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it == null || blank(it.method()) || blank(it.path()) || blank(it.field()) || !SIDES.contains(side(it))) {
                throw new IllegalArgumentException("字段信息不全：要有 method / path / side(REQUEST|RESPONSE) / field");
            }
            if (it.path().length() > 512 || it.field().length() > 255) {
                throw new IllegalArgumentException("路径或字段名太长");
            }
            if (needSig && (blank(it.changeSig()) || it.changeSig().length() > 512)) {
                throw new IllegalArgumentException("缺 changeSig：确认要跟具体的哪次变更挂钩");
            }
            out.add(it);
        }
        return out;
    }

    static String keyOf(String kind, String project, Item it) {
        String raw = String.join("\u0001", kind, nz(project), method(it), it.path().trim(), side(it), it.field().trim(),
                CONFIRM.equals(kind) ? it.changeSig().trim() : "");
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String method(Item it) {
        return it.method().trim().toUpperCase(Locale.ROOT);
    }

    private static String side(Item it) {
        return it.side() == null ? "" : it.side().trim().toUpperCase(Locale.ROOT);
    }

    private static String likePrefix(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
