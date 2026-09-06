package com.mobile.standalone.controller;

import com.mobile.standalone.service.apitest.ResponseSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 接口响应基线：换皮回归的对比基准。
 * <p>
 * 上线前把一场抓包提升成基线，换皮后再抓一场和它比结构。前端每期全改，
 * 但接口返回结构基本不变——这个比对完全不碰页面、不依赖选择器，换皮打不穿。
 */
@RestController
@RequestMapping("/response-snapshots")
@RequiredArgsConstructor
public class ResponseSnapshotController {

    private final ResponseSnapshotService service;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String requirementNo) {
        return ResponseEntity.ok(service.list(requirementNo));
    }

    /** 把一场抓包提升成基线 */
    @PostMapping("/promote")
    public ResponseEntity<?> promote(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(service.promote(
                    body.get("sessionId"), body.get("name"),
                    body.get("requirementNo"), body.get("note")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 拿一场新抓包和某条基线比结构 */
    @PostMapping("/{snapshotId}/diff")
    public ResponseEntity<?> diff(@PathVariable String snapshotId,
                                  @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(service.diffAgainst(snapshotId, body.get("sessionId")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{snapshotId}")
    public ResponseEntity<?> delete(@PathVariable String snapshotId) {
        return ResponseEntity.ok(Map.of("ok", service.delete(snapshotId)));
    }
}
