package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.config.NodeIdentity;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.WorkerNode;
import com.mobile.standalone.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行节点定时向控制面报活。同一进程 ALL 模式则直接写本地库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerHeartbeatAgent {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final NodeIdentity nodeIdentity;
    private final WorkerNodeService workerNodeService;
    private final DeviceMapper deviceMapper;

    @Scheduled(fixedDelayString = "${app.node.heartbeat-ms:15000}")
    public void beat() {
        if (!nodeIdentity.executesLocally()) {
            return;
        }
        try {
            WorkerNode node = snapshot();
            workerNodeService.upsertHeartbeat(node);
            if (nodeIdentity.hasRemoteControlPlane()) {
                postRemote(node);
            }
        } catch (Exception e) {
            log.warn("[worker] 心跳失败: {}", e.getMessage());
        }
    }

    private WorkerNode snapshot() throws Exception {
        WorkerNode node = new WorkerNode();
        node.setId(nodeIdentity.id());
        node.setDisplayName(nodeIdentity.displayName());
        node.setHostname(nodeIdentity.hostname());
        node.setRole(nodeIdentity.role());
        node.setBaseUrl(nodeIdentity.advertiseUrl());
        List<Map<String, Object>> devices = new ArrayList<>();
        for (Device d : deviceMapper.findAll()) {
            if (d.getWorkerId() != null && !d.getWorkerId().isBlank()
                    && !nodeIdentity.id().equals(d.getWorkerId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("name", d.getName());
            row.put("platform", d.getPlatform() == null ? null : d.getPlatform().name());
            row.put("status", d.getStatus() == null ? null : d.getStatus().name());
            devices.add(row);
        }
        node.setDevicesJson(JSON.writeValueAsString(devices));
        return node;
    }

    private void postRemote(WorkerNode node) {
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "id", node.getId(),
                    "displayName", node.getDisplayName(),
                    "hostname", node.getHostname(),
                    "role", node.getRole(),
                    "baseUrl", node.getBaseUrl() == null ? "" : node.getBaseUrl(),
                    "devicesJson", node.getDevicesJson() == null ? "[]" : node.getDevicesJson()
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(nodeIdentity.controlPlaneUrl() + "/worker-nodes/heartbeat"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[worker] 控制面心跳 HTTP {}: {}", resp.statusCode(),
                        resp.body() != null && resp.body().length() > 200
                                ? resp.body().substring(0, 200) : resp.body());
            }
        } catch (Exception e) {
            log.warn("[worker] 上报控制面失败: {}", e.getMessage());
        }
    }
}
