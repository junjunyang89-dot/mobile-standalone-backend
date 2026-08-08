package com.mobile.standalone.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.dto.PageResult;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestEnv;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.mapper.ApiTestEnvMapper;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@DependsOn("apiProjectService")
public class ApiTestCaseService {

    public static final String PROJECT_DATA_CENTER = "data-center";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

    private final ApiTestCaseMapper caseMapper;
    private final ApiTestEnvMapper envMapper;
    private final ApiProjectService projectService;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTablesAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_test_envs (
                        environment VARCHAR(16) PRIMARY KEY,
                        name VARCHAR(128) NOT NULL,
                        base_url VARCHAR(1024) NOT NULL,
                        default_headers TEXT NULL,
                        default_token VARCHAR(512) NULL,
                        enabled TINYINT(1) DEFAULT 1,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_test_cases (
                        id VARCHAR(64) PRIMARY KEY,
                        project VARCHAR(64) DEFAULT NULL,
                        environment VARCHAR(16) NOT NULL DEFAULT 'ANY',
                        name VARCHAR(255) NOT NULL,
                        description VARCHAR(1024) DEFAULT NULL,
                        requirement_ref VARCHAR(255) DEFAULT NULL,
                        method VARCHAR(16) NOT NULL DEFAULT 'GET',
                        path VARCHAR(1024) NOT NULL,
                        url_id VARCHAR(64) DEFAULT NULL,
                        query TEXT NULL,
                        headers TEXT NULL,
                        body TEXT NULL,
                        assertions TEXT NOT NULL,
                        field_notes TEXT NULL,
                        enabled TINYINT(1) DEFAULT 1,
                        sort_order INT DEFAULT 100,
                        created_by VARCHAR(100) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_enabled_sort (enabled, sort_order),
                        INDEX idx_project (project)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            ensureProjectColumn();
            ensureUrlIdColumn();
            ensureEnvironmentColumn();
            seedEnvsIfEmpty();
            projectService.ensureExists(PROJECT_DATA_CENTER, "data-center（数据中心）");
            importProjectSeedIfAbsent(PROJECT_DATA_CENTER, "api-test/seed-data-center.json");
            log.info("[api-test] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-test] 建表/导入失败: {}", e.getMessage());
        }
    }

    private void ensureProjectColumn() {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(1) FROM information_schema.COLUMNS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = 'api_test_cases'
                              AND COLUMN_NAME = 'project'
                            """,
                    Integer.class);
            if (n != null && n == 0) {
                jdbcTemplate.execute(
                        "ALTER TABLE api_test_cases ADD COLUMN project VARCHAR(64) DEFAULT NULL AFTER id");
                jdbcTemplate.execute(
                        "ALTER TABLE api_test_cases ADD INDEX idx_project (project)");
                log.info("[api-test] 已补充 project 列");
            }
        } catch (Exception e) {
            log.debug("[api-test] project 列检查跳过: {}", e.getMessage());
        }
    }

    private void ensureUrlIdColumn() {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(1) FROM information_schema.COLUMNS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = 'api_test_cases'
                              AND COLUMN_NAME = 'url_id'
                            """,
                    Integer.class);
            if (n != null && n == 0) {
                jdbcTemplate.execute(
                        "ALTER TABLE api_test_cases ADD COLUMN url_id VARCHAR(64) DEFAULT NULL AFTER path");
                log.info("[api-test] 已补充 url_id 列");
            }
        } catch (Exception e) {
            log.debug("[api-test] url_id 列检查跳过: {}", e.getMessage());
        }
    }

    private void ensureEnvironmentColumn() {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(1) FROM information_schema.COLUMNS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = 'api_test_cases'
                              AND COLUMN_NAME = 'environment'
                            """,
                    Integer.class);
            if (n != null && n == 0) {
                jdbcTemplate.execute(
                        "ALTER TABLE api_test_cases ADD COLUMN environment VARCHAR(16) NOT NULL DEFAULT 'ANY' AFTER project");
                log.info("[api-test] 已补充 environment 列");
            }
        } catch (Exception e) {
            log.debug("[api-test] environment 列检查跳过: {}", e.getMessage());
        }
    }

    private void seedEnvsIfEmpty() {
        if (envMapper.countAll() > 0) return;
        LocalDateTime now = LocalDateTime.now();
        ApiTestEnv test = new ApiTestEnv();
        test.setEnvironment(TestEnvironment.TEST);
        test.setName("测试环境");
        test.setBaseUrl("https://gateway-qa.arca42.com");
        test.setDefaultHeaders("{\"Accept\":\"application/json\"}");
        test.setEnabled(true);
        test.setUpdatedAt(now);
        envMapper.upsert(test);

        ApiTestEnv prod = new ApiTestEnv();
        prod.setEnvironment(TestEnvironment.PROD);
        prod.setName("线上环境");
        prod.setBaseUrl("https://gateway.arca42.com");
        prod.setDefaultHeaders("{\"Accept\":\"application/json\"}");
        prod.setEnabled(true);
        prod.setUpdatedAt(now);
        envMapper.upsert(prod);
        log.info("[api-test] 已种子 TEST/PROD 环境（baseUrl 可改；token 测环境登录后自填）");
    }

    /**
     * 按项目从 classpath JSON 导入用例（仅 path/方法，不含完整 URL）。
     * 该项目已有数据则跳过，避免覆盖手工改过的用例。
     */
    public int importProjectSeedIfAbsent(String project, String classpathJson) {
        if (caseMapper.countByProject(project) > 0) {
            log.info("[api-test] 项目 {} 已有用例，跳过导入", project);
            return 0;
        }
        return importProjectSeed(project, classpathJson, false);
    }

    /** 强制重导：先删该项目种子用例（created_by 以 seed- 开头），再导入 */
    @Transactional
    public int reimportProjectSeed(String project, String classpathJson) {
        return importProjectSeed(project, classpathJson, true);
    }

    private int importProjectSeed(String project, String classpathJson, boolean replaceSeedRows) {
        try {
            ClassPathResource res = new ClassPathResource(classpathJson);
            if (!res.exists()) {
                log.warn("[api-test] 种子文件不存在: {}", classpathJson);
                return 0;
            }
            if (replaceSeedRows) {
                List<ApiTestCase> existing = caseMapper.findByFilters(project, null, null, null, null, null);
                for (ApiTestCase c : existing) {
                    if (c.getCreatedBy() != null && c.getCreatedBy().startsWith("seed-")) {
                        caseMapper.deleteById(c.getId());
                    }
                }
            }
            String raw;
            try (InputStream in = res.getInputStream()) {
                raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            List<Map<String, Object>> list = JSON.readValue(raw, new TypeReference<>() {});
            LocalDateTime now = LocalDateTime.now();
            int n = 0;
            for (Map<String, Object> m : list) {
                String id = str(m.get("id"));
                if (id == null || id.isBlank()) continue;
                if (caseMapper.findById(id) != null) continue;
                ApiTestCase c = new ApiTestCase();
                c.setId(id);
                c.setProject(str(m.get("project")) != null ? str(m.get("project")) : project);
                String env = str(m.get("environment"));
                c.setEnvironment(env != null && !env.isBlank() ? env : "ANY");
                c.setName(str(m.get("name")));
                c.setDescription(str(m.get("description")));
                c.setRequirementRef(str(m.get("requirementRef")));
                c.setMethod(str(m.get("method")));
                c.setPath(str(m.get("path")));
                c.setUrlId(str(m.get("urlId")));
                c.setQuery(str(m.get("query")));
                c.setHeaders(str(m.get("headers")));
                c.setBody(str(m.get("body")));
                c.setAssertions(str(m.get("assertions")));
                c.setFieldNotes(str(m.get("fieldNotes")));
                Object en = m.get("enabled");
                c.setEnabled(en == null || Boolean.TRUE.equals(en) || "true".equalsIgnoreCase(String.valueOf(en)));
                Object so = m.get("sortOrder");
                c.setSortOrder(so instanceof Number ? ((Number) so).intValue() : 100);
                c.setCreatedBy(str(m.get("createdBy")) != null ? str(m.get("createdBy")) : "seed-" + project);
                c.setCreatedAt(now);
                c.setUpdatedAt(now);
                defaults(c);
                if (c.getName() == null || c.getName().isBlank() || c.getPath() == null || c.getPath().isBlank()) {
                    continue;
                }
                caseMapper.insert(c);
                n++;
            }
            log.info("[api-test] 已导入项目 {} 用例 {} 条（来自 {}）", project, n, classpathJson);
            return n;
        } catch (Exception e) {
            log.warn("[api-test] 导入项目 {} 失败: {}", project, e.getMessage());
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public List<ApiTestEnv> listEnvs() {
        return envMapper.findAll();
    }

    public ApiTestEnv getEnv(String environment) {
        return envMapper.findByEnvironment(TestEnvironment.normalize(environment));
    }

    @Transactional
    public ApiTestEnv saveEnv(ApiTestEnv body) {
        if (body == null || body.getEnvironment() == null || body.getEnvironment().isBlank()) {
            throw new IllegalArgumentException("environment 必填（TEST/PROD）");
        }
        body.setEnvironment(TestEnvironment.normalize(body.getEnvironment()));
        if (body.getBaseUrl() == null || body.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("baseUrl 必填");
        }
        body.setBaseUrl(trimSlash(body.getBaseUrl().trim()));
        if (body.getName() == null || body.getName().isBlank()) {
            body.setName(TestEnvironment.isTest(body.getEnvironment()) ? "测试环境" : "线上环境");
        }
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getDefaultHeaders() == null || body.getDefaultHeaders().isBlank()) {
            body.setDefaultHeaders("{}");
        }
        body.setUpdatedAt(LocalDateTime.now());
        envMapper.upsert(body);
        return envMapper.findByEnvironment(body.getEnvironment());
    }

    public List<ApiTestCase> listCases(String project, String environment, Boolean enabledOnly, String keyword) {
        return caseMapper.findByFilters(blankToNull(project), normalizeListEnv(environment),
                enabledOnly, blankToNull(keyword), null, null);
    }

    public PageResult<ApiTestCase> listCasesPage(String project, String environment, Boolean enabledOnly,
                                                 String keyword, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(200, Math.max(1, size));
        int offset = (p - 1) * s;
        String env = normalizeListEnv(environment);
        long total = caseMapper.countByFilters(blankToNull(project), env, enabledOnly, blankToNull(keyword));
        List<ApiTestCase> content = caseMapper.findByFilters(
                blankToNull(project), env, enabledOnly, blankToNull(keyword), offset, s);
        int totalPages = total == 0 ? 0 : (int) ((total + s - 1) / s);
        return new PageResult<>(content, total, totalPages, p, s);
    }

    /** 列表筛选：TEST/PROD；空则不过滤环境 */
    private static String normalizeListEnv(String environment) {
        if (environment == null || environment.isBlank()) return null;
        String e = environment.trim().toUpperCase(Locale.ROOT);
        if ("ANY".equals(e)) return null;
        return TestEnvironment.normalize(e);
    }

    public List<String> listProjects() {
        return caseMapper.findDistinctProjects();
    }

    public ApiTestCase getCase(String id) {
        return caseMapper.findById(id);
    }

    @Transactional
    public ApiTestCase createCase(ApiTestCase body) {
        validateCase(body);
        if (body.getId() == null || body.getId().isBlank()) {
            body.setId("atc-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (caseMapper.findById(body.getId()) != null) {
            throw new IllegalArgumentException("id 已存在: " + body.getId());
        }
        defaults(body);
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        caseMapper.insert(body);
        return caseMapper.findById(body.getId());
    }

    @Transactional
    public ApiTestCase updateCase(String id, ApiTestCase body) {
        ApiTestCase existing = caseMapper.findById(id);
        if (existing == null) return null;
        body.setId(id);
        validateCase(body);
        defaults(body);
        body.setCreatedAt(existing.getCreatedAt());
        body.setCreatedBy(existing.getCreatedBy());
        body.setUpdatedAt(LocalDateTime.now());
        caseMapper.update(body);
        return caseMapper.findById(id);
    }

    @Transactional
    public boolean deleteCase(String id) {
        return caseMapper.deleteById(id) > 0;
    }

    private void validateCase(ApiTestCase body) {
        if (body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        if (body.getPath() == null || body.getPath().isBlank()) {
            throw new IllegalArgumentException("path 必填");
        }
        if (body.getProject() != null && !body.getProject().isBlank()) {
            String p = body.getProject().trim();
            if (projectService.get(p) == null) {
                throw new IllegalArgumentException("项目不存在: " + p + "，请先到资产管理 · 项目管理新增");
            }
            body.setProject(p);
        }
        String m = body.getMethod() == null ? "GET" : body.getMethod().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(m)) {
            throw new IllegalArgumentException("不支持的 method: " + body.getMethod());
        }
        body.setMethod(m);
    }

    private void defaults(ApiTestCase body) {
        if (body.getProject() == null || body.getProject().isBlank()) {
            body.setProject("default");
        } else {
            body.setProject(body.getProject().trim());
        }
        String env = body.getEnvironment() == null ? "ANY" : body.getEnvironment().trim().toUpperCase(Locale.ROOT);
        if (!"ANY".equals(env) && !TestEnvironment.TEST.equals(env) && !TestEnvironment.PROD.equals(env)) {
            env = "ANY";
        }
        body.setEnvironment(env);
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getSortOrder() == null) body.setSortOrder(100);
        if (body.getQuery() == null || body.getQuery().isBlank()) body.setQuery("{}");
        if (body.getHeaders() == null || body.getHeaders().isBlank()) body.setHeaders("{}");
        if (body.getAssertions() == null || body.getAssertions().isBlank()) {
            body.setAssertions("[{\"type\":\"status\",\"equals\":200}]");
        }
        if (body.getFieldNotes() == null || body.getFieldNotes().isBlank()) body.setFieldNotes("{}");
        if (body.getBody() == null) body.setBody("");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String trimSlash(String base) {
        if (base == null) return "";
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
