package com.mobile.standalone.controller;

import com.mobile.standalone.service.DebugSmokeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * backend-0825 提测新增接口（相对 main 仅这 3 个）。
 * 完整 URL：{@code /api/debug-smoke/...}，需 Bearer 登录（继承 main 鉴权）。
 */
@RestController
@RequestMapping("/debug-smoke")
@RequiredArgsConstructor
public class DebugSmokeController {

    private final DebugSmokeService debugSmokeService;

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(debugSmokeService.ping());
    }

    @PostMapping("/jobs")
    public ResponseEntity<?> createJob(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(debugSmokeService.createJob(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> getJob(@PathVariable String id) {
        Map<String, Object> vo = debugSmokeService.getJob(id);
        return vo == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(vo);
    }
}
