package com.mobile.standalone.controller;

import com.mobile.standalone.service.DebugFlowService;
import com.mobile.standalone.service.DebugFlowService.AddCartRequest;
import com.mobile.standalone.service.DebugFlowService.CheckoutRequest;
import com.mobile.standalone.service.DebugFlowService.OpenSessionRequest;
import com.mobile.standalone.service.DebugFlowService.PayRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第二套 debug 链路：会话 → 目录 → 加购 → 下单 → 支付 → 票据。
 * <p>
 * 每层返回嵌套 JSON，供接口自动化 extract / 变量传递调试。
 * 前缀 /debug-flow，完整 URL /api/debug-flow/...
 */
@RestController
@RequestMapping("/debug-flow")
@RequiredArgsConstructor
public class DebugFlowController {

    private final DebugFlowService debugFlowService;

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("module", "debug-flow");
        body.put("layers", 6);
        return ResponseEntity.ok(body);
    }

    /** 清空内存态，方便重复跑整条链路 */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        return ResponseEntity.ok(debugFlowService.reset());
    }

    /** 第1层：开会话，抽 sessionToken / accountId */
    @PostMapping("/session")
    public ResponseEntity<?> openSession(@RequestBody(required = false) OpenSessionRequest body) {
        try {
            return ResponseEntity.ok(debugFlowService.openSession(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 第2层：目录，抽 productId / sku / unitPrice */
    @GetMapping("/catalog")
    public ResponseEntity<?> catalog(
            @RequestParam String accountId,
            @RequestParam String sessionToken
    ) {
        try {
            return ResponseEntity.ok(debugFlowService.catalog(accountId, sessionToken));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 第3层：加购，抽 cartId / lineId */
    @PostMapping("/cart")
    public ResponseEntity<?> addToCart(@RequestBody AddCartRequest body) {
        try {
            return ResponseEntity.ok(debugFlowService.addToCart(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 第4层：下单，抽 orderId / payToken */
    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody CheckoutRequest body) {
        try {
            return ResponseEntity.ok(debugFlowService.checkout(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 第5层：支付，抽 txnId / receiptNo */
    @PostMapping("/pay")
    public ResponseEntity<?> pay(@RequestBody PayRequest body) {
        try {
            return ResponseEntity.ok(debugFlowService.pay(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 第6层：票据终态，嵌套校验整条链 */
    @GetMapping("/orders/{orderId}/ticket")
    public ResponseEntity<?> ticket(
            @PathVariable String orderId,
            @RequestParam(required = false) String txnId,
            @RequestParam String sessionToken
    ) {
        try {
            return ResponseEntity.ok(debugFlowService.ticket(orderId, txnId, sessionToken));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
