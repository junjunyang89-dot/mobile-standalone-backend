package com.mobile.standalone.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Source;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 把模型返回的 JSON 转成能落库的结论。
 * <p>
 * 各家模型的响应外壳不一样，但内层这份结论 JSON 是同一套约定，所以解析放这里共用——
 * 换供应商时只该动外壳，不该重写一遍字段映射。
 * <p>
 * 解析一律走宽松策略：模型换版本后吐出没见过的枚举值、把 JSON 包在 markdown 代码块里，
 * 这些都不该让整次归因白跑。
 */
@Slf4j
public final class DiagnosisVerdictParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DiagnosisVerdictParser() {
    }

    /**
     * 追加在提示词末尾，要求模型输出结构化 JSON。
     * <p>
     * 给 Gemini 用的是 responseSchema，能在服务端强约束；没有这类能力的接口就靠这段文字，
     * 所以字段名和取值范围都要写死说清楚。
     */
    public static String jsonOutputInstruction() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n---\n\n## 输出格式（重要）\n\n");
        sb.append("只输出一个 JSON 对象，不要有任何解释文字，不要包在 markdown 代码块里。字段如下：\n\n");
        sb.append("```\n{\n");
        sb.append("  \"category\": \"");
        for (int i = 0; i < Category.values().length; i++) {
            sb.append(i > 0 ? " | " : "").append(Category.values()[i].name());
        }
        sb.append("\",\n");
        sb.append("  \"confidence\": 0-100 的整数,\n");
        sb.append("  \"reason\": \"判断依据，指明看的是哪条日志/哪个控件/哪张图\",\n");
        sb.append("  \"suggestion\": \"建议的处理动作\",\n");
        sb.append("  \"suggestedLocatorType\": \"仅 ELEMENT_BROKEN 时填，如 xpath / id；其余留空字符串\",\n");
        sb.append("  \"suggestedLocatorValue\": \"仅 ELEMENT_BROKEN 时填，你认为正确的定位串；其余留空字符串\",\n");
        sb.append("  \"categoryMismatch\": \"六类都不合适时描述你认为的真实类别；合适就留空字符串\"\n");
        sb.append("}\n```\n");
        return sb.toString();
    }

    /**
     * @param modelId 写进结论，便于事后对比不同模型的准确率
     */
    public static Verdict parse(String rawJson, String modelId) throws Exception {
        JsonNode v = JSON.readTree(stripCodeFence(rawJson));

        Verdict verdict = new Verdict();
        verdict.setCategory(parseCategory(v.path("category").asText(null)));
        verdict.setConfidence(v.hasNonNull("confidence") ? v.get("confidence").asInt() : null);
        verdict.setReason(blankToNull(v.path("reason").asText(null)));
        verdict.setSuggestion(blankToNull(v.path("suggestion").asText(null)));
        verdict.setSuggestedLocatorType(blankToNull(v.path("suggestedLocatorType").asText(null)));
        verdict.setSuggestedLocatorValue(blankToNull(v.path("suggestedLocatorValue").asText(null)));
        verdict.setSource(Source.AI);
        verdict.setModel(modelId);

        // 分类不够用是拿来改分类的信号，埋在日志里没人看——并进 reason 让人在结论上直接看见
        String mismatch = blankToNull(v.path("categoryMismatch").asText(null));
        if (mismatch != null) {
            verdict.setReason("【模型认为六类都不合适：" + mismatch + "】"
                    + (verdict.getReason() != null ? " " + verdict.getReason() : ""));
        }
        return verdict;
    }

    /**
     * 剥掉 markdown 代码块。
     * <p>
     * 反复叮嘱「不要包代码块」仍然会有模型包上，为此让归因失败不值当。
     */
    private static String stripCodeFence(String raw) {
        if (raw == null) {
            return "{}";
        }
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline < 0) {
            return s;
        }
        s = s.substring(firstNewline + 1);
        int lastFence = s.lastIndexOf("```");
        return lastFence >= 0 ? s.substring(0, lastFence).trim() : s.trim();
    }

    /** 模型换版本后可能吐出没见过的分类值，按证据不足处理，至少这条归因还留得下来 */
    private static Category parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return Category.UNKNOWN;
        }
        try {
            return Category.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("模型返回了未知分类 [{}]，按 UNKNOWN 记录", raw);
            return Category.UNKNOWN;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
