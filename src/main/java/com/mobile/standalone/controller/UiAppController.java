package com.mobile.standalone.controller;

import com.mobile.standalone.entity.UiApp;
import com.mobile.standalone.service.UiAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 业务应用字典 CRUD。元素/流程下拉选此表，勿与设备安装 apps 混淆。
 */
@RestController
@RequestMapping("/ui-apps")
@RequiredArgsConstructor
public class UiAppController {

    private final UiAppService uiAppService;

    @GetMapping
    public ResponseEntity<List<UiApp>> list(
            @RequestParam(required = false, defaultValue = "false") Boolean enabledOnly
    ) {
        return ResponseEntity.ok(uiAppService.list(enabledOnly));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiApp> get(@PathVariable String id) {
        UiApp a = uiAppService.get(id);
        return a == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(a);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UiApp body) {
        try {
            return ResponseEntity.ok(uiAppService.create(body));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UiApp body) {
        try {
            UiApp updated = uiAppService.update(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return uiAppService.delete(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.notFound().build();
    }
}
