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
    /**
     * 装包准备。<b>不属于任何一条轨道</b>——它不是用例，是把设备弄成可测状态的前置动作。
     * <p>
     * 混在回归/自动化的勾选列表里有两个害处：通过率被稀释（20 条里两条是装包，
     * 那两条通过不说明任何功能是好的），流程一多也找不着——你要装包时得在几百条用例里翻。
     */
    public static final String INSTALL = "INSTALL";

    public static List<String> catalogs(String raw) {
        return isAutomation(raw) ? List.of("AUTOMATION", "SHARED") : List.of("ONLINE", "SHARED");
    }

    /** 装包类流程：单独一个入口取，不进回归/自动化候选 */
    public static List<String> installCatalogs() {
        return List.of(INSTALL);
    }
}
