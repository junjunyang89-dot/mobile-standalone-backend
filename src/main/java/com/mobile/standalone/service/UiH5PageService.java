package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiH5Page;
import com.mobile.standalone.mapper.UiH5PageMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * H5 页面汇总：维护 URL 目录，生成可打开链接（可带 token query），供前端 iframe / 外开预览。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UiH5PageService {

    private final UiH5PageMapper uiH5PageMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTableAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS ui_h5_pages (
                        id VARCHAR(64) PRIMARY KEY,
                        name VARCHAR(128) NOT NULL,
                        url VARCHAR(1024) NOT NULL,
                        app_id VARCHAR(64) DEFAULT NULL,
                        module_tag VARCHAR(64) DEFAULT NULL,
                        description VARCHAR(512) DEFAULT NULL,
                        auth_type VARCHAR(32) DEFAULT 'TOKEN_QUERY',
                        auth_key VARCHAR(64) DEFAULT 'token',
                        auth_token VARCHAR(512) DEFAULT NULL,
                        enabled TINYINT(1) DEFAULT 1,
                        sort_order INT DEFAULT 100,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_app (app_id),
                        INDEX idx_enabled_sort (enabled, sort_order)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            seedIfEmpty();
            log.info("[ui-h5-pages] 表已就绪 ui_h5_pages");
        } catch (Exception e) {
            log.warn("[ui-h5-pages] 建表/种子失败（可手动执行 schema_ui_h5_pages.sql）: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        if (uiH5PageMapper.countAll() > 0) return;
        UiH5Page p = new UiH5Page();
        p.setId("h5-community-detail");
        p.setName("社区帖详情");
        p.setUrl("https://news.wmpvp.com/community-detail.html?id=380194997&qq_aio_chat_type=2");
        p.setAppId("DOMESTIC_ESPORTS");
        p.setModuleTag("社区");
        p.setDescription("帖详情 H5（评论筛选/正文在 WebView）。token 请在编辑里填写，勿提交到 Git。");
        p.setAuthType("TOKEN_QUERY");
        p.setAuthKey("token");
        p.setAuthToken(null);
        p.setEnabled(true);
        p.setSortOrder(10);
        uiH5PageMapper.insert(p);
        log.info("[ui-h5-pages] 已写入种子：社区帖详情（无 token）");
    }

    public List<UiH5Page> list(String appId, Boolean enabledOnly) {
        return uiH5PageMapper.findByFilters(blankToNull(appId), enabledOnly);
    }

    public UiH5Page get(String id) {
        return uiH5PageMapper.findById(id);
    }

    public UiH5Page create(UiH5Page body) {
        validate(body);
        if (body.getId() == null || body.getId().isBlank()) {
            body.setId("h5-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (uiH5PageMapper.findById(body.getId()) != null) {
            throw new IllegalArgumentException("id 已存在: " + body.getId());
        }
        defaults(body);
        uiH5PageMapper.insert(body);
        return uiH5PageMapper.findById(body.getId());
    }

    public UiH5Page update(String id, UiH5Page body) {
        UiH5Page existing = uiH5PageMapper.findById(id);
        if (existing == null) return null;
        body.setId(id);
        validate(body);
        defaults(body);
        // 前端不传 token 时保留原值
        if (body.getAuthToken() == null) {
            body.setAuthToken(existing.getAuthToken());
        }
        uiH5PageMapper.update(body);
        return uiH5PageMapper.findById(id);
    }

    public boolean delete(String id) {
        return uiH5PageMapper.deleteById(id) > 0;
    }

    /**
     * 生成预览用 URL：TOKEN_QUERY 时把 token 挂到 query。
     */
    public Map<String, Object> buildOpenInfo(String id) {
        UiH5Page p = uiH5PageMapper.findById(id);
        if (p == null) return null;
        String openUrl = resolveOpenUrl(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("rawUrl", p.getUrl());
        m.put("openUrl", openUrl);
        m.put("authType", p.getAuthType());
        m.put("hasToken", p.getAuthToken() != null && !p.getAuthToken().isBlank());
        m.put("note", buildNote(p));
        m.put("apiProbeUrl", buildCommunityProbeUrl(p));
        return m;
    }

    /**
     * 社区帖详情：用官方 API 验 token（H5 壳 URL 带 token 不等于接口已登录）。
     * 参考：GET appengine.wmpvp.com/steamcn/community/post/getPostById?isForShare=1&postId=&token=
     */
    public Map<String, Object> probe(String id) {
        UiH5Page p = uiH5PageMapper.findById(id);
        if (p == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("name", p.getName());
        String token = p.getAuthToken() != null ? p.getAuthToken().trim() : "";
        if (token.isEmpty()) {
            out.put("ok", false);
            out.put("message", "未配置 token，无法验活");
            return out;
        }
        String postId = extractQueryParam(p.getUrl(), "id");
        if (postId == null || postId.isBlank()) {
            postId = extractQueryParam(p.getUrl(), "postId");
        }
        if (postId == null || postId.isBlank()) {
            out.put("ok", false);
            out.put("message", "H5 URL 中未找到 id/postId，无法拼 getPostById");
            return out;
        }
        String apiUrl = "https://appengine.wmpvp.com/steamcn/community/post/getPostById"
                + "?isForShare=1&postId=" + URLEncoder.encode(postId, StandardCharsets.UTF_8)
                + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        out.put("apiUrl", apiUrl);
        out.put("postId", postId);
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (compatible; MobileStandalone/1.0)")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            out.put("httpStatus", resp.statusCode());
            String body = resp.body() != null ? resp.body() : "";
            out.put("rawPreview", body.length() > 800 ? body.substring(0, 800) + "…" : body);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 && body.contains("\"code\"")) {
                // 轻量解析，避免强依赖 Jackson 树
                boolean success = body.contains("\"code\":1") || body.contains("\"code\": 1");
                out.put("ok", success);
                out.put("message", success ? "接口验活成功（token 有效）" : "接口有返回但 code≠1，请看 rawPreview");
                int titleIdx = body.indexOf("\"title\":\"");
                if (titleIdx >= 0) {
                    int start = titleIdx + 9;
                    int end = body.indexOf('"', start);
                    if (end > start) out.put("postTitle", body.substring(start, end));
                }
            } else {
                out.put("ok", false);
                out.put("message", "HTTP " + resp.statusCode() + " 或非 JSON，可能被网络/WAF 拦截");
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "请求失败: " + e.getMessage());
        }
        return out;
    }

    private static String buildCommunityProbeUrl(UiH5Page p) {
        String postId = extractQueryParam(p.getUrl(), "id");
        if (postId == null) postId = extractQueryParam(p.getUrl(), "postId");
        if (postId == null || p.getAuthToken() == null || p.getAuthToken().isBlank()) return null;
        return "https://appengine.wmpvp.com/steamcn/community/post/getPostById?isForShare=1&postId="
                + postId + "&token=" + p.getAuthToken().trim();
    }

    private static String extractQueryParam(String url, String key) {
        if (url == null || key == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return null;
        String query = url.substring(q + 1);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if (part.substring(0, eq).equalsIgnoreCase(key)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    public static String resolveOpenUrl(UiH5Page p) {
        String base = p.getUrl() != null ? p.getUrl().trim() : "";
        String type = p.getAuthType() != null ? p.getAuthType().trim().toUpperCase() : "NONE";
        String token = p.getAuthToken() != null ? p.getAuthToken().trim() : "";
        String key = p.getAuthKey() != null && !p.getAuthKey().isBlank() ? p.getAuthKey().trim() : "token";
        if (!"TOKEN_QUERY".equals(type) || token.isEmpty() || base.isEmpty()) {
            return base;
        }
        String enc = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String path = base;
        String query = "";
        int qIdx = base.indexOf('?');
        if (qIdx >= 0) {
            path = base.substring(0, qIdx);
            query = base.substring(qIdx + 1);
            int hashInQuery = query.indexOf('#');
            if (hashInQuery >= 0) {
                // rare: keep as-is in query strip
                query = query.substring(0, hashInQuery);
            }
        }
        java.util.List<String> pairs = new java.util.ArrayList<>();
        if (!query.isBlank()) {
            for (String part : query.split("&")) {
                if (part == null || part.isBlank()) continue;
                int eq = part.indexOf('=');
                String k = eq >= 0 ? part.substring(0, eq) : part;
                if (k.equalsIgnoreCase(key)) continue;
                pairs.add(part);
            }
        }
        pairs.add(key + "=" + enc);
        return path + "?" + String.join("&", pairs);
    }

    private static String buildNote(UiH5Page p) {
        String type = p.getAuthType() != null ? p.getAuthType() : "NONE";
        if ("TOKEN_QUERY".equalsIgnoreCase(type)) {
            if (p.getAuthToken() == null || p.getAuthToken().isBlank()) {
                return "未配置 token：可先浏览公开内容；登录态请在编辑里填写 token。";
            }
            return "token 已写入 H5 URL query。真正拉帖接口是 appengine…/getPostById?postId=&token=；"
                    + "内嵌页失败时请点「接口验活」或「系统浏览器打开」。";
        }
        if ("MANUAL".equalsIgnoreCase(type)) {
            return "手动登录：请在系统浏览器打开后自行登录；内嵌 iframe 无法跨域写 Cookie。";
        }
        return "无鉴权参数。";
    }

    private void validate(UiH5Page body) {
        if (body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        if (body.getUrl() == null || body.getUrl().isBlank()) {
            throw new IllegalArgumentException("url 必填");
        }
        if (!body.getUrl().startsWith("http://") && !body.getUrl().startsWith("https://")) {
            throw new IllegalArgumentException("url 需以 http(s):// 开头");
        }
    }

    private void defaults(UiH5Page body) {
        if (body.getAuthType() == null || body.getAuthType().isBlank()) body.setAuthType("TOKEN_QUERY");
        if (body.getAuthKey() == null || body.getAuthKey().isBlank()) body.setAuthKey("token");
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getSortOrder() == null) body.setSortOrder(100);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
