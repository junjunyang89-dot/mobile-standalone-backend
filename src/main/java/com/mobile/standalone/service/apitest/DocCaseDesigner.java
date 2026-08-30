package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 拿接口文档里的<b>参数约束</b>设计出一组用例。
 * <p>
 * 抓包只能给出「这次是怎么调的」，照它生成的用例是把同一次调用重放一遍——
 * 边界一条都验不到。而边界恰恰是文档里写得最清楚的东西：
 * <pre>
 *   mode    否  默认 1   1=标准，4=天梯；其它回落 1
 *   steamId 是  —        严格 32 位；&lt;=0 或 &gt; THRESHOLD → PARAM_ERROR
 * </pre>
 * 这两行直接对应四条用例：不传 mode 应等于 1、传 99 应回落成 1、
 * 不传 steamId 应报错、传 0 应报错。
 * <p>
 * <b>只生成请求，不猜断言。</b> 期望结果各接口不一样（有的回 PARAM_ERROR、
 * 有的回空数组），猜一个填进去，第一次跑就是红的——而红得没有道理最消耗信任。
 * 所以边界用例一律<b>默认停用</b>，并把文档原话贴在说明里，人扫一眼就能补上断言。
 * 平台省掉的是「把十几个请求一个个敲出来」这件苦力活，不是判断。
 */
public final class DocCaseDesigner {

    private DocCaseDesigner() {
    }

    /**
     * @param kind      MISSING_REQUIRED / DEFAULT / FALLBACK / BOUNDARY
     * @param name      用例名
     * @param query     这条用例要用的 query 参数；{@code null} 值表示<b>不传这个参数</b>
     * @param expectNote 文档原话，直接当成「预期」贴给人看
     * @param enabled   能不能直接启用。断言要人补的一律 false
     */
    public record Variant(String kind, String name, Map<String, String> query,
                          String expectNote, boolean enabled) {
    }

    /** 一个接口最多设计几条：十几条边界用例堆上来没人会逐条补断言，反而全被忽略 */
    private static final int MAX_VARIANTS = 8;

    /**
     * @param docParams  文档里的入参，来自 {@code doc_meta.params}
     * @param baseQuery  抓包抓到的真实参数值。<b>这是能跑起来的关键</b>——
     *                   边界用例只改一个参数，其余全用真实值，否则请求本身就是错的，
     *                   跑出来的失败说明不了任何问题
     */
    public static List<Variant> design(List<Map<String, Object>> docParams,
                                       Map<String, String> baseQuery) {
        List<Variant> out = new ArrayList<>();
        if (docParams == null || docParams.isEmpty()) {
            return out;
        }
        Map<String, String> base = baseQuery == null ? Map.of() : baseQuery;

        for (Map<String, Object> p : docParams) {
            if (out.size() >= MAX_VARIANTS) {
                break;
            }
            String name = str(p.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            boolean required = Boolean.TRUE.equals(p.get("required"));
            String def = str(p.get("default"));
            String note = str(p.get("note"));
            String n = note == null ? "" : note;

            if (required) {
                // 必填不传该报错。这条几乎不会错，也最容易被漏测——
                // 手工测很少有人真的去删一个必填参数试一次
                out.add(new Variant("MISSING_REQUIRED",
                        "不传必填参数 " + name,
                        omit(base, name),
                        expectOf(n, "应该报参数错误，而不是当成默认值继续算"),
                        false));
                if (looksNumeric(base.get(name)) && mentionsBoundary(n)) {
                    out.add(new Variant("BOUNDARY",
                            name + " 传 0（越界）",
                            with(base, name, "0"),
                            expectOf(n, "越界值应该被拒"),
                            false));
                }
                continue;
            }

            if (def != null && !def.isBlank()) {
                // 「不传时应等于默认值」——文档写了默认值，但没人验过它真的生效
                out.add(new Variant("DEFAULT",
                        "不传 " + name + "（应按默认值 " + def + " 处理）",
                        omit(base, name),
                        expectOf(n, "结果应与显式传 " + name + "=" + def + " 一致"),
                        false));
            }
            if (mentionsFallback(n)) {
                // 「其它回落 1」这种规则，是最典型的「文档写了但没人验」
                out.add(new Variant("FALLBACK",
                        name + " 传非法值（应回落）",
                        with(base, name, "99"),
                        expectOf(n, "非法值应回落到默认，而不是报错或返回空"),
                        false));
            }
        }
        return out;
    }

    /** 文档原话优先。归纳过的话一定丢信息，而原话人看得懂 */
    private static String expectOf(String note, String fallback) {
        return note == null || note.isBlank() ? fallback : note;
    }

    static boolean mentionsFallback(String note) {
        String n = note.toLowerCase(Locale.ROOT);
        return n.contains("回落") || n.contains("其它") || n.contains("其他")
                || n.contains("默认") || n.contains("fallback");
    }

    static boolean mentionsBoundary(String note) {
        return note.contains("<=") || note.contains("≤") || note.contains(">")
                || note.contains("非法") || note.contains("PARAM_ERROR")
                || note.contains("越界") || note.contains("阈值") || note.contains("THRESHOLD");
    }

    static boolean looksNumeric(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(v.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** null 值＝这个参数不传。用空串的话会变成 {@code ?steamId=}，那是「传了空值」，两回事 */
    private static Map<String, String> omit(Map<String, String> base, String name) {
        Map<String, String> m = new LinkedHashMap<>(base);
        m.remove(name);
        return m;
    }

    private static Map<String, String> with(Map<String, String> base, String name, String value) {
        Map<String, String> m = new LinkedHashMap<>(base);
        m.put(name, value);
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
