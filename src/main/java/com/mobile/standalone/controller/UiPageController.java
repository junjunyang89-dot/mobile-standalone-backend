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
    private final com.mobile.standalone.service.PageCodeSuggester pageCodeSuggester;

    /**
     * 中文页面名 → 建议的英文编码。
     * <p>
     * 生成不出来时返回空串而不是报错：编码有随机码兜底，
     * 为了一个「建议」把新建页面卡住不值得。
     */
    @org.springframework.web.bind.annotation.GetMapping("/suggest-code")
    public ResponseEntity<java.util.Map<String, String>> suggestCode(
            @org.springframework.web.bind.annotation.RequestParam String nameZh) {
        String code = pageCodeSuggester.suggest(nameZh);
        return ResponseEntity.ok(java.util.Map.of("code", code == null ? "" : code));
    }

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

    /** 页面下还有元素时会被拒绝，并报出有几个——不替人做「这些元素怎么办」这个决定 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            return uiPageService.delete(id)
                    ? ResponseEntity.ok(Map.of("ok", true))
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            // 不接住的话会变成 500 + 一句通用报错，那条写清楚了「还有几个元素」的提示就白写了
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
