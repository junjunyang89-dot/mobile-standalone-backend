package com.mobile.standalone.util;

import java.util.Locale;

/**
 * 执行环境：测试环境 / 生产环境。
 * <p>
 * 之所以做成执行期参数而不是流程属性：两个环境的 APP 包名相同、元素定位通用、
 * 跑的也是同一批流程编排。给流程加环境字段会逼着把每条流程复制成两份，
 * 改一次要改两处，迟早不同步——而真正随环境变的只有「装哪个构建、用哪套验证码」。
 */
public final class TestEnvironment {

    public static final String TEST = "TEST";
    public static final String PROD = "PROD";

    private TestEnvironment() {
    }

    /** 空值一律按生产处理：存量任务与线上回归都没有这个字段，行为必须跟改动前完全一致 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return PROD;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return TEST.equals(s) ? TEST : PROD;
    }

    public static boolean isTest(String raw) {
        return TEST.equals(normalize(raw));
    }

    public static String label(String raw) {
        return isTest(raw) ? "测试环境" : "生产环境";
    }

    /** 任务名里的环境标记，方便列表一眼区分本次跑的是哪边 */
    public static String tag(String raw) {
        return isTest(raw) ? "[测试环境]" : "[生产环境]";
    }
}
