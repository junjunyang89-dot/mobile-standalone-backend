package com.mobile.standalone.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mobile.standalone.service.DeviceInspectService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 真机点选采集：截图 + 坐标命中控件树。
 */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceInspectController {

    private final DeviceInspectService deviceInspectService;

    /**
     * 拉取当前屏截图元信息；screenshotUrl 指向本控制器的 PNG 接口（勿依赖 /tmp 静态映射）。
     */
    @GetMapping("/{id}/inspect/snapshot")
    public ResponseEntity<?> snapshot(@PathVariable String id) {
        try {
            return ResponseEntity.ok(deviceInspectService.snapshot(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "截图失败"));
        }
    }

    /** 返回该设备最近一次点选截图（image/png 或 jpeg） */
    @GetMapping(value = "/{id}/inspect/screenshot")
    public ResponseEntity<byte[]> screenshot(@PathVariable String id,
                                             @RequestParam(required = false) String t) {
        byte[] bytes = deviceInspectService.readLatestScreenshot(id);
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = MediaType.IMAGE_PNG;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            type = MediaType.IMAGE_JPEG;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(type)
                .body(bytes);
    }

    /**
     * 在当前屏控件树上按点击坐标命中，返回推荐 locator 与已匹配元素库记录。
     * 优先传 xRatio/yRatio（相对截图 0~1）；也可传像素 x/y。
     */
    @PostMapping("/{id}/inspect/hit-test")
    public ResponseEntity<?> hitTest(@PathVariable String id, @RequestBody HitTestRequest body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "body 不能为空"));
            }
            return ResponseEntity.ok(deviceInspectService.hitTest(
                    id, body.getXRatio(), body.getYRatio(), body.getX(), body.getY(),
                    body.getAppId(), body.getElementKey()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "命中失败"));
        }
    }

    /**
     * 返回当前屏完整控件树（Appium Inspector 式），供前端「截图 + 树」双栏渲染与画框。
     * refresh=true 强制重新 dump；默认用 snapshot 那一刻冻结的树，与截图严格对齐。
     */
    @GetMapping("/{id}/inspect/hierarchy")
    public ResponseEntity<?> hierarchy(@PathVariable String id,
                                       @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        try {
            return ResponseEntity.ok(deviceInspectService.hierarchy(id, refresh));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "获取控件树失败"));
        }
    }

    /**
     * 取树上某个节点的候选 locator（含唯一性校验）。
     * 前端在树上点节点、或按祖先链「往上跳一级」后调用，返回结构与 hit-test 的 candidates 一致。
     */
    @PostMapping("/{id}/inspect/node-locators")
    public ResponseEntity<?> nodeLocators(@PathVariable String id, @RequestBody NodeLocatorsRequest body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "body 不能为空"));
            }
            return ResponseEntity.ok(deviceInspectService.nodeLocators(id, body.getNodePath(), body.getAppId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "取节点定位失败"));
        }
    }

    @Data
    public static class NodeLocatorsRequest {
        /** 来自 hierarchy 接口的 node.nodePath 或 hit-test 的 selectedNodePath / ancestors[].nodePath */
        @JsonProperty("nodePath")
        @JsonAlias({"node_path", "path"})
        private String nodePath;
        /** 可选，用于匹配 ui_elements */
        private String appId;
    }

    @Data
    public static class HitTestRequest {
        /** 相对截图宽度 0~1；须 @JsonProperty，否则 Jackson 对 xRatio 反序列化失败 */
        @JsonProperty("xRatio")
        @JsonAlias({"x_ratio", "xr"})
        private Double xRatio;
        @JsonProperty("yRatio")
        @JsonAlias({"y_ratio", "yr"})
        private Double yRatio;
        private Integer x;
        private Integer y;
        /** 可选，用于匹配 ui_elements */
        private String appId;
        /** 步骤已绑的元素英文名：百分比相对它的矩形，而不是 H5 屏幕坐标 */
        private String elementKey;
    }
}
