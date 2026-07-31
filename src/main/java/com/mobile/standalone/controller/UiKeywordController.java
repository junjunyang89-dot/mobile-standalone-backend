package com.mobile.standalone.controller;

import com.mobile.standalone.entity.UiKeyword;
import com.mobile.standalone.service.UiKeywordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 关键字/动作库（含固定延迟 WAIT、等待元素等），供拖拽面板使用。
 */
@RestController
@RequestMapping("/ui-keywords")
@RequiredArgsConstructor
public class UiKeywordController {

    private final UiKeywordService uiKeywordService;

    @GetMapping
    public ResponseEntity<List<UiKeyword>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "false") Boolean enabledOnly
    ) {
        return ResponseEntity.ok(uiKeywordService.list(category, enabledOnly));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiKeyword> get(@PathVariable String id) {
        UiKeyword k = uiKeywordService.get(id);
        return k == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(k);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UiKeyword body) {
        try {
            return ResponseEntity.ok(uiKeywordService.create(body));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UiKeyword body) {
        try {
            UiKeyword updated = uiKeywordService.update(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            return uiKeywordService.delete(id)
                    ? ResponseEntity.ok(Map.of("ok", true))
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }
}
