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
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * PushPlus 一对一推送到个人微信。
 * <p>
 * 文档：https://www.pushplus.plus/doc/guide/api.html
 */
@Service
@Slf4j
public class PushPlusNotifyService {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${app.online-regression.alert.enabled:false}")
    private boolean enabled;

    @Value("${app.online-regression.alert.pushplus-token:}")
    private String token;

    @Value("${app.online-regression.alert.pushplus-api:https://www.pushplus.plus/send}")
    private String apiUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isReady() {
        return enabled && token != null && !token.isBlank();
    }

    /**
     * 发送文本消息；失败只打日志，不影响回归主流程。
     *
     * @return true 表示 PushPlus 返回成功
     */
    public boolean sendText(String title, String content) {
        if (!isReady()) {
            log.debug("PushPlus 未启用或未配置 token，跳过推送");
            return false;
        }
        String t = title == null ? "" : title.trim();
        String c = content == null ? "" : content.trim();
        if (t.isEmpty() && c.isEmpty()) {
            return false;
        }
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("token", token.trim());
            body.put("title", t.isEmpty() ? "线上回归告警" : t);
            body.put("content", c.isEmpty() ? t : c);
            body.put("template", "txt");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl.trim()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = JSON.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            if (code == 200) {
                log.info("PushPlus 推送成功: title={}", t);
                return true;
            }
            log.warn("PushPlus 推送失败: http={}, code={}, body={}",
                    resp.statusCode(), code, truncate(resp.body(), 300));
            return false;
        } catch (Exception e) {
            log.warn("PushPlus 推送异常: {}", e.getMessage());
            return false;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
