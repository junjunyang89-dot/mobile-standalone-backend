package com.mobile.standalone.controller;

import com.mobile.standalone.auth.AuthFilter;
import com.mobile.standalone.auth.PlatformUser;
import com.mobile.standalone.service.pack.AppPackService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * APP 打包。鸿蒙、完美电竞安卓、完美电竞 iOS（按分支打 Debug IPA）。
 */
@RestController
@RequestMapping("/app-packs")
@RequiredArgsConstructor
public class AppPackController {

    private final AppPackService appPackService;

    @GetMapping("/options")
    public ResponseEntity<?> options() {
        return ResponseEntity.ok(appPackService.options());
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String platform) {
        return ResponseEntity.ok(Map.of("jobs", appPackService.list(platform)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        Map<String, Object> row = appPackService.get(id);
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(row);
    }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            String id = appPackService.start(
                    str(body, "platform"),
                    str(body, "product"),
                    str(body, "outputType"),
                    createdBy(request),
                    str(body, "deviceId"),
                    str(body, "branch"));
            return ResponseEntity.ok(Map.of("id", id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/install")
    public ResponseEntity<?> install(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            appPackService.installArtifact(id, str(body, "deviceId"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        try {
            return appPackService.cancel(id)
                    ? ResponseEntity.ok(Map.of("success", true))
                    : ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return appPackService.delete(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/artifact")
    public ResponseEntity<?> artifact(@PathVariable String id) {
        Path file = appPackService.artifactFile(id);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        String name = file.getFileName().toString();
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        return String.valueOf(body.get(key));
    }

    private static String createdBy(HttpServletRequest request) {
        Object u = request.getAttribute(AuthFilter.ATTR_USER);
        if (u instanceof PlatformUser pu) {
            if (pu.getDisplayName() != null && !pu.getDisplayName().isBlank()) {
                return pu.getDisplayName();
            }
            return pu.getUsername();
        }
        return null;
    }
}
