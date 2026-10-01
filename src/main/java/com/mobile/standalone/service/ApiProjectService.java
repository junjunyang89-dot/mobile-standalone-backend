package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.apitest.ApiAppCatalog;
import com.mobile.standalone.service.apitest.ApiProjectCatalog;
import com.mobile.standalone.entity.ApiEndpoint;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            ensureColumn("kind", "VARCHAR(16) NULL DEFAULT 'BACKEND' COMMENT 'BACKEND=后端仓库 CLIENT=客户端'");
            ensureColumn("category", "VARCHAR(64) NULL COMMENT '分类 key，留空按编码自动归类'");
            ensureCategoryTableAndSeed();
            retireLegacyCategories();
            syncCategoriesFromProjects();
            dedupeEmptySimilarCategories();
            widenProjectCategoryColumn();
            seedIfEmpty();
            markLegacyClientProjects();
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

    public List<ApiProject> list(Boolean enabledOnly) {
        return projectMapper.findAll(enabledOnly);
    }

    /** 接口清单下拉：只要后端仓库，且已配本地路径或已有接口记录 */
    public List<ApiProject> listForCatalog(Boolean enabledOnly) {
        return projectMapper.findForCatalog(enabledOnly);
    }

    /** 项目管理页：按注册分类分组，含空分类与 APP catalog 占位 */
    public Map<String, Object> buildTree(Boolean enabledOnly) {
        List<Map<String, Object>> rows = list(enabledOnly).stream().map(this::toTreeRow).toList();
        Map<String, List<Map<String, Object>>> buckets = ApiProjectCatalog.bucketProjects(new ArrayList<>(rows));

        List<Map<String, Object>> categories = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        for (Map<String, Object> def : listCategoryRegistry()) {
            String key = String.valueOf(def.get("key"));
            List<Map<String, Object>> projects = new ArrayList<>(buckets.getOrDefault(key, List.of()));
            if (ApiProjectCatalog.APP.key().equals(key)) {
                projects = orderAppCategoryProjects(projects, key);
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
                    new ArrayList<>(e.getValue()),
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
     * APP 还叫旧默认名时改成「电竞APP」；人手改过的名字不动。
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
                        + "WHERE cat_key = 'APP' AND label IN ('APP', 'APP 后端', 'APP后端')",
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

    private List<Map<String, Object>> orderAppCategoryProjects(
            List<Map<String, Object>> projects, String categoryKey) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : projects) {
            byCode.put(String.valueOf(row.get("code")), row);
        }
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (String slot : ApiProjectCatalog.appProjectSlots()) {
            Map<String, Object> hit = byCode.remove(slot);
            if (hit != null) {
                ordered.add(hit);
            } else {
                Map<String, Object> placeholder = new LinkedHashMap<>();
                placeholder.put("code", slot);
                placeholder.put("name", slot);
                placeholder.put("missing", true);
                placeholder.put("enabled", false);
                placeholder.put("category", categoryKey);
                ordered.add(placeholder);
            }
        }
        ordered.addAll(byCode.values());
        return ordered;
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
        catVo.put("count", configured);
        catVo.put("builtin", Boolean.TRUE.equals(builtinFlag) || "true".equals(String.valueOf(builtinFlag)));
        catVo.put("custom", !ApiProjectCatalog.isBuiltinCategory(key));
        if (sortOrder instanceof Number n) {
            catVo.put("sortOrder", n.intValue());
        }
        return catVo;
    }

    /**
     * 接口清单左侧树：环境在页面顶部分开选；这里只给「分类 → 项目」。
     * 项目下不再挂 master / 本分支 —— 那一层太碎，清单页只回答有哪些接口。
     */
    public Map<String, Object> buildEndpointInventoryTree(List<ApiEndpoint> allEndpoints) {
        Map<String, int[]> stats = aggregateEndpointStats(allEndpoints);
        Map<String, ApiProject> projectsByCode = new LinkedHashMap<>();
        for (ApiProject p : list(null)) {
            if (!isBackendKind(p)) {
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
            if (ApiProjectCatalog.APP.key().equals(catKey) || !children.isEmpty()) {
                categories.add(endpointCategoryNode(catKey, label, children));
            }
        }
        return Map.of("categories", categories);
    }

    private static boolean isBackendKind(ApiProject p) {
        String kind = p.getKind();
        return kind == null || kind.isBlank() || ApiProject.KIND_BACKEND.equals(kind);
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
        List<Map<String, Object>> children = new ArrayList<>();

        if (ApiProjectCatalog.APP.key().equals(catKey)) {
            for (String slot : ApiProjectCatalog.appProjectSlots()) {
                // 无条件渲染是刻意的：把 GitLab 上有、平台还没建的项目也摆出来，
                // 「未覆盖」才看得见。见 appProjectSlots() 的 javadoc 与
                // ApiEndpointInventoryTreeTest。
                children.add(endpointProjectNode(slot, stats, projectsByCode));
                assigned.add(slot);
                assigned.add(inventoryProjectKey(slot));
            }
            for (String code : stats.keySet()) {
                if (assigned.contains(code)) {
                    continue;
                }
                // 没手动分类的都算电竞APP；挂在 POGPOG 这类自定义分类下的留给对应分类
                if (!ApiProjectCatalog.APP.key().equals(resolveEndpointProjectCategory(code, projectsByCode))) {
                    continue;
                }
                children.add(endpointProjectNode(code, stats, projectsByCode));
                assigned.add(code);
            }
            return children;
        }

        for (String slot : ApiProjectCatalog.slotsOfCategory(catKey)) {
            if (!stats.containsKey(slot) && !projectsByCode.containsKey(slot)) {
                continue;
            }
            children.add(endpointProjectNode(slot, stats, projectsByCode));
            assigned.add(slot);
        }
        for (String code : stats.keySet()) {
            if (assigned.contains(code)) {
                continue;
            }
            if (!catKey.equals(resolveEndpointProjectCategory(code, projectsByCode))) {
                continue;
            }
            children.add(endpointProjectNode(code, stats, projectsByCode));
            assigned.add(code);
        }
        for (ApiProject p : projectsByCode.values()) {
            String code = p.getCode();
            if (assigned.contains(code)) {
                continue;
            }
            if (catKey.equals(resolveEndpointProjectCategory(code, projectsByCode))) {
                children.add(endpointProjectNode(code, stats, projectsByCode));
                assigned.add(code);
            }
        }
        return children;
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
        m.put("kind", p.getKind());
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
        defaults(body);
        ensureCategoryRegistered(body.getCategory());
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        projectMapper.insert(body);
        return projectMapper.findByCode(body.getCode());
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
            body.setKind(ApiProject.KIND_BACKEND);
        }
        if (body.getCode() != null) {
            body.setCode(body.getCode().trim());
        }
    }
}
