package com.mobile.standalone.controller;

import com.mobile.standalone.service.EnvSetupK8sFilesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 环境搭建：读取 / 保存 k8s-config 下已生成的部署文件，供前端 Tab 编辑。
 */
@RestController
@RequestMapping("/env-setup")
@RequiredArgsConstructor
public class EnvSetupController {

    private final EnvSetupK8sFilesService filesService;

    @GetMapping("/k8s-files")
    public ResponseEntity<?> list(
            @RequestParam String bizLine,
            @RequestParam String serviceName) {
        try {
            return ResponseEntity.ok(filesService.listFiles(bizLine, serviceName));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() == null ? "读取失败" : e.getMessage()));
        }
    }

    @PutMapping("/k8s-files")
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        try {
            String bizLine = str(body.get("bizLine"));
            String serviceName = str(body.get("serviceName"));
            String fileName = str(body.get("fileName"));
            Object raw = body.get("content");
            String content = raw == null ? "" : String.valueOf(raw);
            return ResponseEntity.ok(filesService.saveFile(bizLine, serviceName, fileName, content));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() == null ? "保存失败" : e.getMessage()));
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }
}
