package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从抓包响应体提取顶层字段名和可断言的样本值。
 */
public final class CaptureJsonParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FIELDS = 12;
    private static final int MAX_SAMPLE_LEN = 120;

    private CaptureJsonParser() {
    }

    public record Sample(List<String> fieldNames, Map<String, Object> topLevelSamples, String reqPreview) {
    }

    public static Sample parseBodies(String reqBody, String respBody) {
        List<String> fields = new ArrayList<>();
        Map<String, Object> samples = new LinkedHashMap<>();
        if (respBody != null && !respBody.isBlank()) {
            extractFromJson(respBody.trim(), fields, samples);
        }
        String reqPreview = preview(reqBody);
        return new Sample(List.copyOf(fields), Map.copyOf(samples), reqPreview);
    }

    private static void extractFromJson(String raw, List<String> fields, Map<String, Object> samples) {
        try {
            JsonNode root = JSON.readTree(raw);
            if (!root.isObject()) {
                return;
            }
            Set<String> seen = new LinkedHashSet<>();
            root.fields().forEachRemaining(e -> {
                if (seen.size() >= MAX_FIELDS) {
                    return;
                }
                String name = e.getKey();
                if (name == null || name.isBlank() || !seen.add(name)) {
                    return;
                }
                fields.add(name);
                Object v = sampleValue(e.getValue());
                if (v != null) {
                    samples.put(name, v);
                }
            });
        } catch (Exception ignore) {
            // 非 JSON 响应（HTML/空）跳过，不阻断挂接口
        }
    }

    private static Object sampleValue(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.numberValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isTextual()) {
            String t = n.asText();
            if (t.length() > MAX_SAMPLE_LEN) {
                return t.substring(0, MAX_SAMPLE_LEN) + "…";
            }
            return t;
        }
        // 对象/数组只断言存在，不断言值
        return null;
    }

    private static String preview(String reqBody) {
        if (reqBody == null || reqBody.isBlank()) {
            return null;
        }
        String s = reqBody.trim();
        return s.length() > MAX_SAMPLE_LEN ? s.substring(0, MAX_SAMPLE_LEN) + "…" : s;
    }

    /** 样本值能否写 equals 断言 */
    public static boolean isAssertableValue(Object v) {
        return v instanceof Number || v instanceof Boolean
                || (v instanceof String s && !s.isBlank() && s.length() <= MAX_SAMPLE_LEN);
    }
}
