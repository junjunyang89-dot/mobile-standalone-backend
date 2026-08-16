package com.mobile.standalone.controller;

import com.mobile.standalone.config.NodeIdentity;
import com.mobile.standalone.entity.WorkerNode;
import com.mobile.standalone.service.WorkerNodeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/worker-nodes")
@RequiredArgsConstructor
public class WorkerNodeController {

    private final WorkerNodeService workerNodeService;
    private final NodeIdentity nodeIdentity;

    @GetMapping
    public ResponseEntity<List<WorkerNode>> list() {
        return ResponseEntity.ok(workerNodeService.list());
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        return ResponseEntity.ok(Map.of(
                "id", nodeIdentity.id(),
                "displayName", nodeIdentity.displayName(),
                "role", nodeIdentity.role(),
                "hostname", nodeIdentity.hostname(),
                "advertiseUrl", nodeIdentity.advertiseUrl(),
                "controlPlaneUrl", nodeIdentity.controlPlaneUrl() == null ? "" : nodeIdentity.controlPlaneUrl(),
                "executesLocally", nodeIdentity.executesLocally()
        ));
    }

    /** 执行节点心跳：登记在线状态和本机设备快照 */
    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestBody HeartbeatRequest body) {
        if (body == null || body.getId() == null || body.getId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "id 必填"));
        }
        WorkerNode node = new WorkerNode();
        node.setId(body.getId().trim());
        node.setDisplayName(body.getDisplayName());
        node.setHostname(body.getHostname());
        node.setRole(body.getRole());
        node.setBaseUrl(body.getBaseUrl());
        node.setDevicesJson(body.getDevicesJson());
        return ResponseEntity.ok(workerNodeService.upsertHeartbeat(node));
    }

    @Data
    public static class HeartbeatRequest {
        private String id;
        private String displayName;
        private String hostname;
        private String role;
        private String baseUrl;
        private String devicesJson;
    }
}
