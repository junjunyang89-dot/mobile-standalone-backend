package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.ApiEndpoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为 {@link ApiCaseSkeleton} 准备断言素材。
 * <p>
 * <b>可信来源</b>：代码对比同步的 {@code fields_snapshot}、抓包实测样本。
 * <b>仅参考</b>：各开发自写的接口文档（格式不统一、常与线上一致性无保证）——
 * 只给人看业务背景，<b>不参与</b>自动断言。
 */
public final class EndpointAssertionContext {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EndpointAssertionContext() {
    }

    /**
     * @param nullableFields 文档说可空的字段。<b>不给它们生成断言</b>——
     *                       抓包这次看到有值不代表总有值，照抓包断言换个数据就红，
     *                       而红的原因是断言写错了，不是接口坏了
     * @param docParams      文档里的入参约束，用来设计边界用例
     * @param captureQuery   抓包时的真实参数值，边界用例靠它拼出能跑的请求
     */
    public record Sources(List<String> responseFields, Map<String, Object> captureSamples,
                          String noteHint, String docReference,
                          Set<String> nullableFields,
                          List<Map<String, Object>> docParams,
                          Map<String, String> captureQuery,
                          Map<String, String> captureHeaders) {
    }

    public static Sources of(ApiEndpoint e, ApiEndpointService endpointService) {
        if (e == null) {
            return new Sources(List.of(), Map.of(), null, null, Set.of(), List.of(), Map.of(), Map.of());
        }
        List<String> fields = new ArrayList<>(endpointService.responseFieldsOf(e));
        Map<String, Object> samples = new LinkedHashMap<>(endpointService.captureSamplesOf(e));
        List<String> hints = new ArrayList<>();

        Map<String, Object> cap = endpointService.captureMetaOf(e);
        if (!cap.isEmpty()) {
            hints.add("抓包实测");
        } else if (!fields.isEmpty()) {
            hints.add("代码对比");
        }

        Map<String, Object> doc = endpointService.docMetaOf(e);
        String docRef = docReferenceBrief(doc);

        String hint = hints.isEmpty() ? null : String.join(" + ", hints);
        return new Sources(fields, samples, hint, docRef,
                nullableOf(doc), paramsOf(doc), queryOf(cap), headersOf(cap));
    }

    static Set<String> nullableOf(Map<String, Object> doc) {
        Object v = doc == null ? null : doc.get("nullableFields");
        if (v instanceof List<?> l) {
            Set<String> out = new java.util.LinkedHashSet<>();
            for (Object x : l) {
                if (x != null) {
                    out.add(String.valueOf(x));
                }
            }
            return out;
        }
        return Set.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> paramsOf(Map<String, Object> doc) {
        Object v = doc == null ? null : doc.get("params");
        if (v instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : l) {
                if (x instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    /** 抓包时的请求头。鉴权头在落库时就剔掉了——token 归环境，不写死进用例 */
    static Map<String, String> headersOf(Map<String, Object> cap) {
        return strMap(cap == null ? null : cap.get("reqHeaders"));
    }

    static String reqBodyOf(Map<String, Object> cap) {
        return text(cap, "reqBody");
    }

    static String respSampleOf(Map<String, Object> cap) {
        return text(cap, "respSample");
    }

    private static String text(Map<String, Object> cap, String key) {
        Object v = cap == null ? null : cap.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    static Map<String, String> queryOf(Map<String, Object> cap) {
        return strMap(cap == null ? null : cap.get("queryParams"));
    }

    private static Map<String, String> strMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), val == null ? "" : String.valueOf(val)));
            return out;
        }
        return Map.of();
    }


    /** 给人读的一句话参考，写进用例说明，不驱动断言 */
    private static String docReferenceBrief(Map<String, Object> doc) {
        if (doc == null || doc.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        appendText(parts, doc.get("summary"));
        appendText(parts, doc.get("description"));
        appendList(parts, "MySQL", doc.get("mysqlTables"));
        appendList(parts, "Redis", doc.get("redisKeys"));
        appendList(parts, "关联", doc.get("relatedPaths"));
        if (parts.isEmpty()) {
            return null;
        }
        String body = String.join(" · ", parts);
        if (body.length() > 240) {
            body = body.substring(0, 240) + "…";
        }
        return "文档参考（未验证，各开发格式不一）：" + body;
    }

    private static void appendText(List<String> parts, Object o) {
        if (o == null) {
            return;
        }
        String t = String.valueOf(o).trim();
        if (!t.isEmpty()) {
            parts.add(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendList(List<String> parts, String label, Object o) {
        if (o instanceof List<?> list && !list.isEmpty()) {
            parts.add(label + "=" + String.join(",", list.stream().map(String::valueOf).toList()));
        } else if (o instanceof String s && !s.isBlank()) {
            parts.add(label + "=" + s.trim());
        }
    }

    public static String metaJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> readMeta(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
