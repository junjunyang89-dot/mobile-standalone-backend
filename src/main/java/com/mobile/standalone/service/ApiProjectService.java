package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.apitest.ApiAppCatalog;
import com.mobile.standalone.service.apitest.ApiProjectCatalog;
import com.mobile.standalone.service.apitest.ApiProjectKind;
import com.mobile.standalone.service.apitest.CaptureCatalogService;
import com.mobile.standalone.service.apitest.ControllerBackfillService;
import com.mobile.standalone.entity.ApiEndpoint;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiProjectService {

    private static final Pattern CODE_OK = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$");

    /** 启动时自动种过、不应出现在接口清单里的客户端编码 */
    private static final Set<String> CLIENT_PROJECT_CODES = Set.of(
            "android-domestic-esports",
            "android-overseas-pogpog",
            "ios-domestic-esports",
            "ios-overseas-pogpog",
            "harmonyos-domestic-esports"
    );

    private final ApiProjectMapper projectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Value("${cursor.ask.cwd:${user.home}/projects/qa_coding/app_coding}")
    private String workspace;

    @PostConstruct
    public void ensureTableAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_projects (
                        code VARCHAR(64) PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        description VARCHAR(1024) DEFAULT NULL,
                        enabled TINYINT(1) DEFAULT 1,
                        sort_order INT DEFAULT 100,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            // 建表语句只有最初那几列，后加的列靠这里补齐。
            // 不这么做的话，每加一列就得让人记着去跑一份 migration.sql，漏跑一次就是启动即报错，
            // 而且报出来的是「Unknown column」，看不出该跑哪个文件
            ensureColumn("repo_path", "VARCHAR(512) NULL COMMENT '本地仓库路径'");
            ensureColumn("git_url", "VARCHAR(512) NULL COMMENT 'GitLab 仓库地址'");
            ensureColumn("base_branch", "VARCHAR(128) NULL COMMENT '对比基线分支，默认 master'");
            ensureColumn("success_code_path", "VARCHAR(128) NULL COMMENT '业务成功码路径，如 $.code'");
            ensureColumn("success_code_value", "VARCHAR(64) NULL COMMENT '业务成功码的值，如 0'");
            ensureColumn("kind", "VARCHAR(16) NULL DEFAULT 'BACKEND' COMMENT 'BACKEND=后端 FRONTEND=前端 CLIENT=客户端'");
            ensureColumn("category", "VARCHAR(64) NULL COMMENT '分类 key，留空按编码自动归类'");
            ensureCategoryTableAndSeed();
            retireLegacyCategories();
            syncCategoriesFromProjects();
            dedupeEmptySimilarCategories();
            widenProjectCategoryColumn();
            seedIfEmpty();
            markLegacyClientProjects();
            markFrontendProjects();
            ensureFrontendCatalog();
            log.info("[api-projects] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-projects] 建表失败: {}", e.getMessage());
        }
    }

    /** 列不存在才加。用查 information_schema 而不是 catch 异常：后者会在日志里刷一堆吓人的堆栈 */
    private void ensureColumn(String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'api_projects' AND COLUMN_NAME = ?",
                    Integer.class, column);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE api_projects ADD COLUMN " + column + " " + definition);
            log.info("[api-projects] 补列 {}", column);
        } catch (Exception e) {
            log.warn("[api-projects] 补列 {} 失败: {}", column, e.getMessage());
        }
    }

    private void seedIfEmpty() {
        if (projectMapper.countAll() > 0) return;
        LocalDateTime now = LocalDateTime.now();
        ApiProject p = new ApiProject();
        p.setCode(ApiTestCaseService.PROJECT_DATA_CENTER);
        p.setName("data-center（数据中心）");
        p.setDescription("Deadlock / 生涯等后端数据服务；接口用例与 URL 归属此项目");
        p.setKind(ApiProject.KIND_BACKEND);
        p.setEnabled(true);
        p.setSortOrder(10);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        projectMapper.insert(p);
        log.info("[api-projects] 已种子项目 data-center");
    }

    /** 历史版本启动时种过客户端项目，标成 CLIENT，避免混进接口清单 */
    private void markLegacyClientProjects() {
        for (String code : CLIENT_PROJECT_CODES) {
            try {
                int n = jdbcTemplate.update(
                        "UPDATE api_projects SET kind = ? WHERE code = ? AND (kind IS NULL OR kind = '' OR kind = 'BACKEND')",
                        ApiProject.KIND_CLIENT, code);
                if (n > 0) {
                    log.info("[api-projects] 已将 {} 标记为 CLIENT", code);
                }
            } catch (Exception e) {
                log.debug("[api-projects] 标记 CLIENT 跳过 {}: {}", code, e.getMessage());
            }
        }
    }

    /** H5 / 前端站标成 FRONTEND，不扫接口；网关仍是 BACKEND，扫描时再跳过 */
    private void markFrontendProjects() {
        for (String code : ApiProjectKind.FRONTEND_CODES) {
            try {
                int n = jdbcTemplate.update(
                        "UPDATE api_projects SET kind = ? WHERE LOWER(code) = ? AND (kind IS NULL OR kind = '' OR kind = 'BACKEND')",
                        ApiProject.KIND_FRONTEND, code);
                if (n > 0) {
                    log.info("[api-projects] 已将 {} 标记为 FRONTEND", code);
                }
            } catch (Exception e) {
                log.debug("[api-projects] 标记 FRONTEND 跳过 {}: {}", code, e.getMessage());
            }
        }
    }

    /** 前端仓库写进项目管理：有则补 GitLab / 本地路径，没有就新建。不扫接口。 */
    private void ensureFrontendCatalog() {
        LocalDateTime now = LocalDateTime.now();
        int order = 200;
        for (ApiProjectKind.FrontendCatalog e : ApiProjectKind.FRONTEND_CATALOG) {
            try {
                ApiProject p = projectMapper.findByCode(e.code());
                if (p == null) {
                    p = new ApiProject();
                    p.setCode(e.code());
                    p.setName(e.name());
                    p.setDescription(e.description());
                    p.setKind(ApiProject.KIND_FRONTEND);
                    p.setCategory(ApiProjectCatalog.APP.key());
                    p.setGitUrl(e.gitUrl());
                    p.setRepoPath(localRepoPath(e.code()));
                    p.setEnabled(true);
                    p.setSortOrder(order);
                    p.setCreatedAt(now);
                    p.setUpdatedAt(now);
                    projectMapper.insert(p);
                    log.info("[api-projects] 已录入前端仓库 {}", e.code());
                } else {
                    boolean ch = false;
                    if (p.getGitUrl() == null || p.getGitUrl().isBlank()) {
                        p.setGitUrl(e.gitUrl());
                        ch = true;
                    }
                    if (p.getRepoPath() == null || p.getRepoPath().isBlank()) {
                        String local = localRepoPath(e.code());
                        if (local != null) {
                            p.setRepoPath(local);
                            ch = true;
                        }
                    }
                    if (p.getKind() == null || p.getKind().isBlank() || ApiProject.KIND_BACKEND.equals(p.getKind())) {
                        p.setKind(ApiProject.KIND_FRONTEND);
                        ch = true;
                    }
                    if (p.getCategory() == null || p.getCategory().isBlank()) {
                        p.setCategory(ApiProjectCatalog.APP.key());
                        ch = true;
                    }
                    if (ch) {
                        p.setUpdatedAt(now);
                        projectMapper.update(p);
                        log.info("[api-projects] 已补全前端仓库 {}", e.code());
                    }
                }
            } catch (Exception ex) {
                log.warn("[api-projects] 录入前端仓库 {} 失败: {}", e.code(), ex.getMessage());
            }
            order += 10;
        }
    }

    private String localRepoPath(String code) {
        if (workspace == null || workspace.isBlank() || code == null) {
            return null;
        }
        Path ws = Path.of(workspace.trim());
        if (!Files.isDirectory(ws)) {
            return null;
        }
        for (String name : ControllerBackfillService.repoNameCandidates(code)) {
            Path dir = ws.resolve(name);
            if (ControllerBackfillService.looksLikeSourceRepo(dir)) {
                return dir.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    public List<ApiProject> list(Boolean enabledOnly) {
        return projectMapper.findAll(enabledOnly).stream()
                .filter(p -> !CaptureCatalogService.INBOX.equals(p.getCode()))
                .toList();
    }

    /** 接口清单下拉：只要会扫接口的后端仓库，且已配本地路径或已有接口记录 */
    public List<ApiProject> listForCatalog(Boolean enabledOnly) {
        return projectMapper.findForCatalog(enabledOnly).stream()
                .filter(ApiProjectKind::scansEndpoints)
                .toList();
    }

    /** 项目管理页：分类 → 后端/前端/客户端（空类型不占位）→ 已建项目 */
    public Map<String, Object> buildTree(Boolean enabledOnly) {
        List<Map<String, Object>> rows = list(enabledOnly).stream().map(this::toTreeRow).toList();
        Map<String, List<Map<String, Object>>> buckets = ApiProjectCatalog.bucketProjects(new ArrayList<>(rows));

        List<Map<String, Object>> categories = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        for (Map<String, Object> def : listCategoryRegistry()) {
            String key = String.valueOf(def.get("key"));
            List<Map<String, Object>> projects = new ArrayList<>(buckets.getOrDefault(key, List.of()));
            if (ApiProjectCatalog.APP.key().equals(key)) {
                projects = orderAppCategoryProjects(projects, key, filedElsewhere(rows));
            }
            projects = realProjects(projects);
            if (projects.isEmpty()) {
                continue;
            }
            categories.add(buildCategoryVo(
                    key,
                    String.valueOf(def.get("label")),
                    projects,
                    def.get("builtin"),
                    def.get("sortOrder")));
            handled.add(key);
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : buckets.entrySet()) {
            if (handled.contains(e.getKey()) || e.getValue().isEmpty()) {
                continue;
            }
            ensureCategoryRegistered(e.getKey());
            categories.add(buildCategoryVo(
                    e.getKey(),
                    categoryLabel(e.getKey()),
                    realProjects(e.getValue()),
                    false,
                    null));
            handled.add(e.getKey());
        }
        return Map.of("categories", categories);
    }

    public List<Map<String, Object>> listCategoryRegistry() {
        return jdbcTemplate.query(
                "SELECT cat_key, label, sort_order, builtin FROM api_project_categories "
                        + "ORDER BY sort_order ASC, cat_key ASC",
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", rs.getString("cat_key"));
                    m.put("label", rs.getString("label"));
                    m.put("sortOrder", rs.getInt("sort_order"));
                    m.put("builtin", rs.getBoolean("builtin"));
                    return m;
                });
    }

    @Transactional
    public Map<String, Object> createCategory(String label, Integer sortOrder) {
        String key = normalizeCategoryKey(label);
        if (key.isBlank()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        String display = label == null || label.isBlank() ? ApiProjectCatalog.categoryDisplayLabel(key) : label.trim();
        if (display.length() > 128) {
            throw new IllegalArgumentException("分类名称不超过 128 字符");
        }
        assertCreateLabelNotSimilar(display, null);
        assertNotDuplicateRenameAsCreate(display, key);
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM api_project_categories WHERE cat_key = ?", Integer.class, key);
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("分类已存在: " + display);
        }
        int order = sortOrder != null ? sortOrder : nextCategorySortOrder();
        boolean builtin = ApiProjectCatalog.isBuiltinCategory(key);
        jdbcTemplate.update(
                "INSERT INTO api_project_categories (cat_key, label, sort_order, builtin) VALUES (?, ?, ?, ?)",
                key, display, order, builtin ? 1 : 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("label", display);
        out.put("sortOrder", order);
        out.put("builtin", builtin);
        return out;
    }

    /** 分类展示名：以 api_project_categories 为准，未注册时回退 catalog 默认 */
    public String categoryLabel(String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) {
            return categoryLabel(ApiProjectCatalog.APP.key());
        }
        String key = normalizeCategoryKey(categoryKey);
        for (Map<String, Object> row : listCategoryRegistry()) {
            if (key.equals(String.valueOf(row.get("key")))) {
                return String.valueOf(row.get("label"));
            }
        }
        return ApiProjectCatalog.categoryDisplayLabel(key);
    }

    public Map<String, String> categoryLabelsByKey() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : listCategoryRegistry()) {
            out.put(String.valueOf(row.get("key")), String.valueOf(row.get("label")));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> updateCategory(String key, String label, Integer sortOrder) {
        String k = normalizeCategoryKey(key);
        if (k.isBlank()) {
            throw new IllegalArgumentException("分类无效");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        String display = label.trim();
        if (display.length() > 128) {
            throw new IllegalArgumentException("分类名称不超过 128 字符");
        }
        ensureCategoryRegistered(k);
        int updated;
        if (sortOrder != null) {
            updated = jdbcTemplate.update(
                    "UPDATE api_project_categories SET label = ?, sort_order = ? WHERE cat_key = ?",
                    display, sortOrder, k);
        } else {
            updated = jdbcTemplate.update(
                    "UPDATE api_project_categories SET label = ? WHERE cat_key = ?", display, k);
        }
        if (updated == 0) {
            throw new IllegalArgumentException("分类不存在: " + k);
        }
        assertCreateLabelNotSimilar(display, k);
        boolean builtin = ApiProjectCatalog.isBuiltinCategory(k);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", k);
        out.put("label", display);
        out.put("builtin", builtin);
        if (sortOrder != null) {
            out.put("sortOrder", sortOrder);
        }
        purgeEmptySimilarCategories(k, display);
        return out;
    }

    @Transactional
    public boolean deleteCategory(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String k = normalizeCategoryKey(key);
        if (ApiProjectCatalog.isBuiltinCategory(k)) {
            throw new IllegalArgumentException("内置分类不可删除");
        }
        Integer projectCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM api_projects WHERE category = ?", Integer.class, k);
        if (projectCount != null && projectCount > 0) {
            throw new IllegalArgumentException("该分类下还有项目，请先移走或删除项目");
        }
        return jdbcTemplate.update(
                "DELETE FROM api_project_categories WHERE cat_key = ? AND builtin = 0", k) > 0;
    }

    private void ensureCategoryTableAndSeed() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS api_project_categories (
                    cat_key VARCHAR(64) PRIMARY KEY,
                    label VARCHAR(128) NOT NULL,
                    sort_order INT DEFAULT 100,
                    builtin TINYINT(1) DEFAULT 0,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM api_project_categories", Integer.class);
        if (n != null && n > 0) {
            return;
        }
        for (ApiProjectCatalog.Category cat : ApiProjectCatalog.categoriesInOrder()) {
            jdbcTemplate.update(
                    "INSERT INTO api_project_categories (cat_key, label, sort_order, builtin) VALUES (?, ?, ?, 1)",
                    cat.key(), cat.label(), categorySeedOrder(cat.key()), 1);
        }
        log.info("[api-projects] 已种子内置分类");
    }

    /**
     * PVP / WEB / 其他已下线：注册表和项目上的旧值都清掉。
     * APP 还叫旧默认名时改成「电竞」；人手改过的名字不动。
     */
    private void retireLegacyCategories() {
        for (String key : ApiProjectCatalog.RETIRED_CATEGORY_KEYS) {
            int cats = jdbcTemplate.update("DELETE FROM api_project_categories WHERE cat_key = ?", key);
            int projects = jdbcTemplate.update(
                    "UPDATE api_projects SET category = NULL WHERE UPPER(category) = ?", key);
            if (cats > 0 || projects > 0) {
                log.info("[api-projects] 已下线分类 {}（清掉 {} 个项目上的旧值）", key, projects);
            }
        }
        jdbcTemplate.update(
                "UPDATE api_project_categories SET label = ? "
                        + "WHERE cat_key = 'APP' AND label IN ('APP', 'APP 后端', 'APP后端', '电竞APP')",
                ApiProjectCatalog.APP.label());
    }

    private static int categorySeedOrder(String key) {
        return "APP".equals(key) ? 10 : 100;
    }

    private void syncCategoriesFromProjects() {
        List<String> cats = jdbcTemplate.queryForList(
                "SELECT DISTINCT category FROM api_projects WHERE category IS NOT NULL AND TRIM(category) <> ''",
                String.class);
        for (String cat : cats) {
            ensureCategoryRegistered(cat);
        }
    }

    /** 去掉因旧接口误把重命名当新建而产生的空重复分类（如 APP 与 APP后端） */
    private void dedupeEmptySimilarCategories() {
        List<Map<String, Object>> registry = new ArrayList<>(listCategoryRegistry());
        Map<String, String> fpToPreferredKey = new LinkedHashMap<>();
        for (Map<String, Object> row : registry) {
            String key = String.valueOf(row.get("key"));
            String fp = labelFingerprint(String.valueOf(row.get("label")));
            if (fp.isEmpty()) {
                continue;
            }
            long projectCount = countProjectsInCategoryKey(key);
            if (projectCount > 0 || ApiProjectCatalog.isBuiltinCategory(key)) {
                fpToPreferredKey.put(fp, key);
            }
        }
        for (ApiProjectCatalog.Category cat : ApiProjectCatalog.categoriesInOrder()) {
            fpToPreferredKey.putIfAbsent(labelFingerprint(cat.label()), cat.key());
        }
        for (Map<String, Object> row : registry) {
            String key = String.valueOf(row.get("key"));
            if (ApiProjectCatalog.isBuiltinCategory(key)) {
                continue;
            }
            if (countProjectsInCategoryKey(key) > 0) {
                continue;
            }
            String fp = labelFingerprint(String.valueOf(row.get("label")));
            String preferred = fpToPreferredKey.get(fp);
            if (preferred != null && !preferred.equals(key)) {
                jdbcTemplate.update(
                        "DELETE FROM api_project_categories WHERE cat_key = ? AND builtin = 0", key);
                log.info("[api-projects] 移除误建空分类 {}（与 {} 重复）", key, preferred);
            }
        }
    }

    private long countProjectsInCategoryKey(String categoryKey) {
        Integer explicit = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM api_projects WHERE category = ?", Integer.class, categoryKey);
        if (explicit != null && explicit > 0) {
            return explicit;
        }
        long inferred = 0;
        for (ApiProject p : projectMapper.findAll(null)) {
            String resolved = ApiProjectCatalog.resolveCategoryKey(p.getCode(), p.getCategory());
            if (categoryKey.equals(resolved)) {
                inferred++;
            }
        }
        return inferred;
    }

    private void assertNotDuplicateRenameAsCreate(String display, String newKey) {
        String fp = labelFingerprint(display);
        if (fp.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : listCategoryRegistry()) {
            String existingKey = String.valueOf(row.get("key"));
            if (existingKey.equals(newKey)) {
                continue;
            }
            if (!labelFingerprint(String.valueOf(row.get("label"))).equals(fp)) {
                continue;
            }
            if (countProjectsInCategoryKey(existingKey) > 0 || ApiProjectCatalog.isBuiltinCategory(existingKey)) {
                throw new IllegalArgumentException(
                        "名称与已有分类「" + row.get("label") + "」相同，请点 ✎ 重命名，不要新建");
            }
        }
    }

    private void purgeEmptySimilarCategories(String keepKey, String display) {
        String fp = labelFingerprint(display);
        if (fp.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : listCategoryRegistry()) {
            String key = String.valueOf(row.get("key"));
            if (keepKey.equals(key) || ApiProjectCatalog.isBuiltinCategory(key)) {
                continue;
            }
            if (countProjectsInCategoryKey(key) > 0) {
                continue;
            }
            if (labelFingerprint(String.valueOf(row.get("label"))).equals(fp)) {
                jdbcTemplate.update(
                        "DELETE FROM api_project_categories WHERE cat_key = ? AND builtin = 0", key);
                log.info("[api-projects] 重命名后清理误建空分类 {}", key);
            }
        }
    }

    private void assertCreateLabelNotSimilar(String display, String excludeKey) {
        String fp = labelFingerprint(display);
        if (fp.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : listCategoryRegistry()) {
            String key = String.valueOf(row.get("key"));
            if (excludeKey != null && excludeKey.equals(key)) {
                continue;
            }
            if (labelFingerprint(String.valueOf(row.get("label"))).equals(fp)) {
                if (!ApiProjectCatalog.isBuiltinCategory(key) && countProjectsInCategoryKey(key) == 0) {
                    continue;
                }
                throw new IllegalArgumentException(
                        "已有分类「" + row.get("label") + "」，请点左侧 ✎ 重命名，不要新建");
            }
        }
    }

    private static String labelFingerprint(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        return label.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private void widenProjectCategoryColumn() {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE api_projects MODIFY COLUMN category VARCHAR(64) NULL "
                            + "COMMENT '分类 key，留空按编码自动归类'");
        } catch (Exception e) {
            log.debug("[api-projects] 扩 category 列跳过: {}", e.getMessage());
        }
    }

    /**
     * 确保分类已注册。
     * <p>
     * <b>只接受内置 key</b>。原先见到任何未注册的值就自动建一条分类，结果是：
     * 有人把分类的 <i>label</i>（「APP后端」）当 key 写进了 {@code api_projects.category}，
     * 这里就照单全收，把它注册成了一条正式分类——树上于是并排出现「APP」和「APP后端」两个
     * 长得一样的节点。库里实测被这样污染出 2 条垃圾分类、11 个项目。
     * <p>
     * 自定义分类应当走 {@code POST /api-projects/categories} 显式创建，而不是靠写错一个值
     * 顺带创建出来。未知值记警告并忽略，让问题停在日志里而不是长进分类表。
     */
    void ensureCategoryRegistered(String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) {
            return;
        }
        String key = normalizeCategoryKey(categoryKey);
        if (!ApiProjectCatalog.isBuiltinCategory(key) && !categoryExists(key)) {
            log.warn("[api-projects] 忽略未注册的分类值「{}」——请先在分类管理里创建，"
                    + "或确认是不是把分类展示名误当成了 key", key);
            return;
        }
        String label = ApiProjectCatalog.categoryDisplayLabel(key);
        boolean builtin = ApiProjectCatalog.isBuiltinCategory(key);
        jdbcTemplate.update(
                "INSERT IGNORE INTO api_project_categories (cat_key, label, sort_order, builtin) VALUES (?, ?, ?, ?)",
                key, label, builtin ? categorySeedOrder(key) : nextCategorySortOrder(), builtin ? 1 : 0);
    }

    /** 分类是否已注册。功能模块挂在分类下，建模块前要先确认分类真实存在 */
    public boolean categoryExists(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM api_project_categories WHERE cat_key = ?",
                Integer.class, normalizeCategoryKey(key));
        return n != null && n > 0;
    }

    private int nextCategorySortOrder() {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sort_order), 0) FROM api_project_categories", Integer.class);
        return (max == null ? 0 : max) + 10;
    }

    private static String normalizeCategoryKey(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        String t = label.trim();
        if (ApiProjectCatalog.isBuiltinCategory(t)) {
            return t.toUpperCase(Locale.ROOT);
        }
        return t;
    }

    /** 在项目管理里手动挂到电竞以外分类的项目：电竞的固定槽位不能再把它们拉回来 */
    private static Set<String> filedElsewhere(List<Map<String, Object>> rows) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String code = String.valueOf(row.get("code"));
            Object cat = row.get("category");
            if (!ApiProjectCatalog.APP.key().equals(
                    ApiProjectCatalog.resolveCategoryKey(code, cat == null ? null : String.valueOf(cat)))) {
                out.add(code);
            }
        }
        return out;
    }

    private List<Map<String, Object>> orderAppCategoryProjects(
            List<Map<String, Object>> projects, String categoryKey, Set<String> elsewhere) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : projects) {
            byCode.put(String.valueOf(row.get("code")), row);
        }
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (String slot : ApiProjectCatalog.appProjectSlots()) {
            if (elsewhere.contains(slot)) {
                continue;
            }
            Map<String, Object> hit = byCode.remove(slot);
            if (hit != null) {
                ordered.add(hit);
            }
        }
        ordered.addAll(byCode.values());
        return ordered;
    }

    private static List<Map<String, Object>> realProjects(List<Map<String, Object>> projects) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : projects) {
            if (!Boolean.TRUE.equals(p.get("missing"))) {
                out.add(p);
            }
        }
        return out;
    }

    private Map<String, Object> buildCategoryVo(
            String key,
            String label,
            List<Map<String, Object>> projects,
            Object builtinFlag,
            Object sortOrder) {
        long configured = projects.stream()
                .filter(p -> !Boolean.TRUE.equals(p.get("missing")))
                .count();
        Map<String, Object> catVo = new LinkedHashMap<>();
        catVo.put("key", key);
        catVo.put("label", label);
        catVo.put("projects", projects);
        catVo.put("kinds", groupByKind(projects));
        catVo.put("count", configured);
        catVo.put("builtin", Boolean.TRUE.equals(builtinFlag) || "true".equals(String.valueOf(builtinFlag)));
        catVo.put("custom", !ApiProjectCatalog.isBuiltinCategory(key));
        if (sortOrder instanceof Number n) {
            catVo.put("sortOrder", n.intValue());
        }
        return catVo;
    }

    /** 空类型不占位：这个产品线下没有前端仓库就不出现「前端」 */
    static List<Map<String, Object>> groupByKind(List<Map<String, Object>> projects) {
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (String k : ApiProjectKind.ORDER) {
            buckets.put(k, new ArrayList<>());
        }
        for (Map<String, Object> p : projects) {
            if (Boolean.TRUE.equals(p.get("missing"))) {
                continue;
            }
            String kind = ApiProjectKind.normalize(p.get("kind") == null ? null : String.valueOf(p.get("kind")));
            buckets.computeIfAbsent(kind, x -> new ArrayList<>()).add(p);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : ApiProjectKind.ORDER) {
            List<Map<String, Object>> rows = buckets.getOrDefault(k, List.of());
            if (rows.isEmpty()) {
                continue;
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("key", k);
            g.put("label", ApiProjectKind.label(k));
            g.put("count", rows.size());
            g.put("projects", rows);
            out.add(g);
        }
        return out;
    }

    /**
     * 接口清单左侧树：环境在页面顶部分开选；这里只给「分类 → 后端 → 项目」。
     * 前端 / 客户端 / 网关不出现。空类型不占位。项目下不再挂分支。
     */
    public Map<String, Object> buildEndpointInventoryTree(List<ApiEndpoint> allEndpoints) {
        Map<String, int[]> stats = aggregateEndpointStats(allEndpoints);
        Map<String, ApiProject> projectsByCode = new LinkedHashMap<>();
        for (ApiProject p : list(null)) {
            if (!ApiProjectKind.scansEndpoints(p)) {
                continue;
            }
            projectsByCode.putIfAbsent(p.getCode(), p);
            String treeKey = inventoryProjectKey(p.getCode());
            projectsByCode.putIfAbsent(treeKey, p);
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        Map<String, String> labels = categoryLabelsByKey();

        for (Map<String, Object> def : listCategoryRegistry()) {
            String catKey = String.valueOf(def.get("key"));
            String label = labels.getOrDefault(catKey, String.valueOf(def.get("label")));
            List<Map<String, Object>> children = buildEndpointCategoryChildren(
                    catKey, stats, projectsByCode, assigned);
            if (!children.isEmpty()) {
                categories.add(endpointCategoryNode(catKey, label, children));
            }
        }
        return Map.of("categories", categories);
    }

    public static String inventoryProjectKey(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return "";
        }
        if (ApiProjectCatalog.APP.key().equals(ApiProjectCatalog.categoryKeyOf(projectCode))) {
            return ApiAppCatalog.treeProjectKey(projectCode);
        }
        return projectCode.trim();
    }

    private static Map<String, int[]> aggregateEndpointStats(List<ApiEndpoint> allEndpoints) {
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (ApiEndpoint e : allEndpoints) {
            String key = inventoryProjectKey(e.getProject());
            if (key.isBlank()) {
                continue;
            }
            int[] c = stats.computeIfAbsent(key, k -> new int[2]);
            c[0]++;
            if (e.getEnabledCaseCount() == null || e.getEnabledCaseCount() == 0) {
                c[1]++;
            }
        }
        return stats;
    }

    private static Map<String, Object> endpointProjectNode(
            String code, Map<String, int[]> stats, Map<String, ApiProject> projectsByCode) {
        int[] c = stats.getOrDefault(code, new int[]{0, 0});
        ApiProject p = projectsByCode.get(code);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("project", code);
        node.put("label", ApiProjectCatalog.projectDisplayLabel(code));
        node.put("type", "PROJECT");
        node.put("total", c[0]);
        node.put("pending", c[1]);
        node.put("configured", p != null);
        node.put("missing", p == null);
        return node;
    }

    private List<Map<String, Object>> buildEndpointCategoryChildren(
            String catKey,
            Map<String, int[]> stats,
            Map<String, ApiProject> projectsByCode,
            Set<String> assigned) {
        List<Map<String, Object>> projects = new ArrayList<>();

        if (ApiProjectCatalog.APP.key().equals(catKey)) {
            for (String slot : ApiProjectCatalog.appProjectSlots()) {
                if (!projectsByCode.containsKey(slot) && !stats.containsKey(slot)) {
                    continue;
                }
                if (!ApiProjectCatalog.APP.key().equals(resolveEndpointProjectCategory(slot, projectsByCode))) {
                    continue;
                }
                if (ApiProjectKind.skipScan(slot)) {
                    continue;
                }
                projects.add(endpointProjectNode(slot, stats, projectsByCode));
                assigned.add(slot);
                assigned.add(inventoryProjectKey(slot));
            }
            for (String code : stats.keySet()) {
                if (assigned.contains(code) || ApiProjectKind.skipScan(code)) {
                    continue;
                }
                if (!ApiProjectCatalog.APP.key().equals(resolveEndpointProjectCategory(code, projectsByCode))) {
                    continue;
                }
                projects.add(endpointProjectNode(code, stats, projectsByCode));
                assigned.add(code);
            }
            for (ApiProject p : projectsByCode.values()) {
                String code = p.getCode();
                if (assigned.contains(code) || assigned.contains(inventoryProjectKey(code))) {
                    continue;
                }
                if (ApiProjectCatalog.APP.key().equals(resolveEndpointProjectCategory(code, projectsByCode))) {
                    projects.add(endpointProjectNode(code, stats, projectsByCode));
                    assigned.add(code);
                }
            }
            return wrapKind(projects);
        }

        for (String slot : ApiProjectCatalog.slotsOfCategory(catKey)) {
            if (!stats.containsKey(slot) && !projectsByCode.containsKey(slot)) {
                continue;
            }
            if (ApiProjectKind.skipScan(slot)) {
                continue;
            }
            projects.add(endpointProjectNode(slot, stats, projectsByCode));
            assigned.add(slot);
        }
        for (String code : stats.keySet()) {
            if (assigned.contains(code) || ApiProjectKind.skipScan(code)) {
                continue;
            }
            if (!catKey.equals(resolveEndpointProjectCategory(code, projectsByCode))) {
                continue;
            }
            projects.add(endpointProjectNode(code, stats, projectsByCode));
            assigned.add(code);
        }
        for (ApiProject p : projectsByCode.values()) {
            String code = p.getCode();
            if (assigned.contains(code)) {
                continue;
            }
            if (catKey.equals(resolveEndpointProjectCategory(code, projectsByCode))) {
                projects.add(endpointProjectNode(code, stats, projectsByCode));
                assigned.add(code);
            }
        }
        return wrapKind(projects);
    }

    /** 接口清单只有会扫的后端，所以这里最多出现「后端」一层；没有项目就不占位 */
    private static List<Map<String, Object>> wrapKind(List<Map<String, Object>> projects) {
        if (projects.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(kindNode(ApiProjectKind.BACKEND, projects)));
    }

    private static Map<String, Object> kindNode(String kind, List<Map<String, Object>> children) {
        int total = 0;
        int pending = 0;
        for (Map<String, Object> child : children) {
            total += (int) child.get("total");
            pending += (int) child.get("pending");
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", kind);
        node.put("label", ApiProjectKind.label(kind));
        node.put("type", "KIND");
        node.put("total", total);
        node.put("pending", pending);
        node.put("children", children);
        return node;
    }

    private static String resolveEndpointProjectCategory(String code, Map<String, ApiProject> projectsByCode) {
        ApiProject p = projectsByCode.get(code);
        if (p != null) {
            return ApiProjectCatalog.resolveCategoryKey(code, p.getCategory());
        }
        return ApiProjectCatalog.categoryKeyOf(code);
    }

    private static Map<String, Object> endpointCategoryNode(
            String key, String label, List<Map<String, Object>> children) {
        int total = 0;
        int pending = 0;
        for (Map<String, Object> child : children) {
            total += (int) child.get("total");
            pending += (int) child.get("pending");
        }
        children.sort((a, b) -> {
            int pa = (int) a.get("pending");
            int pb = (int) b.get("pending");
            if (pa != pb) {
                return Integer.compare(pb, pa);
            }
            return Integer.compare((int) b.get("total"), (int) a.get("total"));
        });
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", key);
        node.put("label", label);
        node.put("total", total);
        node.put("pending", pending);
        node.put("children", children);
        return node;
    }

    private Map<String, Object> toTreeRow(ApiProject p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getCode());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("kind", ApiProjectKind.of(p));
        m.put("scanEndpoints", ApiProjectKind.scansEndpoints(p));
        m.put("enabled", p.getEnabled());
        m.put("sortOrder", p.getSortOrder());
        m.put("successCodePath", p.getSuccessCodePath());
        m.put("successCodeValue", p.getSuccessCodeValue());
        m.put("gitUrl", p.getGitUrl());
        m.put("repoPath", p.getRepoPath());
        m.put("baseBranch", p.getBaseBranch());
        m.put("category", ApiProjectCatalog.resolveCategoryKey(p.getCode(), p.getCategory()));
        m.put("categoryStored", p.getCategory());
        m.put("missing", false);
        return m;
    }

    public ApiProject get(String code) {
        return projectMapper.findByCode(code);
    }

    @Transactional
    public ApiProject create(ApiProject body) {
        validate(body, true);
        if (projectMapper.findByCode(body.getCode()) != null) {
            throw new IllegalArgumentException("项目编码已存在: " + body.getCode());
        }
        ApiProject twin = twinInSameGroup(projectMapper.findAll(null), body);
        if (twin != null) {
            String cat = ApiProjectCatalog.resolveCategoryKey(twin.getCode(), twin.getCategory());
            throw new IllegalArgumentException("「" + categoryLabel(cat) + "」组下已经有这个项目：" + twin.getCode()
                    + (ApiProjectCatalog.APP.key().equals(cat) ? "（app- 前缀不算区别）" : "")
                    + "，同组项目不能重名，直接用它");
        }
        defaults(body);
        ensureCategoryRegistered(body.getCategory());
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        projectMapper.insert(body);
        return projectMapper.findByCode(body.getCode());
    }

    /**
     * 同组里编码或名称撞上的已有项目。组内项目不重名，跨组（APP / PVP / WEB）可以同名；
     * APP 组里 data-center 和 app-data-center 是同一个项目，比较前先去掉这层差别。
     */
    static ApiProject twinInSameGroup(List<ApiProject> all, ApiProject body) {
        if (all == null || body == null) return null;
        String cat = ApiProjectCatalog.resolveCategoryKey(body.getCode(), body.getCategory());
        String code = groupKey(body.getCode(), cat);
        String name = groupKey(body.getName(), cat);
        for (ApiProject p : all) {
            if (!cat.equals(ApiProjectCatalog.resolveCategoryKey(p.getCode(), p.getCategory()))) continue;
            String pc = groupKey(p.getCode(), cat);
            String pn = groupKey(p.getName(), cat);
            if ((!code.isEmpty() && (code.equals(pc) || code.equals(pn)))
                    || (!name.isEmpty() && (name.equals(pc) || name.equals(pn)))) {
                return p;
            }
        }
        return null;
    }

    private static String groupKey(String s, String cat) {
        if (s == null || s.isBlank()) return "";
        String v = s.trim().toLowerCase(Locale.ROOT);
        return ApiProjectCatalog.APP.key().equals(cat) ? ApiAppCatalog.canonicalCode(v).toLowerCase(Locale.ROOT) : v;
    }

    @Transactional
    public ApiProject update(String code, ApiProject body) {
        ApiProject existing = projectMapper.findByCode(code);
        if (existing == null) return null;
        body.setCode(code);
        validate(body, false);
        defaults(body);
        ensureCategoryRegistered(body.getCategory());
        // 请求里没给的字段保留原值。update 是全列覆盖，前端那个编辑表单又只有编码/名称/说明/启用/排序——
        // 不兜这一下，改一次项目名就把 repoPath 清成 null，接口对比分析直接报「未配仓库」，
        // 而且现场看不出是刚才那次编辑干的
        keepIfNull(body::getKind, body::setKind, existing.getKind());
        keepIfNull(body::getGitUrl, body::setGitUrl, existing.getGitUrl());
        keepIfNull(body::getRepoPath, body::setRepoPath, existing.getRepoPath());
        keepIfNull(body::getBaseBranch, body::setBaseBranch, existing.getBaseBranch());
        keepIfNull(body::getSuccessCodePath, body::setSuccessCodePath, existing.getSuccessCodePath());
        keepIfNull(body::getSuccessCodeValue, body::setSuccessCodeValue, existing.getSuccessCodeValue());
        keepIfNull(body::getCategory, body::setCategory, existing.getCategory());
        body.setCreatedAt(existing.getCreatedAt());
        body.setUpdatedAt(LocalDateTime.now());
        projectMapper.update(body);
        return projectMapper.findByCode(code);
    }

    /**
     * null＝这次不改，保留原值；空串＝明确要清空。
     * <p>
     * 两者必须分开，否则「清掉仓库路径」这个操作就做不到了——传空串会被当成没传。
     */
    private static void keepIfNull(java.util.function.Supplier<String> get,
                                   java.util.function.Consumer<String> set,
                                   String existing) {
        if (get.get() == null) {
            set.accept(existing);
        }
    }

    @Transactional
    public boolean delete(String code) {
        return projectMapper.deleteByCode(code) > 0;
    }

    /** 确保编码存在（种子/导入时可调用） */
    public void ensureExists(String code, String nameIfCreate) {
        ensureExists(code, nameIfCreate, null, 100);
    }

    public void ensureExists(String code, String nameIfCreate, String description, int sortOrder) {
        if (code == null || code.isBlank()) return;
        if (projectMapper.findByCode(code) != null) return;
        ApiProject p = new ApiProject();
        p.setCode(code.trim());
        p.setName(nameIfCreate != null && !nameIfCreate.isBlank() ? nameIfCreate : code);
        p.setDescription(description);
        p.setKind(ApiProject.KIND_BACKEND);
        p.setEnabled(true);
        p.setSortOrder(sortOrder);
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        try {
            projectMapper.insert(p);
            log.info("[api-projects] 已补项目 {} ({})", p.getCode(), p.getName());
        } catch (Exception e) {
            log.debug("[api-projects] ensureExists 跳过: {}", e.getMessage());
        }
    }

    private void validate(ApiProject body, boolean creating) {
        if (creating) {
            if (body.getCode() == null || body.getCode().isBlank()) {
                throw new IllegalArgumentException("项目编码 code 必填");
            }
            String code = body.getCode().trim();
            if (!CODE_OK.matcher(code).matches()) {
                throw new IllegalArgumentException("编码仅支持字母数字及 ._- ，且不超过 63 字符");
            }
            body.setCode(code);
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("项目名称必填");
        }
        if (body.getKind() != null && !body.getKind().isBlank()) {
            body.setKind(ApiProjectKind.normalize(body.getKind()));
        }
        if (body.getCategory() != null && !body.getCategory().isBlank()) {
            String k = body.getCategory().trim();
            if (k.length() > 64) {
                throw new IllegalArgumentException("分类名称不超过 64 字符");
            }
            if (ApiProjectCatalog.isBuiltinCategory(k)) {
                body.setCategory(k.toUpperCase(Locale.ROOT));
            } else {
                body.setCategory(k);
            }
        }
        if (body.getGitUrl() != null && !body.getGitUrl().isBlank()) {
            String url = body.getGitUrl().trim();
            if (!ProjectRepoSync.looksLikeGitUrl(url)) {
                throw new IllegalArgumentException("GitLab 地址要填 clone 地址，如 http://gitlab.shdev.net/SteamCN/data-center.git");
            }
            body.setGitUrl(url);
        }
        if (body.getRepoPath() != null && !body.getRepoPath().isBlank()) {
            java.nio.file.Path p = java.nio.file.Path.of(body.getRepoPath().trim()).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isDirectory(p)) {
                throw new IllegalArgumentException("本地仓库目录不存在（要填后端所在机器上的路径）: " + p);
            }
            if (!java.nio.file.Files.exists(p.resolve(".git"))) {
                throw new IllegalArgumentException("不是 git 仓库（缺少 .git）: " + p);
            }
            body.setRepoPath(p.toString());
        }
    }

    private void defaults(ApiProject body) {
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getSortOrder() == null) body.setSortOrder(100);
        if (body.getKind() == null || body.getKind().isBlank()) {
            String code = body.getCode() == null ? "" : body.getCode().trim().toLowerCase(Locale.ROOT);
            if (CLIENT_PROJECT_CODES.contains(code)) {
                body.setKind(ApiProject.KIND_CLIENT);
            } else if (ApiProjectKind.FRONTEND_CODES.contains(code)
                    || ApiProjectKind.FRONTEND_CODES.contains(ApiAppCatalog.canonicalCode(code).toLowerCase(Locale.ROOT))) {
                body.setKind(ApiProject.KIND_FRONTEND);
            } else {
                body.setKind(ApiProject.KIND_BACKEND);
            }
        } else {
            body.setKind(ApiProjectKind.normalize(body.getKind()));
        }
        if (body.getCode() != null) {
            body.setCode(body.getCode().trim());
        }
    }
}
