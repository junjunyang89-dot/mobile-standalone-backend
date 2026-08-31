package com.mobile.standalone.controller;

import com.mobile.standalone.entity.AppProjectLink;
import com.mobile.standalone.service.AppProjectLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 客户端 App ↔ 项目 关联。
 * <p>
 * 建立这层关联之后，一个项目节点下才能同时看到它的接口资产与 UI 自动化资产。
 */
@RestController
@RequestMapping("/app-project-links")
@RequiredArgsConstructor
public class AppProjectLinkController {

    private final AppProjectLinkService service;

    /** 两个过滤条件都可省略；带上 App 与项目的展示名 */
    @GetMapping
    public ResponseEntity<List<AppProjectLink>> list(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String projectCode) {
        return ResponseEntity.ok(service.list(appId, projectCode));
    }

    /**
     * 沿关联统计各项目的 UI 资产量。
     * <p>
     * 元素按端精确归属；流程与页面表上没有端的维度，给的是 App 级的数
     * （字段名 {@code flowCount} / {@code pageCount} 对应「不分端」）。
     */
    @GetMapping("/asset-stats")
    public ResponseEntity<List<Map<String, Object>>> assetStats(
            @RequestParam(required = false) String projectCode) {
        return ResponseEntity.ok(service.assetStats(projectCode));
    }

    /** 还没挂上任何项目的 App——它们的资产在树上看不见，属于要补的关联 */
    @GetMapping("/unlinked-apps")
    public ResponseEntity<List<Map<String, Object>>> unlinkedApps() {
        return ResponseEntity.ok(service.unlinkedApps());
    }

    /** 新增或更新（主键是 appId + projectCode，重复提交即更新） */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody AppProjectLink body) {
        try {
            return ResponseEntity.ok(service.save(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> delete(@RequestParam String appId, @RequestParam String projectCode) {
        return ResponseEntity.ok(Map.of("ok", service.delete(appId, projectCode)));
    }
}
