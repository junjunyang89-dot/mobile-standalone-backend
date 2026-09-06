package com.mobile.standalone.controller;

import com.mobile.standalone.service.AppPortalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * APP 门户：日常外链（两套 Jenkins 等）汇总，点开即访问。
 */
@RestController
@RequestMapping("/app-portal")
@RequiredArgsConstructor
public class AppPortalController {

    private final AppPortalService service;

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of("groups", service.grouped()));
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(service.save(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", service.delete(id)));
    }
}
