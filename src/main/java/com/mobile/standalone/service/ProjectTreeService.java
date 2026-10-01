package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.apitest.ApiProjectCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一树。
 * <p>
 * 平台原先有三棵各自为政的树（{@code /api-projects/tree} 分类→项目、
 * {@code /api-test/collections/tree} scope→项目→分支、{@code /api-endpoints/tree} 接口清单），
 * 节点结构互不相同，前端要写三套渲染。这里收敛成一个协议：
 * <pre>
 * 分类(CATEGORY) → 项目(PROJECT) → 模块(MODULE)
 * </pre>
 * 每个节点都是 {@code {key,label,type,counts,children}}，前端一套渲染走到底。
 * <p>
 * <b>counts 是这棵树的重点</b>。只画层级的话，它只是个好看点的目录；带上每个项目
 * 有多少用例／接口／UI 流程／Bug，导航本身就在回答「哪个项目没人测」——
 * 那正是「用例跟不上迭代」时最需要看见的东西。
 * <p>
 * 实现上刻意<b>先聚合再组装</b>：几条 GROUP BY 把全量计数拉进内存，再拼树。
 * 逐个项目去查是 N+1，对着一台公网远程库会慢得没法用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTreeService {

    /** 模块节点。key 是稳定标识，前端自己映射到路由——后端不该规定前端的 URL */
    private record Module(String key, String label, String countField) {
    }

    private static final List<Module> MODULES = List.of(
            new Module("API_CASES", "接口用例", "apiCases"),
            new Module("API_ENDPOINTS", "接口清单", "endpoints"),
            new Module("API_COLLECTIONS", "用例集合", "collections"),
            new Module("UI_FLOWS", "UI 流程", "uiFlows"),
            new Module("UI_ELEMENTS", "UI 元素", "uiElements"),
            new Module("BUGS", "Bug", "bugs"));

    private final ApiProjectMapper projectMapper;
    private final ApiProjectService projectService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param includeEmpty 是否保留没有任何资产的项目。默认保留——「这个项目一条用例都没有」
     *                     本身就是要看见的信息，藏起来等于把欠账藏起来
     */
    public Map<String, Object> build(boolean includeEmpty) {
        Map<String, Map<String, Long>> counts = collectCounts();

        // 分类 → 该分类下的项目节点
        Map<String, List<Map<String, Object>>> byCategory = new LinkedHashMap<>();
        for (ApiProject p : projectMapper.findAll(null)) {
            if (Boolean.FALSE.equals(p.getEnabled())) {
                continue;
            }
            Map<String, Long> c = counts.getOrDefault(p.getCode(), Map.of());
            long total = c.values().stream().mapToLong(Long::longValue).sum();
            if (!includeEmpty && total == 0) {
                continue;
            }
            byCategory.computeIfAbsent(categoryOf(p), k -> new ArrayList<>()).add(projectNode(p, c, total));
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map<String, Object> def : projectService.listCategoryRegistry()) {
            String key = String.valueOf(def.get("key"));
            List<Map<String, Object>> children = byCategory.remove(key);
            categories.add(categoryNode(key, String.valueOf(def.get("label")),
                    children == null ? new ArrayList<>() : children));
        }
        // 注册表里没有、但项目上确实挂着的分类值：仍旧列出来而不是丢掉。
        // 静默吞掉的话，那些项目会整个从树上消失，比多一个陌生分类糟得多。
        for (Map.Entry<String, List<Map<String, Object>>> e : byCategory.entrySet()) {
            log.warn("[tree] 分类「{}」未在注册表中，仍按原样展示（{} 个项目）", e.getKey(), e.getValue().size());
            categories.add(categoryNode(e.getKey(), e.getKey(), e.getValue()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", "project");
        out.put("nodes", categories);
        return out;
    }

    private static String categoryOf(ApiProject p) {
        return ApiProjectCatalog.resolveCategoryKey(p.getCode(), p.getCategory());
    }

    private Map<String, Object> categoryNode(String key, String label, List<Map<String, Object>> children) {
        Map<String, Long> sum = new LinkedHashMap<>();
        for (Map<String, Object> child : children) {
            @SuppressWarnings("unchecked")
            Map<String, Long> c = (Map<String, Long>) child.get("counts");
            c.forEach((k, v) -> sum.merge(k, v, Long::sum));
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", key);
        node.put("label", label);
        node.put("type", "CATEGORY");
        node.put("counts", sum);
        node.put("projectCount", children.size());
        node.put("children", children);
        return node;
    }

    private Map<String, Object> projectNode(ApiProject p, Map<String, Long> counts, long total) {
        Map<String, Long> full = new LinkedHashMap<>();
        for (Module m : MODULES) {
            full.put(m.countField(), counts.getOrDefault(m.countField(), 0L));
        }
        List<Map<String, Object>> modules = new ArrayList<>();
        for (Module m : MODULES) {
            Map<String, Object> mod = new LinkedHashMap<>();
            mod.put("key", m.key());
            mod.put("label", m.label());
            mod.put("type", "MODULE");
            mod.put("count", full.get(m.countField()));
            mod.put("projectCode", p.getCode());
            modules.add(mod);
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", p.getCode());
        // 展示名读 name 列，不从 code 推导：APP-ESPORTS-iOS 的小写 i 推导不出来
        node.put("label", p.getName() == null || p.getName().isBlank() ? p.getCode() : p.getName());
        node.put("type", "PROJECT");
        node.put("kind", p.getKind() == null || p.getKind().isBlank() ? "BACKEND" : p.getKind());
        node.put("counts", full);
        node.put("total", total);
        node.put("children", modules);
        return node;
    }

    /**
     * 一次把所有计数拉齐：每张来源表一条 GROUP BY，结果按项目码归并。
     */
    private Map<String, Map<String, Long>> collectCounts() {
        Map<String, Map<String, Long>> out = new HashMap<>();
        accumulate(out, "apiCases",
                "SELECT project AS k, COUNT(*) AS n FROM api_test_cases WHERE project IS NOT NULL GROUP BY project");
        accumulate(out, "endpoints",
                "SELECT project AS k, COUNT(*) AS n FROM api_endpoints WHERE project IS NOT NULL GROUP BY project");
        accumulate(out, "collections",
                "SELECT project AS k, COUNT(*) AS n FROM api_test_collections WHERE project IS NOT NULL GROUP BY project");
        accumulate(out, "bugs",
                "SELECT project_code AS k, COUNT(*) AS n FROM requirement_bugs WHERE project_code IS NOT NULL GROUP BY project_code");
        // UI 资产沿 app_project_links 过桥。元素按 platform 精确归属，
        // 否则一个覆盖双端的 App 会把全部元素同时算进两个端的项目，看着像翻倍
        accumulate(out, "uiElements", """
                SELECT l.project_code AS k, COUNT(e.id) AS n
                  FROM app_project_links l
                  JOIN ui_elements e ON e.app_id = l.app_id
                   AND (l.platform IS NULL OR UPPER(IFNULL(e.platform, '')) = l.platform)
                 GROUP BY l.project_code
                """);
        // 流程表上没有端的维度，只能给到 App 级——同一 App 的两个端会看到相同的流程数。
        // 这是数据粒度所限，不是重复计算
        accumulate(out, "uiFlows", """
                SELECT l.project_code AS k, COUNT(f.id) AS n
                  FROM app_project_links l
                  JOIN ui_flows f ON f.app_id = l.app_id
                 GROUP BY l.project_code
                """);
        return out;
    }

    private void accumulate(Map<String, Map<String, Long>> out, String field, String sql) {
        try {
            for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
                Object k = row.get("k");
                Object n = row.get("n");
                if (k == null) {
                    continue;
                }
                out.computeIfAbsent(String.valueOf(k), x -> new LinkedHashMap<>())
                        .put(field, n == null ? 0L : ((Number) n).longValue());
            }
        } catch (Exception e) {
            // 某张表还没建好不该让整棵树查不出来：缺的那一项按 0 计，树照常展示
            log.warn("[tree] 统计 {} 失败，按 0 计: {}", field, e.getMessage());
        }
    }
}
