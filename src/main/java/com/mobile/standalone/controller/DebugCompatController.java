package com.mobile.standalone.controller;

import com.mobile.standalone.dto.debugcompat.CompatOrderRequest;
import com.mobile.standalone.dto.debugcompat.CompatOrderVo;
import com.mobile.standalone.dto.debugcompat.CompatProfileVo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 「可能不兼容 / BREAKING」调试接口（破坏版）。
 * <p>
 * 相对基线 commit 的故意破坏：
 * <ul>
 *   <li>{@code GET /profile}：新增必填 QUERY {@code tenantId}；返回体删 remark、score 改类型</li>
 *   <li>{@code POST /order}：body 新增必填 {@code region}；返回体删 status</li>
 *   <li>{@code GET /legacy-ping}：整段删除 → REMOVED</li>
 * </ul>
 * 接口对比分析请选：基准={@code 596fb8d}（或「debug-compat baseline」），待测=本提交 / HEAD。
 */
@RestController
@RequestMapping("/debug-compat")
public class DebugCompatController {

    /**
     * 查资料（破坏版）：多了必填 tenantId。
     */
    @GetMapping("/profile")
    public ResponseEntity<?> profile(
            @RequestParam String userId,
            @RequestParam String tenantId
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "tenantId 不能为空"));
        }
        CompatProfileVo vo = new CompatProfileVo();
        vo.setId(1001L);
        vo.setName("compat-" + userId + "@" + tenantId);
        vo.setScore(85);
        return ResponseEntity.ok(vo);
    }

    /**
     * 下单（破坏版）：body 必须带 region。
     */
    @PostMapping("/order")
    public ResponseEntity<?> order(@RequestBody CompatOrderRequest body) {
        if (body == null || body.getSku() == null || body.getSku().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "sku 不能为空"));
        }
        if (body.getRegion() == null || body.getRegion().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "region 不能为空"));
        }
        int qty = body.getQty() == null || body.getQty() < 1 ? 1 : body.getQty();
        CompatOrderVo vo = new CompatOrderVo();
        vo.setOrderId("COMPAT-ORD-1");
        vo.setAmount(39.9 * qty);
        return ResponseEntity.ok(vo);
    }

    // legacy-ping 已删除：相对基线为 REMOVED / BREAKING
}
