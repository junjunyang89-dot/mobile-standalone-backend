package com.mobile.standalone.util;

import java.util.List;
import java.util.Locale;

/**
 * 批量执行的轨道，与 {@link TestEnvironment} 正交。
 * <p>
 * 两条轨道跑的是同一个执行器，区别只在<b>候选用例</b>和<b>顺序怎么来</b>：
 * <ul>
 *   <li>{@code REGRESSION}：勾选顺序即执行顺序。回归的顺序反映的是「这次想怎么验」，由人定。</li>
 *   <li>{@code AUTOMATION}：按流程声明的前置依赖拓扑排序，被依赖的流程一批里只跑一次。</li>
 * </ul>
 * 空值一律按 {@code REGRESSION}：线上回归是已经在用的东西，加轨道这件事不能让它有任何变化。
 */
public final class RunTrack {

    public static final String REGRESSION = "REGRESSION";
    public static final String AUTOMATION = "AUTOMATION";

    private RunTrack() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return REGRESSION;
        }
        return AUTOMATION.equals(raw.trim().toUpperCase(Locale.ROOT)) ? AUTOMATION : REGRESSION;
    }

    public static boolean isAutomation(String raw) {
        return AUTOMATION.equals(normalize(raw));
    }

    public static String label(String raw) {
        return isAutomation(raw) ? "自动化编排" : "回归";
    }

    /** 该轨道收哪些 catalog 的流程。SHARED 两条轨道都收——它的语义就是「两个入口都进」 */
    public static List<String> catalogs(String raw) {
        return isAutomation(raw) ? List.of("AUTOMATION", "SHARED") : List.of("ONLINE", "SHARED");
    }
}
