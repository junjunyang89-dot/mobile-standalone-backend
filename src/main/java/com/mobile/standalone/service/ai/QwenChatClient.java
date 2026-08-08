package com.mobile.standalone.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 千问通用对话客户端（DashScope 兼容 OpenAI 接口），文本 + 可选图片。
 * <p>
 * 抽出来是因为归因和用例生成都要调同一个模型，而这里唯一有技术含量的部分——
 * <b>必须走流式</b>——不该被抄第二遍：推理模型在长提示词上会先生成大量推理 token，
 * 非流式要等全部生成完才返回第一个字节，实测 300 秒都等不到，中间网关先把连接断了。
 * 流式下首字节很快到达，请求超时只约束到响应头，后面边收边攒。
 * <p>
 * 只取 {@code delta.content}，不取 {@code delta.reasoning_content}：后者是模型的思考过程，
 * 混进来会把要解析的 JSON 冲垮。
 */
@Slf4j
@Component
public class QwenChatClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MODEL = "qwen3.8-max";

    /** 复用归因那套配置：同一个供应商、同一把 key，没必要让人在两处各填一遍 */
    @Value("${diagnosis.ai.api-key:}")
    private String apiKey;

    @Value("${diagnosis.ai.model:}")
    private String model;

    @Value("${diagnosis.ai.qwen.api-base:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String apiBase;

    @Value("${diagnosis.ai.timeout-seconds:300}")
    private int timeoutSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String modelId() {
        return model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    /** 一张图：原始字节 + MIME，转成 data URI 发出去 */
    public record Image(byte[] bytes, String contentType) {
    }

    public String chat(String prompt) throws Exception {
        return chat(prompt, List.of());
    }

    /**
     * 发一轮对话，返回模型正文。
     *
     * @param images 可为空；非空时模型必须具备视觉能力，否则接口会报 invalid_parameter_error
     */
    public String chat(String prompt, List<Image> images) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "千问未配置：请在 application.yml 的 diagnosis.ai.api-key 填入密钥");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(apiBase) + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(Math.max(30, timeoutSeconds)))
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(buildRequest(prompt, images)), StandardCharsets.UTF_8))
                .build();

        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() / 100 != 2) {
            String err = response.body().limit(20).collect(Collectors.joining("\n"));
            throw new IllegalStateException("千问返回 " + response.statusCode() + ": " + truncate(err, 500));
        }
        return accumulate(response.body());
    }

    ObjectNode buildRequest(String prompt, List<Image> images) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", modelId());
        body.put("stream", true);

        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        if (images == null || images.isEmpty()) {
            message.put("content", prompt);
            return body;
        }
        // 有图时 content 必须是数组形态，纯字符串会被当成没有图片
        ArrayNode content = message.putArray("content");
        for (Image img : images) {
            if (img == null || img.bytes() == null || img.bytes().length == 0) {
                continue;
            }
            ObjectNode part = content.addObject();
            part.put("type", "image_url");
            part.putObject("image_url").put("url", dataUri(img));
        }
        content.addObject().put("type", "text").put("text", prompt);
        return body;
    }

    /** 累积流式返回里的正文；只取 delta.content */
    String accumulate(Stream<String> lines) {
        StringBuilder content = new StringBuilder();
        String finishReason = null;
        for (String line : (Iterable<String>) lines::iterator) {
            if (line == null || !line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonNode chunk;
            try {
                chunk = JSON.readTree(payload);
            } catch (Exception e) {
                continue;
            }
            JsonNode choice = chunk.path("choices").path(0);
            JsonNode piece = choice.path("delta").path("content");
            if (piece.isTextual()) {
                content.append(piece.asText());
            }
            String fr = choice.path("finish_reason").asText(null);
            if (fr != null && !fr.isBlank()) {
                finishReason = fr;
            }
        }
        if (content.isEmpty()) {
            throw new IllegalStateException("千问未返回内容"
                    + (finishReason == null ? "" : "，finish_reason=" + finishReason));
        }
        return content.toString();
    }

    private static String dataUri(Image img) {
        String mime = img.contentType() == null || img.contentType().isBlank()
                ? "image/png" : img.contentType().trim();
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(img.bytes());
    }

    private static String trimSlash(String s) {
        String v = s == null ? "" : s.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
