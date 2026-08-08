package com.mobile.standalone.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mobile.standalone.dto.ExecutionLogEntryDto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行日志 JSON 读写：兼容 timestamp 为 ISO 字符串或 Jackson 数组写法。
 */
public final class ExecutionLogJson {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final TypeReference<List<ExecutionLogEntryDto>> LIST_TYPE = new TypeReference<>() {};

    private ExecutionLogJson() {
    }

    public static ObjectMapper mapper() {
        return JSON;
    }

    public static List<ExecutionLogEntryDto> parse(String executionLogJson) {
        if (executionLogJson == null || executionLogJson.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(executionLogJson, LIST_TYPE);
        } catch (Exception ignored) {
            // fall through：兼容历史库中 timestamp=[y,m,d,h,mi,s,nano] 导致严格反序列化失败
        }
        try {
            return parseLenient(executionLogJson);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ExecutionLogEntryDto> parseLenient(String executionLogJson) throws Exception {
        JsonNode arr = JSON.readTree(executionLogJson);
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<ExecutionLogEntryDto> list = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) continue;
            ExecutionLogEntryDto e = new ExecutionLogEntryDto();
            e.setStep(text(n, "step"));
            e.setLevel(text(n, "level"));
            e.setStatus(text(n, "status"));
            e.setMessage(text(n, "message"));
            e.setSelector(text(n, "selector"));
            e.setElementKey(text(n, "elementKey"));
            e.setScreenshotPath(text(n, "screenshotPath"));
            e.setScreenshotUrl(text(n, "screenshotUrl"));
            // 逐字段重建，漏掉的字段会在「从数据库读回执行日志」时静默丢失
            e.setQrPayload(text(n, "qrPayload"));
            e.setScreenshotSettled(bool(n, "screenshotSettled"));
            e.setTimestamp(parseTimestamp(n.get("timestamp")));
            list.add(e);
        }
        return list;
    }

    private static Boolean bool(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asBoolean();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return s != null && !s.isBlank() ? s : null;
    }

    private static LocalDateTime parseTimestamp(JsonNode ts) {
        if (ts == null || ts.isNull()) return null;
        try {
            if (ts.isTextual()) {
                return LocalDateTime.parse(ts.asText());
            }
            if (ts.isArray() && ts.size() >= 6) {
                int y = ts.get(0).asInt();
                int m = ts.get(1).asInt();
                int d = ts.get(2).asInt();
                int h = ts.get(3).asInt();
                int mi = ts.get(4).asInt();
                int s = ts.get(5).asInt();
                int nano = ts.size() > 6 ? ts.get(6).asInt() : 0;
                return LocalDateTime.of(y, m, d, h, mi, s, nano);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
