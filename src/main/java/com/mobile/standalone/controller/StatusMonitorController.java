package com.mobile.standalone.controller;

import com.mobile.standalone.service.StatusMonitorProbeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 业务状态监控：按 URL 管理里的地址 + 健康路径探活。
 */
@RestController
@RequestMapping("/status-monitor")
@RequiredArgsConstructor
public class StatusMonitorController {

    private final StatusMonitorProbeService probeService;

    /**
     * body: { "targets": [ { "id", "urlId", "healthPath" }, ... ] }
     * 返回: { "results": [ { id, status, latencyMs, httpStatus, error, probeUrl, ... } ] }
     */
    @PostMapping("/probe")
    public ResponseEntity<?> probe(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("targets");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "targets 不能为空"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) (List<?>) list;
        List<Map<String, Object>> results = probeService.probe(targets);
        return ResponseEntity.ok(Map.of("results", results));
    }
}
