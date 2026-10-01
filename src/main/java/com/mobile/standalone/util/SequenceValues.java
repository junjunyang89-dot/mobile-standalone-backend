package com.mobile.standalone.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把「依次点击」的序列收成一组值。
 * <p>
 * 两种写法：
 * <ul>
 *   <li>文案列表：逗号、顿号或换行，顺序就是点击顺序，如 {@code 没毛病,加油,可以}</li>
 *   <li>数字区间：{@code 2025-2013}、{@code 2025年-2013年}。起点大于终点就倒着走</li>
 * </ul>
 */
public final class SequenceValues {

    private static final int MAX_ITEMS = 100;

    /** 2025-2013 / 2025年-2013年 / 2025年到2013年 / 2025~2013 */
    private static final Pattern RANGE = Pattern.compile(
            "^(\\d{1,4})(年)?\\s*[-~～到至]\\s*(\\d{1,4})(年)?$");

    private SequenceValues() {
    }

    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("请填写依次取值：没毛病,加油,可以 或 2025-2013");
        }
        String text = raw.trim();
        Matcher range = RANGE.matcher(text);
        if (range.matches()) {
            return expandRange(
                    Integer.parseInt(range.group(1)),
                    Integer.parseInt(range.group(3)),
                    range.group(2) != null || range.group(4) != null);
        }
        List<String> items = new ArrayList<>();
        for (String part : text.split("[,，、\\n]+")) {
            String one = part.trim();
            if (!one.isEmpty()) {
                items.add(one);
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("依次取值是空的，请用逗号或换行列出文案，或填数字区间");
        }
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("一次最多 " + MAX_ITEMS + " 项，现在 " + items.size());
        }
        return List.copyOf(items);
    }

    private static List<String> expandRange(int from, int to, boolean yearSuffix) {
        int count = Math.abs(to - from) + 1;
        if (count > MAX_ITEMS) {
            throw new IllegalArgumentException("区间一共 " + count + " 项，超过上限 " + MAX_ITEMS);
        }
        int step = from <= to ? 1 : -1;
        List<String> items = new ArrayList<>(count);
        for (int n = from; n != to + step; n += step) {
            items.add(yearSuffix ? n + "年" : String.valueOf(n));
        }
        return List.copyOf(items);
    }
}
