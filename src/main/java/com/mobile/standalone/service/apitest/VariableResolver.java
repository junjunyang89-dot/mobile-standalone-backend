package com.mobile.standalone.service.apitest;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 URL / header / body / 断言里的 <code>{{变量}}</code> 换成真值。
 * <p>
 * 这东西是接口自动化能不能自己跑起来的分界线。没有它，token 只能写死在环境配置里，
 * 一过期几百条用例集体变红，人得手动去粘一个新的进去——那种方案等于没做。
 * 有了它，一条登录用例把 token 抽进上下文，后面的用例直接 {{token}}，每轮自己新鲜。
 * <p>
 * 取值优先级（高→低）：
 * <ol>
 *   <li><b>运行时上下文</b>——本轮前面的用例 extract 出来的，最新，优先级最高</li>
 *   <li><b>环境变量</b>——人配在环境里的固定值</li>
 *   <li><b>内置动态变量</b>——{@code $timestamp} / {@code $randomInt} / {@code $guid}，每次求值都不同</li>
 * </ol>
 */
public final class VariableResolver {

    private VariableResolver() {
    }

    /** {@code {{ name }}}，允许两侧留空格——人手写时很容易带上 */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z_$][\\w$.\\-]*)\\s*}}");

    /**
     * 变量值本身可以再引用变量（如 {@code authHeader = "Bearer {{token}}"}）。
     * 限制层数是为了防自引用死循环：{@code a = "{{b}}"}、{@code b = "{{a}}"} 这种配错一次就能把执行线程挂死。
     */
    private static final int MAX_DEPTH = 5;

    /** 找不到的变量报出来，别静默留着原样 */
    public static class UnresolvedException extends RuntimeException {
        public UnresolvedException(String message) {
            super(message);
        }
    }

    /**
     * @param text    含 {@code {{var}}} 的原文，null 原样返回
     * @param runtime 本轮运行时上下文（extract 写进来的），可为 null
     * @param envVars 环境变量，可为 null
     * @throws UnresolvedException 有变量取不到值时抛出，并列出是哪几个
     */
    public static String resolve(String text, Map<String, String> runtime, Map<String, String> envVars) {
        if (text == null || text.indexOf("{{") < 0) {
            return text;
        }
        Set<String> missing = new LinkedHashSet<>();
        String out = expand(text, runtime, envVars, missing, MAX_DEPTH);
        if (!missing.isEmpty()) {
            // 留着 {{token}} 原样发出去只会换来一个 401，看着像后端的锅，查半天才发现是变量没配。
            // 直接说清楚缺哪个、去哪儿配。
            throw new UnresolvedException("变量取不到值：" + String.join("、", missing)
                    + "。要么在「环境」里配上，要么让前面某条用例 extract 出来（比如登录用例抽 token）");
        }
        return out;
    }

    /** 宽松版：取不到就原样留着，用于给人看的预览（如列表里显示 URL），不能用于真正发请求 */
    public static String resolveLenient(String text, Map<String, String> runtime, Map<String, String> envVars) {
        if (text == null || text.indexOf("{{") < 0) {
            return text;
        }
        return expand(text, runtime, envVars, new LinkedHashSet<>(), MAX_DEPTH);
    }

    private static String expand(String text, Map<String, String> runtime, Map<String, String> envVars,
                                 Set<String> missing, int depth) {
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            String name = m.group(1);
            String value = lookup(name, runtime, envVars);
            if (value == null) {
                missing.add(name);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            changed = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        String out = sb.toString();
        // 展开出来的值里还有变量就再来一轮；depth 用尽时不再展开，留着原样比无限循环好
        if (changed && depth > 1 && out.indexOf("{{") >= 0) {
            return expand(out, runtime, envVars, missing, depth - 1);
        }
        return out;
    }

    private static String lookup(String name, Map<String, String> runtime, Map<String, String> envVars) {
        if (runtime != null && runtime.get(name) != null) {
            return runtime.get(name);
        }
        if (envVars != null && envVars.get(name) != null) {
            return envVars.get(name);
        }
        return builtin(name);
    }

    /**
     * 内置动态变量。每次求值都给新值——这正是它们的用途：
     * 造数据时手机号/昵称不能重复，写死一个跑第二遍就唯一键冲突。
     */
    static String builtin(String name) {
        return switch (name) {
            case "$timestamp" -> String.valueOf(System.currentTimeMillis());
            case "$timestampSec" -> String.valueOf(System.currentTimeMillis() / 1000);
            case "$randomInt" -> String.valueOf(ThreadLocalRandom.current().nextInt(0, 1_000_000));
            case "$guid", "$uuid" -> UUID.randomUUID().toString();
            default -> null;
        };
    }

    /** 供前端提示用：这份文本引用了哪些变量 */
    public static Set<String> referenced(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = VAR.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
