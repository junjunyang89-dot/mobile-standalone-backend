package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiUrlEntry;
import com.mobile.standalone.mapper.ApiUrlMapper;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@DependsOn("apiProjectService")
public class ApiUrlService {

    private final ApiUrlMapper urlMapper;
    private final ApiProjectService projectService;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTableAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_url_entries (
                        id VARCHAR(64) PRIMARY KEY,
                        project VARCHAR(64) NOT NULL,
                        name VARCHAR(255) NOT NULL,
                        environment VARCHAR(16) NOT NULL DEFAULT 'TEST',
                        base_url VARCHAR(1024) NOT NULL,
                        description VARCHAR(1024) DEFAULT NULL,
                        enabled TINYINT(1) DEFAULT 1,
                        sort_order INT DEFAULT 100,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_project_env (project, environment)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            projectService.ensureExists(ApiTestCaseService.PROJECT_DATA_CENTER, "data-center（数据中心）");
            seedDataCenterUrlsIfEmpty();
            log.info("[api-url] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-url] 建表失败: {}", e.getMessage());
        }
    }

    private void seedDataCenterUrlsIfEmpty() {
        if (urlMapper.countByProject(ApiTestCaseService.PROJECT_DATA_CENTER) > 0) return;
        LocalDateTime now = LocalDateTime.now();
        upsertSeed("url-dc-test", "data-center", "data-center · 测试网关", TestEnvironment.TEST,
                "https://gateway-qa.arca42.com", "QA 网关前缀；用例 path 拼在后面", 10, now);
        upsertSeed("url-dc-prod", "data-center", "data-center · 线上网关", TestEnvironment.PROD,
                "https://gateway.arca42.com", "生产网关前缀", 20, now);
        log.info("[api-url] 已种子 data-center 测试/线上网关");
    }

    private void upsertSeed(String id, String project, String name, String env, String baseUrl,
                            String desc, int sort, LocalDateTime now) {
        if (urlMapper.findById(id) != null) return;
        ApiUrlEntry u = new ApiUrlEntry();
        u.setId(id);
        u.setProject(project);
        u.setName(name);
        u.setEnvironment(env);
        u.setBaseUrl(ApiTestCaseService.trimSlash(baseUrl));
        u.setDescription(desc);
        u.setEnabled(true);
        u.setSortOrder(sort);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        urlMapper.insert(u);
    }

    public List<ApiUrlEntry> list(String project, String environment, Boolean enabledOnly) {
        String env = blank(environment);
        if (env != null && !"ANY".equalsIgnoreCase(env)) {
            env = TestEnvironment.normalize(env);
        }
        return urlMapper.findByFilters(blank(project), env, enabledOnly);
    }

    public ApiUrlEntry get(String id) {
        return urlMapper.findById(id);
    }

    @Transactional
    public ApiUrlEntry create(ApiUrlEntry body) {
        validate(body);
        if (body.getId() == null || body.getId().isBlank()) {
            body.setId("url-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (urlMapper.findById(body.getId()) != null) {
            throw new IllegalArgumentException("id 已存在: " + body.getId());
        }
        defaults(body);
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        urlMapper.insert(body);
        return urlMapper.findById(body.getId());
    }

    @Transactional
    public ApiUrlEntry update(String id, ApiUrlEntry body) {
        ApiUrlEntry existing = urlMapper.findById(id);
        if (existing == null) return null;
        body.setId(id);
        validate(body);
        defaults(body);
        body.setCreatedAt(existing.getCreatedAt());
        body.setUpdatedAt(LocalDateTime.now());
        urlMapper.update(body);
        return urlMapper.findById(id);
    }

    @Transactional
    public boolean delete(String id) {
        return urlMapper.deleteById(id) > 0;
    }

    private void validate(ApiUrlEntry body) {
        if (body.getProject() == null || body.getProject().isBlank()) {
            throw new IllegalArgumentException("project 必填（请先在「项目管理」中创建）");
        }
        if (projectService.get(body.getProject().trim()) == null) {
            throw new IllegalArgumentException("项目不存在: " + body.getProject() + "，请先到资产管理 · 项目管理新增");
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        if (body.getBaseUrl() == null || body.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("baseUrl 必填");
        }
    }

    private void defaults(ApiUrlEntry body) {
        body.setProject(body.getProject().trim());
        body.setBaseUrl(ApiTestCaseService.trimSlash(body.getBaseUrl().trim()));
        String env = body.getEnvironment() == null ? TestEnvironment.TEST : body.getEnvironment().trim().toUpperCase(Locale.ROOT);
        if (!"ANY".equals(env)) {
            env = TestEnvironment.normalize(env);
        }
        body.setEnvironment(env);
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getSortOrder() == null) body.setSortOrder(100);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
