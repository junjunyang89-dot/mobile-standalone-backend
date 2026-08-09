package com.mobile.standalone.service.perf;

import java.util.Locale;
import java.util.Map;

/**
 * 指标的「好方向」。
 * <p>
 * 基线对比不能只算涨跌百分比：<b>FPS 涨了是好事，内存涨了是坏事</b>。
 * 不带方向的话，帧率从 45 提到 58 会被报成「恶化 29%」——
 * 人核过一次假的就不信这份报告了，而这份报告的全部价值就在于可信。
 */
public enum MetricDirection {
    /** 越小越好：CPU、内存、耗时、温度、流量 */
    LOWER_IS_BETTER,
    /** 越大越好：FPS */
    HIGHER_IS_BETTER,
    /** 只记录，不判好坏：操作次数、截图数这类 */
    NEUTRAL;

    /**
     * 按指标名判方向。
     * <p>
     * 用后缀/关键词匹配而不是穷举全表：新加一个 {@code memoryPssPeak} 之类的指标，
     * 不该因为忘了在这里登记就被静默当成 NEUTRAL——那等于新指标永远不报警。
     */
    private static final Map<String, MetricDirection> EXACT = Map.of(
            "fps", HIGHER_IS_BETTER,
            "totaloperations", NEUTRAL,
            "screenshotcount", NEUTRAL,
            "launchcount", NEUTRAL,
            "clickcount", NEUTRAL,
            "swipecount", NEUTRAL);

    public static MetricDirection of(String metricKey) {
        if (metricKey == null || metricKey.isBlank()) {
            return NEUTRAL;
        }
        String k = metricKey.toLowerCase(Locale.ROOT).replace("_", "");
        MetricDirection exact = EXACT.get(k);
        if (exact != null) {
            return exact;
        }
        if (k.contains("fps") || k.contains("framerate")) {
            return HIGHER_IS_BETTER;
        }
        if (k.contains("cpu") || k.contains("memory") || k.contains("pss")
                || k.contains("temperature") || k.contains("duration") || k.contains("cost")
                || k.contains("time") || k.contains("bytes") || k.contains("traffic")
                || k.contains("battery") || k.contains("crash") || k.contains("anr")
                // 耗时类的名字五花八门：coldStartMs / launchLatency / renderElapsed，
                // 漏一个就是这个指标永远不报警——恰好这几个又都是最该盯的
                || k.contains("latency") || k.contains("elapsed") || k.contains("delay")
                || k.contains("start") || k.endsWith("ms")) {
            return LOWER_IS_BETTER;
        }
        return NEUTRAL;
    }
}
