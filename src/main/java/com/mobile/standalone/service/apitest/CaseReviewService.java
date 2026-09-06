package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 变更影响的用例复查清单。
 * <p>
 * 这是「同步的难点是哪些用例过期了」的落点。接口改了字段、表改了结构，用例可能悄悄失效——
 * 断言在取一个已经删掉的字段，却还显示绿；或者查库断言对着一张改了结构的表。40 项目每周迭代，
 * 靠人逐条看必然漏。
 * <p>
 * 两条反查线合在这里：
 * <ul>
 *   <li><b>字段线</b>：变更/删除的响应字段 → 用例的断言(assertions) / 提取(extractJson)
 *       里引用了这个字段名的，需复查</li>
 *   <li><b>表线</b>：变更的表 → 读写它的接口({@link TableImpactService}) → 接口下的用例，需复查</li>
 * </ul>
 * <p>
 * 刻意<b>不</b>在用例表上加"需复查"状态列。那会变成脏数据——分析完置上、谁改了忘置回。
 * 这里是纯查询：给一份变更（字段+表），当场算出受影响的用例，不落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseReviewService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final TableImpactService tableImpactService;

    /** 为什么这条用例要复查 */
    public record Reason(String kind, String detail) {
        // kind: FIELD 引用了变更字段 / TABLE 读写了变更表
    }

    public record CaseToReview(String id, String name, String project, String endpointId,
                               String method, String path, List<Reason> reasons) {
    }

    public record Result(int count, List<CaseToReview> cases, String hint) {
    }

    /**
     * 算出受一次变更影响、需要复查的用例。
     *
     * @param project        项目
     * @param changedFields  变更/删除的响应字段名（来自对比分析的 FieldChange）
     * @param changedTables  变更的表名（来自对比分析的 DDL 变更）
     */
    public Result forChanges(String project, List<String> changedFields, List<String> changedTables) {
        // caseId → 复查原因（一条用例可能既引用了变更字段、又读写了变更表）
        Map<String, List<Reason>> reasons = new LinkedHashMap<>();
        Map<String, Map<String, Object>> caseRows = new LinkedHashMap<>();

        // ---- 表线：变更表 → 接口 → 用例 ----
        Set<String> impactedEndpointIds = new LinkedHashSet<>();
        Map<String, String> endpointToTable = new LinkedHashMap<>();
        for (String table : changedTables == null ? List.<String>of() : changedTables) {
            if (table == null || table.isBlank()) continue;
            TableImpactService.Impact imp = tableImpactService.forTable(table, project);
            for (var e : imp.endpoints()) {
                impactedEndpointIds.add(e.id());
                endpointToTable.putIfAbsent(e.id(), table.trim());
            }
        }
        if (!impactedEndpointIds.isEmpty()) {
            for (Map<String, Object> row : casesOfEndpoints(impactedEndpointIds)) {
                String id = str(row.get("id"));
                caseRows.putIfAbsent(id, row);
                reasons.computeIfAbsent(id, k -> new ArrayList<>()).add(new Reason("TABLE",
                        "读写了变更表 " + endpointToTable.getOrDefault(str(row.get("endpoint_id")), "")));
            }
        }

        // ---- 字段线：变更字段名 → 断言/提取里引用它的用例 ----
        List<String> cleanFields = (changedFields == null ? List.<String>of() : changedFields).stream()
                .filter(f -> f != null && !f.isBlank()).map(String::trim).distinct().toList();
        if (!cleanFields.isEmpty()) {
            for (Map<String, Object> row : casesWithAssertions(project)) {
                String assertions = str(row.get("assertions"));
                String extract = str(row.get("extract_json"));
                List<String> hit = fieldsReferencedIn(assertions, extract, cleanFields);
                if (hit.isEmpty()) continue;
                String id = str(row.get("id"));
                caseRows.putIfAbsent(id, row);
                reasons.computeIfAbsent(id, k -> new ArrayList<>()).add(new Reason("FIELD",
                        "断言/提取引用了变更字段 " + String.join("、", hit)));
            }
        }

        List<CaseToReview> out = new ArrayList<>();
        for (Map.Entry<String, List<Reason>> e : reasons.entrySet()) {
            Map<String, Object> row = caseRows.get(e.getKey());
            out.add(new CaseToReview(
                    e.getKey(), str(row.get("name")), str(row.get("project")),
                    str(row.get("endpoint_id")), str(row.get("http_method")), str(row.get("path")),
                    e.getValue()));
        }
        return new Result(out.size(), out, buildHint(out.size(), cleanFields.size(), impactedEndpointIds.size()));
    }

    static String buildHint(int cases, int fields, int endpoints) {
        if (cases == 0) {
            return "没有用例受这次变更影响——要么这些字段/表还没有用例覆盖，要么接口清单还没同步";
        }
        return cases + " 条用例可能因这次变更失效，需复查。它们的断言在取变更过的字段、"
                + "或读写了结构变了的表——现在可能还显示绿，其实已经不对";
    }

    /**
     * 在断言和提取的 JSON 里，找引用了哪些变更字段。
     * <p>
     * 断言的 path 是 {@code $.result.token} 这种，字段名是路径的某一段。所以按<b>路径分段</b>
     * 精确比，不是子串包含——子串会让字段 {@code id} 命中 {@code $.orderId}，全是误报。
     */
    List<String> fieldsReferencedIn(String assertionsJson, String extractJson, List<String> changedFields) {
        Set<String> pathSegments = new LinkedHashSet<>();
        collectPaths(assertionsJson, pathSegments);
        collectPaths(extractJson, pathSegments);
        if (pathSegments.isEmpty()) {
            return List.of();
        }
        List<String> hit = new ArrayList<>();
        for (String f : changedFields) {
            if (pathSegments.contains(f.toLowerCase(Locale.ROOT))) {
                hit.add(f);
            }
        }
        return hit;
    }

    /** 从断言/提取 JSON 里把所有 path 拆成字段段收集起来 */
    private void collectPaths(String json, Set<String> out) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode root = JSON.readTree(json);
            // 断言是数组 [{type,path,...}]；提取是对象 {name: "$.x.y"}
            if (root.isArray()) {
                for (JsonNode a : root) {
                    splitPath(a.path("path").asText(null), out);
                }
            } else if (root.isObject()) {
                root.fields().forEachRemaining(e -> splitPath(e.getValue().asText(null), out));
            }
        } catch (Exception e) {
            log.debug("[case-review] 解析用例 JSON 跳过: {}", e.getMessage());
        }
    }

    private void splitPath(String path, Set<String> out) {
        if (path == null || path.isBlank()) return;
        // $.result.tasks[0].name → result / tasks / name
        for (String seg : path.replace("$", "").split("[.\\[\\]]")) {
            String s = seg.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty() && !s.chars().allMatch(Character::isDigit)) {
                out.add(s);
            }
        }
    }

    private List<Map<String, Object>> casesOfEndpoints(Set<String> endpointIds) {
        String in = String.join(",", endpointIds.stream().map(x -> "?").toList());
        return jdbcTemplate.queryForList(
                "SELECT id, name, project, endpoint_id, method AS http_method, path "
                        + "FROM api_test_cases WHERE endpoint_id IN (" + in + ")",
                endpointIds.toArray());
    }

    private List<Map<String, Object>> casesWithAssertions(String project) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, name, project, endpoint_id, method AS http_method, path, "
                        + "assertions, extract_json FROM api_test_cases WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (project != null && !project.isBlank()) {
            sql.append(" AND project = ?");
            args.add(project.trim());
        }
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
