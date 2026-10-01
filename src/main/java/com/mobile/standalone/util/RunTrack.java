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
    /**
     * 兼容性测试：核心流程 × 多台设备。
     * <p>
     * 候选只取标了核心的流程，不分回归/自动化用途；顺序和失败处理跟自动化一样——
     * 一台机上某条核心流程挂了，其余不相干的照跑，否则一张矩阵里只剩第一个红格有信息量。
     */
    public static final String COMPATIBILITY = "COMPATIBILITY";

    /**
     * 试跑：在流程编辑器里改完步骤跑一下看通不通。
     * <p>
     * <b>它不是一条批量轨道</b>——{@link #normalize} 刻意不认它，候选筛选和排序都跟它无关。
     * 它存在的唯一理由是<b>把调试产生的执行从质量数据里摘出去</b>：
     * 你在编辑器里调三次挂两次，那两次失败不该算进工作台通过率、不该被当成这条流程的
     * 「最近结果」、更不该触发告警。不区分的话，通过率这个数从第一天起就是不可信的，
     * 而一个不可信的数会被人一直拿去汇报，直到某天出事。
     */
    public static final String DEBUG = "DEBUG";

    private RunTrack() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return REGRESSION;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (AUTOMATION.equals(t) || COMPATIBILITY.equals(t)) {
            return t;
        }
        return REGRESSION;
    }

    public static boolean isAutomation(String raw) {
        return AUTOMATION.equals(normalize(raw));
    }

    public static boolean isCompatibility(String raw) {
        return COMPATIBILITY.equals(normalize(raw));
    }

    /** 按前置依赖拓扑排序、自动补入没勾的前置 */
    public static boolean ordersByDependency(String raw) {
        return isAutomation(raw) || isCompatibility(raw);
    }

    /** 一条失败只跳过依赖它的，其余继续跑；回归是线性串，一步挂了整批停 */
    public static boolean continuesOnFailure(String raw) {
        return isAutomation(raw) || isCompatibility(raw);
    }

    /**
     * 是不是试跑。
     * <p>
     * 刻意不走 {@link #normalize}——那个方法是给批量执行定候选和排序的，
     * 把 DEBUG 塞进去会让它必须在每个调用点都想一遍「试跑算哪条轨」，而答案是「都不算」。
     */
    public static boolean isDebug(String raw) {
        return raw != null && DEBUG.equalsIgnoreCase(raw.trim());
    }

    public static String label(String raw) {
        if (isDebug(raw)) {
            return "试跑";
        }
        if (isCompatibility(raw)) {
            return "兼容性测试";
        }
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
        if (isCompatibility(raw)) {
            return List.of("ONLINE", "AUTOMATION", "SHARED");
        }
        return isAutomation(raw) ? List.of("AUTOMATION", "SHARED") : List.of("ONLINE", "SHARED");
    }

    /** 装包类流程：单独一个入口取，不进回归/自动化候选 */
    public static List<String> installCatalogs() {
        return List.of(INSTALL);
    }
}
