package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.service.ApiProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 资产管理 · 项目管理：接口/URL 归属的项目编码集中维护。
 */
@RestController
@RequestMapping("/api-projects")
@RequiredArgsConstructor
public class ApiProjectController {

    private final ApiProjectService projectService;

    @GetMapping
    public ResponseEntity<List<ApiProject>> list(
            @RequestParam(required = false) Boolean enabledOnly,
            @RequestParam(required = false) Boolean catalogOnly
    ) {
        if (Boolean.TRUE.equals(catalogOnly)) {
            return ResponseEntity.ok(projectService.listForCatalog(enabledOnly));
        }
        return ResponseEntity.ok(projectService.list(enabledOnly));
    }

    /** 项目管理页树：APP / PVP / WEB / 其他 */
    @GetMapping("/tree")
    public ResponseEntity<Map<String, Object>> tree(
            @RequestParam(required = false) Boolean enabledOnly
    ) {
        return ResponseEntity.ok(projectService.buildTree(enabledOnly));
    }

    @GetMapping("/{code}")
    public ResponseEntity<?> get(@PathVariable String code) {
        ApiProject p = projectService.get(code);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ApiProject body) {
        try {
            return ResponseEntity.ok(projectService.create(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{code}")
    public ResponseEntity<?> update(@PathVariable String code, @RequestBody ApiProject body) {
        try {
            ApiProject updated = projectService.update(code, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<?> delete(@PathVariable String code) {
        return ResponseEntity.ok(Map.of("ok", projectService.delete(code)));
    }
}
