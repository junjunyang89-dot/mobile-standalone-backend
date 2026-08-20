package com.mobile.standalone.controller;

import com.mobile.standalone.dto.debugcompat.CompatOrderRequest;
import com.mobile.standalone.dto.debugcompat.CompatOrderVo;
import com.mobile.standalone.dto.debugcompat.CompatProfileVo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「可能不兼容 / BREAKING」调试接口（基线版本）。
 * <p>
 * 用法：先提交本基线，再改成破坏版并再提交；接口对比分析选
 * <b>基线 commit → 破坏版 commit</b>（不要只对 main，新接口对 main 只会标 NEW）。
 * <p>
 * 前缀 /debug-compat，完整 URL /api/debug-compat/...
 */
@RestController
@RequestMapping("/debug-compat")
public class DebugCompatController {

    /**
     * 查资料。基线：只要 userId。
     * 破坏版会新增必填 tenantId，且返回体删 remark、score 改类型。
     */
    @GetMapping("/profile")
    public ResponseEntity<CompatProfileVo> profile(@RequestParam String userId) {
        CompatProfileVo vo = new CompatProfileVo();
        vo.setId(1001L);
        vo.setName("compat-" + userId);
        vo.setScore("80");
        vo.setRemark("baseline-remark");
        return ResponseEntity.ok(vo);
    }

    /**
     * 下单。基线 body：sku + qty。
     * 破坏版会在 body 增加必填 region，并删除返回 status。
     */
    @PostMapping("/order")
    public ResponseEntity<?> order(@RequestBody CompatOrderRequest body) {
        if (body == null || body.getSku() == null || body.getSku().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "sku 不能为空"));
        }
        int qty = body.getQty() == null || body.getQty() < 1 ? 1 : body.getQty();
        CompatOrderVo vo = new CompatOrderVo();
        vo.setOrderId("COMPAT-ORD-1");
        vo.setAmount(39.9 * qty);
        vo.setStatus("CREATED");
        return ResponseEntity.ok(vo);
    }

    /**
     * 遗留探活。破坏版会整段删除本接口 → REMOVED / BREAKING。
     */
    @GetMapping("/legacy-ping")
    public ResponseEntity<Map<String, Object>> legacyPing() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("module", "debug-compat");
        body.put("version", "baseline");
        return ResponseEntity.ok(body);
    }
}
