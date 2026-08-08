package com.mobile.standalone.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;
import com.mobile.standalone.util.ScreenshotDownscaler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 阿里云千问（DashScope）归因调用，走 OpenAI 兼容接口。
 * <p>
 * 前端不直连，密钥只留服务端——与 CodeWords 代理同一个路子。
 * <p>
 * 必须用带视觉能力的模型（qwen-vl 系列）：归因要看截图判断页面停在哪、有没有被弹窗盖住，
 * 纯文本模型收到图片部分会直接报错或静默忽略。
 */
@Service
@ConditionalOnProperty(name = "diagnosis.ai.provider", havingValue = "qwen", matchIfMissing = true)
@Slf4j
public class QwenDiagnosisClient implements DiagnosisModelClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MODEL = "qwen-vl-max";


    @Value("${diagnosis.ai.enabled:false}")
    private boolean enabled;

    /** 通用配置项：换供应商时不用挪 key 的位置 */
    @Value("${diagnosis.ai.api-key:}")
    private String apiKey;

    /**
     * 留空则用 {@value #DEFAULT_MODEL}。
     * <p>
     * 模型名迭代很快，写死会在某次下线后让整条链路静默失效；判不准时换模型也该是改一行配置的事。
     */
    @Value("${diagnosis.ai.model:}")
    private String model;

    /** Token Plan 等订阅形态若用别的接入点，改这里即可 */
    @Value("${diagnosis.ai.qwen.api-base:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String apiBase;

    /** 最多上传几张截图。默认 0：判据主要在控件树上，带图会让单次判断慢一个数量级 */
    @Value("${diagnosis.ai.max-images:0}")
    private int maxImages;

    @Value("${diagnosis.ai.image-max-edge:1024}")
    private int imageMaxEdge;

    /**
     * 单次调用超时。
     * <p>
     * 推理模型在近万字的提示词加一张截图上要想很久，实测 120 秒不够——而归因是后台异步任务，
     * 没有任何人在等它，卡死的风险远小于超时导致每次归因都白跑。
     */
    @Value("${diagnosis.ai.timeout-seconds:300}")
    private int timeoutSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String modelId() {
        return model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    @Override
    public Verdict diagnose(String prompt, List<Path> screenshots) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("归因模型未配置：需要 diagnosis.ai.enabled=true 且填入 api-key");
        }
        ObjectNode body = buildRequest(prompt, screenshots);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(apiBase) + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(Math.max(30, timeoutSeconds)))
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() / 100 != 2) {
            String err = response.body().limit(20).collect(Collectors.joining("\n"));
            throw new IllegalStateException("千问返回 " + response.statusCode() + ": " + truncate(err, 500));
        }
        return parseStream(response.body());
    }

    /**
     * 累积流式返回里的正文。
     * <p>
     * 必须走流式：推理模型在近万字提示词上会先生成大量推理 token，非流式要等全部生成完才返回
     * 第一个字节，实测 300 秒都等不到——中间网关先把连接断了。流式下首字节很快到达，
     * 请求超时只约束到响应头，后面边收边攒。
     * <p>
     * 只取 {@code delta.content}，不要 {@code delta.reasoning_content}：后者是模型的思考过程，
     * 混进来会把要解析的 JSON 冲垮。
     */
    Verdict parseStream(Stream<String> lines) throws Exception {
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
        return DiagnosisVerdictParser.parse(content.toString(), modelId());
    }

    ObjectNode buildRequest(String prompt, List<Path> screenshots) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", modelId());
        // 归因要的是稳定复现的判断，不是多样性
        body.put("temperature", 0);

        // 必须流式，理由见 parseStream
        body.put("stream", true);

        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");

        int used = 0;
        for (Path shot : screenshots) {
            if (used >= Math.max(0, maxImages)) {
                break;
            }
            try {
                ScreenshotDownscaler.Scaled scaled = ScreenshotDownscaler.scale(shot, imageMaxEdge);
                ObjectNode part = content.addObject();
                part.put("type", "image_url");
                part.putObject("image_url").put("url", "data:" + scaled.mimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(scaled.bytes()));
                used++;
            } catch (Exception e) {
                // 少一张图还能靠控件树判，为此让整次归因失败不划算
                log.warn("截图压缩失败，跳过 {}: {}", shot, e.getMessage());
            }
        }

        // 文本放在图片之后：视觉模型普遍对「先看图、再读要求」这个顺序响应更好
        content.addObject()
                .put("type", "text")
                .put("text", prompt + DiagnosisVerdictParser.jsonOutputInstruction());
        return body;
    }

    private static String trimSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
