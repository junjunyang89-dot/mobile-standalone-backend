package com.mobile.standalone.service.apitest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从分支名里认出需求单号。
 * <p>
 * 你们的分支长这样：{@code fch/20260804/APP-8928} —— 单号<b>已经写在分支名里了</b>。
 * 所以「这个需求改了哪些接口」不需要人再填一遍：跑一次接口对比分析，
 * 变更的接口自动就能挂到那个单子上。
 * <p>
 * 让人手填是不行的：一个版本几十个单子、每个几个接口，填两周就没人填了，
 * 而填了一半的关联比没有更糟——你会以为没列出来的接口就是没改。
 */
public final class BranchIssueKey {

    private BranchIssueKey() {
    }

    /**
     * 单号形如 {@code APP-8928}。
     * <p>
     * 项目 key 只认纯字母（APP / PWA / DEADLOCK）。允许带数字的话，
     * {@code v2APP-1234} 会被认成项目 {@code V2APP} 的单子。
     * <p>
     * 这里有意选「宁可少认」：<b>关联错了是静默的</b>——接口挂到别的单子上，两边看着都正常；
     * 而关联漏了是看得见的——你会发现这个需求下面一个接口都没有，然后来问。
     * <p>
     * 数字限 2~6 位：1 位多半是版本号误伤（v1-2），7 位以上不是单号。
     */
    private static final Pattern KEY = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Z]{2,10})-(\\d{2,6})(?![0-9])");

    /**
     * 日期段会被误认成单号，先剔掉。
     * {@code fch/20260804/APP-8928} 里的 {@code 20260804} 不带字母前缀，
     * 正则本身不会匹配；但 {@code REL-20260804} 这种会——那是日期不是单号。
     */
    private static final Pattern DATE_LIKE = Pattern.compile("^(19|20)\\d{6}$");

    /**
     * 分支名里的所有单号，按出现顺序、去重。
     * <p>
     * 返回列表而不是单个：{@code feat/APP-100-APP-101} 这种一条分支带两个单的写法是存在的，
     * 只取第一个会让第二个单永远关联不上，而那是看不出来的。
     */
    public static List<String> allIn(String branch) {
        Set<String> out = new LinkedHashSet<>();
        if (branch == null || branch.isBlank()) {
            return List.of();
        }
        Matcher m = KEY.matcher(branch.trim().toUpperCase(Locale.ROOT));
        while (m.find()) {
            String num = m.group(2);
            if (DATE_LIKE.matcher(num).matches()) {
                continue;
            }
            out.add(m.group(1) + "-" + num);
        }
        return List.copyOf(out);
    }

    /** 第一个单号；认不出来返回 null。绝大多数分支只带一个 */
    public static String firstIn(String branch) {
        List<String> all = allIn(branch);
        return all.isEmpty() ? null : all.get(0);
    }
}
