package com.mobile.standalone.controller;

import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.service.UiFlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 拖拽流程/组件 CRUD。steps 只引用 keywordCode + elementKey。
 */
@RestController
@RequestMapping("/ui-flows")
@RequiredArgsConstructor
public class UiFlowController {

    private final UiFlowService uiFlowService;

    @GetMapping
    public ResponseEntity<List<UiFlow>> list(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) Boolean componentOnly
    ) {
        return ResponseEntity.ok(uiFlowService.list(appId, platform, catalog, componentOnly));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiFlow> get(@PathVariable String id) {
        UiFlow f = uiFlowService.get(id);
        return f == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(f);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UiFlow body) {
        try {
            return ResponseEntity.ok(uiFlowService.create(body));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UiFlow body) {
        try {
            UiFlow updated = uiFlowService.update(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return uiFlowService.delete(id) ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.notFound().build();
    }
}
