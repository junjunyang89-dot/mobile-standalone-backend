package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CodeWords Runtime 代理：前端不直连，密钥仅留在服务端。
 */
@Service
@Slf4j
public class CodeWordsService {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${codewords.enabled:false}")
    private boolean enabled;

    @Value("${codewords.api-base:https://runtime.codewords.ai}")
    private String apiBase;

    @Value("${codewords.api-key:}")
    private String apiKey;

    @Value("${codewords.agent-id:workflow_skeleton_85e9599e}")
    private String agentId;

    @Value("${codewords.monitor-workflow-id:csgo_element_monitor_b69770d5}")
    private String monitorWorkflowId;

    @Value("${codewords.timeout-seconds:90}")
    private int timeoutSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("configured", isConfigured());
        m.put("agentId", agentId);
        m.put("monitorWorkflowId", monitorWorkflowId);
        m.put("apiBase", trimSlash(apiBase));
        return m;
    }

    public JsonNode chat(String message, String sessionId) {
        ensureConfigured();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? "user_yang" : sessionId.trim();
        ObjectNode body = JSON.createObjectNode();
        body.put("message", message.trim());
        body.put("session_id", sid);
        return postRun(agentId, body);
    }

    public JsonNode monitor(String url, Boolean schedule) {
        ensureConfigured();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("url", url.trim());
        body.put("schedule", schedule == null || schedule);
        return postRun(monitorWorkflowId, body);
    }

    private JsonNode postRun(String workflowId, ObjectNode body) {
        String endpoint = trimSlash(apiBase) + "/run/" + workflowId;
        try {
            String payload = JSON.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(Math.max(30, timeoutSeconds)))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            log.info("[codewords] POST {} payload={}", endpoint, truncate(payload, 200));
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String respBody = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("CodeWords 调用失败 HTTP " + resp.statusCode() + ": "
                        + truncate(respBody, 500));
            }
            if (respBody.isBlank()) {
                return JSON.createObjectNode();
            }
            return JSON.readTree(respBody);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("CodeWords 请求异常: " + e.getMessage(), e);
        }
    }

    private void ensureConfigured() {
        if (!enabled) {
            throw new IllegalStateException("CodeWords 未启用：请在 application.yml 设置 codewords.enabled=true");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置 CodeWords API Key：请设置环境变量 CODEWORDS_API_KEY=cwk-xxxx 后重启后端");
        }
        if (!apiKey.trim().startsWith("cwk-")) {
            throw new IllegalStateException("CodeWords API Key 格式异常，应以 cwk- 开头");
        }
    }

    private boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank() && apiKey.trim().startsWith("cwk-");
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
