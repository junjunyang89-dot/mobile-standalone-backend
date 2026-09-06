package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 响应的<b>结构指纹</b>，以及两个指纹的差异。
 * <p>
 * 用来做活动页的回归基准：上线前把接口响应存成基线，换皮后再抓一次对比。
 * <p>
 * <b>关键是只比结构、不比值。</b>原始响应逐字节比会像素级全红——时间戳、token、
 * 随机排序的列表每次都在变，那种「全变了」的结论等于没有结论。真正换皮打不穿的信号是
 * <b>形状</b>：哪些字段在、类型是什么、数组多长。它抓的是「后端少返了一个字段」
 * 「奖品从 8 个变 7 个」「某个字段从对象变成了 null」这类回归，而这些恰恰是
 * 换皮时最容易被前端问题掩盖、人工又最难一眼看出的。
 * <p>
 * 规约方式：
 * <ul>
 *   <li>对象 → 按 key 排序后递归每个字段的形状</li>
 *   <li>数组 → 记长度 + 合并所有元素的形状（元素结构不一致也能看出来）</li>
 *   <li>标量 → 只留类型（string/number/bool/null），丢掉具体值</li>
 * </ul>
 * 数组长度<b>留在指纹里</b>：奖品少一个正是要抓的，不能规约掉。代价是天然多变的列表
 * （如资讯流）会报长度差——但基线是人挑着上线前那一版存的，可以只对稳定接口建快照。
 */
public final class ResponseShape {

    private ResponseShape() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 太深就不像结构而像把整棵对象图铺开了，也防御环形/超大响应 */
    private static final int MAX_DEPTH = 12;

    /**
     * 把响应体规约成结构指纹。解析不了 JSON（HTML 错误页、纯文本）时返回一个标注了
     * 类型的占位指纹，而不是抛异常——「这次返回的不是 JSON」本身就是要对比出来的差异。
     */
    public static String of(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (Exception e) {
            // 不是 JSON：网关的 HTML 错误页、502 文本页都会走到这里
            return "<non-json:" + Math.min(body.length(), 999999) + "B>";
        }
        return shapeOf(node, MAX_DEPTH);
    }

    private static String shapeOf(JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (depth <= 0) {
            return "…";
        }
        if (node.isObject()) {
            // TreeMap 天然按 key 排序：字段顺序不该算差异，只有增删才算
            Map<String, String> fields = new TreeMap<>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(), shapeOf(e.getValue(), depth - 1)));
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(e.getKey()).append(':').append(e.getValue());
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            int n = node.size();
            if (n == 0) {
                return "[0]";
            }
            // 合并所有元素的形状：绝大多数是同构的，合并后就是那一个；
            // 不同构时合并结果会带上差异，也能看出来
            String merged = shapeOf(node.get(0), depth - 1);
            for (int i = 1; i < n; i++) {
                String s = shapeOf(node.get(i), depth - 1);
                if (!s.equals(merged)) {
                    merged = merged + "|" + s;
                }
            }
            return "[" + n + ":" + merged + "]";
        }
        if (node.isTextual()) return "string";
        if (node.isBoolean()) return "bool";
        if (node.isNumber()) return "number";
        return "scalar";
    }

    /**
     * 一处结构差异。path 指到具体字段，让人直接知道是哪儿变了，而不是「响应变了」。
     *
     * @param kind ADDED 新增字段 / REMOVED 少了字段 / TYPE_CHANGED 类型变 /
     *             ARRAY_SIZE 数组长度变
     */
    public record Diff(String path, String kind, String baseline, String current) {
    }

    /**
     * 比两份响应体的结构，逐字段列出差异。空列表＝结构一致（值可能变了，但那是预期的）。
     */
    public static List<Diff> diff(String baselineBody, String currentBody) {
        JsonNode a = tryParse(baselineBody);
        JsonNode b = tryParse(currentBody);
        List<Diff> out = new ArrayList<>();
        if (a == null || b == null) {
            // 有一侧根本不是 JSON：直接按整体形状比，别硬钻字段
            String sa = of(baselineBody);
            String sb = of(currentBody);
            if (!sa.equals(sb)) {
                out.add(new Diff("$", "TYPE_CHANGED", sa, sb));
            }
            return out;
        }
        walk("$", a, b, out, MAX_DEPTH);
        return out;
    }

    private static void walk(String path, JsonNode a, JsonNode b, List<Diff> out, int depth) {
        if (depth <= 0) {
            return;
        }
        String ta = typeName(a);
        String tb = typeName(b);
        if (!ta.equals(tb)) {
            out.add(new Diff(path, "TYPE_CHANGED", ta, tb));
            return;
        }
        if (a.isObject()) {
            // 少了的字段：基线有、当前没有——这是后端回归最典型的一类
            a.fieldNames().forEachRemaining(f -> {
                if (!b.has(f)) {
                    out.add(new Diff(path + "." + f, "REMOVED", typeName(a.get(f)), "—"));
                }
            });
            b.fieldNames().forEachRemaining(f -> {
                if (!a.has(f)) {
                    out.add(new Diff(path + "." + f, "ADDED", "—", typeName(b.get(f))));
                } else {
                    walk(path + "." + f, a.get(f), b.get(f), out, depth - 1);
                }
            });
        } else if (a.isArray()) {
            if (a.size() != b.size()) {
                // 奖品少一个就在这里冒出来
                out.add(new Diff(path, "ARRAY_SIZE", String.valueOf(a.size()), String.valueOf(b.size())));
            }
            // 逐元素比结构（取较短的那段，长度差已单独报过）
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                walk(path + "[" + i + "]", a.get(i), b.get(i), out, depth - 1);
            }
        }
        // 标量：类型一致就不再深究值——值本来就允许变
    }

    private static String typeName(JsonNode n) {
        if (n == null || n.isNull()) return "null";
        if (n.isObject()) return "object";
        if (n.isArray()) return "array";
        if (n.isTextual()) return "string";
        if (n.isBoolean()) return "bool";
        if (n.isNumber()) return "number";
        return "scalar";
    }

    private static JsonNode tryParse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(body);
            return (n instanceof ObjectNode || n instanceof ArrayNode) ? n : null;
        } catch (Exception e) {
            return null;
        }
    }
}
