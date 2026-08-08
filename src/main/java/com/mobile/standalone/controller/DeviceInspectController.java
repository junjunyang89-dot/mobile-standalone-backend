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
                    id, body.getXRatio(), body.getYRatio(), body.getX(), body.getY(), body.getAppId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "命中失败"));
        }
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
    }
}
