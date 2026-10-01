package com.mobile.standalone.service.apitest;

import java.util.Locale;

/**
 * 接口清单上给人看的那一列。
 * <p>
 * 它是<b>算出来的</b>，不是存下来的——存一列人手维护的状态，跟「已实现自动化」那个标记
 * 会犯同样的错：接口改了、用例删了，没人回来改这一列，于是清单越看越像真的，实际全是旧账。
 */
public enum ApiEndpointStatus {

    /** 人工确认已下线。和 DEPRECATED 分开：那个是扫描判的，这个是人拍板的，扫描不会把它改回去 */
    RETIRED("已标记废弃", "人工确认已下线：不再生成用例，也不算进未覆盖"),

    /** master 上已经没有这个接口了 */
    DEPRECATED("已废弃", "master 上找不到了。确认是有意下线还是分支合错——挂在它下面的用例先别删"),

    /** 本次迭代新增，还没写用例 */
    NEW_UNCOVERED("本版新增 · 待写用例", "这次迭代新加的接口，还没有用例"),

    /** 字段/入参变了，已有用例的断言可能过期 */
    CHANGED_NEEDS_REVIEW("有变更 · 待复核用例", "接口改了但用例没跟着改——断言还在验旧字段，跑起来照样是绿的，这类最容易漏"),

    /** 变了但一条用例都没有 */
    CHANGED_UNCOVERED("有变更 · 待补用例", "接口改了，而且从没写过用例"),

    /**
     * 有用例，但那些用例只断言了 HTTP 状态码。
     * <p>
     * 单独列一档，是因为把它算进「已覆盖」会让整份清单变成一句谎话：
     * 屏幕上 258 条全绿，实际上一个字段都没验过。<b>只验 200 的用例证明的是服务器还活着，
     * 不是这个接口对</b>——接口返回 {@code {"code":500,"data":null}} 时它照样是绿的。
     * <p>
     * 这一档比「未覆盖」更值得先处理：未覆盖你知道自己没验，
     * 而这一档会让人以为已经验过了。
     */
    WEAK_COVERED("有用例 · 但没在验",
            "有用例，但没有一条在真正验东西：要么只断言了 HTTP 200（接口返回错误码照样是绿的），要么用例是停用的"),

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
                || this == DEPRECATED || this == WEAK_COVERED;
    }

    /**
     * @param status     接口自身状态（ACTIVE / DEPRECATED）
     * @param changeFlag 本次迭代的变化标记
     * @param caseCount  这个接口下有多少条用例
     */
    public static ApiEndpointStatus of(String status, String changeFlag, int caseCount) {
        return of(status, changeFlag, caseCount, caseCount);
    }

    /**
     * @param strongCaseCount 其中<b>验了返回字段</b>的有几条。0 表示这个接口的用例全是只验 200 的
     */
    public static ApiEndpointStatus of(String status, String changeFlag, int caseCount, int strongCaseCount) {
        return of(status, changeFlag, caseCount, strongCaseCount, false);
    }

    /**
     * @param projectHasBizCode 项目配了业务成功码。配了的话，执行时每条用例都会自动追加一条
     *                          {@code $.code == 0} 断言——那是真验，所以不能再算「仅探活」。
     *                          <p>
     *                          这一条是实测出来的：只看存库的断言文本，会得出「配了业务码也没变化」的结论，
     *                          而页面上恰好又建议人去配业务码。照做了却看不到变化，比不给建议更糟。
     */
    public static ApiEndpointStatus of(String status, String changeFlag, int caseCount,
                                       int strongCaseCount, boolean projectHasBizCode) {
        return of(status, changeFlag, caseCount, strongCaseCount, caseCount, projectHasBizCode);
    }

    /**
     * @param enabledCaseCount 启用中的用例数。停用的用例什么都不验，
     *                         算它覆盖的话，把一条不稳定的用例停掉就能让清单变绿
     */
    public static ApiEndpointStatus of(String status, String changeFlag, int caseCount,
                                       int strongCaseCount, int enabledCaseCount,
                                       boolean projectHasBizCode) {
        if ("DEPRECATED".equalsIgnoreCase(nz(status))) {
            return DEPRECATED;
        }
        String flag = nz(changeFlag).toUpperCase(Locale.ROOT);
        boolean covered = caseCount > 0;
        // 有用例但一条都没验返回字段：这比没用例更需要先处理，
        // 因为它会让人以为已经验过了
        // 一条都没启用，或者启用的那些只验 HTTP 200（且项目没配业务码）——
        // 两种情况都是「有用例但没在验」
        boolean actuallyVerifying = enabledCaseCount > 0 && (strongCaseCount > 0 || projectHasBizCode);
        if (covered && !actuallyVerifying && flag.isEmpty()) {
            return WEAK_COVERED;
        }

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
