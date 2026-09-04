package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.ApiTestSchedule;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务最近几次执行的环形记录。
 * <p>
 * 列表页要画「近 7 次」圆点、算近 7 天成功率，不能只靠 last_status 一个字段。
 * 存在任务自己身上，而不是去扫执行报告：报告里没有 schedule_id，对不上。
 */
final class ScheduleRecentRuns {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP =
            new TypeReference<>() {};
    static final int KEEP = 7;

    private ScheduleRecentRuns() {
    }

    static String push(String existingJson, String status, String runId, LocalDateTime at, String message) {
        List<Map<String, Object>> items = parseRaw(existingJson);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("status", status);
        if (runId != null && !runId.isBlank()) {
            item.put("runId", runId);
        }
        if (at != null) {
            item.put("at", at.toString());
        }
        if (message != null && !message.isBlank()) {
            item.put("message", message.length() > 200 ? message.substring(0, 200) : message);
        }
        items.add(0, item);
        if (items.size() > KEEP) {
            items = new ArrayList<>(items.subList(0, KEEP));
        }
        try {
            return JSON.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    static List<ApiTestSchedule.RecentRun> hydrate(ApiTestSchedule s) {
        List<ApiTestSchedule.RecentRun> parsed = toRuns(parseRaw(s.getRecentResults()));
        if (!parsed.isEmpty()) {
            return parsed;
        }
        if (s.getLastStatus() == null || s.getLastStatus().isBlank()) {
            return List.of();
        }
        ApiTestSchedule.RecentRun one = new ApiTestSchedule.RecentRun();
        one.setStatus(s.getLastStatus());
        one.setRunId(s.getLastRunId());
        one.setAt(s.getLastRunAt());
        one.setMessage(s.getLastMessage());
        return List.of(one);
    }

    private static List<Map<String, Object>> parseRaw(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> list = JSON.readValue(json, LIST_MAP);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<ApiTestSchedule.RecentRun> toRuns(List<Map<String, Object>> raw) {
        List<ApiTestSchedule.RecentRun> out = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            if (m == null) {
                continue;
            }
            ApiTestSchedule.RecentRun r = new ApiTestSchedule.RecentRun();
            r.setStatus(str(m.get("status")));
            r.setRunId(str(m.get("runId")));
            r.setMessage(str(m.get("message")));
            r.setAt(parseTime(m.get("at")));
            out.add(r);
        }
        return out;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() || "null".equals(s) ? null : s;
    }

    private static LocalDateTime parseTime(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(s.replace(' ', 'T'));
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
