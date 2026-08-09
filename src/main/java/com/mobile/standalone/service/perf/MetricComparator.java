package com.mobile.standalone.service.perf;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把「这版的值」和「上版的值」变成一句能驱动动作的结论。
 * <p>
 * 纯计算，不碰数据库，好测。
 */
public final class MetricComparator {

    private MetricComparator() {
    }

    /**
     * 默认阈值 15%。
     * <p>
     * 定这个数是因为真机采样本身就有波动：同一个包连跑两次，CPU 均值差个 5~8% 很正常。
     * 阈值定太低（比如 5%）会天天报警，报到第三次就没人看了；
     * 定太高（30%）又会漏掉真正的劣化。15% 是「跑两次不会误报、真涨了能抓住」的位置。
     */
    public static final double DEFAULT_THRESHOLD_PERCENT = 15.0;

    /** 少数指标噪声特别大，单独放宽 */
    private static final Map<String, Double> THRESHOLDS = new LinkedHashMap<>(Map.of(
            // FPS 受当时在放什么内容影响极大，同一个包两次能差 20%
            "fps", 25.0,
            // 温度跟环境温度、连不连充电线都有关，参考价值最低
            "temperature", 30.0));

    private static final Map<String, String> LABELS = Map.of(
            "cpuApp", "CPU 使用率",
            "memoryPss", "内存 PSS",
            "fps", "帧率",
            "temperature", "温度",
            "networkRxBytes", "下行流量",
            "networkTxBytes", "上行流量",
            "coldStartMs", "冷启动耗时",
            "crashCount", "崩溃次数",
            "anrCount", "ANR 次数");

    private static final Map<String, String> UNITS = Map.of(
            "cpuApp", "%",
            "memoryPss", "KB",
            "fps", "fps",
            "temperature", "℃",
            "networkRxBytes", "B",
            "networkTxBytes", "B",
            "coldStartMs", "ms",
            "crashCount", "次",
            "anrCount", "次");

    public static double thresholdOf(String metricKey) {
        if (metricKey == null) {
            return DEFAULT_THRESHOLD_PERCENT;
        }
        Double t = THRESHOLDS.get(metricKey);
        return t != null ? t : DEFAULT_THRESHOLD_PERCENT;
    }

    /**
     * @param current         这次测出来的值
     * @param baseline        上个版本同机型的值，null＝第一次测
     * @param baselineVersion 基线取自哪个版本
     */
    public static MetricBaseline compare(String metricKey, Double current,
                                         Double baseline, String baselineVersion,
                                         String deviceModel) {
        MetricBaseline out = new MetricBaseline();
        out.setMetricKey(metricKey);
        out.setLabel(LABELS.getOrDefault(metricKey, metricKey));
        out.setUnit(UNITS.getOrDefault(metricKey, ""));
        out.setCurrent(current);
        out.setBaseline(baseline);
        out.setBaselineVersion(baselineVersion);
        out.setDeviceModel(deviceModel);
        MetricDirection dir = MetricDirection.of(metricKey);
        out.setDirection(dir);

        if (current == null) {
            out.setLevel(MetricBaseline.Level.NO_BASELINE);
            out.setSummary("本次没采到");
            return out;
        }
        if (baseline == null) {
            out.setLevel(MetricBaseline.Level.NO_BASELINE);
            out.setSummary("首次采集，" + fmt(current) + out.getUnit() + "，还没有可比的上个版本");
            return out;
        }
        if (baseline == 0d) {
            // 除零。基线是 0 说明上版就没这个东西（比如崩溃次数 0），
            // 这时候「涨了百分之几」没有意义，直接看绝对变化
            out.setDeltaPercent(null);
            boolean worse = dir == MetricDirection.LOWER_IS_BETTER && current > 0;
            out.setLevel(worse ? MetricBaseline.Level.WORSE : MetricBaseline.Level.STABLE);
            out.setSummary(worse
                    ? "上个版本是 0，这次是 " + fmt(current) + out.getUnit() + "，新出现的"
                    : "两版都是 0");
            return out;
        }

        // 判定用的是四舍五入后的值，跟 summary 里显示的是同一个数。
        // 用原始值判、显示舍入值，会出现两条都写着「15.0%」却一红一绿的情况——
        // 那种不一致会让人怀疑整份报告
        double delta = round((current - baseline) / Math.abs(baseline) * 100.0);
        out.setDeltaPercent(delta);
        double threshold = thresholdOf(metricKey);

        // 边界取「不报警」：正好压线的多半是噪声，报了只会稀释真问题
        if (dir == MetricDirection.NEUTRAL || Math.abs(delta) <= threshold) {
            out.setLevel(MetricBaseline.Level.STABLE);
            out.setSummary(String.format(Locale.ROOT, "%s%s（上版 %s%s，%s%.1f%%，在波动范围内）",
                    fmt(current), out.getUnit(), fmt(baseline), out.getUnit(),
                    delta >= 0 ? "+" : "", delta));
            return out;
        }

        boolean improved = (dir == MetricDirection.LOWER_IS_BETTER) == (delta < 0);
        out.setLevel(improved ? MetricBaseline.Level.BETTER : MetricBaseline.Level.WORSE);
        out.setSummary(String.format(Locale.ROOT, "%s%s，比 %s 版%s了 %.1f%%（%s%s → %s%s）",
                fmt(current), out.getUnit(), baselineVersion == null ? "上" : baselineVersion,
                improved ? "好" : "差", Math.abs(delta),
                fmt(baseline), out.getUnit(), fmt(current), out.getUnit()));
        return out;
    }

    private static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e12) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
