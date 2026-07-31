package com.mobile.standalone.controller;

import com.mobile.standalone.service.PgyerAppService;
import com.mobile.standalone.service.PgyerAppService.InstallResult;
import com.mobile.standalone.service.PgyerAppService.PgyerBuildVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 蒲公英版本列表 / 下载安装（专项测试等非线上回归场景）。
 */
@RestController
@RequestMapping("/pgyer")
@RequiredArgsConstructor
public class PgyerController {

    private final PgyerAppService pgyerAppService;

    /** 列出历史版本；appKey 默认取配置 pgyer.default-app-key */
    @GetMapping("/builds")
    public ResponseEntity<?> listBuilds(
            @RequestParam(required = false) String appKey,
            @RequestParam(required = false, defaultValue = "1") int page) {
        try {
            List<PgyerBuildVo> list = pgyerAppService.listBuilds(appKey, page);
            return ResponseEntity.ok(list);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage() != null ? e.getMessage() : "查询失败"));
        }
    }

    /** 按 buildKey 下载安装包并安装到设备 */
    @PostMapping("/install")
    public ResponseEntity<?> install(@RequestBody InstallRequest body) {
        try {
            if (body == null || body.getDeviceId() == null || body.getBuildKey() == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "deviceId / buildKey 必填"));
            }
            InstallResult result = pgyerAppService.downloadAndInstall(
                    body.getDeviceId(),
                    body.getBuildKey(),
                    body.getUninstallFirst() == null || body.getUninstallFirst()
            );
            return ResponseEntity.ok(result);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage() != null ? e.getMessage() : "安装失败"));
        }
    }

    @Data
    public static class InstallRequest {
        private String deviceId;
        private String buildKey;
        /** 默认 true：先卸再装 */
        private Boolean uninstallFirst;
    }
}
