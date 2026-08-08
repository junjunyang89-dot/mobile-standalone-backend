package com.mobile.standalone.controller;

import com.mobile.standalone.entity.UiPage;
import com.mobile.standalone.service.UiPageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 页面/弹窗字典：元素归属；列表按页折叠。
 */
@RestController
@RequestMapping("/ui-pages")
@RequiredArgsConstructor
public class UiPageController {

    private final UiPageService uiPageService;

    @GetMapping
    public ResponseEntity<List<UiPage>> list(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false, defaultValue = "false") Boolean enabledOnly
    ) {
        return ResponseEntity.ok(uiPageService.list(appId, enabledOnly));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiPage> get(@PathVariable String id) {
        UiPage p = uiPageService.get(id);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UiPage body) {
        try {
            return ResponseEntity.ok(uiPageService.create(body));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    /** 不存在则创建：{ appId, pageId, nameZh?, moduleTag? } */
    @PostMapping("/ensure")
    public ResponseEntity<?> ensure(@RequestBody Map<String, Object> body) {
        try {
            String appId = body.get("appId") != null ? String.valueOf(body.get("appId")) : null;
            String pageId = body.get("pageId") != null ? String.valueOf(body.get("pageId")) : null;
            String nameZh = body.get("nameZh") != null ? String.valueOf(body.get("nameZh")) : null;
            String moduleTag = body.get("moduleTag") != null ? String.valueOf(body.get("moduleTag")) : null;
            return ResponseEntity.ok(uiPageService.ensurePage(appId, pageId, nameZh, moduleTag));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UiPage body) {
        try {
            UiPage updated = uiPageService.update(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return uiPageService.delete(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.notFound().build();
    }
}
