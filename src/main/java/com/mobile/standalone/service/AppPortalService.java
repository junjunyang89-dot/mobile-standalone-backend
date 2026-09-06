package com.mobile.standalone.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * APP 门户：把测试日常要开的外链（两套 Jenkins、发包、文档）收在一个页面。
 * <p>
 * 不嵌套 iframe——Jenkins 通常禁了被嵌入。这里只做「点一下就打开」，省得每次换浏览器找地址。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppPortalService {

    private final JdbcTemplate jdbc;

    @PostConstruct
    public void ensureTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS app_portal_links (
                        id VARCHAR(64) PRIMARY KEY,
                        group_key VARCHAR(32) NOT NULL,
                        title VARCHAR(128) NOT NULL,
                        url VARCHAR(1024) NOT NULL DEFAULT '',
                        remark VARCHAR(255) NULL,
                        sort_order INT NOT NULL DEFAULT 100,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_group_sort (group_key, sort_order)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            seedIfEmpty();
        } catch (Exception e) {
            log.warn("[app-portal] 建表失败: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM app_portal_links", Integer.class);
        if (n != null && n > 0) {
            return;
        }
        insert("jenkins", "Jenkins · 电竞 / 国内", "", "第一套 Jenkins，把任务页地址填进来", 10);
        insert("jenkins", "Jenkins · 另一套", "", "第二套 Jenkins（海外或另一套流水线）", 20);
        insert("release", "蒲公英 / 发包", "", "安装包下载或内测页", 30);
        insert("docs", "接口 / 需求文档", "", "Confluence 或接口文档入口", 40);
        log.info("[app-portal] 已写入默认入口，请到页面里补上真实地址");
    }

    public List<Map<String, Object>> grouped() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, group_key, title, url, remark, sort_order
                  FROM app_portal_links
                 ORDER BY sort_order, title
                """);
        Map<String, List<Map<String, Object>>> by = new LinkedHashMap<>();
        for (String g : List.of("jenkins", "release", "docs", "other")) {
            by.put(g, new ArrayList<>());
        }
        for (Map<String, Object> row : rows) {
            String g = String.valueOf(row.get("group_key"));
            by.computeIfAbsent(g, k -> new ArrayList<>()).add(vo(row));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : by.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("key", e.getKey());
            g.put("label", groupLabel(e.getKey()));
            g.put("links", e.getValue());
            out.add(g);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> save(Map<String, Object> body) {
        String title = str(body.get("title"));
        if (title.isBlank()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        String url = str(body.get("url"));
        if (!url.isBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("地址要以 http:// 或 https:// 开头");
        }
        String group = str(body.get("groupKey"));
        if (group.isBlank()) {
            group = "other";
        }
        String remark = str(body.get("remark"));
        int sort = body.get("sortOrder") instanceof Number n ? n.intValue() : 100;
        String id = str(body.get("id"));
        if (id.isBlank()) {
            id = insert(group, title, url, remark, sort);
        } else {
            jdbc.update("""
                    UPDATE app_portal_links
                       SET group_key=?, title=?, url=?, remark=?, sort_order=?
                     WHERE id=?
                    """, group, title, url, remark.isBlank() ? null : remark, sort, id);
        }
        return Map.of("id", id, "ok", true);
    }

    @Transactional
    public boolean delete(String id) {
        return jdbc.update("DELETE FROM app_portal_links WHERE id=?", id) > 0;
    }

    private String insert(String group, String title, String url, String remark, int sort) {
        String id = "apl-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO app_portal_links (id, group_key, title, url, remark, sort_order)
                VALUES (?,?,?,?,?,?)
                """, id, group, title, url == null ? "" : url, remark, sort);
        return id;
    }

    private static Map<String, Object> vo(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id"));
        m.put("groupKey", row.get("group_key"));
        m.put("title", row.get("title"));
        m.put("url", row.get("url"));
        m.put("remark", row.get("remark"));
        m.put("sortOrder", row.get("sort_order"));
        m.put("ready", row.get("url") != null && !String.valueOf(row.get("url")).isBlank());
        return m;
    }

    static String groupLabel(String key) {
        return switch (key == null ? "" : key) {
            case "jenkins" -> "Jenkins";
            case "release" -> "发包 / 安装包";
            case "docs" -> "文档";
            default -> "其他";
        };
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
