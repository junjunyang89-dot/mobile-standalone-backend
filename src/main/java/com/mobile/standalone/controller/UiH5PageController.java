package com.mobile.standalone.controller;

import com.mobile.standalone.entity.UiH5Page;
import com.mobile.standalone.service.UiH5PageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * H5 页面汇总 CRUD + 打开信息（带 token 的预览 URL）。
 */
@RestController
@RequestMapping("/ui-h5-pages")
@RequiredArgsConstructor
public class UiH5PageController {

    private final UiH5PageService uiH5PageService;

    @GetMapping
    public ResponseEntity<List<UiH5Page>> list(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) Boolean enabledOnly
    ) {
        return ResponseEntity.ok(uiH5PageService.list(appId, enabledOnly));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiH5Page> get(@PathVariable String id) {
        UiH5Page p = uiH5PageService.get(id);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    @GetMapping("/{id}/open")
    public ResponseEntity<?> openInfo(@PathVariable String id) {
        Map<String, Object> info = uiH5PageService.buildOpenInfo(id);
        return info == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(info);
    }

    /** 社区帖等：用 appengine getPostById 验 token 是否可用 */
    @GetMapping("/{id}/probe")
    public ResponseEntity<?> probe(@PathVariable String id) {
        Map<String, Object> info = uiH5PageService.probe(id);
        return info == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(info);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UiH5Page body) {
        try {
            return ResponseEntity.ok(uiH5PageService.create(body));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UiH5Page body) {
        try {
            UiH5Page updated = uiH5PageService.update(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return uiH5PageService.delete(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.notFound().build();
    }
}
