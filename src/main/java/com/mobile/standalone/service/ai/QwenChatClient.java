package com.mobile.standalone.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 通用对话客户端，文本 + 可选图片。默认走千问（DashScope 兼容 OpenAI 接口），
 * {@code ai.chat.provider=openai} 时改走 ChatGPT——两家是同一套接口，只换地址、密钥、模型和代理。
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

    /** qwen | openai */
    @Value("${ai.chat.provider:qwen}")
    private String provider;

    @Autowired(required = false)
    private OpenAiSettings openAi;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public boolean usesOpenAi() {
        return provider != null && "openai".equalsIgnoreCase(provider.trim()) && openAi != null;
    }

    /** 报错和页面提示里用的供应商名 */
    public String providerLabel() {
        return usesOpenAi() ? "ChatGPT" : "千问";
    }

    /** 没配密钥时给人看的提示，按当前供应商指到该填的那一项 */
    public String notConfiguredMessage() {
        return usesOpenAi()
                ? "ChatGPT 没配：application.yml 里 ai.openai.api-key 填上密钥"
                : "千问没配：application.yml 里 diagnosis.ai.api-key 填上密钥";
    }

    public boolean isConfigured() {
        if (usesOpenAi()) {
            return openAi.isConfigured();
        }
        return apiKey != null && !apiKey.isBlank();
    }

    public String modelId() {
        if (usesOpenAi()) {
            return openAi.model();
        }
        return model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    /**
     * 调用方指定的模型（如 {@code ai-case.model}）多半是千问的名字；切到 ChatGPT 后原样发过去必然 404，
     * 所以千问系的名字在这里丢掉，退回 ChatGPT 的默认模型。
     */
    String effectiveModel(String modelOverride) {
        if (modelOverride == null || modelOverride.isBlank()) {
            return modelId();
        }
        String m = modelOverride.trim();
        if (usesOpenAi() && m.toLowerCase().startsWith("qwen")) {
            return modelId();
        }
        return m;
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
        return chat(prompt, images, null);
    }

    /** @param modelOverride 这一轮换个模型；空则用配置的 */
    public String chat(String prompt, List<Image> images, String modelOverride) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException(notConfiguredMessage());
        }
        boolean openai = usesOpenAi();
        String base = openai ? openAi.apiBase() : trimSlash(apiBase);
        String key = openai ? openAi.apiKey() : apiKey;
        HttpClient client = openai ? openAi.http() : http;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .timeout(Duration.ofSeconds(Math.max(30, timeoutSeconds)))
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(buildRequest(prompt, images, modelOverride)), StandardCharsets.UTF_8))
                .build();

        HttpResponse<Stream<String>> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            throw new IllegalStateException(providerLabel() + " 连不上：" + base
                    + (openai ? "（国内一般要配 ai.openai.proxy）" : ""), e);
        }
        if (response.statusCode() / 100 != 2) {
            String err = response.body().limit(20).collect(Collectors.joining("\n"));
            throw new IllegalStateException(providerLabel() + " 返回 " + response.statusCode() + ": " + truncate(err, 500));
        }
        return accumulate(response.body());
    }

    ObjectNode buildRequest(String prompt, List<Image> images) {
        return buildRequest(prompt, images, null);
    }

    ObjectNode buildRequest(String prompt, List<Image> images, String modelOverride) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", effectiveModel(modelOverride));
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
            throw new IllegalStateException(providerLabel() + " 未返回内容"
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
