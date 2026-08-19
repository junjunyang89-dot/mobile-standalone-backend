package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.List;

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
                        List<String> responseFields) {
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
        asserts.add("{\"type\":\"status\",\"equals\":200}");

        boolean meaningful = false;

        String codePath = nz(in.successCodePath());
        if (!codePath.isEmpty()) {
            String want = nz(in.successCodeValue()).isEmpty() ? "0" : nz(in.successCodeValue());
            String equals = isNumeric(want) ? want : "\"" + want + "\"";
            asserts.add("{\"type\":\"jsonPath\",\"path\":\"" + esc(codePath) + "\",\"equals\":" + equals + "}");
            meaningful = true;
        }

        List<String> fields = in.responseFields() == null ? List.of() : in.responseFields();
        int used = 0;
        for (String f : fields) {
            if (f == null || f.isBlank() || used >= MAX_FIELD_ASSERTIONS) {
                continue;
            }
            String path = f.startsWith("$") ? f : "$." + f.trim();
            if (path.equals(codePath)) {
                continue;
            }
            // 只断言「字段在」，不断言值：值是什么只有跑一次才知道，
            // 猜一个填进去，第一次跑就是红的，而红得没有道理最消耗信任
            asserts.add("{\"type\":\"jsonPath\",\"path\":\"" + esc(path) + "\",\"exists\":true}");
            used++;
            meaningful = true;
        }

        String json = "[" + String.join(",", asserts) + "]";
        String base = nz(in.summary()).isEmpty()
                ? in.httpMethod() + " " + in.path()
                : in.summary();

        if (meaningful) {
            return new Draft(json, true, base,
                    used > 0
                            ? "已生成 " + used + " 条字段存在性断言，值需要跑一次之后按实际补"
                            : "已带上业务码断言。要验具体字段的话，跑一次拿到返回体再补");
        }
        return new Draft(json, false,
                base + "（待补断言）",
                "这个项目没配业务码，接口返回体字段也未知，只能生成 status=200——"
                        + "那等于没验。所以先生成为「停用」：跑一次拿到返回体，补上字段断言再启用。"
                        + "更省力的做法是在项目管理里配上业务码，配完这个项目所有用例自动带上 code 断言");
    }

    private static boolean isNumeric(String s) {
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
