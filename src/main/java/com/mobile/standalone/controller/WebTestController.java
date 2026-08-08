package com.mobile.standalone.controller;

import com.mobile.standalone.dto.WebTestDtos.RunRequest;
import com.mobile.standalone.entity.WebTestCase;
import com.mobile.standalone.service.WebTestCaseService;
import com.mobile.standalone.service.WebTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Web 端测试：员工在页面编排用例步骤，后台 Playwright 执行。
 */
@RestController
@RequestMapping("/web-test")
@RequiredArgsConstructor
public class WebTestController {

    private final WebTestService webTestService;
    private final WebTestCaseService webTestCaseService;

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(webTestService.status());
    }

    @GetMapping("/keywords")
    public ResponseEntity<?> keywords() {
        return ResponseEntity.ok(webTestCaseService.keywords());
    }

    @GetMapping("/cases")
    public ResponseEntity<List<WebTestCase>> listCases(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Boolean enabledOnly,
            @RequestParam(required = false) String keyword
    ) {
        return ResponseEntity.ok(webTestCaseService.list(channel, enabledOnly, keyword));
    }

    @GetMapping("/cases/{id}")
    public ResponseEntity<?> getCase(@PathVariable String id) {
        WebTestCase c = webTestCaseService.get(id);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
    }

    @PostMapping("/cases")
    public ResponseEntity<?> createCase(@RequestBody WebTestCase body) {
        try {
            return ResponseEntity.ok(webTestCaseService.create(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/cases/{id}")
    public ResponseEntity<?> updateCase(@PathVariable String id, @RequestBody WebTestCase body) {
        try {
            WebTestCase updated = webTestCaseService.update(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/cases/{id}")
    public ResponseEntity<?> deleteCase(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", webTestCaseService.delete(id)));
    }

    /** body 可传 caseIds（编排用例）或 cases（临时 URL） */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody RunRequest req) {
        try {
            return ResponseEntity.ok(webTestService.run(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }
}
