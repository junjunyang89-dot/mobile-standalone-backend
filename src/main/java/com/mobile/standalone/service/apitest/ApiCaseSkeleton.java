package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从接口清单生成用例骨架。
 * <p>
 * 这里最容易犯的错是<b>生成一条只断言 {@code status=200} 的用例然后启用它</b>——
 * 那正是清单要标出来的问题，一键生成会把它复制两百多遍，
 * 而且每一条都会在报告里显示成绿的。
 * <p>
 * 所以规则是：<b>凑不出一条有意义的断言，就生成为「停用」</b>。
 * 停用的用例不跑、不进通过率，在清单上仍然显示「有用例但待补断言」，
 * 人看到的是一个待办，而不是一个假的已完成。
 */
public final class ApiCaseSkeleton {

    private ApiCaseSkeleton() {
    }

    /**
     * @param successCodePath  项目配的业务码路径，如 {@code $.code}；空表示没配
     * @param successCodeValue 业务成功值，如 {@code 0}
     * @param responseFields   已知的返回体顶层字段名，来自接口对比分析；没有就传空
     */
    public record Input(String httpMethod, String path, String summary,
                        String successCodePath, String successCodeValue,
                        List<String> responseFields,
                        Map<String, Object> captureSamples,
                        Set<String> nullableFields) {
        public Input(String httpMethod, String path, String summary,
                     String successCodePath, String successCodeValue,
                     List<String> responseFields) {
            this(httpMethod, path, summary, successCodePath, successCodeValue,
                    responseFields, Map.of(), Set.of());
        }

        public Input(String httpMethod, String path, String summary,
                     String successCodePath, String successCodeValue,
                     List<String> responseFields, Map<String, Object> captureSamples) {
            this(httpMethod, path, summary, successCodePath, successCodeValue,
                    responseFields, captureSamples, Set.of());
        }
    }

    /**
     * @param assertions 断言 JSON
     * @param enabled    要不要直接启用
     * @param name       用例名
     * @param note       给人看的一句话，说明这条为什么是这个状态
     */
    public record Draft(String assertions, boolean enabled, String name, String note) {
    }

    /** 顶层字段最多断言几个：全断上去会把用例变成一份字段清单，改起来比重写还慢 */
    private static final int MAX_FIELD_ASSERTIONS = 5;

    public static Draft build(Input in) {
        List<String> asserts = new ArrayList<>();
        // HTTP 状态码永远断言，但它一条不算「验过」
        asserts.add("{\"type\":\"status\",\"equals\":200" + src(AssertionSource.DEFAULT, "成功用例固定断言 HTTP 200") + "}");

        // 只有在真实数据上站得住的断言才算数；按约定写的业务码不算——没在这个接口上验过
        boolean meaningful = false;
        boolean conventionOnly = false;

        Map<String, Object> samples = in.captureSamples() == null ? Map.of() : in.captureSamples();
        String codePath = nz(in.successCodePath());
        if (!codePath.isEmpty()) {
            String want = nz(in.successCodeValue()).isEmpty() ? "0" : nz(in.successCodeValue());
            String equals = isNumeric(want) ? want : "\"" + want + "\"";
            Object seen = samples.get(codePath.startsWith("$.") ? codePath.substring(2) : codePath);
            boolean verified = seen != null && want.equals(String.valueOf(seen));
            asserts.add("{\"type\":\"jsonPath\",\"path\":\"" + esc(codePath) + "\",\"equals\":" + equals
                    + (verified
                    ? src(AssertionSource.CAPTURE, "抓包响应里就是 " + want)
                    : src(AssertionSource.CONVENTION, "项目配置的业务成功码，没在这个接口上验证过"))
                    + "}");
            if (verified) {
                meaningful = true;
            } else {
                conventionOnly = true;
            }
        }

        List<String> fields = in.responseFields() == null ? List.of() : in.responseFields();
        int used = 0;
        Set<String> sampled = new java.util.LinkedHashSet<>();
        // 文档写明「查不到可空」的字段，一律不生成断言。
        // 抓包这次看到它有值，不代表它总有值——照抓包断言 equals/exists，
        // 换个数据就红，而红的原因是断言写错了，不是接口坏了。
        // 这种假失败最伤：修几次之后，人就不再相信这套用例了
        Set<String> nullable = in.nullableFields() == null ? Set.of() : in.nullableFields();

        // 抓包样本：顶层字段写 equals（比 exists 更有验据）
        for (Map.Entry<String, Object> e : samples.entrySet()) {
            if (used >= MAX_FIELD_ASSERTIONS) {
                break;
            }
            String field = e.getKey();
            Object val = e.getValue();
            if (field == null || field.isBlank() || !CaptureJsonParser.isAssertableValue(val)) {
                continue;
            }
            if (nullable.contains(field.trim())) {
                continue;
            }
            String path = field.startsWith("$") ? field : "$." + field.trim();
            if (path.equals(codePath)) {
                continue;
            }
            asserts.add("{\"type\":\"jsonPath\",\"path\":\"" + esc(path) + "\",\"equals\":"
                    + jsonLiteral(val) + src(AssertionSource.CAPTURE, "抓包响应里就是这个值") + "}");
            sampled.add(field.trim());
            used++;
            meaningful = true;
        }

        for (String f : fields) {
            if (f == null || f.isBlank() || used >= MAX_FIELD_ASSERTIONS) {
                continue;
            }
            String trimmed = f.trim();
            if (sampled.contains(trimmed) || nullable.contains(trimmed)) {
                continue;
            }
            String path = trimmed.startsWith("$") ? trimmed : "$." + trimmed;
            if (path.equals(codePath)) {
                continue;
            }
            // 只断言「字段在」，不断言值：值是什么只有跑一次才知道，
            // 猜一个填进去，第一次跑就是红的，而红得没有道理最消耗信任
            asserts.add("{\"type\":\"jsonPath\",\"path\":\"" + esc(path) + "\",\"exists\":true"
                    + src(AssertionSource.FIELDS, "接口对比 / 抓包记录过这个返回字段") + "}");
            used++;
            meaningful = true;
        }

        String json = "[" + String.join(",", asserts) + "]";
        String base = displayName(in.httpMethod(), in.path(), in.summary());

        if (meaningful) {
            return new Draft(json, true, base, "断言来自抓包实测或代码对比；开发文档仅参考，不参与");
        }
        if (conventionOnly) {
            return new Draft(json, false, base + "（待验证）",
                    "只有按项目约定写的业务码断言，没在这个接口的真实响应上验证过。"
                            + "抓一次包或跑一次确认返回，再启用");
        }
        return new Draft(json, false,
                base + "（待补断言）",
                "没有抓包样本或代码对比字段，只有 status=200——那等于没验。"
                        + "先抓包挂接口（拿真实返回），或跑接口对比分析（拿代码里的字段），再生成/补断言。"
                        + "开发文档只能参考，不会自动写进断言。");
    }

    /**
     * 用例展示名：优先 @ApiOperation 等中文说明；抓包来的 host 不算说明。
     */
    public static String displayName(String httpMethod, String path, String summary) {
        if (isMeaningfulSummary(summary)) {
            return summary.trim();
        }
        return formatMethodPath(httpMethod, path);
    }

    static boolean isMeaningfulSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        String s = summary.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return false;
        }
        // 抓包挂接口时 summary 常是 host，如 gateway-qa.arca42.com
        if (s.matches("^[a-zA-Z0-9][a-zA-Z0-9.-]*\\.[a-zA-Z]{2,}$")) {
            return false;
        }
        // 已是 METHOD /path 形式
        if (s.matches("^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+.+")) {
            return true;
        }
        // 含中文或空格，多半是接口说明
        if (s.chars().anyMatch(ch -> ch >= 0x4E00 && ch <= 0x9FFF) || s.contains(" ")) {
            return true;
        }
        return false;
    }

    static String formatMethodPath(String httpMethod, String path) {
        String m = nz(httpMethod).isEmpty() ? "GET" : httpMethod.trim().toUpperCase();
        String p = nz(path);
        if (p.isBlank()) {
            return m;
        }
        String[] parts = p.split("/");
        String leaf = "";
        String parent = "";
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i] != null && !parts[i].isBlank()) {
                leaf = parts[i];
                if (i > 0) {
                    for (int j = i - 1; j >= 0; j--) {
                        if (parts[j] != null && !parts[j].isBlank()) {
                            parent = parts[j];
                            break;
                        }
                    }
                }
                break;
            }
        }
        if (leaf.isEmpty()) {
            return m + " " + p;
        }
        if (!parent.isEmpty() && isGenericSegment(leaf)) {
            return m + " " + parent + "/" + leaf;
        }
        return m + " " + leaf;
    }

    private static boolean isGenericSegment(String seg) {
        return Set.of("list", "detail", "cnt", "search", "info", "status", "index")
                .contains(seg.toLowerCase());
    }

    private static boolean isNumeric(String s) {
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String src(String source, String evidence) {
        return ",\"source\":\"" + source + "\",\"evidence\":\"" + esc(evidence) + "\"";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String jsonLiteral(Object val) {
        if (val instanceof Number || val instanceof Boolean) {
            return String.valueOf(val);
        }
        return "\"" + esc(String.valueOf(val)) + "\"";
    }
}
