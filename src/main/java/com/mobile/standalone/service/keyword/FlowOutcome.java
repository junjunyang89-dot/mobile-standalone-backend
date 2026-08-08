package com.mobile.standalone.service.keyword;

import java.util.List;
import java.util.Locale;

/**
 * 流程提前结束时的判定。
 * <p>
 * 「判断元素存在才往下走」是流程编排的主力写法，但元素没出现时流程只是悄悄提前返回，
 * 外面看到的和跑完全部步骤一模一样——第 2 步就退出的流程，回归报告上仍然是通过。
 * 这个枚举让编排的人自己说清楚：这个元素没出现，到底是正常、是 bug、还是本次不适用。
 */
public enum FlowOutcome {

    /** 必现元素没出现 = 缺陷，任务判失败 */
    FAIL("判失败"),

    /** 可选弹窗没出现很正常，任务照常通过 */
    PASS("判通过"),

    /** 前置条件不满足，本次不适用；线上回归记为「跳过」，不计入通过率 */
    SKIP("判跳过");

    private final String displayName;

    FlowOutcome(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 供关键字参数下拉使用，顺序即默认展示顺序 */
    public static List<String> optionNames() {
        return List.of(FAIL.name(), PASS.name(), SKIP.name());
    }

    /** 解析参数值；空或非法一律回落到 {@link #FAIL}——必现页面是主流写法，静默通过才是危险的默认 */
    public static FlowOutcome from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAIL;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FAIL;
        }
    }
}
