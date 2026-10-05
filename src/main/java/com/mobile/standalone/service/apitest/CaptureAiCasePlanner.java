package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 没有源码、只有抓包时，让模型按「一次真实请求 + 响应」设计用例和断言。
 * <p>
 * 模型写的断言不直接用：正常用例的每一条都拿抓到的响应验一遍，不成立的丢掉。
 * 这样既用上了模型「看得懂哪些字段要紧」的能力，又不会出现第一次跑就红的瞎猜断言。
 * 边界用例没有真实响应可验，一律停用，等人确认预期。
 */
final class CaptureAiCasePlanner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CASES = 4;
    private static final int MAX_ASSERTS = 8;
    private static final Pattern PATH_OK = Pattern.compile("^\\$(\\.[A-Za-z_][\\w-]*(\\[-?\\d+])?)+$");
    private static final Set<String> AUTH_PARAMS = Set.of("token", "accesstoken", "access_token", "authorization");

    private CaptureAiCasePlanner() {
    }

    /** @param codeNotes 源码体检读出来的字段含义、业务码、登录要求，没有源码时为空 */
    record Input(String method, String path, String summary, Map<String, String> query, String reqBody,
                 String respSample, String codePath, String successValue, String codeNotes) {
        Input(String method, String path, String summary, Map<String, String> query, String reqBody,
              String respSample, String codePath, String successValue) {
            this(method, path, summary, query, reqBody, respSample, codePath, successValue, null);
        }
    }

    record PlannedCase(String name, boolean normal, Map<String, String> query, String body, String basis,
                       String assertions, boolean enabled, String note) {
    }

    static String prompt(Input in) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是接口测试工程师。下面是抓包抓到的一次真实请求和响应，据此设计这个接口的测试用例和断言。\n\n");
        sb.append("接口：").append(in.method()).append(' ').append(in.path());
        if (in.summary() != null && !in.summary().isBlank()) {
            sb.append("（").append(in.summary().trim()).append("）");
        }
        sb.append('\n');
        Map<String, String> shown = noAuth(in.query());
        if (!shown.isEmpty()) {
            sb.append("请求参数：").append(toJson(shown)).append('\n');
        }
        if (in.reqBody() != null && !in.reqBody().isBlank()) {
            sb.append("请求体：").append(truncate(in.reqBody(), 3000)).append('\n');
        }
        sb.append("响应（数组只保留了前 2 个，长文本截断）：\n").append(in.respSample()).append('\n');
        if (in.codePath() != null && !in.codePath().isBlank()) {
            sb.append("业务成功码：").append(in.codePath()).append(" = ")
                    .append(in.successValue() == null || in.successValue().isBlank() ? "0" : in.successValue())
                    .append('\n');
        }
        if (in.codeNotes() != null && !in.codeNotes().isBlank()) {
            sb.append("\n后端源码里读出来的（比抓包更能说明字段含义和报错码，但没跑过）：\n")
                    .append(truncate(in.codeNotes(), 2500)).append('\n')
                    .append("边界用例的报错码优先用上面列出的错误码；源码里的可疑写法不要写成期望结果。")
                    .append("不带 token 的用例平台会单独生成，这里不要写。\n");
        }
        String dated = datedParams(shown);
        if (dated != null) {
            sb.append("\n注意：").append(dated)
                    .append(" 是日期/时间参数，用例以后照抄这个值重放，那天的列表可能已经是空的——")
                    .append("列表只断言节点存在，不要断言列表里某一项的字段。\n");
        }
        sb.append("""

                要求：
                1. 第一条是正常用例：kind=NORMAL，expect=SUCCESS，参数照抄抓包值。
                2. 边界用例 kind=BOUNDARY，最多 3 条：缺参数、非法值、越界、分页参数为 0 或负数等，\
                只写从参数名和取值能看出有意义的，basis 用一句话说为什么这么测。expect 写 ERROR 或 SUCCESS。
                3. 每条写 assertions。正常用例的断言必须在上面这份响应里成立：
                   - 业务码、状态这类稳定值用 equals；
                   - 数据节点、列表、关键字段用 exists:true；
                   - 不要断言 id、时间、计数、金额、昵称这类换一份数据就会变的值；
                   - 最多 6 条，挑最能说明接口正常的。
                   边界用例只断言业务码不是成功码（notEquals），或你确定的报错码。
                4. 断言只用这三种写法，path 只用 $.a.b 和 $.a[0].b：
                   {"type":"jsonPath","path":"$.data.list","exists":true}
                   {"type":"jsonPath","path":"$.code","equals":0}
                   {"type":"jsonPath","path":"$.code","notEquals":0}
                5. 不要填 token、accessToken 这类登录参数，平台会自动带。
                6. name 用简短中文，如「正常查询」「缺少 matchId」。

                只输出 JSON，不要解释：
                {"cases":[{"name":"","kind":"NORMAL","expect":"SUCCESS","query":{},"body":null,"basis":"","assertions":[]}]}
                """);
        return sb.toString();
    }

    static List<PlannedCase> plan(String raw, Input in) throws Exception {
        String s = raw == null ? "" : raw.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw new IllegalStateException("模型返回的不是 JSON：" + truncate(s, 120));
        }
        JsonNode root = JSON.readTree(s.substring(a, b + 1));
        List<PlannedCase> out = new ArrayList<>();
        boolean haveNormal = false;
        for (JsonNode c : root.path("cases")) {
            if (out.size() >= MAX_CASES) break;
            boolean normal = !"BOUNDARY".equalsIgnoreCase(c.path("kind").asText());
            if (normal && haveNormal) {
                normal = false;
            }
            String name = c.path("name").asText("").trim();
            if (name.isEmpty()) name = normal ? "正常请求" : "边界";
            if (normal) {
                haveNormal = true;
                out.add(0, normalCase(name, c, in));
            } else {
                out.add(boundaryCase(name, c, in));
            }
        }
        if (!haveNormal) {
            out.add(0, normalCase("正常请求", JSON.createObjectNode(), in));
        }
        return out;
    }

    /** 参数和请求体照抄抓包：模型改过的值没跑过，不能信 */
    private static PlannedCase normalCase(String name, JsonNode c, Input in) {
        ArrayNode asserts = verified(c.path("assertions"), in.respSample());
        String failCode = capturedFailure(in);
        if (failCode == null) {
            FieldTypeAsserts.add(asserts, in.respSample(), AssertionSource.CAPTURE, "抓包响应里");
        }
        boolean enabled = failCode == null && asserts.size() > 1;
        String note;
        if (failCode != null) {
            note = "抓包这次返回的就是失败（" + in.codePath() + "=" + failCode + "），换一次成功的请求重抓";
        } else if (!enabled) {
            note = "抓包响应里没有可断言的字段，只留了状态码，补上断言再启用";
        } else {
            note = "断言已用抓包响应验证过（" + (asserts.size() - 1) + " 条）";
        }
        String body = "GET".equalsIgnoreCase(in.method()) ? null : in.reqBody();
        return new PlannedCase(name, true, authAsVariable(in.query()), body, c.path("basis").asText(""),
                asserts.toString(), enabled, note);
    }

    private static PlannedCase boundaryCase(String name, JsonNode c, Input in) {
        ArrayNode asserts = JSON.createArrayNode();
        for (JsonNode x : c.path("assertions")) {
            if (asserts.size() >= MAX_ASSERTS) break;
            if (wellFormed(x) && (x.has("equals") || x.has("notEquals"))) {
                ObjectNode a = JSON.createObjectNode();
                a.put("type", "jsonPath").put("path", x.get("path").asText());
                if (x.has("equals")) a.set("equals", x.get("equals").deepCopy());
                if (x.has("notEquals")) a.set("notEquals", x.get("notEquals").deepCopy());
                asserts.add(AssertionSource.mark(a, AssertionSource.AI, "AI 按参数推测的报错，没有真实响应可验"));
            }
        }
        Map<String, String> query = new LinkedHashMap<>();
        JsonNode q = c.get("query");
        if (q != null && q.isObject()) {
            q.fields().forEachRemaining(f -> {
                if (!AUTH_PARAMS.contains(f.getKey().toLowerCase())) {
                    JsonNode v = f.getValue();
                    query.put(f.getKey(), v == null || v.isNull() ? "" : v.isValueNode() ? v.asText() : v.toString());
                }
            });
        }
        // 边界用例测的是别的参数，登录态照样要带，否则全死在鉴权上
        authAsVariable(in.query()).forEach((k, v) -> {
            if (AUTH_PARAMS.contains(k.toLowerCase())) query.putIfAbsent(k, v);
        });
        JsonNode body = c.get("body");
        String bodyText = body == null || body.isNull() || "GET".equalsIgnoreCase(in.method()) ? null : body.toString();
        return new PlannedCase(name, false, query, bodyText, c.path("basis").asText(""),
                asserts.isEmpty() ? null : asserts.toString(), false,
                "边界用例没有真实响应可验，确认预期后再启用");
    }

    /** 状态码打底，模型的断言逐条拿抓包响应验；带数组下标的值断言不留——列表第一条换个时间就不是它了 */
    static ArrayNode verified(JsonNode proposed, String respSample) {
        ArrayNode out = JSON.createArrayNode();
        AssertionSource.mark(out.addObject().put("type", "status").put("equals", 200),
                AssertionSource.DEFAULT, "成功用例固定断言 HTTP 200");
        if (proposed == null || !proposed.isArray()) {
            return out;
        }
        for (JsonNode x : proposed) {
            if (out.size() > MAX_ASSERTS) break;
            if (!wellFormed(x)) continue;
            String path = x.get("path").asText();
            JsonNode actual;
            try {
                actual = JsonPaths.read(respSample, path);
            } catch (Exception e) {
                continue;
            }
            if (actual == null || actual.isMissingNode() || actual.isNull()) continue;
            if (x.path("exists").asBoolean(false)) {
                AssertionSource.mark(out.addObject().put("type", "jsonPath").put("path", path).put("exists", true),
                        AssertionSource.CAPTURE, "抓包响应里有这个节点");
            } else if (x.has("equals") && actual.isValueNode() && !path.contains("[")
                    && actual.asText().equals(x.get("equals").asText())) {
                ObjectNode a = out.addObject().put("type", "jsonPath").put("path", path);
                a.set("equals", actual.deepCopy());
                AssertionSource.mark(a, AssertionSource.CAPTURE, "抓包响应里就是 " + actual.asText());
            }
        }
        return out;
    }

    private static boolean wellFormed(JsonNode x) {
        return x != null && x.isObject() && "jsonPath".equals(x.path("type").asText())
                && PATH_OK.matcher(x.path("path").asText("")).matches();
    }

    /** 抓到的这次本身就是失败返回，返回实际业务码；成功或判断不了返回 null */
    private static String capturedFailure(Input in) {
        if (in.codePath() == null || in.codePath().isBlank()) return null;
        String want = in.successValue() == null || in.successValue().isBlank() ? "0" : in.successValue().trim();
        try {
            String got = JsonPaths.readAsString(in.respSample(), in.codePath());
            return got == null || want.equals(got) ? null : got;
        } catch (Exception e) {
            return null;
        }
    }

    static final String TOKEN_VAR = "{{token}}";

    /**
     * 抓包 query 里的登录参数换成 {@code {{token}}}：值会过期不能照抄，
     * 但键必须留着——token 走 query 的网关（?token=…），缺了这个键请求根本不带登录态。
     */
    static Map<String, String> authAsVariable(Map<String, String> q) {
        Map<String, String> out = new LinkedHashMap<>();
        if (q != null) {
            q.forEach((k, v) -> out.put(k, AUTH_PARAMS.contains(k.toLowerCase()) ? TOKEN_VAR : v));
        }
        return out;
    }

    private static final Pattern DATE_VALUE = Pattern.compile("^\\d{4}-\\d{1,2}-\\d{1,2}([ T+]\\d{1,2}:\\d{2}(:\\d{2})?)?$");

    /** 取值长得像日期的参数名，逗号隔开；没有返回 null */
    static String datedParams(Map<String, String> q) {
        if (q == null) {
            return null;
        }
        List<String> names = q.entrySet().stream()
                .filter(en -> en.getValue() != null && DATE_VALUE.matcher(en.getValue().trim()).matches())
                .map(Map.Entry::getKey).toList();
        return names.isEmpty() ? null : String.join("、", names);
    }

    private static Map<String, String> noAuth(Map<String, String> q) {
        Map<String, String> out = new LinkedHashMap<>();
        if (q != null) {
            q.forEach((k, v) -> {
                if (!AUTH_PARAMS.contains(k.toLowerCase())) out.put(k, v);
            });
        }
        return out;
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
