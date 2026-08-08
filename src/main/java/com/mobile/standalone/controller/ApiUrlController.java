package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ApiUrlEntry;
import com.mobile.standalone.service.ApiUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 资产管理 · URL 管理：按项目维护网关/Base URL，供接口测试下拉选用。
 */
@RestController
@RequestMapping("/api-urls")
@RequiredArgsConstructor
public class ApiUrlController {

    private final ApiUrlService urlService;

    @GetMapping
    public ResponseEntity<List<ApiUrlEntry>> list(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) Boolean enabledOnly
    ) {
        return ResponseEntity.ok(urlService.list(project, environment, enabledOnly));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        ApiUrlEntry u = urlService.get(id);
        return u == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(u);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ApiUrlEntry body) {
        try {
            return ResponseEntity.ok(urlService.create(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ApiUrlEntry body) {
        try {
            ApiUrlEntry updated = urlService.update(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", urlService.delete(id)));
    }
}
