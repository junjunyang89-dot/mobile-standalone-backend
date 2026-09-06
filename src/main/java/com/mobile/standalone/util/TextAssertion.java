package com.mobile.standalone.util;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文案断言的判定方式。
 *
 * <p><b>为什么要有 equals</b>：原来只有一种判定——{@code actual.contains(expected)}。
 * 它的问题不是「不够用」，是<b>会放过真 bug</b>：断言「余额 100」，页面显示「余额 1000」时照样通过。
 * 金额、数量、倒计时、比分这类场景全中招，而且失败时是<b>静默</b>的——用例绿着，缺陷溜过去了。
 * 生产巡检靠这种断言判「结果对不对」是不成立的。
 *
 * <p>默认仍是 {@code CONTAINS}，存量流程行为一个字不变；要严格判定的用例显式改成 {@code EQUALS}。
 *
 * <p>抽成独立类是为了能脱离 Spring 和真机直接测——判定逻辑错了，
 * 结果是「所有用例都在骗你」，这是最不该只靠肉眼 review 的一段代码。
 */
public final class TextAssertion {

    private TextAssertion() {
    }

    public enum Mode {
        /** 实际文案包含期望值（默认，历史行为） */
        CONTAINS("包含"),
        /** 完全相等，两边各自 trim 后比较 */
        EQUALS("等于"),
        /** 期望值当正则，对实际文案做查找（非全匹配，要全匹配自己写 ^$） */
        REGEX("匹配正则"),
        /** 实际文案不包含期望值 */
        NOT_CONTAINS("不包含");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 认不出的写法一律退回 CONTAINS：断言方式认错了不该让流程直接跑不了 */
        public static Mode from(String raw) {
            if (raw == null || raw.isBlank()) {
                return CONTAINS;
            }
            String v = raw.trim().toUpperCase(Locale.ROOT).replace("-", "_");
            for (Mode m : values()) {
                if (m.name().equals(v)) {
                    return m;
                }
            }
            if ("NOTCONTAINS".equals(v)) {
                return NOT_CONTAINS;
            }
            return CONTAINS;
        }
    }

    /** 正则写错时抛这个，调用方据此给出「正则语法错误」而不是「断言失败」——这两件事的处理方式完全不同 */
    public static class BadPatternException extends RuntimeException {
        public BadPatternException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @param actual   页面上读到的文案，可能为 null（元素没取到文案）
     * @param expected 期望值
     * @return 是否通过
     */
    public static boolean matches(Mode mode, String actual, String expected) {
        Mode m = mode == null ? Mode.CONTAINS : mode;
        // 读不到文案：除了「不包含」之外一律不通过。
        // 「不包含」在这里通过是刻意的——元素上没有文案，自然也就不含那段错误提示
        if (actual == null) {
            return m == Mode.NOT_CONTAINS;
        }
        if (expected == null) {
            return false;
        }
        switch (m) {
            case EQUALS:
                // 两边都 trim：控件文案常带首尾空白和换行，
                // 不 trim 的话 equals 几乎永远失败，人就会退回去用 contains
                return actual.trim().equals(expected.trim());
            case NOT_CONTAINS:
                return !actual.contains(expected);
            case REGEX:
                try {
                    return Pattern.compile(expected).matcher(actual).find();
                } catch (PatternSyntaxException e) {
                    throw new BadPatternException("正则写错了：" + e.getDescription(), e);
                }
            case CONTAINS:
            default:
                return actual.contains(expected);
        }
    }

    /** 失败时给人看的一句话 */
    public static String describeFailure(Mode mode, String actual, String expected) {
        Mode m = mode == null ? Mode.CONTAINS : mode;
        return "期望" + m.label() + "「" + expected + "」，实际「"
                + (actual == null ? "（读不到文案）" : actual) + "」";
    }
}
