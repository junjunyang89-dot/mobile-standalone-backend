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

    /**
     * 数据中心项目码。
     * <p>
     * 2026-08-30 由 {@code data-center} 统一为 {@code app-data-center}，与其余
     * {@code app-{gitlab}} 命名一致（迁移见 {@code db/migration_project_model_01_cleanup.sql}）。
     * 库里 500+ 行引用已随之更新，此处必须同步——否则启动时 {@code ensureExists}
     * 会按旧码再造出一个空项目，和迁移后的项目并存。
     */
    public static final String PROJECT_DATA_CENTER = "app-data-center";
    /** 接口对比调试用的 CRUD 样例项目，和真实业务项目分开放 */
    public static final String PROJECT_DEBUG_TESTS = "debug-tests";
    /** 多层依赖 + 嵌套 JSON 抽取专用调试项目 */
    public static final String PROJECT_DEBUG_FLOW = "debug-flow";
    /** 故意制造 BREAKING 变更，调试「可能不兼容」 */
    public static final String PROJECT_DEBUG_COMPAT = "debug-compat";
    /** 0825 提测冒烟用例种子（接口在 backend-0825 分支） */
    public static final String PROJECT_DEBUG_SMOKE = "debug-smoke";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

    private final ApiTestCaseMapper caseMapper;
    private final ApiTestEnvMapper envMapper;
    private final ApiProjectService projectService;
    private final JdbcTemplate jdbcTemplate;
    /**
     * 用例保存时自动挂接口。用 setter 注入而不是构造器：
     * {@code ApiEndpointService} 上有 {@code @DependsOn("apiTestCaseService")}，
     * 构造器注入会绕成循环依赖，启动直接失败
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.mobile.standalone.service.apitest.ApiEndpointService endpointService;

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
            // 老表名 api_test_variables 改成 env_variables：这套东西 UI 自动化也要用，
            // 名字里带 api 会让人以为跟接口测试绑定，于是又去建第二套
            try {
                Integer old = jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM information_schema.TABLES "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'api_test_variables'",
                        Integer.class);
                if (old != null && old > 0) {
                    jdbcTemplate.execute("RENAME TABLE api_test_variables TO env_variables");
                    log.info("[env-vars] api_test_variables 已改名为 env_variables");
                }
            } catch (Exception e) {
                log.debug("[env-vars] 改名跳过: {}", e.getMessage());
            }
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS env_variables (
                        environment VARCHAR(16) NOT NULL,
                        name VARCHAR(64) NOT NULL,
                        value VARCHAR(2048) NULL,
                        secret TINYINT(1) NOT NULL DEFAULT 0,
                        enabled TINYINT(1) NOT NULL DEFAULT 1,
                        description VARCHAR(255) NULL,
                        sort_order INT NOT NULL DEFAULT 100,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (environment, name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_test_collections (
                        id VARCHAR(64) PRIMARY KEY,
                        project VARCHAR(64) NOT NULL,
                        name VARCHAR(128) NOT NULL,
                        description VARCHAR(512) NULL,
                        enabled TINYINT(1) NOT NULL DEFAULT 1,
                        sort_order INT NOT NULL DEFAULT 100,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_project_name (project, name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            ensureProjectColumn();
            ensureUrlIdColumn();
            ensureEnvironmentColumn();
            ensureColumn("api_test_cases", "collection_id",
                    "VARCHAR(64) NULL COMMENT '归属集合' AFTER project");
            ensureCollectionScopeColumns();
            ensureColumn("api_test_cases", "extract_json",
                    "TEXT NULL COMMENT '执行后抽变量，如 {\"token\":\"$.result.token\"}' AFTER assertions");
            // 数据库断言的连接跟着环境走，和 base_url 一个逻辑：
            // 切到预发就该查预发的库，否则「接口打的是预发、断言查的是测试库」，
            // 这种错查出来的结论全是反的
            ensureColumn("api_test_envs", "db_host",
                    "VARCHAR(255) NULL COMMENT '数据库断言用的只读库地址'");
            ensureColumn("api_test_envs", "db_port", "INT NULL COMMENT '默认 3306'");
            ensureColumn("api_test_envs", "db_name", "VARCHAR(128) NULL");
            ensureColumn("api_test_envs", "db_user",
                    "VARCHAR(128) NULL COMMENT '务必用只读账号'");
            ensureColumn("api_test_envs", "db_password", "VARCHAR(255) NULL");
            seedEnvsIfEmpty();
            projectService.ensureExists(PROJECT_DATA_CENTER, "APP-DATA-CENTER（数据中心）");
            importProjectSeedIfAbsent(PROJECT_DATA_CENTER, "api-test/seed-data-center.json");
            // 接口对比调试用的那套 CRUD 用例。
            // 原来是一个要手跑的 seed_debug_api_test_cases.sql，里面先 DELETE 再 INSERT——
            // 那种写法不能放到启动时跑：每次重启都会把人改过的断言冲掉，
            // 而表现是「我明明改过的用例又变回去了」。走 IfAbsent，只在这个项目一条用例都没有时导一次
            projectService.ensureExists(PROJECT_DEBUG_TESTS, "debug-tests（接口对比调试）");
            importProjectSeedIfAbsent(PROJECT_DEBUG_TESTS, "api-test/seed-debug-tests.json");
            projectService.ensureExists(PROJECT_DEBUG_FLOW, "debug-flow（多层依赖嵌套调试）");
            importProjectSeedIfAbsent(PROJECT_DEBUG_FLOW, "api-test/seed-debug-flow.json");
            projectService.ensureExists(PROJECT_DEBUG_COMPAT, "debug-compat（不兼容变更调试）");
            importProjectSeedIfAbsent(PROJECT_DEBUG_COMPAT, "api-test/seed-debug-compat.json");
            projectService.ensureExists(PROJECT_DEBUG_SMOKE, "debug-smoke（0825提测）");
            importProjectSeedIfAbsent(PROJECT_DEBUG_SMOKE, "api-test/seed-debug-smoke.json");
            log.info("[api-test] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-test] 建表/导入失败: {}", e.getMessage());
        }
    }

    /**
     * 列不存在才加。
     * <p>
     * 后加的列都走这里，不再一列写一个 ensureXxxColumn——上面那三个是历史遗留。
     * 靠人记着去跑 migration.sql 是不行的：漏跑一次就是启动即报「Unknown column」，
     * 而报错本身看不出该跑哪个文件。
     */
    private void ensureColumn(String table, String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n != null && n == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("[api-test] 已补充 {}.{} 列", table, column);
            }
        } catch (Exception e) {
            log.debug("[api-test] {}.{} 列检查跳过: {}", table, column, e.getMessage());
        }
    }

    private void ensureCollectionScopeColumns() {
        ensureColumn("api_test_collections", "scope",
                "VARCHAR(16) NULL DEFAULT 'LIVE' COMMENT 'TEST=迭代临时 LIVE=master回归'");
        ensureColumn("api_test_collections", "lifecycle",
                "VARCHAR(16) NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED'");
        ensureColumn("api_test_collections", "requirement_no",
                "VARCHAR(64) NULL COMMENT '关联需求单 APP-xxxx'");
        ensureColumn("api_test_collections", "app_version",
                "VARCHAR(128) NULL COMMENT '版本迭代标签如 9.3 国内客户端'");
        // 分支对比来的集合关联分支而不是需求单：一个分支上可能合了好几个单，
        // 硬塞一个单号进去，另外几个单就在链路上消失了
        ensureColumn("api_test_collections", "source_branch",
                "VARCHAR(255) NULL COMMENT '分支对比来的集合关联的分支' AFTER app_version");
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
                List<ApiTestCase> existing = caseMapper.findByFilters(project, null, null, null, null, null, null);
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
                c.setExtractJson(str(m.get("extractJson")));
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

    /**
     * 列环境。数据库断言的密码<b>不回显</b>，只给一个遮罩。
     * <p>
     * 这是个前端能拿到的接口，明文吐出去就等于把测试库账号贴在了页面上。
     * 和 {@code env_variables} 的敏感值一个处理方式。
     */
    public List<ApiTestEnv> listEnvs() {
        List<ApiTestEnv> list = envMapper.findAll();
        for (ApiTestEnv e : list) {
            if (e.getDbPassword() != null && !e.getDbPassword().isBlank()) {
                e.setDbPassword(EnvVariableService.MASK);
            }
        }
        return list;
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
        // 前端拿到的是遮罩，原样传回来时不能当成「用户把密码改成了这串点」
        ApiTestEnv old = envMapper.findByEnvironment(body.getEnvironment());
        if (old != null && (body.getDbPassword() == null
                || EnvVariableService.MASK.equals(body.getDbPassword()))) {
            body.setDbPassword(old.getDbPassword());
        }
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

    public List<ApiTestCase> listCases(String project, String collectionId, String environment,
                                       Boolean enabledOnly, String keyword) {
        return caseMapper.findByFilters(blankToNull(project), blankToNull(collectionId), normalizeListEnv(environment),
                enabledOnly, blankToNull(keyword), null, null);
    }

    public PageResult<ApiTestCase> listCasesPage(String project, String collectionId, String environment,
                                                 Boolean enabledOnly, String keyword, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(200, Math.max(1, size));
        int offset = (p - 1) * s;
        String env = normalizeListEnv(environment);
        long total = caseMapper.countByFilters(
                blankToNull(project), blankToNull(collectionId), env, enabledOnly, blankToNull(keyword));
        List<ApiTestCase> content = caseMapper.findByFilters(
                blankToNull(project), blankToNull(collectionId), env, enabledOnly, blankToNull(keyword), offset, s);
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
        linkEndpoint(body);
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
        // 路径或方法被改过时要重新挂：改完还挂在老接口上，
        // 清单两边的用例数就都是错的
        linkEndpoint(body);
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

    /**
     * 按 项目+方法+路径 把用例挂到接口清单上。
     * <p>
     * 放在服务层而不是让调用方填：靠调用方记着填，迟早有一条漏了，
     * 而漏了的表现是清单上那个接口显示「0 条用例」，于是有人去补一条已经存在的用例。
     */
    private void linkEndpoint(ApiTestCase body) {
        if (endpointService == null) {
            return;
        }
        body.setEndpointId(endpointService.ensureEndpointFor(
                body.getProject(), body.getMethod(), body.getPath(),
                body.getRequirementRef(), body.getName()));
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
            // 留 null，不要塞 "default"。
            // validateCase 只在 project 非空时校验存在性，所以这条路是绕开校验的：
            // 塞进去的 "default" 在 api_projects 里并不存在，等于凭空造孤儿值，
            // 加上外键之后会直接写不进去。列本身可空，null 才是「未归属」的诚实表达。
            body.setProject(null);
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
