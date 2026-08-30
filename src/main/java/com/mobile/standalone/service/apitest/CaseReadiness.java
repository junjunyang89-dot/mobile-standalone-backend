package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.ApiTestCase;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 这条生成出来的用例还差什么，补齐了没有。
 * <p>
 * 从清单生成的用例默认是<b>停用</b>的，带一句「待补断言」——因为骨架能生成请求
 * 和状态码断言，生成不了「关注后列表应该多一条」这种业务断言。
 * 问题是补完之后没人知道它已经能启用了：清单上永远挂着「有用例 · 但没在验」，
 * 而人以为自己还没补完。
 * <p>
 * 这里把「还差什么」算出来，让页面能直说：<b>还差请求体和业务断言</b>，
 * 或者<b>已经齐了，可以启用</b>。
 * <p>
 * 判定一律<b>宁可说还差</b>：误报「还差」最多让人多看一眼，
 * 误报「已就绪」会让一条什么都没验的用例混进通过率里。
 */
public final class CaseReadiness {

    private CaseReadiness() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 这些头带上了才算有登录态 */
    private static final Set<String> AUTH_HEADERS = Set.of(
            "authorization", "cookie", "x-access-token", "x-auth-token", "token");

    /** {@code {{name}}} 和 {@code {name}} 两种路径变量写法 */
    private static final Pattern VAR_DOUBLE = Pattern.compile("\\{\\{\\s*([A-Za-z_][\\w.-]*)\\s*}}");
    private static final Pattern VAR_SINGLE = Pattern.compile("(?<!\\{)\\{([A-Za-z_][\\w.-]*)}(?!})");

    /**
     * @param ready 没有待补项了，可以启用
     * @param todos 还要人去补的，按重要性排。已满足的不会出现在这里
     */
    public record Result(boolean ready, List<String> todos) {
    }

    /**
     * @param knownVariables 环境变量 + 用例抽取变量的名字。路径里的 {@code {{id}}}
     *                       要能在这里面找到，否则跑起来是一条带字面量花括号的 URL
     */
    public static Result check(ApiTestCase c, Set<String> knownVariables) {
        List<String> todos = new ArrayList<>();
        if (c == null) {
            return new Result(false, List.of("用例不存在"));
        }
        if (!hasMeaningfulBody(c)) {
            todos.add("请求体是空的，补一个真实的入参");
        }
        if (!hasAuthReference(c)) {
            todos.add("没带登录态，补 Authorization 或 token 头（可以引用 {{token}}）");
        }
        List<String> missing = missingPathVars(c.getPath(), knownVariables);
        if (!missing.isEmpty()) {
            todos.add("路径引用了没定义的变量：" + String.join("、", missing));
        }
        if (!hasBusinessAssertion(c)) {
            // 只断言状态码等于什么都没验：接口返回 200 但 data 是空的，这条照样绿
            todos.add("只有状态码断言，补一条业务断言（比如某个字段的值）");
        }
        return new Result(todos.isEmpty(), todos);
    }

    /**
     * 有没有像样的请求体。
     * <p>
     * GET/HEAD/DELETE 本来就不该有 body，直接算满足——
     * 否则清单上一半的接口会永远挂着一条补不掉的待办。
     */
    static boolean hasMeaningfulBody(ApiTestCase c) {
        String m = c.getMethod() == null ? "GET" : c.getMethod().trim().toUpperCase(Locale.ROOT);
        if ("GET".equals(m) || "HEAD".equals(m) || "DELETE".equals(m) || "OPTIONS".equals(m)) {
            return true;
        }
        String body = c.getBody() == null ? "" : c.getBody().trim();
        if (body.isEmpty()) {
            return false;
        }
        try {
            JsonNode n = JSON.readTree(body);
            if (n.isObject()) {
                // "{}" 是骨架生成的占位，不是人填的入参
                return !n.isEmpty();
            }
            return !n.isNull();
        } catch (Exception e) {
            // 不是 JSON（form 表单之类）就看长度，别把 "{}" 之外的都判死
            return body.length() > 2;
        }
    }

    /** 带没带登录态。写成 {@code {{token}}} 也算——那是变量，跑的时候会被替换 */
    static boolean hasAuthReference(ApiTestCase c) {
        JsonNode headers = parse(c.getHeaders());
        if (headers == null || !headers.isObject()) {
            return false;
        }
        var it = headers.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (AUTH_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT))
                    && e.getValue() != null && !e.getValue().asText("").isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** 路径里引用了、但没人定义的变量名 */
    static List<String> missingPathVars(String path, Set<String> known) {
        Set<String> names = new LinkedHashSet<>();
        if (path != null) {
            for (Pattern p : List.of(VAR_DOUBLE, VAR_SINGLE)) {
                Matcher m = p.matcher(path);
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String n : names) {
            if (known == null || !known.contains(n)) {
                missing.add(n);
            }
        }
        return missing;
    }

    /**
     * 有没有业务断言。
     * <p>
     * {@code status} 和 {@code responseTime} 不算：接口返回 200 但 data 是空的，
     * 只断言状态码的用例照样是绿的——那种绿比红更糟，因为它让人以为验过了。
     */
    static boolean hasBusinessAssertion(ApiTestCase c) {
        JsonNode arr = parse(c.getAssertions());
        if (arr == null || !arr.isArray()) {
            return false;
        }
        for (JsonNode a : arr) {
            String type = a.path("type").asText("");
            if ("jsonPath".equals(type) || "bodyIncludes".equals(type)
                    || "db".equals(type) || "dbDelta".equals(type) || "header".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
