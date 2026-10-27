package com.mobile.standalone.controller;

import com.mobile.standalone.service.releasemerge.TestingMergeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 合并到 testing：JIRA 拉分支 → 核对 → 逐条合进 testing。
 */
@RestController
@RequestMapping("/testing-merge")
@RequiredArgsConstructor
public class TestingMergeController {

    private final TestingMergeService service;

    @GetMapping("/settings")
    public ResponseEntity<?> settings() {
        return wrap(service::settings);
    }

    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestBody Map<String, Object> body) {
        return wrap(() -> service.resolve(str(body, "text")));
    }

    @PostMapping("/check")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> check(@RequestBody Map<String, Object> body) {
        Object rows = body.get("rows");
        return wrap(() -> service.check(rows instanceof List<?> l ? (List<Map<String, Object>>) l : null));
    }

    @PostMapping("/merge")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> merge(@RequestBody Map<String, Object> body) {
        Object acked = body.get("acked");
        return wrap(() -> service.merge(str(body, "projectCode"), str(body, "branch"), str(body, "sha"),
                acked instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of()));
    }

    @GetMapping("/logs")
    public ResponseEntity<?> logs(@RequestParam(defaultValue = "50") int limit) {
        return wrap(() -> service.logs(limit));
    }

    private static ResponseEntity<?> wrap(Supplier<?> fn) {
        try {
            return ResponseEntity.ok(fn.get());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        return String.valueOf(body.get(key));
    }
}
