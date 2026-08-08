package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 极简 JSONPath：支持 {@code $.a.b} 与 {@code $.a[0].b}。
 * <p>
 * 断言和 extract 都要按路径取值，所以放在一处，别两边各写一份——
 * 两份实现迟早对同一个路径给出不同结果，那种 bug 极难查。
 * <p>
 * 没有引 jayway JsonPath：过滤表达式（{@code $.list[?(@.id==3)]}）这类高级语法，
 * 测试写用例时几乎用不上，多一个依赖不值。
 */
public final class JsonPaths {

    private JsonPaths() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 取不到返回 null；路径存在但值为 null 时返回 NullNode，两者要能区分 */
    public static JsonNode read(String body, String path) throws Exception {
        if (body == null || body.isBlank() || path == null || path.isBlank()) {
            return null;
        }
        JsonNode cur = JSON.readTree(body);
        String p = path.trim();
        if (p.startsWith("$.")) {
            p = p.substring(2);
        } else if (p.startsWith("$")) {
            p = p.substring(1);
        }
        if (p.startsWith(".")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return cur;
        }
        for (String part : p.split("\\.")) {
            if (cur == null || cur.isMissingNode() || cur.isNull()) {
                return cur;
            }
            if (part.contains("[")) {
                int lb = part.indexOf('[');
                int rb = part.indexOf(']');
                String name = part.substring(0, lb);
                int idx;
                try {
                    idx = Integer.parseInt(part.substring(lb + 1, rb).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (!name.isEmpty()) {
                    cur = cur.path(name);
                }
                if (cur == null || !cur.isArray()) {
                    return null;
                }
                // 支持负数下标：$.list[-1] 取最后一个。造完数据取「刚插进去那条」时很顺手
                int real = idx < 0 ? cur.size() + idx : idx;
                if (real < 0 || real >= cur.size()) {
                    return null;
                }
                cur = cur.get(real);
            } else {
                cur = cur.path(part);
            }
        }
        return cur;
    }

    /** 取成字符串给变量用；对象/数组按 JSON 原文，标量按字面值（不带引号） */
    public static String readAsString(String body, String path) throws Exception {
        JsonNode n = read(body, path);
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        return n.isValueNode() ? n.asText() : n.toString();
    }
}
