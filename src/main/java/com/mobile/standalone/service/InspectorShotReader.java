package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.ai.QwenChatClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从 <b>Appium Inspector 截图</b>里读出元素。
 * <p>
 * 关键前提：Inspector 右侧属性面板把 {@code resource-id}、{@code xpath}、{@code content-desc}
 * 都以<b>文字</b>形式显示在界面上，所以视觉模型能直接读出来——
 * 这跟「拿一张 App 界面截图让 AI 猜定位器」完全是两回事，后者做不到。
 * <p>
 * <b>一律不自动入库。</b>模型是在<b>认字</b>，{@code btn_login} 认成 {@code btn_1ogin} 完全可能，
 * 而错一个字符的定位器不会报错，只会在跑流程时「找不到元素」——
 * 那时候没人会想到是当初录错了。所以这里只出草稿，由人在表单里核一眼再存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectorShotReader {

    private final QwenChatClient qwen;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一次最多认这么多张，超了给明确提示而不是静默截断 */
    public static final int MAX_IMAGES = 8;

    @Data
    public static class Draft {
        /** 元素英文键，流程里引用它 */
        private String nameEn;
        /** 中文名，给人看 */
        private String nameZh;
        /** ID | XPATH | ACCESSIBILITY_ID | TEXT | CLASS_NAME */
        private String locatorType;
        private String locatorValue;
        /** 备选定位，主定位失效时兜底 */
        private List<String> fallbacks = new ArrayList<>();
        /** 界面上的可见文案，帮人核对认没认错 */
        private String visualHint;
        /** 模型自己认为把握有多大：HIGH / MEDIUM / LOW */
        private String confidence;
        /** 认不准的地方，直接写给人看 */
        private String note;
    }

    public List<Draft> read(List<QwenChatClient.Image> images) throws Exception {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("请上传 Appium Inspector 的截图");
        }
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException(
                    "一次最多 " + MAX_IMAGES + " 张，当前 " + images.size() + " 张。张数太多模型会串行漏读");
        }
        String raw = qwen.chat(PROMPT, images);
        return parse(raw);
    }

    static final String PROMPT = """
            你在看的是 Appium Inspector 的截图。左边是手机界面，右边是选中元素的属性面板
            （Attribute / Value 两列，里面有 resource-id、content-desc、text、class、bounds 等）。

            请把每张截图里<b>当前选中的那一个元素</b>读出来，输出 JSON 数组，每个元素一个对象：
            {
              "nameEn": "英文键，小写下划线，如 login_phone_input",
              "nameZh": "中文名，如 手机号输入框",
              "locatorType": "ID | XPATH | ACCESSIBILITY_ID | TEXT | CLASS_NAME",
              "locatorValue": "定位值，原样照抄，一个字符都不要改",
              "fallbacks": ["备选定位值，没有就空数组"],
              "visualHint": "界面上看到的文案或图标描述",
              "confidence": "HIGH | MEDIUM | LOW",
              "note": "认不清的地方写在这里，没有就空字符串"
            }

            定位器优先级：resource-id（安卓）/ name、accessibility id（iOS）> content-desc > text > xpath。
            xpath 又长又脆，只在没有别的可用时才选。

            重要：
            - locatorValue 必须<b>逐字符照抄</b>属性面板里的值。看不清的字符不要猜，
              把 confidence 标成 LOW 并在 note 里写清楚哪几个字符不确定。
            - 认错一个字符，这个元素在流程里就永远找不到，而且不会报错，只会超时。
              所以宁可标 LOGE 让人来核，也不要蒙一个看起来像的。
            - 只输出 JSON 数组，不要 markdown 代码块，不要任何解释文字。
            """;

    /**
     * 解析模型返回。
     * <p>
     * 模型经常包一层 ```json，或者在数组前后加一句话。与其每次调提示词，不如在这里剥干净——
     * 提示词只能提高听话的概率，剥壳能保证结果。
     */
    static List<Draft> parse(String raw) {
        List<Draft> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String s = raw.trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return out;
        }
        try {
            JsonNode arr = JSON.readTree(s.substring(start, end + 1));
            for (JsonNode n : arr) {
                Draft d = new Draft();
                d.setNameEn(normalizeKey(text(n, "nameEn")));
                d.setNameZh(text(n, "nameZh"));
                d.setLocatorType(normalizeType(text(n, "locatorType")));
                d.setLocatorValue(text(n, "locatorValue"));
                d.setVisualHint(text(n, "visualHint"));
                d.setConfidence(normalizeConfidence(text(n, "confidence")));
                d.setNote(text(n, "note"));
                for (JsonNode f : n.path("fallbacks")) {
                    String v = f.asText("");
                    if (!v.isBlank()) {
                        d.getFallbacks().add(v);
                    }
                }
                // 定位值是这条记录唯一不可替代的东西，没有就整条丢掉——
                // 留一条只有名字的元素，人存进去之后才发现用不了
                if (d.getLocatorValue() != null && !d.getLocatorValue().isBlank()) {
                    out.add(d);
                }
            }
        } catch (Exception e) {
            log.warn("[inspector-shot] 解析模型返回失败: {}", e.getMessage());
        }
        return out;
    }

    /** 英文键：小写 + 下划线，跟元素库里已有的命名保持一致 */
    static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_+|_+$", "");
        return s.isEmpty() ? null : s;
    }

    static String normalizeType(String raw) {
        String t = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (t) {
            case "ID", "RESOURCE_ID", "RESOURCEID" -> "ID";
            case "XPATH" -> "XPATH";
            case "ACCESSIBILITY_ID", "ACCESSIBILITYID", "CONTENT_DESC", "NAME" -> "ACCESSIBILITY_ID";
            case "TEXT" -> "TEXT";
            case "CLASS_NAME", "CLASSNAME", "CLASS" -> "CLASS_NAME";
            // 认不出来时按 XPATH 处理：它最通用，人一眼能看出对不对；
            // 猜成 ID 反而会让人以为已经是对的
            default -> t.isEmpty() ? "XPATH" : t;
        };
    }

    static String normalizeConfidence(String raw) {
        String c = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "HIGH", "MEDIUM", "LOW" -> c;
            // 模型没给或给了别的词，一律按最低处理——把握程度上宁可保守
            default -> "LOW";
        };
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
