package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 按真实响应给每个字段断言类型：{@code {"type":"jsonPath","path":"$.result.highlight","op":"type","value":"number"}}。
 * <p>
 * 值不断言——计数、时间换一次请求就变，断了只会制造假失败；类型不会变，字段丢了、
 * 数字变成字符串、对象变成 null 都能抓到。
 * <p>
 * 不进数组元素：列表下次可能是空的，{@code $.list[0].x} 取不到就红，那不是接口坏了。
 * 值为 null 的字段也跳过：这次是 null 看不出该是什么类型，下次有值反而会红。
 */
public final class FieldTypeAsserts {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_DEPTH = 4;
    static final int MAX_ASSERTS = 40;

    private FieldTypeAsserts() {}

    public static String typeOf(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return "null";
        if (n.isNumber()) return "number";
        if (n.isTextual()) return "string";
        if (n.isBoolean()) return "boolean";
        if (n.isArray()) return "array";
        if (n.isObject()) return "object";
        return "unknown";
    }

    /**
     * 往 out 里追加类型断言。out 里已经断言过的 path（业务码 equals、模型给的 exists）不再重复。
     *
     * @param evidencePrefix 「抓包响应里」/「试跑响应里」，后面接类型和样例值
     */
    public static void add(ArrayNode out, String body, String source, String evidencePrefix) {
        JsonNode root;
        try {
            root = body == null || body.isBlank() ? null : JSON.readTree(body);
        } catch (Exception e) {
            return;
        }
        if (root == null || !root.isObject()) return;
        Set<String> seen = new HashSet<>();
        for (JsonNode a : out) {
            if (a.hasNonNull("path")) seen.add(a.get("path").asText());
        }
        walk(root, "$", 1, out, seen, source, evidencePrefix);
    }

    private static void walk(JsonNode obj, String path, int depth, ArrayNode out, Set<String> seen,
                             String source, String evidencePrefix) {
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            var f = it.next();
            JsonNode v = f.getValue();
            if (v == null || v.isNull() || !f.getKey().matches("[A-Za-z_]\\w*")) continue;
            String p = path + "." + f.getKey();
            if (!seen.contains(p)) {
                if (count(out) >= MAX_ASSERTS) return;
                String t = typeOf(v);
                ObjectNode a = out.addObject().put("type", "jsonPath").put("path", p).put("op", "type").put("value", t);
                AssertionSource.mark(a, source, evidencePrefix + "是 " + t + sample(v));
                seen.add(p);
            }
            if (v.isObject() && depth < MAX_DEPTH) {
                walk(v, p, depth + 1, out, seen, source, evidencePrefix);
            }
        }
    }

    private static int count(ArrayNode out) {
        int n = 0;
        for (JsonNode a : out) {
            if ("type".equals(a.path("op").asText())) n++;
        }
        return n;
    }

    private static String sample(JsonNode v) {
        if (!v.isValueNode()) return v.isArray() ? "（" + v.size() + " 条）" : "";
        String s = v.asText();
        return "（" + (s.length() > 30 ? s.substring(0, 30) + "…" : s) + "）";
    }
}
