package com.mobile.standalone.util;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定位串模板。
 * <p>
 * 两类占位符：
 * <ul>
 *   <li><b>随机下标</b> {@code [X]} / {@code [x]} / {@code [RAND]}——执行时随机替换为数字</li>
 *   <li><b>命名变量</b> {@code ${varName}}——执行时按参数替换，用于把会变的内容从定位里挪出去</li>
 * </ul>
 * 命名变量解决的是这类定位：{@code //*[@name="赛事高光 · 26分钟前"]}。
 * 里面钉着相对时间戳，过几分钟就匹配不上，换个环境更是必挂。写成
 * {@code //*[contains(@name,"${newsKeyword}")]} 之后，同一条定位在测试环境和生产环境都能用。
 * <p>
 * 随机下标的说明：
 * <ul>
 *   <li>{@code Other[X]} → 随机 1～3，如 {@code Other[2]}</li>
 *   <li>{@code Other[X:1-5]} → 随机 1～5</li>
 *   <li>{@code (//*[@id='card'])[X:2-4]} → 随机取第 2～4 个</li>
 * </ul>
 * 同一条定位里多个 [X] 共用同一次随机结果（便于列表项内相对路径一致）。
 */
public final class LocatorTemplate {

    private static final Pattern RAND_INDEX = Pattern.compile(
            "\\[(X|x|RAND|rand)(?:[:：](\\d+)\\s*-\\s*(\\d+))?]");

    /** ${变量名}：字母数字下划线，不含花括号 */
    private static final Pattern NAMED_VAR = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    /** 变量值里的数字区间：{@code 1-5} 或 {@code [X:1-5]}。锚定纯数字，
     *  免得把 {@code APP-9083}、{@code 2026-09-01} 这类带横杠的文案误当区间。 */
    private static final Pattern VALUE_RANGE_WRAPPED = Pattern.compile(
            "^\\[(?:X|x|RAND|rand)[:：](\\d+)\\s*-\\s*(\\d+)]$");
    private static final Pattern VALUE_RANGE_BARE = Pattern.compile(
            "^(\\d+)\\s*-\\s*(\\d+)$");

    private LocatorTemplate() {
    }

    public static boolean needsRandomIndex(String selector) {
        return selector != null && RAND_INDEX.matcher(selector).find();
    }

    /** 这条定位用到的变量名，按出现顺序去重。前端据此列出要填的输入框 */
    public static java.util.List<String> variablesIn(String selector) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (selector != null) {
            Matcher m = NAMED_VAR.matcher(selector);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return new java.util.ArrayList<>(names);
    }

    /**
     * 替换命名变量。
     *
     * @throws MissingVariableException 有变量没提供值时抛出。
     *         <p>刻意不静默留着字面量：那样会拿 {@code ${newsKeyword}} 去页面上找，
     *         得到一句「元素未找到」——看起来像页面改了，实际是忘了填参数。
     *         这种误导排查起来最费时间，不如当场说清楚缺哪个变量。
     */
    public static String expandVariables(String selector, java.util.Map<String, String> params) {
        if (selector == null || selector.isEmpty()) {
            return selector;
        }
        Matcher m = NAMED_VAR.matcher(selector);
        if (!m.find()) {
            return selector;
        }
        m.reset();
        java.util.List<String> missing = new java.util.ArrayList<>();
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(selector, last, m.start());
            String name = m.group(1);
            String value = params == null ? null : params.get(name);
            if (value == null || value.isBlank()) {
                if (!missing.contains(name)) {
                    missing.add(name);
                }
                out.append(m.group());
            } else {
                out.append(resolveValue(value));
            }
            last = m.end();
        }
        out.append(selector.substring(last));
        if (!missing.isEmpty()) {
            throw new MissingVariableException(missing);
        }
        return out.toString();
    }

    /**
     * 解析变量值：数字区间（{@code 1-5} 或 {@code [X:1-5]}）取区间内随机整数，
     * 固定数字与普通文案原样返回。让同一个变量既能填死值、又能填随机区间。
     */
    static String resolveValue(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        Matcher wrapped = VALUE_RANGE_WRAPPED.matcher(v);
        Matcher bare = VALUE_RANGE_BARE.matcher(v);
        int min;
        int max;
        if (wrapped.matches()) {
            min = parseBound(wrapped.group(1), 1);
            max = parseBound(wrapped.group(2), 1);
        } else if (bare.matches()) {
            min = parseBound(bare.group(1), 1);
            max = parseBound(bare.group(2), 1);
        } else {
            return value; // 固定数字、纯文案：原样
        }
        if (min > max) {
            int t = min;
            min = max;
            max = t;
        }
        return String.valueOf(ThreadLocalRandom.current().nextInt(min, max + 1));
    }

    /** 变量没填。带上变量名，让人一眼知道该填什么，而不是去查为什么元素找不到 */
    public static class MissingVariableException extends RuntimeException {
        private final java.util.List<String> names;

        public MissingVariableException(java.util.List<String> names) {
            super("定位变量未提供值: " + String.join("、", names));
            this.names = names;
        }

        public java.util.List<String> getNames() {
            return names;
        }
    }

    /**
     * 展开随机下标；无可替换片段时原样返回。
     */
    public static String expandRandomIndexes(String selector) {
        return expandRandomIndexes(selector, ThreadLocalRandom.current());
    }

    static String expandRandomIndexes(String selector, ThreadLocalRandom random) {
        if (selector == null || selector.isEmpty()) {
            return selector;
        }
        Matcher m = RAND_INDEX.matcher(selector);
        if (!m.find()) {
            return selector;
        }
        m.reset();
        StringBuilder out = new StringBuilder();
        Integer shared = null;
        int last = 0;
        while (m.find()) {
            out.append(selector, last, m.start());
            int min = 1;
            int max = 3;
            if (m.group(2) != null && m.group(3) != null) {
                min = parseBound(m.group(2), 1);
                max = parseBound(m.group(3), 3);
            }
            if (min > max) {
                int t = min;
                min = max;
                max = t;
            }
            min = Math.max(1, Math.min(100, min));
            max = Math.max(1, Math.min(100, max));
            if (min > max) {
                int t = min;
                min = max;
                max = t;
            }
            int picked;
            if (shared == null) {
                picked = random.nextInt(min, max + 1);
                shared = picked;
            } else {
                picked = Math.min(max, Math.max(min, shared));
            }
            out.append('[').append(picked).append(']');
            last = m.end();
        }
        out.append(selector.substring(last));
        return out.toString();
    }

    /**
     * 把 [X:1-10] 展开成范围内每条定位，而不是随机抽一个。
     * 列表里「滑到谁露出来点谁」用这个：不知道会命中哪一张，范围内任一可见即可。
     * 无占位符时原样一条。
     */
    public static java.util.List<String> expandAllIndexes(String selector) {
        if (selector == null || selector.isEmpty()) {
            return java.util.List.of();
        }
        Matcher probe = RAND_INDEX.matcher(selector);
        if (!probe.find()) {
            return java.util.List.of(selector);
        }
        int min = 1;
        int max = 3;
        if (probe.group(2) != null && probe.group(3) != null) {
            min = parseBound(probe.group(2), 1);
            max = parseBound(probe.group(3), 3);
        }
        if (min > max) {
            int t = min;
            min = max;
            max = t;
        }
        min = Math.max(1, Math.min(100, min));
        max = Math.max(1, Math.min(100, max));
        if (min > max) {
            int t = min;
            min = max;
            max = t;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = min; i <= max; i++) {
            out.add(replaceAllRandWith(selector, i));
        }
        return out;
    }

    private static String replaceAllRandWith(String selector, int index) {
        Matcher m = RAND_INDEX.matcher(selector);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(selector, last, m.start());
            int min = 1;
            int max = 100;
            if (m.group(2) != null && m.group(3) != null) {
                min = parseBound(m.group(2), 1);
                max = parseBound(m.group(3), 100);
                if (min > max) {
                    int t = min;
                    min = max;
                    max = t;
                }
            }
            int picked = Math.min(max, Math.max(min, index));
            out.append('[').append(picked).append(']');
            last = m.end();
        }
        out.append(selector.substring(last));
        return out.toString();
    }

    private static int parseBound(String raw, int def) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
