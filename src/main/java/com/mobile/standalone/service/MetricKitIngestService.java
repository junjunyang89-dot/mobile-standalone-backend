package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * iOS MetricKit 测试包上报缓存：App POST JSON 后，读 performance-report 时合并进 metricKitSummary。
 * 数据按 taskId 落盘，便于任务结束后迟到的 payload 仍能展示。
 */
@Service
@Slf4j
public class MetricKitIngestService {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${performance.metrickit.store-dir:${user.home}/.mobile-standalone/metrickit}")
    private String storeDir;

    private final Map<String, JsonNode> memory = new ConcurrentHashMap<>();

    public void ingest(String taskId, JsonNode payload) {
        if (taskId == null || taskId.isBlank() || payload == null) {
            throw new IllegalArgumentException("taskId 与 payload 不能为空");
        }
        ObjectNode wrap = JSON.createObjectNode();
        wrap.put("receivedAt", Instant.now().toString());
        wrap.put("taskId", taskId);
        wrap.set("payload", payload);
        memory.put(taskId, wrap);
        try {
            Path dir = Paths.get(storeDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(safeName(taskId) + ".json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), wrap);
            log.info("MetricKit 已接收 taskId={} bytes≈{}", taskId, payload.toString().length());
        } catch (Exception e) {
            log.warn("MetricKit 落盘失败（内存仍保留）taskId={}: {}", taskId, e.getMessage());
        }
    }

    public JsonNode get(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        JsonNode mem = memory.get(taskId);
        if (mem != null) return mem;
        try {
            Path file = Paths.get(storeDir).toAbsolutePath().normalize().resolve(safeName(taskId) + ".json");
            if (Files.isRegularFile(file)) {
                JsonNode n = JSON.readTree(file.toFile());
                memory.put(taskId, n);
                return n;
            }
        } catch (Exception e) {
            log.debug("读取 MetricKit 缓存失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将缓存摘要合并进已解析的报告 ObjectNode（若有）。
     */
    public Object mergeIntoReport(String taskId, Object reportObj) {
        JsonNode mk = get(taskId);
        if (mk == null) return reportObj;
        try {
            ObjectNode root;
            if (reportObj instanceof ObjectNode on) {
                root = on;
            } else if (reportObj instanceof Map || reportObj instanceof String) {
                root = (ObjectNode) JSON.valueToTree(reportObj);
            } else {
                root = (ObjectNode) JSON.valueToTree(reportObj);
            }
            root.set("metricKitSummary", mk);
            return root;
        } catch (Exception e) {
            log.debug("合并 MetricKit 到报告失败: {}", e.getMessage());
            return reportObj;
        }
    }

    private static String safeName(String taskId) {
        return taskId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
