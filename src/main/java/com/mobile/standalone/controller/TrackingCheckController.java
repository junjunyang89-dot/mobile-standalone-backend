package com.mobile.standalone.controller;

import com.mobile.standalone.dto.TrackingCheckDtos.VerifyRequest;
import com.mobile.standalone.service.TrackingCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 埋点校验：Wiki 埋点表 vs 用户上传的 Kibana JSON。
 */
@RestController
@RequestMapping("/tracking-check")
@RequiredArgsConstructor
public class TrackingCheckController {

    private final TrackingCheckService trackingCheckService;

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest req) {
        try {
            return ResponseEntity.ok(trackingCheckService.verify(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }
}
