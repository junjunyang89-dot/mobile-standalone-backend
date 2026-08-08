package com.mobile.standalone.controller;

import com.mobile.standalone.entity.EngineeringMemory;
import com.mobile.standalone.service.EngineeringMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工程记忆时间线 API。
 * <ul>
 *   <li>GET /engineering-memory — 按时间倒序检索</li>
 *   <li>POST /engineering-memory — 追加一条摘要</li>
 * </ul>
 */
@RestController
@RequestMapping("/engineering-memory")
@RequiredArgsConstructor
public class EngineeringMemoryController {

    private final EngineeringMemoryService engineeringMemoryService;

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(engineeringMemoryService.summary());
    }

    @GetMapping
    public ResponseEntity<List<EngineeringMemory>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(engineeringMemoryService.list(type, tag, q, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        EngineeringMemory m = engineeringMemoryService.get(id);
        return m == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(m);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody EngineeringMemory body) {
        try {
            return ResponseEntity.ok(engineeringMemoryService.create(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "保存失败"));
        }
    }
}
