package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.UiH5Page;
import com.mobile.standalone.entity.WebTestCase;
import com.mobile.standalone.mapper.WebTestCaseMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebTestCaseService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebTestCaseMapper webTestCaseMapper;
    private final UiH5PageService uiH5PageService;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTableAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS web_test_cases (
                        id VARCHAR(64) PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        description VARCHAR(1024) DEFAULT NULL,
                        requirement_ref VARCHAR(255) DEFAULT NULL,
                        channel VARCHAR(32) NOT NULL DEFAULT 'H5',
                        page_url VARCHAR(1024) DEFAULT NULL,
                        h5_page_id VARCHAR(64) DEFAULT NULL,
                        auth_mode VARCHAR(32) DEFAULT 'none',
                        auth_token VARCHAR(512) DEFAULT NULL,
                        auth_query_key VARCHAR(64) DEFAULT 'token',
                        steps TEXT NOT NULL,
                        enabled TINYINT(1) DEFAULT 1,
                        sort_order INT DEFAULT 100,
                        created_by VARCHAR(100) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_channel (channel),
                        INDEX idx_enabled_sort (enabled, sort_order),
                        INDEX idx_h5 (h5_page_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            seedIfEmpty();
            log.info("[web-test-cases] 表已就绪");
        } catch (Exception e) {
            log.warn("[web-test-cases] 建表失败: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        if (webTestCaseMapper.countAll() > 0) return;
        WebTestCase c = new WebTestCase();
        c.setId("wtc-pogpog-report-smoke");
        c.setName("PogPog 周报页可打开并可见内容");
        c.setDescription("示例：按步骤打开周报页并断言页面骨架（请在编辑里填 token）");
        c.setRequirementRef("");
        c.setChannel("H5");
        c.setPageUrl("https://lock.pogpog.gg/players/76561199278980753/report?week=2026-06-29");
        c.setAuthMode("query");
        c.setAuthQueryKey("token");
        c.setAuthToken(null);
        c.setSteps("""
                [
                  {"keywordCode":"OPEN_URL","params":{}},
                  {"keywordCode":"WAIT_MS","params":{"ms":2000}},
                  {"keywordCode":"ASSERT_HTML_MIN","params":{"minLength":200}},
                  {"keywordCode":"ASSERT_TITLE_NOT_EMPTY","params":{}}
                ]
                """);
        c.setEnabled(true);
        c.setSortOrder(10);
        c.setCreatedBy("seed");
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        webTestCaseMapper.insert(c);
        log.info("[web-test-cases] 已写入示例用例");
    }

    public List<WebTestCase> list(String channel, Boolean enabledOnly, String keyword) {
        return webTestCaseMapper.findByFilters(blankToNull(channel), enabledOnly, blankToNull(keyword));
    }

    public WebTestCase get(String id) {
        return webTestCaseMapper.findById(id);
    }

    @Transactional
    public WebTestCase create(WebTestCase body) {
        validate(body);
        if (body.getId() == null || body.getId().isBlank()) {
            body.setId("wtc-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (webTestCaseMapper.findById(body.getId()) != null) {
            throw new IllegalArgumentException("id 已存在: " + body.getId());
        }
        defaults(body);
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        webTestCaseMapper.insert(body);
        return webTestCaseMapper.findById(body.getId());
    }

    @Transactional
    public WebTestCase update(String id, WebTestCase body) {
        WebTestCase existing = webTestCaseMapper.findById(id);
        if (existing == null) return null;
        body.setId(id);
        validate(body);
        defaults(body);
        if (body.getAuthToken() == null) {
            body.setAuthToken(existing.getAuthToken());
        }
        body.setCreatedAt(existing.getCreatedAt());
        body.setCreatedBy(existing.getCreatedBy());
        body.setUpdatedAt(LocalDateTime.now());
        webTestCaseMapper.update(body);
        return webTestCaseMapper.findById(id);
    }

    @Transactional
    public boolean delete(String id) {
        return webTestCaseMapper.deleteById(id) > 0;
    }

    /** 关键字面板：员工点选，不写代码 */
    public List<Map<String, Object>> keywords() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(kw("OPEN_URL", "打开页面", "ACTION", "打开 pageUrl（可覆盖 params.url），自动带鉴权"));
        list.add(kw("CLICK", "点击", "ACTION", "params.selector，如 text=登录 或 #id 或 css=.btn"));
        list.add(kw("FILL", "输入", "ACTION", "params.selector + params.value"));
        list.add(kw("WAIT_MS", "等待毫秒", "WAIT", "params.ms"));
        list.add(kw("WAIT_TEXT", "等待文案出现", "WAIT", "params.text + 可选 timeoutSec"));
        list.add(kw("ASSERT_TEXT", "断言包含文案", "ASSERT", "params.text"));
        list.add(kw("ASSERT_URL", "断言 URL 包含", "ASSERT", "params.contains"));
        list.add(kw("ASSERT_TITLE_NOT_EMPTY", "断言标题非空", "ASSERT", "无参数"));
        list.add(kw("ASSERT_HTML_MIN", "断言页面有内容", "ASSERT", "params.minLength，默认 200"));
        list.add(kw("WAIT_RESPONSE", "等待接口", "ASSERT", "params.urlIncludes + 可选 status/timeoutSec"));
        list.add(kw("API_GET", "接口 GET 断言", "ASSERT", "params.url + expectStatus + 可选 expectBodyIncludes"));
        return list;
    }

    public Map<String, Object> toRunnablePayload(WebTestCase c, String tokenOverride) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("channel", c.getChannel());
        m.put("requirementRef", c.getRequirementRef());
        String url = c.getPageUrl();
        String token = tokenOverride != null && !tokenOverride.isBlank() ? tokenOverride : c.getAuthToken();
        String authMode = c.getAuthMode() != null ? c.getAuthMode() : "none";
        String authKey = c.getAuthQueryKey() != null ? c.getAuthQueryKey() : "token";
        if (c.getH5PageId() != null && !c.getH5PageId().isBlank()) {
            UiH5Page p = uiH5PageService.get(c.getH5PageId());
            if (p != null) {
                if (url == null || url.isBlank()) url = UiH5PageService.resolveOpenUrl(p);
                if ((token == null || token.isBlank()) && p.getAuthToken() != null) token = p.getAuthToken();
                if (p.getAuthKey() != null && !p.getAuthKey().isBlank()) authKey = p.getAuthKey();
                if (("none".equalsIgnoreCase(authMode) || authMode.isBlank())
                        && "TOKEN_QUERY".equalsIgnoreCase(p.getAuthType())) {
                    authMode = "query";
                }
            }
        }
        m.put("pageUrl", url);
        m.put("authMode", authMode);
        m.put("authToken", token);
        m.put("authQueryKey", authKey);
        try {
            JsonNode steps = JSON.readTree(c.getSteps() != null ? c.getSteps() : "[]");
            m.put("steps", steps);
        } catch (Exception e) {
            m.put("steps", List.of());
        }
        return m;
    }

    private void validate(WebTestCase body) {
        if (body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("用例名称不能为空");
        }
        if (body.getSteps() == null || body.getSteps().isBlank()) {
            body.setSteps("[]");
        }
        try {
            JsonNode n = JSON.readTree(body.getSteps());
            if (!n.isArray()) throw new IllegalArgumentException("steps 必须是 JSON 数组");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("steps JSON 无效: " + e.getMessage());
        }
    }

    private void defaults(WebTestCase body) {
        if (body.getChannel() == null || body.getChannel().isBlank()) body.setChannel("H5");
        if (body.getAuthMode() == null || body.getAuthMode().isBlank()) body.setAuthMode("none");
        if (body.getAuthQueryKey() == null || body.getAuthQueryKey().isBlank()) body.setAuthQueryKey("token");
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getSortOrder() == null) body.setSortOrder(100);
        if (body.getSteps() == null || body.getSteps().isBlank()) body.setSteps("[]");
    }

    private static Map<String, Object> kw(String code, String nameZh, String category, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("nameZh", nameZh);
        m.put("category", category);
        m.put("description", desc);
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
