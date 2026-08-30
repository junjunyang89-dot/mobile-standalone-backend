package com.mobile.standalone.service;

import jakarta.annotation.PostConstruct;
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
 * 单个需求单（APP-xxxx）的「提测覆盖」——客户端 / H5 / 后端接口是否都有 P0 级可追溯覆盖。
 * <p>
 * 快版本只有 2 天测试窗口，不可能每个接口手写完整用例；这套规则的目标是：
 * <b>该涉及的端都在看板上可见，没测完不能假装测完，豁免要签字。</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementCoverageService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS requirement_coverage_waivers (
                        id VARCHAR(64) PRIMARY KEY,
                        requirement_no VARCHAR(64) NOT NULL,
                        dimension VARCHAR(32) NOT NULL COMMENT 'CLIENT/H5/SERVER/ADMIN',
                        reason VARCHAR(512) NOT NULL,
                        created_by VARCHAR(64) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_req_dim (requirement_no, dimension)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        } catch (Exception e) {
            log.warn("[req-coverage] 建表失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> coverage(String requirementNo, String category) {
        String req = nz(requirementNo);
        if (req.isEmpty()) {
            return Map.of("ready", false, "message", "缺少需求单号");
        }
        List<Map<String, Object>> cases = loadCases(req, category);
        Set<String> involved = involvedSides(cases);
        int endpointTotal = countEndpoints(req);
        if (endpointTotal > 0) {
            involved.add(RequirementSide.SERVER);
        }

        Map<String, String> waivers = loadWaivers(req);
        List<Map<String, Object>> dimensions = new ArrayList<>();

        if (involved.contains(RequirementSide.H5)) {
            dimensions.add(functionalDimension("H5", "H5", cases, RequirementSide.H5, waivers));
        }
        if (involvesClient(involved)) {
            dimensions.add(clientDimension(cases, waivers));
        }
        if (involved.contains(RequirementSide.SERVER)) {
            dimensions.add(serverDimension(req, waivers));
        }
        if (involved.contains(RequirementSide.ADMIN)) {
            dimensions.add(functionalDimension("ADMIN", "管理后台", cases, RequirementSide.ADMIN, waivers));
        }

        if (dimensions.isEmpty()) {
            dimensions.add(Map.of(
                    "key", "UNSET",
                    "label", "涉及端",
                    "involved", true,
                    "status", "RED",
                    "statusLabel", "未标注",
                    "hint", "请在本单用例上批量设置「涉及端」（客户端 / H5 / 后端），否则无法判断有没有漏测",
                    "p0Total", countP0(cases),
                    "p0Reviewed", countP0Reviewed(cases),
                    "caseTotal", cases.size()
            ));
        }

        boolean ready = dimensions.stream().allMatch(RequirementCoverageService::dimensionReady);
        String summary = buildSummary(dimensions, ready);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requirementNo", req);
        out.put("ready", ready);
        out.put("summary", summary);
        out.put("dimensions", dimensions);
        return out;
    }

    public boolean waive(String requirementNo, String dimension, String reason, String createdBy) {
        String req = nz(requirementNo);
        String dim = nz(dimension).toUpperCase(Locale.ROOT);
        String why = nz(reason);
        if (req.isEmpty() || dim.isEmpty() || why.isEmpty()) {
            return false;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO requirement_coverage_waivers (id, requirement_no, dimension, reason, created_by)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE reason = VALUES(reason), created_by = VALUES(created_by),
                        created_at = CURRENT_TIMESTAMP
                    """,
                    "w-" + java.util.UUID.randomUUID(), req, dim, why,
                    createdBy == null || createdBy.isBlank() ? null : createdBy.trim());
            return true;
        } catch (Exception e) {
            log.warn("[req-coverage] 豁免失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean clearWaiver(String requirementNo, String dimension) {
        String req = nz(requirementNo);
        String dim = nz(dimension).toUpperCase(Locale.ROOT);
        if (req.isEmpty() || dim.isEmpty()) {
            return false;
        }
        return jdbcTemplate.update(
                "DELETE FROM requirement_coverage_waivers WHERE requirement_no = ? AND dimension = ?",
                req, dim) > 0;
    }

    private List<Map<String, Object>> loadCases(String req, String category) {
        try {
            if (category != null && !category.isBlank()) {
                return jdbcTemplate.queryForList("""
                        SELECT priority, status, sides, server_service AS serverService
                        FROM ai_test_cases
                        WHERE requirement_no = ? AND category = ?
                        """, req, category.trim());
            }
            return jdbcTemplate.queryForList("""
                    SELECT priority, status, sides, server_service AS serverService
                    FROM ai_test_cases WHERE requirement_no = ?
                    """, req);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Set<String> involvedSides(List<Map<String, Object>> cases) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> c : cases) {
            for (String s : RequirementSide.split(str(c.get("sides")))) {
                out.add(s);
            }
        }
        return out;
    }

    private boolean involvesClient(Set<String> involved) {
        return involved.contains(RequirementSide.CLIENT_ANDROID)
                || involved.contains(RequirementSide.CLIENT_IOS)
                || involved.contains(RequirementSide.CLIENT_HARMONY);
    }

    private Map<String, Object> clientDimension(List<Map<String, Object>> cases, Map<String, String> waivers) {
        List<Map<String, Object>> scoped = cases.stream()
                .filter(c -> caseHasAnySide(c, RequirementSide.CLIENT_ANDROID,
                        RequirementSide.CLIENT_IOS, RequirementSide.CLIENT_HARMONY))
                .toList();
        return functionalDimension("CLIENT", "客户端", scoped, null, waivers);
    }

    private Map<String, Object> functionalDimension(String key, String label,
                                                    List<Map<String, Object>> scoped,
                                                    String exactSide,
                                                    Map<String, String> waivers) {
        List<Map<String, Object>> rows = exactSide == null
                ? scoped
                : scoped.stream().filter(c -> caseHasSide(c, exactSide)).toList();
        int p0 = countP0(rows);
        int p0Reviewed = countP0Reviewed(rows);
        int total = rows.size();

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("key", key);
        d.put("label", label);
        d.put("involved", true);
        d.put("p0Total", p0);
        d.put("p0Reviewed", p0Reviewed);
        d.put("caseTotal", total);

        String waived = waivers.get(key);
        if (waived != null) {
            d.put("status", "WAIVED");
            d.put("statusLabel", "已豁免");
            d.put("hint", waived);
            d.put("waiveReason", waived);
            d.put("badge", label + " 豁免");
            return d;
        }

        if (p0 == 0) {
            d.put("status", "RED");
            d.put("statusLabel", "缺 P0");
            d.put("hint", "至少 1 条 P0");
            d.put("badge", label + " 0 P0");
        } else if (p0Reviewed < p0) {
            d.put("status", "YELLOW");
            d.put("statusLabel", "待复核");
            d.put("hint", "P0 复核完即可");
            d.put("badge", label + " P0 " + p0Reviewed + "/" + p0);
        } else {
            d.put("status", "GREEN");
            d.put("statusLabel", "就绪");
            d.put("hint", "P0 已复核");
            d.put("badge", label + " P0 " + p0 + "/" + p0);
        }
        return d;
    }

    private Map<String, Object> serverDimension(String req, Map<String, String> waivers) {
        List<Map<String, Object>> eps = listEndpointsWithCases(req);
        int total = eps.size();
        int ready = 0;
        int weak = 0;
        int pending = 0;
        for (Map<String, Object> ep : eps) {
            int enabled = intVal(ep.get("enabledCases"));
            int strong = intVal(ep.get("strongCases"));
            if (enabled == 0) {
                pending++;
            } else if (strong == 0) {
                weak++;
            } else {
                ready++;
            }
        }

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("key", "SERVER");
        d.put("label", "后端接口");
        d.put("involved", true);
        d.put("endpointTotal", total);
        d.put("endpointReady", ready);
        d.put("endpointWeak", weak);
        d.put("endpointPending", pending);

        String waived = waivers.get("SERVER");
        if (waived != null) {
            d.put("status", "WAIVED");
            d.put("statusLabel", "已豁免");
            d.put("hint", waived);
            d.put("waiveReason", waived);
            d.put("badge", "接口 豁免");
            return d;
        }

        if (total == 0) {
            d.put("status", "YELLOW");
            d.put("statusLabel", "未识别");
            d.put("hint", "抓包或「涉及接口 → 补上」");
            d.put("badge", "接口 —");
        } else if (ready < total) {
            if (pending > 0) {
                d.put("status", "RED");
                d.put("statusLabel", "待生成用例");
                d.put("hint", total + " 个路径已识别，还差启用用例（清单里「生成用例」）");
            } else {
                d.put("status", "YELLOW");
                d.put("statusLabel", "待加强断言");
                d.put("hint", weak + " 个仅验 200，补 jsonPath 或业务码");
            }
            d.put("badge", "接口 " + ready + "/" + total);
        } else {
            d.put("status", "GREEN");
            d.put("statusLabel", "就绪");
            d.put("hint", "可跑用例已齐");
            d.put("badge", "接口 " + ready + "/" + total);
        }
        return d;
    }

    private List<Map<String, Object>> listEndpointsWithCases(String req) {
        try {
            return jdbcTemplate.queryForList("""
                    SELECT re.id, re.http_method AS httpMethod, re.path, re.summary,
                           (SELECT COUNT(1) FROM api_test_cases c
                             WHERE c.enabled = 1 AND (
                               (re.endpoint_id IS NOT NULL AND re.endpoint_id != '' AND c.endpoint_id = re.endpoint_id)
                               OR (UPPER(c.method) = UPPER(re.http_method)
                                   AND c.path = re.path)
                             )) AS enabledCases,
                           (SELECT COUNT(1) FROM api_test_cases c
                             WHERE c.enabled = 1 AND c.assertions LIKE '%jsonPath%'
                               AND (
                               (re.endpoint_id IS NOT NULL AND re.endpoint_id != '' AND c.endpoint_id = re.endpoint_id)
                               OR (UPPER(c.method) = UPPER(re.http_method)
                                   AND c.path = re.path)
                             )) AS strongCases
                    FROM requirement_endpoints re
                    WHERE re.requirement_no = ?
                    ORDER BY re.source, re.path
                    """, req);
        } catch (Exception e) {
            log.debug("[req-coverage] 接口查询跳过: {}", e.getMessage());
            return List.of();
        }
    }

    private int countEndpoints(String req) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM requirement_endpoints WHERE requirement_no = ?", Integer.class, req);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<String, String> loadWaivers(String req) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT dimension, reason FROM requirement_coverage_waivers WHERE requirement_no = ?", req);
            for (Map<String, Object> r : rows) {
                out.put(str(r.get("dimension")), str(r.get("reason")));
            }
        } catch (Exception ignored) {
            /* 表不存在时当没有豁免 */
        }
        return out;
    }

    private static int countP0(List<Map<String, Object>> cases) {
        return (int) cases.stream().filter(c -> "P0".equalsIgnoreCase(str(c.get("priority")))).count();
    }

    private static int countP0Reviewed(List<Map<String, Object>> cases) {
        return (int) cases.stream()
                .filter(c -> "P0".equalsIgnoreCase(str(c.get("priority")))
                        && "REVIEWED".equalsIgnoreCase(str(c.get("status"))))
                .count();
    }

    private static boolean caseHasSide(Map<String, Object> c, String side) {
        return RequirementSide.split(str(c.get("sides"))).contains(side);
    }

    private static boolean caseHasAnySide(Map<String, Object> c, String... sides) {
        List<String> have = RequirementSide.split(str(c.get("sides")));
        for (String s : sides) {
            if (have.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 可否出提测结论。
     * <p>
     * 功能端（H5/客户端）「有 P0 但未全复核」只算软提醒，不挡提测——
     * 下面表格里本来就有复核状态，再在待办里卡一道是重复的。
     * 硬阻塞：功能端一条 P0 都没有；接口路径有了但用例未启用。
     */
    private static boolean dimensionReady(Map<String, Object> d) {
        String status = str(d.get("status"));
        String key = str(d.get("key"));
        if ("WAIVED".equals(status) || "GREEN".equals(status)) {
            return true;
        }
        if ("SERVER".equals(key)) {
            return false;
        }
        // 功能端：YELLOW＝有 P0 待复核，不挡
        return "YELLOW".equals(status);
    }

    private static String buildSummary(List<Map<String, Object>> dimensions, boolean ready) {
        if (ready) {
            return "无硬阻塞；标签黄的是软提醒（如 P0 待复核）";
        }
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> d : dimensions) {
            if (dimensionReady(d)) {
                continue;
            }
            parts.add(str(d.get("label")) + "：" + str(d.get("statusLabel")));
        }
        return parts.isEmpty() ? "尚有未就绪项" : String.join("；", parts);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
