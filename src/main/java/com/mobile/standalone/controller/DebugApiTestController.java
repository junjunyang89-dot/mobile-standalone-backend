package com.mobile.standalone.controller;

import com.mobile.standalone.entity.DebugApiTest;
import com.mobile.standalone.service.DebugApiTestService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口对比分析调试接口。改路径 / 入参 / 返回字段后再跑对比，核对 ADDED / REMOVED / MODIFIED。
 * <p>
 * 前缀 /debug-tests，完整 URL 为 /api/debug-tests。
 */
@RestController
@RequestMapping("/debug-tests")
public class DebugApiTestController {

    @Autowired
    private DebugApiTestService debugApiTestService;

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("module", "debug-tests");
        return ResponseEntity.ok(body);
    }

    /**
     * 固定多层嵌套 JSON，专门测 jsonPath 抽取 / 断言。
     * 路径必须在 {@code /{id}} 之前声明，否则会被当成 id。
     */
    @GetMapping("/nested-json")
    public ResponseEntity<Map<String, Object>> nestedJson(
            @RequestParam(required = false, defaultValue = "demo") String scene
    ) {
        return ResponseEntity.ok(DebugApiTestService.buildNestedJsonSample(scene));
    }

    /**
     * 校验调用方传来的嵌套 body 是否与样例一致，并回包更深一层。
     * body 示例见种子用例 debug-test-09b。
     */
    @PostMapping("/nested-json/verify")
    public ResponseEntity<?> verifyNestedJson(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(debugApiTestService.verifyNestedJson(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<DebugApiTest>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(debugApiTestService.list(status, keyword, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        DebugApiTest row = debugApiTestService.get(id);
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(row);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody DebugApiTest body) {
        try {
            return ResponseEntity.ok(debugApiTestService.create(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody DebugApiTest body) {
        try {
            DebugApiTest row = debugApiTestService.update(id, body);
            return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(row);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("ok", debugApiTestService.delete(id)));
    }

    /**
     * 切换启用态。嵌套调试场景：登录抽 token → 列表抽 debugId/debugName → 本接口带 Bearer 更新。
     * <p>
     * 返回 {@code {id, name, enabled, status}}，其中 {@code enabled} 对应 {@code status==ACTIVE}。
     */
    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable Long id, @RequestBody EnabledRequest body) {
        try {
            DebugApiTest row = debugApiTestService.setEnabled(id, body == null ? null : body.getEnabled());
            if (row == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(toEnabledVo(row));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 把库内记录包进多层嵌套结构，测 {@code $.result.data.item.id} 一类路径。
     */
    @GetMapping("/{id}/nested-bundle")
    public ResponseEntity<?> nestedBundle(@PathVariable Long id) {
        Map<String, Object> vo = debugApiTestService.nestedBundle(id);
        return vo == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(vo);
    }

    /**
     * 快照层：验 expectName 与库内 name 一致，抽 snapshotId / detailScore 给后续链路。
     */
    @GetMapping("/{id}/snapshot")
    public ResponseEntity<?> snapshot(
            @PathVariable Long id,
            @RequestParam(required = false) String expectName
    ) {
        Map<String, Object> vo = debugApiTestService.snapshot(id, expectName);
        return vo == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(vo);
    }

    /** 设绝对 score，抽 newScore；重复跑结果稳定 */
    @PostMapping("/{id}/score")
    public ResponseEntity<?> setScore(@PathVariable Long id, @RequestBody ScoreRequest body) {
        try {
            Map<String, Object> vo = debugApiTestService.setScore(id, body == null ? null : body.getScore());
            return vo == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(vo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 链路回显层：body 里带上前几步 extract 的 debugId/debugName/snapshotId/newScore/enabled，
     * 返回 chainVerified / chainToken。
     */
    @PostMapping("/{id}/chain-echo")
    public ResponseEntity<?> chainEcho(
            @PathVariable Long id,
            @RequestParam(required = false) String tag,
            @RequestBody(required = false) DebugApiTestService.ChainEchoInput body
    ) {
        Map<String, Object> vo = debugApiTestService.chainEcho(id, tag, body);
        return vo == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(vo);
    }

    /**
     * 链路收据层：query 汇总 snapshotId / newScore / debugName，抽 receiptId / allPassed。
     */
    @GetMapping("/{id}/chain-receipt")
    public ResponseEntity<?> chainReceipt(
            @PathVariable Long id,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false) Integer score,
            @RequestParam(required = false) String name
    ) {
        Map<String, Object> vo = debugApiTestService.chainReceipt(id, snapshotId, score, name);
        return vo == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(vo);
    }

    /** 回显请求体，用来对比 @RequestBody 字段增删。 */
    @PostMapping("/{id}/echo")
    public ResponseEntity<Map<String, Object>> echo(
            @PathVariable Long id,
            @RequestParam(required = false) String tag,
            @RequestBody(required = false) DebugApiTest body
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("tag", tag);
        out.put("body", body);
        out.put("existing", debugApiTestService.get(id));
        return ResponseEntity.ok(out);
    }

    static Map<String, Object> toEnabledVo(DebugApiTest row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("name", row.getName());
        out.put("enabled", DebugApiTestService.isEnabled(row));
        out.put("status", row.getStatus());
        return out;
    }

    @Data
    public static class EnabledRequest {
        private Boolean enabled;
    }

    @Data
    public static class ScoreRequest {
        private Integer score;
    }
}
