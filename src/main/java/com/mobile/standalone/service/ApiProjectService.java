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
            ensureColumn("base_branch", "VARCHAR(128) NULL COMMENT '对比基线分支，默认 master'");
            ensureColumn("success_code_path", "VARCHAR(128) NULL COMMENT '业务成功码路径，如 $.code'");
            ensureColumn("success_code_value", "VARCHAR(64) NULL COMMENT '业务成功码的值，如 0'");
            ensureColumn("kind", "VARCHAR(16) NULL DEFAULT 'BACKEND' COMMENT 'BACKEND=后端仓库 CLIENT=客户端'");
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

    /** 项目管理页：按 APP / PVP / WEB / 其他 分组，APP 内补 catalog 占位项 */
    public Map<String, Object> buildTree(Boolean enabledOnly) {
        List<Map<String, Object>> rows = list(enabledOnly).stream().map(this::toTreeRow).toList();
        Map<String, List<Map<String, Object>>> buckets = ApiProjectCatalog.bucketProjects(new ArrayList<>(rows));

        List<Map<String, Object>> categories = new ArrayList<>();
        for (ApiProjectCatalog.Category cat : ApiProjectCatalog.categoriesInOrder()) {
            List<Map<String, Object>> projects = new ArrayList<>(buckets.get(cat.key()));
            if (ApiProjectCatalog.APP.key().equals(cat.key())) {
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
                        placeholder.put("category", cat.key());
                        ordered.add(placeholder);
                    }
                }
                ordered.addAll(byCode.values());
                projects = ordered;
            }
            long configured = projects.stream()
                    .filter(p -> !Boolean.TRUE.equals(p.get("missing")))
                    .count();
            Map<String, Object> catVo = new LinkedHashMap<>();
            catVo.put("key", cat.key());
            catVo.put("label", cat.label());
            catVo.put("projects", projects);
            catVo.put("count", configured);
            categories.add(catVo);
        }
        return Map.of("categories", categories);
    }

    /**
     * 接口清单左侧树：与「项目管理」同一套 APP / PVP / WEB catalog，
     * 再叠加接口数统计。不再只展示「库里碰巧有接口」的项目。
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

        for (ApiProjectCatalog.Category cat : ApiProjectCatalog.categoriesInOrder()) {
            List<Map<String, Object>> children = new ArrayList<>();

            if (ApiProjectCatalog.APP.key().equals(cat.key())) {
                for (String slot : ApiProjectCatalog.appProjectSlots()) {
                    children.add(endpointProjectNode(slot, stats, projectsByCode));
                    assigned.add(slot);
                    assigned.add(inventoryProjectKey(slot));
                }
                for (String code : stats.keySet()) {
                    if (assigned.contains(code)) {
                        continue;
                    }
                    if (!ApiProjectCatalog.APP.key().equals(ApiProjectCatalog.categoryKeyOf(code))) {
                        continue;
                    }
                    children.add(endpointProjectNode(code, stats, projectsByCode));
                    assigned.add(code);
                }
                categories.add(endpointCategoryNode(cat, children));
                continue;
            }

            for (String slot : ApiProjectCatalog.slotsOfCategory(cat.key())) {
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
                if (!cat.key().equals(ApiProjectCatalog.categoryKeyOf(code))) {
                    continue;
                }
                children.add(endpointProjectNode(code, stats, projectsByCode));
                assigned.add(code);
            }
            if (!children.isEmpty()) {
                categories.add(endpointCategoryNode(cat, children));
            }
        }
        return Map.of("categories", categories);
    }

    private static boolean isBackendKind(ApiProject p) {
        String kind = p.getKind();
        return kind == null || kind.isBlank() || ApiProject.KIND_BACKEND.equals(kind);
    }

    private static String inventoryProjectKey(String projectCode) {
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

    private static Map<String, Object> endpointCategoryNode(
            ApiProjectCatalog.Category cat, List<Map<String, Object>> children) {
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
        node.put("key", cat.key());
        node.put("label", cat.label());
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
        m.put("repoPath", p.getRepoPath());
        m.put("baseBranch", p.getBaseBranch());
        m.put("category", ApiProjectCatalog.categoryKeyOf(p.getCode()));
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
        // 请求里没给的字段保留原值。update 是全列覆盖，前端那个编辑表单又只有编码/名称/说明/启用/排序——
        // 不兜这一下，改一次项目名就把 repoPath 清成 null，接口对比分析直接报「未配仓库」，
        // 而且现场看不出是刚才那次编辑干的
        keepIfNull(body::getKind, body::setKind, existing.getKind());
        keepIfNull(body::getRepoPath, body::setRepoPath, existing.getRepoPath());
        keepIfNull(body::getBaseBranch, body::setBaseBranch, existing.getBaseBranch());
        keepIfNull(body::getSuccessCodePath, body::setSuccessCodePath, existing.getSuccessCodePath());
        keepIfNull(body::getSuccessCodeValue, body::setSuccessCodeValue, existing.getSuccessCodeValue());
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
