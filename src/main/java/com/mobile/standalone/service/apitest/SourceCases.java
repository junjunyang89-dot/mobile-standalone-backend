package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.service.apitest.ParamInsight.Outcome;
import com.mobile.standalone.service.apitest.ParamInsight.Param;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 参数体检的结论变成用例：抓包那次请求当底子，每条只改一个参数（不传 / 传错类型 / 越界 / 传个不认识的值）。
 * <p>
 * 断言按<b>代码现状</b>写——跑通过说明代码确实这么处理，源码读对了；
 * 现状本身可疑的（缺参静默成功、没判空报系统繁忙）另外标「疑似问题」，交给人去找开发确认。
 * 这样「读对没读对」和「该不该这样」分开，不会出现一条红色用例既可能是读错了、又可能是真 bug。
 */
public final class SourceCases {

    private SourceCases() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int MAX_CASES = 8;

    public static final String NORMAL = "NORMAL";
    public static final String NEGATIVE = "NEGATIVE";

    /**
     * @param param  改的是哪个参数
     * @param intent NORMAL（可选参数不传，本来就是正常用法）/ NEGATIVE（异常输入）
     * @param body   改过的 JSON 请求体；没动请求体时是抓包原样
     * @param expect 一句话：代码会怎样、依据在哪一行、为什么可疑
     */
    public record Planned(String name, String param, String intent, boolean suspect, Map<String, String> query,
                          String body, String assertions, String expect) {
    }

    /**
     * @param capturedQuery 抓包 query（token 已换成变量）
     * @param capturedBody  抓包请求体
     * @param codePath      业务码路径；项目没配时用返回体里的 $.code
     */
    public static List<Planned> plan(EndpointInsight.Report r, Map<String, String> capturedQuery, String capturedBody,
                                     String codePath, String configuredSuccess, int max) {
        if (r == null || r.params() == null || r.params().isEmpty()) {
            return List.of();
        }
        String path = codePath != null && !codePath.isBlank() ? codePath.trim()
                : r.fields().stream().anyMatch(f -> "$.code".equals(f.path())) ? "$.code" : null;
        String success = r.success() != null && r.success().code() != null ? r.success().code()
                : configuredSuccess == null || configuredSuccess.isBlank() ? null : configuredSuccess.trim();
        String dataPath = dataPath(r);
        Map<String, String> query = capturedQuery == null ? Map.of() : capturedQuery;
        ObjectNode body = objectBody(capturedBody);

        List<Cand> cands = new ArrayList<>();
        boolean badTypeTaken = false;
        for (Param p : r.params()) {
            boolean inQuery = "query".equals(p.in());
            boolean inBody = "body".equals(p.in()) && body != null;
            if (!inQuery && !inBody) {
                continue;
            }
            boolean present = inQuery ? query.containsKey(p.name()) : body.has(p.name());
            if (p.missing() != null && (present || p.required())) {
                cands.add(new Cand(p, "缺少 " + p.name(), null, true, p.missing(), priority(p.missing(), 1)));
            }
            if (p.badType() != null && (!badTypeTaken || p.badType().suspect())) {
                badTypeTaken = true;
                cands.add(new Cand(p, p.name() + " 传 abc", "abc", false, p.badType(), priority(p.badType(), 3)));
            }
            for (ParamInsight.Bound b : p.bounds()) {
                cands.add(new Cand(p, p.name() + "=" + b.value() + "（" + b.rule() + "）", b.value(), false,
                        b.outcome(), priority(b.outcome(), 2)));
            }
            if (p.unsupported() != null && !p.values().isEmpty()) {
                String v = unknownValue(p.values());
                cands.add(new Cand(p, p.name() + "=" + v + "（代码只认 " + String.join("/", p.values()) + "）", v,
                        false, p.unsupported(), priority(p.unsupported(), 2)));
            }
        }
        cands.sort(Comparator.comparingInt(Cand::priority));

        List<Planned> out = new ArrayList<>();
        for (Cand c : cands) {
            if (out.size() >= max) {
                break;
            }
            ArrayNode asserts = assertions(c.outcome(), path, success, dataPath);
            if (asserts == null) {
                continue;
            }
            Map<String, String> q = new LinkedHashMap<>(query);
            String b = capturedBody == null ? "" : capturedBody;
            if ("query".equals(c.p().in())) {
                if (c.remove()) {
                    q.remove(c.p().name());
                } else {
                    q.put(c.p().name(), c.value());
                }
            } else {
                ObjectNode nb = body.deepCopy();
                if (c.remove()) {
                    nb.remove(c.p().name());
                } else if (c.value().matches("-?\\d+") && !"string".equals(c.p().type())) {
                    nb.put(c.p().name(), Long.parseLong(c.value()));
                } else {
                    nb.put(c.p().name(), c.value());
                }
                b = nb.toString();
            }
            Outcome o = c.outcome();
            boolean suspect = o.suspect();
            String intent = c.remove() && (ParamInsight.OPTIONAL.equals(o.kind()) || ParamInsight.DEFAULT.equals(o.kind()))
                    ? NORMAL : NEGATIVE;
            String name = (suspect ? "疑似问题 · " : "") + c.action() + " → " + behavior(o);
            out.add(new Planned(name, c.p().name(), intent, suspect, q, b, asserts.toString(), expect(o)));
        }
        return out;
    }

    private record Cand(Param p, String action, String value, boolean remove, Outcome outcome, int priority) {
    }

    /** 疑似问题排最前，其次「不传」，再越界 / 不认识的值，类型错最后（几个参数的类型错结局都一样） */
    private static int priority(Outcome o, int base) {
        return (o.suspect() ? 0 : 10) + base;
    }

    static ArrayNode assertions(Outcome o, String codePath, String success, String dataPath) {
        ArrayNode a = JSON.createArrayNode();
        String evidence = "源码：" + o.detail() + (o.where() == null ? "" : "（" + o.where() + "）");
        switch (o.kind()) {
            case ParamInsight.HTTP -> {
                ObjectNode s = a.addObject().put("type", "status").put("equals", Integer.parseInt(o.code()));
                AssertionSource.mark(s, AssertionSource.CODE, evidence);
            }
            case ParamInsight.REJECT, ParamInsight.CRASH -> {
                if (codePath == null) {
                    return null;
                }
                ObjectNode s = a.addObject().put("type", "jsonPath").put("path", codePath);
                if (o.code() != null) {
                    s.put("op", "eq").put("value", o.code());
                } else if (success != null) {
                    s.put("op", "ne").put("value", success);
                } else {
                    return null;
                }
                AssertionSource.mark(s, AssertionSource.CODE, evidence);
            }
            default -> {
                if (codePath == null || success == null) {
                    return null;
                }
                AssertionSource.mark(a.addObject().put("type", "jsonPath").put("path", codePath)
                        .put("op", "eq").put("value", success), AssertionSource.CODE, evidence);
                if (ParamInsight.SILENT.equals(o.kind()) && dataPath != null) {
                    if ("null".equals(o.returns())) {
                        AssertionSource.mark(a.addObject().put("type", "jsonPath").put("path", dataPath)
                                .put("exists", false), AssertionSource.CODE, evidence);
                    } else if ("emptyList".equals(o.returns())) {
                        AssertionSource.mark(a.addObject().put("type", "jsonPath").put("path", dataPath)
                                .put("size", 0), AssertionSource.CODE, evidence);
                    }
                }
            }
        }
        return a;
    }

    static String behavior(Outcome o) {
        return switch (o.kind()) {
            case ParamInsight.REJECT -> o.code() == null ? "报错（码由异常带出）" : "报 " + label(o);
            case ParamInsight.CRASH -> "没判空，报 " + (o.code() == null ? "异常" : label(o));
            case ParamInsight.HTTP -> "HTTP " + o.code();
            case ParamInsight.SILENT -> "返回成功但" + ("emptyList".equals(o.returns()) ? "列表为空"
                    : "null".equals(o.returns()) ? "数据为空" : "数据是空对象");
            case ParamInsight.DEFAULT -> "用默认值，正常返回";
            default -> "正常返回";
        };
    }

    static String expect(Outcome o) {
        StringBuilder sb = new StringBuilder("代码行为：").append(o.detail());
        if (o.where() != null) {
            sb.append("（").append(o.where()).append("）");
        }
        if (o.suspect()) {
            sb.append("。疑似问题：").append(switch (o.kind()) {
                case ParamInsight.SILENT -> "参数不对也返回成功，客户端分不清是「没数据」还是「传错了」";
                case ParamInsight.CRASH -> "没判空，空指针落到兜底异常，返回系统繁忙之类，不是参数错误";
                default -> "没有专门处理，异常落到兜底，返回系统繁忙之类，不是参数错误";
            });
        }
        return sb.toString();
    }

    private static String label(Outcome o) {
        return o.codeName() == null || "HTTP".equals(o.codeName()) ? o.code() : o.codeName() + " " + o.code();
    }

    /** 外层的数据字段：$.result / $.data … */
    static String dataPath(EndpointInsight.Report r) {
        Set<String> names = Set.of("data", "result", "body", "obj", "content");
        for (EndpointInsight.Field f : r.fields()) {
            String p = f.path();
            if (p != null && p.startsWith("$.") && p.indexOf('.', 2) < 0 && !p.contains("[")
                    && names.contains(f.name())) {
                return p;
            }
        }
        return null;
    }

    private static String unknownValue(List<String> values) {
        for (String v : List.of("99", "999", "-1")) {
            if (!values.contains(v)) {
                return v;
            }
        }
        return "9999";
    }

    private static ObjectNode objectBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw);
            return n instanceof ObjectNode o ? o : null;
        } catch (Exception e) {
            return null;
        }
    }
}
