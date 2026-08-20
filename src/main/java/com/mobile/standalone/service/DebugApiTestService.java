package com.mobile.standalone.service;

import com.mobile.standalone.entity.DebugApiTest;
import com.mobile.standalone.mapper.DebugApiTestMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 接口对比分析调试数据。表不存在时自动建，并按 name 幂等补全样例行。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DebugApiTestService {

    private final DebugApiTestMapper debugApiTestMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTableAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS debug_api_tests (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        name VARCHAR(128) NOT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                        score INT DEFAULT 0,
                        remark VARCHAR(512) DEFAULT NULL,
                        extra VARCHAR(1024) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_status (status),
                        INDEX idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[debug-api-tests] 表已就绪 debug_api_tests");
            ensureSeedSamples();
        } catch (Exception e) {
            log.warn("[debug-api-tests] 建表失败（可手动执行 schema_debug_api_tests.sql）: {}", e.getMessage());
        }
    }

    /** 按 name 幂等补样例：已有同名行则跳过，方便老库重启后也能补到新行 */
    private void ensureSeedSamples() {
        int added = 0;
        added += insertSeedIfAbsent("样例-登录", "ACTIVE", 80, "接口对比调试种子", "{\"scene\":\"login\"}");
        added += insertSeedIfAbsent("样例-支付", "ACTIVE", 75, "链路调试-支付", "{\"scene\":\"pay\"}");
        added += insertSeedIfAbsent("样例-退款", "ACTIVE", 70, "链路调试-退款", "{\"scene\":\"refund\"}");
        added += insertSeedIfAbsent("样例-查询", "ACTIVE", 65, "链路调试-查询", "{\"scene\":\"query\"}");
        added += insertSeedIfAbsent("样例-注销", "ACTIVE", 55, "链路调试-注销", "{\"scene\":\"logout\"}");
        added += insertSeedIfAbsent("样例-下单", "DISABLED", 60, "改字段后跑对比", "{\"scene\":\"order\"}");
        added += insertSeedIfAbsent("样例-归档", "DISABLED", 50, "链路调试-已停用", "{\"scene\":\"archive\"}");
        if (added > 0) {
            log.info("[debug-api-tests] 已补全样例 {} 条（ACTIVE×5 / DISABLED×2）", added);
        }
    }

    private int insertSeedIfAbsent(String name, String status, int score, String remark, String extra) {
        if (debugApiTestMapper.findByName(name) != null) {
            return 0;
        }
        DebugApiTest row = new DebugApiTest();
        row.setName(name);
        row.setStatus(status);
        row.setScore(score);
        row.setRemark(remark);
        row.setExtra(extra);
        debugApiTestMapper.insert(row);
        return 1;
    }

    public List<DebugApiTest> list(String status, String keyword, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        return debugApiTestMapper.search(blankToNull(status), blankToNull(keyword), lim);
    }

    /**
     * 固定多层嵌套样例。字段值稳定，方便断言 equals；数组下标可测 {@code $[0]} / {@code .items[1]}。
     */
    public static Map<String, Object> buildNestedJsonSample(String scene) {
        String sc = scene == null || scene.isBlank() ? "demo" : scene.trim();

        Map<String, Object> address = new LinkedHashMap<>();
        address.put("city", "上海");
        address.put("district", "浦东");
        address.put("detail", Map.of("street", "世纪大道", "building", "1号楼"));

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("displayName", "嵌套调试用户");
        profile.put("level", 3);
        profile.put("address", address);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "u-1001");
        user.put("profile", profile);

        Map<String, Object> item0 = new LinkedHashMap<>();
        item0.put("sku", "SKU-A");
        item0.put("qty", 2);
        item0.put("meta", Map.of("color", "red", "tags", List.of("hot", "new")));

        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("sku", "SKU-B");
        item1.put("qty", 1);
        item1.put("meta", Map.of("color", "blue", "tags", List.of("sale")));

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", "ORD-9001");
        order.put("amount", 128.5);
        order.put("items", List.of(item0, item1));
        order.put("pay", Map.of(
                "channel", "alipay",
                "receipt", Map.of("txnId", "TXN-7788", "success", true)
        ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scene", sc);
        payload.put("user", user);
        payload.put("order", order);
        payload.put("flags", Map.of("nested", true, "depth", 5));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", 0);
        root.put("message", "ok");
        root.put("data", payload);
        root.put("meta", Map.of(
                "traceId", "trace-nested-" + sc,
                "page", Map.of("pageNo", 1, "pageSize", 10, "total", 2)
        ));
        return root;
    }

    /**
     * 校验嵌套入参，并再包一层返回。期望：
     * {@code $.expect.userId / $.expect.firstSku / $.expect.txnId / $.expect.scene}
     */
    public Map<String, Object> verifyNestedJson(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("body 不能为空");
        }
        Object expectObj = body.get("expect");
        if (!(expectObj instanceof Map<?, ?> expect)) {
            throw new IllegalArgumentException("body.expect 必须是对象");
        }
        String userId = stringVal(expect.get("userId"));
        String firstSku = stringVal(expect.get("firstSku"));
        String txnId = stringVal(expect.get("txnId"));
        String scene = stringVal(expect.get("scene"));
        if (userId.isBlank() || firstSku.isBlank() || txnId.isBlank()) {
            throw new IllegalArgumentException("expect.userId / firstSku / txnId 不能为空");
        }

        Map<String, Object> sample = buildNestedJsonSample(scene.isBlank() ? "demo" : scene);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sample.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) data.get("user");
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) data.get("order");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> pay = (Map<String, Object>) order.get("pay");
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) pay.get("receipt");

        boolean userOk = Objects.equals(userId, user.get("id"));
        boolean skuOk = Objects.equals(firstSku, items.get(0).get("sku"));
        boolean txnOk = Objects.equals(txnId, receipt.get("txnId"));
        boolean sceneOk = scene.isBlank() || Objects.equals(scene, data.get("scene"));

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("userIdMatched", userOk);
        checks.put("firstSkuMatched", skuOk);
        checks.put("txnIdMatched", txnOk);
        checks.put("sceneMatched", sceneOk);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("verified", userOk && skuOk && txnOk && sceneOk);
        result.put("checks", checks);
        result.put("echo", Map.of(
                "layer", Map.of(
                        "inner", Map.of(
                                "userId", userId,
                                "firstSku", firstSku,
                                "txnId", txnId,
                                "scene", scene.isBlank() ? "demo" : scene
                        )
                )
        ));
        result.put("ticket", "NEST-" + txnId);
        return result;
    }

    /** 真实记录包进嵌套结构，供 {@code $.result.data.item.id} 抽取 */
    public Map<String, Object> nestedBundle(Long id) {
        DebugApiTest row = debugApiTestMapper.findById(id);
        if (row == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("name", row.getName());
        item.put("status", row.getStatus());
        item.put("score", row.getScore());
        item.put("enabled", isEnabled(row));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item", item);
        data.put("relations", List.of(
                Map.of("type", "parent", "ref", "bundle-" + id),
                Map.of("type", "child", "ref", "leaf-" + id)
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("summary", Map.of("depth", 4, "source", "debug_api_tests"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", 0);
        root.put("result", result);
        root.put("bundleId", "bundle-" + id);
        return root;
    }

    private static String stringVal(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    public DebugApiTest get(Long id) {
        return debugApiTestMapper.findById(id);
    }

    @Transactional
    public DebugApiTest create(DebugApiTest req) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        DebugApiTest row = new DebugApiTest();
        row.setName(req.getName().trim());
        row.setStatus(normalizeStatus(req.getStatus()));
        row.setScore(req.getScore() == null ? 0 : req.getScore());
        row.setRemark(blankToNull(req.getRemark()));
        row.setExtra(blankToNull(req.getExtra()));
        debugApiTestMapper.insert(row);
        return debugApiTestMapper.findById(row.getId());
    }

    @Transactional
    public DebugApiTest update(Long id, DebugApiTest req) {
        DebugApiTest existing = debugApiTestMapper.findById(id);
        if (existing == null) {
            return null;
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            existing.setName(req.getName().trim());
        }
        if (req.getStatus() != null) {
            existing.setStatus(normalizeStatus(req.getStatus()));
        }
        if (req.getScore() != null) {
            existing.setScore(req.getScore());
        }
        if (req.getRemark() != null) {
            existing.setRemark(blankToNull(req.getRemark()));
        }
        if (req.getExtra() != null) {
            existing.setExtra(blankToNull(req.getExtra()));
        }
        debugApiTestMapper.update(existing);
        return debugApiTestMapper.findById(id);
    }

    @Transactional
    public boolean delete(Long id) {
        return debugApiTestMapper.deleteById(id) > 0;
    }

    /**
     * 切换启用态。返回体带 {@code enabled} 布尔字段，供接口测试 extract {@code $.enabled}。
     */
    @Transactional
    public DebugApiTest setEnabled(Long id, Boolean enabled) {
        if (enabled == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        DebugApiTest existing = debugApiTestMapper.findById(id);
        if (existing == null) {
            return null;
        }
        existing.setStatus(enabled ? "ACTIVE" : "DISABLED");
        debugApiTestMapper.update(existing);
        return debugApiTestMapper.findById(id);
    }

    public static boolean isEnabled(DebugApiTest row) {
        return row != null && "ACTIVE".equalsIgnoreCase(row.getStatus());
    }

    /** 快照：验 name、返回 score，供下一层 extract snapshotId / detailScore */
    public Map<String, Object> snapshot(Long id, String expectName) {
        DebugApiTest row = debugApiTestMapper.findById(id);
        if (row == null) {
            return null;
        }
        boolean nameMatched = expectName == null || expectName.isBlank()
                || Objects.equals(expectName.trim(), row.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("name", row.getName());
        out.put("enabled", isEnabled(row));
        out.put("status", row.getStatus());
        out.put("score", row.getScore());
        out.put("nameMatched", nameMatched);
        out.put("snapshotId", "snap-" + row.getId());
        return out;
    }

    /** 设绝对分值，便于重复跑时结果稳定 */
    @Transactional
    public Map<String, Object> setScore(Long id, Integer score) {
        if (score == null) {
            throw new IllegalArgumentException("score 不能为空");
        }
        DebugApiTest row = debugApiTestMapper.findById(id);
        if (row == null) {
            return null;
        }
        int previous = row.getScore() == null ? 0 : row.getScore();
        row.setScore(score);
        debugApiTestMapper.update(row);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("name", row.getName());
        out.put("previousScore", previous);
        out.put("newScore", score);
        return out;
    }

    /**
     * 回显链路变量并做一致性校验。
     * refId/refName/refSnapshotId 应与路径 id 及库内记录一致。
     */
    public Map<String, Object> chainEcho(Long id, String tag, ChainEchoInput input) {
        DebugApiTest row = debugApiTestMapper.findById(id);
        if (row == null) {
            return null;
        }
        ChainEchoInput in = input == null ? new ChainEchoInput() : input;
        boolean idOk = in.getRefId() == null || Objects.equals(String.valueOf(in.getRefId()), String.valueOf(id));
        boolean nameOk = in.getRefName() == null || Objects.equals(in.getRefName().trim(), row.getName());
        boolean snapshotOk = in.getRefSnapshotId() == null
                || Objects.equals(in.getRefSnapshotId(), "snap-" + id);
        boolean scoreOk = in.getRefScore() == null
                || Objects.equals(in.getRefScore(), row.getScore());
        boolean enabledOk = in.getRefEnabled() == null
                || Objects.equals(parseBool(in.getRefEnabled()), isEnabled(row));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("tag", tag);
        out.put("refId", in.getRefId());
        out.put("refName", in.getRefName());
        out.put("refSnapshotId", in.getRefSnapshotId());
        out.put("refScore", in.getRefScore());
        out.put("refEnabled", in.getRefEnabled());
        out.put("chainVerified", idOk && nameOk && snapshotOk && scoreOk && enabledOk);
        out.put("chainDepth", 4);
        out.put("chainToken", "chain-" + id + "-" + (tag == null ? "none" : tag));
        return out;
    }

    /** 终态收据：query 里带上前几层 extract 的变量做汇总校验 */
    public Map<String, Object> chainReceipt(Long id, String snapshotId, Integer score, String name) {
        DebugApiTest row = debugApiTestMapper.findById(id);
        if (row == null) {
            return null;
        }
        boolean snapshotOk = snapshotId == null || Objects.equals(snapshotId, "snap-" + id);
        boolean scoreOk = score == null || Objects.equals(score, row.getScore());
        boolean nameOk = name == null || name.isBlank() || Objects.equals(name.trim(), row.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("receiptId", "rcpt-" + id);
        out.put("allPassed", snapshotOk && scoreOk && nameOk && isEnabled(row));
        out.put("layersChecked", 5);
        out.put("finalScore", row.getScore());
        out.put("finalName", row.getName());
        out.put("finalEnabled", isEnabled(row));
        return out;
    }

    private static Boolean parseBool(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
            return false;
        }
        return null;
    }

    @lombok.Data
    public static class ChainEchoInput {
        private Object refId;
        private String refName;
        private String refSnapshotId;
        private Integer refScore;
        private Object refEnabled;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ACTIVE";
        }
        String s = status.trim().toUpperCase();
        if (!"ACTIVE".equals(s) && !"DISABLED".equals(s)) {
            throw new IllegalArgumentException("status 须为 ACTIVE 或 DISABLED");
        }
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
