package com.mobile.standalone.service.apitest;

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
 * 表变更的影响面：某张表改了，反查它牵动哪些接口、进而哪些用例。
 * <p>
 * 这是「完整测试」缺的那条线。接口测试只验响应，但表结构一动——加了唯一索引、
 * 删了字段、改了类型——真正受影响的是<b>所有读写这张表的接口</b>，而它们分散在各处，
 * 靠人记不住。对比分析已经把「接口→表」算出来存进了 {@code api_endpoints.tables_json}，
 * 这里把它反过来查：<b>表 → 接口 → 用例</b>。
 * <p>
 * 和「字段变→反查用例」是一套机制的两条腿：一条顺着响应字段，一条顺着表。
 * 上线同步时，两条腿一起把「这些用例需要复查」标出来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableImpactService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    /** 一个受影响的接口，及它下面的用例数 */
    public record ImpactedEndpoint(String id, String httpMethod, String path, String handler,
                                   String status, int caseCount, List<String> tables) {
    }

    public record Impact(String table, List<ImpactedEndpoint> endpoints,
                         int endpointCount, int caseCount, String hint) {
    }

    /**
     * 某张表牵动哪些接口。
     *
     * @param table   表名
     * @param project 限定项目；为空则跨项目查
     */
    public Impact forTable(String table, String project) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        String want = table.trim().toLowerCase(Locale.ROOT);

        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.http_method, e.path, e.handler, e.status, e.tables_json,
                       (SELECT COUNT(*) FROM api_test_cases c WHERE c.endpoint_id = e.id) AS case_count
                  FROM api_endpoints e
                 WHERE e.tables_json IS NOT NULL
                """);
        List<Object> args = new ArrayList<>();
        if (project != null && !project.isBlank()) {
            sql.append(" AND e.project = ?");
            args.add(project.trim());
        }

        List<ImpactedEndpoint> hits = new ArrayList<>();
        int totalCases = 0;
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            List<String> tables = parseTables(str(row.get("tables_json")));
            // 精确匹配表名（忽略大小写），不用 LIKE：LIKE 会让 user 命中 user_log
            boolean touches = tables.stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).equals(want));
            if (!touches) {
                continue;
            }
            int cases = ((Number) row.get("case_count")).intValue();
            totalCases += cases;
            hits.add(new ImpactedEndpoint(
                    str(row.get("id")), str(row.get("http_method")), str(row.get("path")),
                    str(row.get("handler")), str(row.get("status")), cases, tables));
        }

        return new Impact(table.trim(), hits, hits.size(), totalCases,
                buildHint(table.trim(), hits.size(), totalCases));
    }

    /**
     * 一次查多张表的影响（对比分析出的 DDL 变更表一把传进来）。
     * 按接口去重——一个接口可能同时动了好几张变更表，只该算一次。
     */
    public Map<String, Object> forTables(List<String> tables, String project) {
        Map<String, Impact> byTable = new LinkedHashMap<>();
        Set<String> endpointIds = new LinkedHashSet<>();
        int caseTotal = 0;
        for (String t : tables == null ? List.<String>of() : tables) {
            if (t == null || t.isBlank()) continue;
            Impact imp = forTable(t, project);
            byTable.put(t.trim(), imp);
            for (ImpactedEndpoint e : imp.endpoints()) {
                endpointIds.add(e.id());
            }
        }
        // 去重后的用例数：按接口聚合，避免同一接口在多张表下被重复计数
        if (!endpointIds.isEmpty()) {
            String in = String.join(",", endpointIds.stream().map(x -> "?").toList());
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM api_test_cases WHERE endpoint_id IN (" + in + ")",
                    Integer.class, endpointIds.toArray());
            caseTotal = n == null ? 0 : n;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", byTable);
        out.put("distinctEndpoints", endpointIds.size());
        out.put("distinctCases", caseTotal);
        out.put("hint", endpointIds.isEmpty()
                ? "这些表没牵动任何已入库的接口——可能接口清单还没同步，或这些表没有接口直接读写"
                : "共 " + endpointIds.size() + " 个接口读写了这些变更表，涉及 " + caseTotal
                        + " 条用例，建议回归这些接口");
        return out;
    }

    static String buildHint(String table, int endpoints, int cases) {
        if (endpoints == 0) {
            return "没有已入库的接口读写 " + table + "。可能接口清单还没跑过对比分析同步，"
                    + "或这张表只被别的表 join、没有接口直接操作它";
        }
        return table + " 牵动 " + endpoints + " 个接口、" + cases + " 条用例。"
                + "表结构变了，这些接口都该回归——尤其查库那类断言可能要跟着改";
    }

    private List<String> parseTables(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, JSON.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
