package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.handbook.HandbookCategories;
import com.mobile.standalone.service.ai.QwenChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 将粘贴的杂乱文字 / 截图汇总为知识手册短记（现象 / 原因 / 处理），不落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandbookAiSummarizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_CATEGORY = "app";

    private final QwenChatClient qwen;

    public boolean isConfigured() {
        return qwen.isConfigured();
    }

    /**
     * @param text         粘贴素材，可空
     * @param images       截图，可空；与 text 至少一项
     * @param hintCategory 当前页分类提示，可空
     * @param hintProject  当前选中的项目 code，可空
     * @return category/project/title/symptom/cause/fix/tags/model
     */
    public Map<String, Object> summarize(
            String text,
            List<QwenChatClient.Image> images,
            String hintCategory,
            String hintProject
    ) throws Exception {
        if (!qwen.isConfigured()) {
            throw new IllegalStateException("千问未配置：请在 application.yml 的 diagnosis.ai.api-key 填入密钥");
        }
        boolean hasText = text != null && !text.isBlank();
        boolean hasImages = images != null && !images.isEmpty();
        if (!hasText && !hasImages) {
            throw new IllegalArgumentException("请至少粘贴一段文字或上传一张截图");
        }

        String hint = HandbookCategories.normalize(hintCategory);
        if (hint == null) {
            hint = DEFAULT_CATEGORY;
        }
        String projectHint = hintProject == null || hintProject.isBlank() ? null : hintProject.trim();

        String raw = qwen.chat(prompt(hasText ? text.trim() : null, hasImages, hint, projectHint), images);
        Map<String, Object> draft = parseDraft(raw, hint, projectHint);
        draft.put("model", qwen.modelId());
        return draft;
    }

    String prompt(String text, boolean hasImages, String hintCategory, String hintProject) {
        String categoryList = HandbookCategories.LABELS.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("你是测试/研发排障经验助理。用户会丢来杂乱素材（日志、聊天、截图），");
        sb.append("请整理成「知识手册」短记：现象 / 原因 / 处理，各一两句即可，不要长文。\n\n");
        sb.append("【当前分类提示】").append(hintCategory)
                .append("（").append(HandbookCategories.labelOf(hintCategory)).append("）\n");
        sb.append("若素材明显属于其他领域，可改选更合适的 category；否则优先用提示分类。\n");
        if (hintProject != null) {
            sb.append("【归属项目提示】").append(hintProject)
                    .append("（来自平台项目管理；业务类短记应带上此 project）\n");
        }
        sb.append("【category 白名单】").append(categoryList).append('\n');
        if (hasImages) {
            sb.append("【截图】见随附图片。请依据图中真实信息归纳，不要臆造图中没有的内容。\n");
        }
        if (text != null) {
            sb.append("【文字素材】\n").append(text).append('\n');
        }
        sb.append("""

                【要求】
                1. title：一句话标题，便于列表识别。
                2. symptom：现象——遇到了什么（一两句）。
                3. cause：原因——为什么（一两句；不确定就写「待确认：…」）。
                4. fix：处理——怎么解决的（一两句；若素材未给解法，写「待补充」）。
                5. tags：可选，逗号分隔短标签，无则空字符串。
                6. category：必须是白名单中的 code。
                7. project：项目编码；有项目提示则原样回写，无则空字符串。

                【只输出 JSON，不要解释、不要 markdown 代码块】
                {
                  "category": "app",
                  "project": "",
                  "title": "标题",
                  "symptom": "现象",
                  "cause": "原因",
                  "fix": "处理",
                  "tags": "tag1,tag2"
                }
                """);
        return sb.toString();
    }

    Map<String, Object> parseDraft(String raw, String fallbackCategory, String fallbackProject) {
        JsonNode root = tryReadJson(stripFence(raw));
        if (root == null) {
            root = tryReadJson(sliceJson(raw));
        }
        if (root == null || !root.isObject()) {
            log.warn("[handbook-ai] 未解析出 JSON，原文前 500 字：{}", truncate(raw, 500));
            throw new IllegalStateException("模型没有返回可解析的短记，请补充文字或换一张更清晰的截图");
        }

        String title = textOf(root, "title");
        String symptom = textOf(root, "symptom");
        String cause = textOf(root, "cause");
        String fix = textOf(root, "fix");
        if (title.isBlank() || symptom.isBlank() || cause.isBlank() || fix.isBlank()) {
            throw new IllegalStateException("模型返回字段不完整（需 title/symptom/cause/fix）");
        }

        String cat = HandbookCategories.normalize(textOf(root, "category"));
        if (cat == null) {
            cat = fallbackCategory != null ? fallbackCategory : DEFAULT_CATEGORY;
        }

        String project = textOf(root, "project").trim();
        if (project.isBlank() && fallbackProject != null) {
            project = fallbackProject;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category", cat);
        out.put("project", project);
        out.put("title", truncate(title.trim(), 255));
        out.put("symptom", symptom.trim());
        out.put("cause", cause.trim());
        out.put("fix", fix.trim());
        String tags = textOf(root, "tags").trim();
        out.put("tags", tags.isBlank() ? "" : tags);
        return out;
    }

    private static String textOf(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return "";
        }
        return n.asText("");
    }

    private static JsonNode tryReadJson(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    static String stripFence(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int nl = s.indexOf('\n');
        if (nl < 0) {
            return s;
        }
        s = s.substring(nl + 1);
        int end = s.lastIndexOf("```");
        return end >= 0 ? s.substring(0, end).trim() : s.trim();
    }

    static String sliceJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
