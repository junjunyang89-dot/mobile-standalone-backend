package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiTestCase;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用例跑完之后，回写「这个接口验过没有」。
 * <p>
 * 没有这一步，需求单上的「接口 0/4」就永远是 0——因为它统计的是「有没有启用的用例」，
 * 而不是「跑过没有、跑通没有」。人跑完一轮，页面上一点变化都没有，
 * 下次就不会再来看这个数字了。
 * <p>
 * <b>只升不降是错的</b>：验过之后又跑失败，必须退回「待测」。
 * 一个只会变绿的指标，绿了之后就不再有信息量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndpointVerifyService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        ensureColumn("verified", "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '关联用例全跑通过'");
        ensureColumn("verified_at", "DATETIME DEFAULT NULL");
        ensureColumn("last_run_status", "VARCHAR(16) DEFAULT NULL COMMENT 'PASSED/FAILED'");
    }

    private void ensureColumn(String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'requirement_endpoints' "
                            + "AND COLUMN_NAME = ?",
                    Integer.class, column);
            if (n != null && n == 0) {
                jdbcTemplate.execute("ALTER TABLE requirement_endpoints ADD COLUMN "
                        + column + " " + definition);
            }
        } catch (Exception e) {
            log.debug("[verify] 加列 {} 跳过: {}", column, e.getMessage());
        }
    }

    /** 一条用例的执行结果，只取回写需要的三个字段 */
    public record CaseOutcome(ApiTestCase testCase, boolean passed) {
    }

    /**
     * 按这一轮的执行结果回写。
     * <p>
     * 匹配顺序和 testforge 那边一致：先认 {@code endpoint_id}（强关联，最准），
     * 再退到 {@code 方法 + 路径} 签名。只按签名匹配的话，
     * 同一个路径在不同项目下会互相串味。
     *
     * @return 实际改动的行数
     */
    public int applyRunOutcome(List<CaseOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return 0;
        }
        // 同一个接口这一轮可能跑了好几条用例（正常 + 异常）。
        // 「全部通过」才算验过——有一条挂了就说明这个接口这轮没验干净
        Map<String, Boolean> byEndpointId = new LinkedHashMap<>();
        Map<String, Boolean> bySignature = new LinkedHashMap<>();
        for (CaseOutcome o : outcomes) {
            ApiTestCase c = o.testCase();
            if (c == null) {
                continue;
            }
            if (c.getEndpointId() != null && !c.getEndpointId().isBlank()) {
                byEndpointId.merge(c.getEndpointId(), o.passed(), Boolean::logicalAnd);
            }
            String sig = signature(c.getMethod(), c.getPath());
            if (sig != null) {
                bySignature.merge(sig, o.passed(), Boolean::logicalAnd);
            }
        }

        int changed = 0;
        for (var e : byEndpointId.entrySet()) {
            changed += write("endpoint_id = ?", e.getKey(), e.getValue());
        }
        for (var e : bySignature.entrySet()) {
            String[] parts = e.getKey().split(" ", 2);
            // 已经按 endpoint_id 写过的不再按签名写一遍——那会把别的项目下同名路径也带上
            changed += jdbcTemplate.update("""
                    UPDATE requirement_endpoints
                       SET verified = ?, verified_at = ?, last_run_status = ?
                     WHERE http_method = ? AND path = ?
                       AND (endpoint_id IS NULL OR endpoint_id = '')
                    """,
                    e.getValue() ? 1 : 0,
                    e.getValue() ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
                    e.getValue() ? "PASSED" : "FAILED",
                    parts[0], parts.length > 1 ? parts[1] : "");
        }
        if (changed > 0) {
            log.info("[verify] 回写 {} 条接口的验证状态", changed);
        }
        return changed;
    }

    private int write(String whereClause, String key, boolean passed) {
        return jdbcTemplate.update(
                "UPDATE requirement_endpoints SET verified = ?, verified_at = ?, last_run_status = ? "
                        + "WHERE " + whereClause,
                passed ? 1 : 0,
                passed ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
                passed ? "PASSED" : "FAILED",
                key);
    }

    /** {@code GET /api/user/list}。路径上的 query 要去掉，否则同一个接口翻页就成了两个 */
    static String signature(String method, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        String m = method == null || method.isBlank()
                ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        return m + " " + p;
    }

    /** 某个需求单下的验证情况，给「接口 x/y」用 */
    public Map<String, Object> summaryOf(String requirementNo) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            rows = jdbcTemplate.queryForList("""
                    SELECT http_method AS m, path AS p, verified AS v,
                           last_run_status AS s, verified_at AS at
                      FROM requirement_endpoints WHERE requirement_no = ?
                    """, requirementNo);
        } catch (Exception e) {
            log.debug("[verify] 查询验证情况失败: {}", e.getMessage());
        }
        long verified = rows.stream().filter(r -> num(r.get("v")) == 1).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("verified", verified);
        out.put("rows", rows);
        return out;
    }

    /**
     * TINYINT(1) 在 MySQL 驱动里会变成 Boolean 而不是数字。
     * 直接 parseInt 会抛 NumberFormatException——而它抛在统计这一步，
     * 表现是「接口 x/y」整块查不出来，看着像没数据，其实是崩了。
     */
    private static int num(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
