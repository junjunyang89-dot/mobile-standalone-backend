package com.mobile.standalone.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
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
import java.util.Locale;

/**
 * Gemini 归因调用。前端不直连，密钥只留服务端——与 CodeWords 代理同一个路子。
 * <p>
 * 用 responseSchema 约束成结构化输出，而不是让它自由发挥再去解析文本：
 * 归因结论要落库、要驱动动作，解析失败就等于这次归因白跑了。
 */
@Service
@ConditionalOnProperty(name = "diagnosis.ai.provider", havingValue = "gemini")
@Slf4j
public class GeminiDiagnosisClient implements DiagnosisModelClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";


    @Value("${diagnosis.ai.enabled:false}")
    private boolean enabled;

    /** 通用配置项：换供应商时不用挪 key 的位置 */
    @Value("${diagnosis.ai.api-key:}")
    private String apiKey;

    /**
     * 模型 ID 走配置，不写死。
     * <p>
     * Gemini 的模型名迭代很快，写死会在某次下线后整条链路静默失效；
     * 而且判不准时换模型应该是改一行配置的事。
     */
    @Value("${diagnosis.ai.model:}")
    private String model;

    @Value("${diagnosis.ai.gemini.api-base:https://generativelanguage.googleapis.com/v1beta}")
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
                .uri(URI.create(trimSlash(apiBase) + "/models/" + modelId() + ":generateContent"))
                .header("Content-Type", "application/json")
                // key 放 header 而不是 query，避免出现在日志与 URL 里
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(Math.max(30, timeoutSeconds)))
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Gemini 返回 " + response.statusCode() + ": "
                    + truncate(response.body(), 500));
        }
        return parseVerdict(response.body());
    }

    ObjectNode buildRequest(String prompt, List<Path> screenshots) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode parts = body.putArray("contents").addObject().putArray("parts");
        parts.addObject().put("text", prompt);

        int used = 0;
        for (Path shot : screenshots) {
            if (used >= Math.max(0, maxImages)) {
                break;
            }
            try {
                ScreenshotDownscaler.Scaled scaled = ScreenshotDownscaler.scale(shot, imageMaxEdge);
                ObjectNode inline = parts.addObject().putObject("inline_data");
                inline.put("mime_type", scaled.mimeType());
                inline.put("data", Base64.getEncoder().encodeToString(scaled.bytes()));
                used++;
            } catch (Exception e) {
                // 少一张图还能靠控件树判，为此让整次归因失败不划算
                log.warn("截图压缩失败，跳过 {}: {}", shot, e.getMessage());
            }
        }

        ObjectNode config = body.putObject("generationConfig");
        // 归因要的是稳定复现的判断，不是多样性
        config.put("temperature", 0);
        config.put("responseMimeType", "application/json");
        config.set("responseSchema", verdictSchema());
        return body;
    }

    /** 结构化输出约束：让模型只能返回这几个字段，省掉解析自由文本的不确定性 */
    ObjectNode verdictSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "OBJECT");
        ObjectNode props = schema.putObject("properties");

        ObjectNode category = props.putObject("category");
        category.put("type", "STRING");
        ArrayNode categoryEnum = category.putArray("enum");
        for (Category c : Category.values()) {
            categoryEnum.add(c.name());
        }
        category.put("description", "证据不足时必须选 UNKNOWN，不要用剩下的证据硬凑");

        props.putObject("confidence").put("type", "INTEGER").put("description", "0-100");
        props.putObject("reason").put("type", "STRING")
                .put("description", "判断依据，指明看的是哪条日志/哪个控件/哪张图");
        props.putObject("suggestion").put("type", "STRING").put("description", "建议的处理动作");
        props.putObject("suggestedLocatorType").put("type", "STRING")
                .put("description", "仅 ELEMENT_BROKEN 时填，如 xpath / id；其余情况留空");
        props.putObject("suggestedLocatorValue").put("type", "STRING")
                .put("description", "仅 ELEMENT_BROKEN 时填，你认为正确的定位串；其余情况留空");
        props.putObject("categoryMismatch").put("type", "STRING")
                .put("description", "六个分类都不合适时，在这里描述你认为的真实类别；合适就留空");

        ArrayNode required = schema.putArray("required");
        required.add("category");
        required.add("confidence");
        required.add("reason");
        return schema;
    }

    Verdict parseVerdict(String responseBody) throws Exception {
        JsonNode root = JSON.readTree(responseBody);
        JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (text.isMissingNode() || text.asText("").isBlank()) {
            String finishReason = root.path("candidates").path(0).path("finishReason").asText("");
            throw new IllegalStateException("Gemini 未返回内容"
                    + (finishReason.isBlank() ? "" : "，finishReason=" + finishReason));
        }
        return DiagnosisVerdictParser.parse(text.asText(), modelId());
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
