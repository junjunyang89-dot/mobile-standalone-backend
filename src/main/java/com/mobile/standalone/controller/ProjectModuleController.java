package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ProjectModule;
import com.mobile.standalone.service.ProjectModuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 功能模块树。
 * <p>
 * 模块按产品线（分类）维护、项目共用一棵——两端功能一样，不该各存一份。
 * 它要替换的是散在 {@code ui_elements.module_tag} / {@code ai_test_cases.module}
 * 里的自由文本。
 */
@RestController
@RequestMapping("/project-modules")
@RequiredArgsConstructor
public class ProjectModuleController {

    private final ProjectModuleService service;

    /** 平铺列表，供下拉选择 */
    @GetMapping
    public ResponseEntity<List<ProjectModule>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean enabledOnly) {
        return ResponseEntity.ok(service.list(category, enabledOnly));
    }

    /** 树形，供左侧导航；每个节点带「有多少需求覆盖了它」 */
    @GetMapping("/tree")
    public ResponseEntity<List<ProjectModule>> tree(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean enabledOnly) {
        return ResponseEntity.ok(service.tree(category, enabledOnly));
    }

    /** 新增或更新（带 id 即更新） */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody ProjectModule body) {
        try {
            return ResponseEntity.ok(service.save(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ProjectModule body) {
        try {
            body.setId(id);
            return ResponseEntity.ok(service.save(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 有子模块或已被需求引用时拒绝，不做静默级联删除 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            return ResponseEntity.ok(Map.of("ok", service.delete(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
