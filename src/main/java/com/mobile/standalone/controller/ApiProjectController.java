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

    @GetMapping("/categories")
    public ResponseEntity<List<Map<String, Object>>> categories() {
        return ResponseEntity.ok(projectService.listCategoryRegistry());
    }

    /** 重命名分类（独立路径，避免与 POST /categories 新建或 /categories/{key} 路由混淆） */
    @PostMapping("/category-rename")
    public ResponseEntity<?> renameCategory(@RequestBody Map<String, Object> body) {
        try {
            String key = body.get("key") != null ? String.valueOf(body.get("key")).trim() : "";
            if (key.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "分类标识 key 必填"));
            }
            String label = body.get("label") != null ? String.valueOf(body.get("label")) : "";
            Integer sortOrder = body.get("sortOrder") instanceof Number n ? n.intValue() : null;
            return ResponseEntity.ok(projectService.updateCategory(key, label, sortOrder));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 新建或重命名分类：body 带 key 则更新已有分类，不带 key 则新建。
     * 重命名走 POST 而非 PUT，避免部分环境对 PUT /categories/{key} 返回 405。
     */
    @PostMapping("/categories")
    public ResponseEntity<?> saveCategory(@RequestBody Map<String, Object> body) {
        try {
            String label = body.get("label") != null ? String.valueOf(body.get("label")) : "";
            Integer sortOrder = body.get("sortOrder") instanceof Number n ? n.intValue() : null;
            String key = body.get("key") != null ? String.valueOf(body.get("key")).trim() : "";
            if (!key.isBlank()) {
                return ResponseEntity.ok(projectService.updateCategory(key, label, sortOrder));
            }
            return ResponseEntity.ok(projectService.createCategory(label, sortOrder));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/categories/{key}")
    public ResponseEntity<?> updateCategory(@PathVariable String key, @RequestBody Map<String, Object> body) {
        try {
            String label = body.get("label") != null ? String.valueOf(body.get("label")) : "";
            Integer sortOrder = body.get("sortOrder") instanceof Number n ? n.intValue() : null;
            return ResponseEntity.ok(projectService.updateCategory(key, label, sortOrder));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/categories/{key}")
    public ResponseEntity<?> deleteCategory(@PathVariable String key) {
        try {
            return ResponseEntity.ok(Map.of("ok", projectService.deleteCategory(key)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
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
