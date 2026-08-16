package com.mobile.standalone.service.apitest;

import java.util.Locale;

/**
 * 接口清单上给人看的那一列。
 * <p>
 * 它是<b>算出来的</b>，不是存下来的——存一列人手维护的状态，跟「已实现自动化」那个标记
 * 会犯同样的错：接口改了、用例删了，没人回来改这一列，于是清单越看越像真的，实际全是旧账。
 */
public enum ApiEndpointStatus {

    /** master 上已经没有这个接口了 */
    DEPRECATED("已废弃", "master 上找不到了。确认是有意下线还是分支合错——挂在它下面的用例先别删"),

    /** 本次迭代新增，还没写用例 */
    NEW_UNCOVERED("本版新增 · 待写用例", "这次迭代新加的接口，还没有用例"),

    /** 字段/入参变了，已有用例的断言可能过期 */
    CHANGED_NEEDS_REVIEW("有变更 · 待复核用例", "接口改了但用例没跟着改——断言还在验旧字段，跑起来照样是绿的，这类最容易漏"),

    /** 变了但一条用例都没有 */
    CHANGED_UNCOVERED("有变更 · 待补用例", "接口改了，而且从没写过用例"),

    /** 有用例且接口这次没变 */
    COVERED("已覆盖", "有用例，接口本次没有变更"),

    /** 接口没变，也从没写过用例 */
    UNCOVERED("未覆盖", "从没写过用例。不是每个接口都必须有，但你得知道它没有");

    private final String label;
    private final String hint;

    ApiEndpointStatus(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }

    /** 要不要在清单里高亮——待办的才高亮，已覆盖和未覆盖是常态 */
    public boolean actionable() {
        return this == NEW_UNCOVERED || this == CHANGED_NEEDS_REVIEW || this == CHANGED_UNCOVERED
                || this == DEPRECATED;
    }

    /**
     * @param status     接口自身状态（ACTIVE / DEPRECATED）
     * @param changeFlag 本次迭代的变化标记
     * @param caseCount  这个接口下有多少条用例
     */
    public static ApiEndpointStatus of(String status, String changeFlag, int caseCount) {
        if ("DEPRECATED".equalsIgnoreCase(nz(status))) {
            return DEPRECATED;
        }
        String flag = nz(changeFlag).toUpperCase(Locale.ROOT);
        boolean covered = caseCount > 0;

        if ("NEW".equals(flag)) {
            // 新增接口即使已经写了用例也不算「已覆盖」——它这次才出现，
            // 用例是刚写的，还没被任何一轮回归验证过
            return covered ? CHANGED_NEEDS_REVIEW : NEW_UNCOVERED;
        }
        if (!flag.isEmpty()) {
            // 变更了：有用例要复核（断言可能过期），没用例要补
            return covered ? CHANGED_NEEDS_REVIEW : CHANGED_UNCOVERED;
        }
        return covered ? COVERED : UNCOVERED;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
