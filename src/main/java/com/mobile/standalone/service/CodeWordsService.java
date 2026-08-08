package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.entity.KnowledgeHandbook;
import com.mobile.standalone.handbook.HandbookCategories;
import com.mobile.standalone.service.ai.QwenChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 助手与外部工作流代理。
 * <p>
 * 对话主路径已改为本地千问（{@link QwenChatClient}）；元素监控等工作流仍可走 CodeWords Runtime。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CodeWordsService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int HANDBOOK_HIT_LIMIT = 6;
    private static final int HISTORY_LIMIT = 12;

    private static final String CHAT_SYSTEM = """
            你是平台「AI 助手」，面向测试工程师。请用简洁中文回答。
            擅长：接口/APP/自动化排障、测试设计、知识手册短记（现象→原因→处理）。
            若下方提供了【知识手册检索结果】，请优先据此组织回答，并分条写清要点；无关则可忽略。
            不要编造手册里没有的具体处理步骤；不确定时明确说明。
            """;

    private final KnowledgeHandbookService knowledgeHandbookService;
    private final AgentStorageService agentStorageService;
    private final QwenChatClient qwen;

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

    @Value("${codewords.test-case-generator-id:test_case_generator_9e72b111}")
    private String testCaseGeneratorId;

    @Value("${codewords.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${app.agent-upload-dir:${user.home}/.mobile-standalone/uploads/agent-files}")
    private String agentUploadDir;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        // 前端「Agent 在线」以千问对话为准
        m.put("configured", qwen.isConfigured());
        m.put("chatProvider", "qwen");
        m.put("chatModel", qwen.isConfigured() ? qwen.modelId() : "");
        m.put("codewordsConfigured", isCodewordsConfigured());
        m.put("agentId", agentId);
        m.put("monitorWorkflowId", monitorWorkflowId);
        m.put("testCaseGeneratorId", testCaseGeneratorId);
        m.put("apiBase", trimSlash(apiBase));
        return m;
    }

    /**
     * AI 助手对话：千问 + 知识手册检索（不再走 CodeWords /run）。
     */
    public JsonNode chat(String message, String sessionId) {
        ensureQwenConfigured();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String userMsg = message.trim();
        String sid = (sessionId == null || sessionId.isBlank()) ? "user_yang" : sessionId.trim();

        List<KnowledgeHandbook> hits = searchHandbook(userMsg);
        String prompt = buildChatPrompt(userMsg, sid, hits);

        try {
            String answer = qwen.chat(prompt);
            ObjectNode result = JSON.createObjectNode();
            result.put("response", answer);
            result.put("provider", "qwen");
            result.put("model", qwen.modelId());
            result.put("session_id", sid);
            return attachHandbookHits(result, hits);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("千问调用失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
    }

    private String buildChatPrompt(String userMsg, String sessionId, List<KnowledgeHandbook> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append(CHAT_SYSTEM).append("\n\n");

        List<Map<String, Object>> history = loadRecentHistory(sessionId);
        if (!history.isEmpty()) {
            sb.append("【最近对话】\n");
            for (Map<String, Object> m : history) {
                String role = String.valueOf(m.getOrDefault("role", ""));
                String content = String.valueOf(m.getOrDefault("content", ""));
                if (content.isBlank()) {
                    continue;
                }
                if ("user".equals(role)) {
                    sb.append("用户：").append(truncate(content, 800)).append('\n');
                } else if ("assistant".equals(role)) {
                    sb.append("助手：").append(truncate(content, 800)).append('\n');
                }
            }
            sb.append('\n');
        }

        if (hits != null && !hits.isEmpty()) {
            sb.append(formatHandbookBlock(hits)).append('\n');
        }

        sb.append("【用户问题】\n").append(userMsg);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadRecentHistory(String sessionId) {
        try {
            Map<String, Object> conv = agentStorageService.loadConversation(sessionId);
            Object raw = conv == null ? null : conv.get("messages");
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> all = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> map) {
                    all.add((Map<String, Object>) map);
                }
            }
            if (all.size() <= HISTORY_LIMIT) {
                return all;
            }
            return all.subList(all.size() - HISTORY_LIMIT, all.size());
        } catch (Exception e) {
            log.debug("[chat] 加载会话历史跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /** 按用户问题关键词检索知识手册短记（失败不影响主对话） */
    private List<KnowledgeHandbook> searchHandbook(String userMsg) {
        try {
            List<String> queries = searchQueries(userMsg);
            if (queries.isEmpty()) {
                return List.of();
            }
            Map<Long, KnowledgeHandbook> merged = new LinkedHashMap<>();
            for (String q : queries) {
                List<KnowledgeHandbook> rows = knowledgeHandbookService.list(null, null, q, HANDBOOK_HIT_LIMIT);
                if (rows == null) {
                    continue;
                }
                for (KnowledgeHandbook h : rows) {
                    if (h.getId() != null) {
                        merged.putIfAbsent(h.getId(), h);
                    }
                }
                if (merged.size() >= HANDBOOK_HIT_LIMIT) {
                    break;
                }
            }
            return merged.values().stream().limit(HANDBOOK_HIT_LIMIT).toList();
        } catch (Exception e) {
            log.warn("[chat] 知识手册检索跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 生成若干检索词：整句往往匹配不上短记，辅以短前缀与领域词。
     */
    static List<String> searchQueries(String userMsg) {
        String s = extractSearchQuery(userMsg);
        if (s.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(s);
        for (int len : new int[]{12, 8, 4}) {
            if (s.length() > len) {
                out.add(s.substring(0, len));
            }
        }
        for (String kw : List.of(
                "接口", "API", "幂等", "越权", "限流", "状态机", "鉴权",
                "Appium", "Playwright", "鸿蒙", "K8S", "Docker", "Jenkins", "Redis"
        )) {
            if (s.toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))) {
                out.add(kw);
            }
        }
        return out.stream().map(String::trim).filter(t -> !t.isEmpty()).distinct().toList();
    }

    /**
     * 取用户问题里较有信息量的片段作 LIKE 关键词；过长截断，避免整段日志当 q。
     */
    static String extractSearchQuery(String userMsg) {
        if (userMsg == null) {
            return "";
        }
        String s = userMsg.trim();
        int marker = s.lastIndexOf("【用户问题】");
        if (marker >= 0) {
            s = s.substring(marker + "【用户问题】".length()).trim();
        }
        s = s.replaceAll("[？?！!。．\\s]+$", "");
        s = s.replaceAll("(要覆盖哪些点|怎么测|如何测|有哪些|是什么|怎么办|帮我看看)$", "");
        if (s.length() > 80) {
            s = s.substring(0, 80);
        }
        return s.trim();
    }

    private static String formatHandbookBlock(List<KnowledgeHandbook> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("【知识手册检索结果】\n");
        int i = 1;
        for (KnowledgeHandbook h : hits) {
            sb.append(i++).append(". ");
            sb.append('[').append(HandbookCategories.labelOf(h.getCategory()));
            if (h.getProject() != null && !h.getProject().isBlank()) {
                sb.append('/').append(h.getProject());
            }
            sb.append("] ").append(nullToEmpty(h.getTitle())).append('\n');
            sb.append("   现象：").append(nullToEmpty(h.getSymptom())).append('\n');
            sb.append("   原因：").append(nullToEmpty(h.getCause())).append('\n');
            sb.append("   处理：").append(nullToEmpty(h.getFix())).append('\n');
            if (h.getTags() != null && !h.getTags().isBlank()) {
                sb.append("   标签：").append(h.getTags().trim()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private JsonNode attachHandbookHits(JsonNode result, List<KnowledgeHandbook> hits) {
        ObjectNode out;
        if (result != null && result.isObject()) {
            out = ((ObjectNode) result).deepCopy();
        } else {
            out = JSON.createObjectNode();
            if (result != null) {
                out.set("response", result);
            }
        }
        ArrayNode arr = out.putArray("handbookHits");
        if (hits != null) {
            for (KnowledgeHandbook h : hits) {
                ObjectNode n = arr.addObject();
                if (h.getId() != null) {
                    n.put("id", h.getId());
                }
                n.put("category", nullToEmpty(h.getCategory()));
                n.put("categoryLabel", HandbookCategories.labelOf(h.getCategory()));
                if (h.getProject() != null) {
                    n.put("project", h.getProject());
                }
                n.put("title", nullToEmpty(h.getTitle()));
                n.put("symptom", nullToEmpty(h.getSymptom()));
                n.put("cause", nullToEmpty(h.getCause()));
                n.put("fix", nullToEmpty(h.getFix()));
                if (h.getTags() != null) {
                    n.put("tags", h.getTags());
                }
            }
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private void ensureQwenConfigured() {
        if (!qwen.isConfigured()) {
            throw new IllegalStateException("千问未配置：请在 application.yml 的 diagnosis.ai.api-key 填入密钥");
        }
    }

    public JsonNode monitor(String url, Boolean schedule) {
        ensureCodewordsConfigured();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("url", url.trim());
        body.put("schedule", schedule == null || schedule);
        return postRun(monitorWorkflowId, body);
    }

    /**
     * 测试用例生成器 v2：text_content / images / elements 至少一种。
     * images 支持 data URI；本地 /agent-uploads/ 或 localhost URL 会自动读盘转 base64。
     */
    public JsonNode generateTestCases(String pageName, JsonNode elements,
                                      String screenshotUrl, String requirements,
                                      String textContent, JsonNode images) {
        ensureCodewordsConfigured();
        List<String> imageDataUris = resolveImagesToDataUris(images, screenshotUrl);
        boolean hasElements = elements != null && elements.isArray() && !elements.isEmpty();
        boolean hasImages = !imageDataUris.isEmpty();
        boolean hasText = textContent != null && !textContent.isBlank();
        if (!hasElements && !hasImages && !hasText) {
            throw new IllegalArgumentException("请至少提供 text_content、images、elements 或上传图片之一");
        }

        String resolvedPage = (pageName == null || pageName.isBlank() || "测试页面".equals(pageName.trim()))
                ? null
                : pageName.trim();
        if (resolvedPage == null) {
            resolvedPage = guessPageNameFromText(textContent);
        }
        if (resolvedPage == null || resolvedPage.isBlank()) {
            resolvedPage = hasImages ? "界面截图页" : "未命名页面";
        }

        ObjectNode body = JSON.createObjectNode();
        body.put("page_name", resolvedPage);
        if (hasText) {
            body.put("text_content", textContent.trim());
        } else if (hasImages) {
            body.put("text_content", "请根据上传的界面截图生成完整测试用例（含正常流与异常流）");
        }
        if (hasImages) {
            ArrayNode arr = body.putArray("images");
            for (String uri : imageDataUris) {
                arr.add(uri);
            }
            // 不再发公网/本机 URL；首图已是 data URI，screenshot_url 同传兼容旧字段
            body.put("screenshot_url", imageDataUris.get(0));
        }
        if (hasElements) {
            body.set("elements", elements);
        } else {
            var stub = body.putArray("elements");
            var el = stub.addObject();
            el.put("element_name", "界面截图/需求文档");
            el.put("element_type", "screen");
            el.put("resource_id", "");
            el.put("note", "由 text_content / images(base64) 驱动生成");
        }
        if (requirements != null && !requirements.isBlank()) {
            body.put("requirements", requirements.trim());
        }
        log.info("[codewords] generateTestCases page={} images={} textChars={}",
                resolvedPage, imageDataUris.size(),
                textContent == null ? 0 : textContent.length());
        return postRun(testCaseGeneratorId, body);
    }

    /** Multipart：文件直接转 base64 data URI 后调生成器 */
    public JsonNode generateTestCasesFromUpload(String pageName, String textContent,
                                                String requirements, MultipartFile[] files) {
        ensureCodewordsConfigured();
        List<String> dataUris = new ArrayList<>();
        StringBuilder docText = new StringBuilder();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
                String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
                boolean image = ct.startsWith("image/")
                        || name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp)$");
                try {
                    if (image) {
                        dataUris.add(multipartToDataUri(file));
                    } else if (AgentDocumentParseService.isSupported(name)) {
                        // 文档类：交给调用方先抽文；此处仅记录文件名提示
                        docText.append("[").append(name).append("]\n");
                    } else {
                        // 尝试当图片读
                        dataUris.add(multipartToDataUri(file));
                    }
                } catch (Exception e) {
                    throw new IllegalArgumentException("处理文件失败 " + name + ": " + e.getMessage());
                }
            }
        }
        String text = textContent;
        if (docText.length() > 0) {
            text = (text == null || text.isBlank()) ? docText.toString() : text + "\n\n" + docText;
        }
        ArrayNode images = JSON.createArrayNode();
        for (String u : dataUris) images.add(u);
        return generateTestCases(pageName, null, null, requirements, text, images.isEmpty() ? null : images);
    }

    /** 兼容旧调用 */
    public JsonNode generateTestCases(String pageName, JsonNode elements,
                                      String screenshotUrl, String requirements) {
        return generateTestCases(pageName, elements, screenshotUrl, requirements, null, null);
    }

    private List<String> resolveImagesToDataUris(JsonNode images, String screenshotUrl) {
        List<String> out = new ArrayList<>();
        if (images != null && images.isArray()) {
            for (JsonNode n : images) {
                if (n == null || n.isNull()) continue;
                String s = n.asText(null);
                if (s == null || s.isBlank()) continue;
                out.add(toDataUri(s.trim()));
            }
        }
        if (out.isEmpty() && screenshotUrl != null && !screenshotUrl.isBlank()) {
            out.add(toDataUri(screenshotUrl.trim()));
        }
        return out;
    }

    /**
     * URL / 相对路径 → data URI；已是 data: 则原样返回。
     */
    public String toDataUri(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("图片路径为空");
        }
        String s = raw.trim();
        if (s.startsWith("data:image/")) {
            return s;
        }
        Path file = resolveLocalImagePath(s);
        if (file == null || !Files.isRegularFile(file)) {
            // 非本机可读路径：若是 http(s) 公网 URL 原样转发（CodeWords 或可拉取）
            if (s.startsWith("http://") || s.startsWith("https://")) {
                if (s.contains("127.0.0.1") || s.contains("localhost")) {
                    throw new IllegalArgumentException(
                            "本机图片 URL 无法被 CodeWords 拉取，请走上传（将自动转 base64）：" + truncate(s, 80));
                }
                log.warn("[codewords] 图片仍为远程 URL（未转 base64）: {}", truncate(s, 120));
                return s;
            }
            throw new IllegalArgumentException("找不到本地图片文件: " + s);
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String mime = mimeFromPath(file);
            String b64 = Base64.getEncoder().encodeToString(bytes);
            log.info("[codewords] 本地图转 base64: {} ({} bytes, {})", file.getFileName(), bytes.length, mime);
            return "data:" + mime + ";base64," + b64;
        } catch (Exception e) {
            throw new IllegalStateException("读取图片失败: " + file + " — " + e.getMessage(), e);
        }
    }

    public String multipartToDataUri(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        String mime = file.getContentType();
        if (mime == null || mime.isBlank() || !mime.startsWith("image/")) {
            String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
            mime = mimeFromName(name);
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private Path resolveLocalImagePath(String s) {
        // /api/agent-uploads/xxx 或 /agent-uploads/xxx
        String path = s;
        int idx = path.indexOf("/agent-uploads/");
        if (idx >= 0) {
            String fileName = path.substring(idx + "/agent-uploads/".length());
            int q = fileName.indexOf('?');
            if (q >= 0) fileName = fileName.substring(0, q);
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                return null;
            }
            return Paths.get(agentUploadDir).toAbsolutePath().normalize().resolve(fileName);
        }
        // 纯文件名
        if (!path.contains("/") && !path.contains("\\") && path.contains(".")) {
            Path p = Paths.get(agentUploadDir).toAbsolutePath().normalize().resolve(path);
            if (Files.isRegularFile(p)) return p;
        }
        // 绝对本地路径
        if (path.startsWith("/") && !path.startsWith("/api/") && !path.startsWith("http")) {
            Path p = Paths.get(path);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static String mimeFromPath(Path file) {
        return mimeFromName(file.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private static String mimeFromName(String name) {
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    private static String guessPageNameFromText(String text) {
        if (text == null || text.isBlank()) return null;
        var m = java.util.regex.Pattern
                .compile("(?:页面|模块|功能)[：:]\\s*([^\\n，,。]{2,40})")
                .matcher(text);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private JsonNode postRun(String workflowId, ObjectNode body) {
        String endpoint = trimSlash(apiBase) + "/run/" + workflowId;
        try {
            String payload = JSON.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(Math.max(60, timeoutSeconds)))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            log.info("[codewords] POST {} payload={}", endpoint, truncateForLog(payload, 240));
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

    private void ensureCodewordsConfigured() {
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

    private boolean isCodewordsConfigured() {
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

    /** 日志里把 base64 截短，避免刷屏 */
    private static String truncateForLog(String s, int max) {
        if (s == null) return "";
        String redacted = s.replaceAll("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+",
                "data:image/...;base64,<omitted>");
        return truncate(redacted, max);
    }
}
