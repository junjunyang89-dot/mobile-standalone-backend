package com.mobile.standalone.controller;

import com.mobile.standalone.auth.AuthFilter;
import com.mobile.standalone.auth.PlatformUser;
import com.mobile.standalone.service.IncidentPlaybookService;
import com.mobile.standalone.service.buildhost.MavenCacheService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 事故沉淀。记录、归类；依赖包事故可以 SSH 去构建机看 Maven 缓存。
 */
@RestController
@RequestMapping("/incident-playbooks")
@RequiredArgsConstructor
public class IncidentPlaybookController {

    private final IncidentPlaybookService service;
    private final MavenCacheService mavenCacheService;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String toolStatus,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(service.list(category, toolStatus, q));
    }

    @GetMapping("/tools/maven-cache")
    public ResponseEntity<?> mavenOptions() {
        return ResponseEntity.ok(mavenCacheService.options());
    }

    @PostMapping("/tools/maven-cache/inspect")
    public ResponseEntity<?> mavenInspect(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> p = body == null ? Map.of() : body;
            return ResponseEntity.ok(mavenCacheService.inspect(
                    str(p.get("groupId")), str(p.get("artifactId")), str(p.get("version"))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/tools/maven-cache/clean")
    public ResponseEntity<?> mavenClean(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> p = body == null ? Map.of() : body;
            return ResponseEntity.ok(mavenCacheService.clean(
                    str(p.get("groupId")), str(p.get("artifactId")), str(p.get("version"))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        Map<String, Object> row = service.get(id);
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(row);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(Map.of("id", service.create(body, createdBy(request))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            return service.update(id, body)
                    ? ResponseEntity.ok(Map.of("success", true))
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return service.delete(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
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
