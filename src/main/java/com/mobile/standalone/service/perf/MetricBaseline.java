package com.mobile.standalone.service.perf;

import lombok.Data;

/**
 * 一个指标和上个版本比的结果。
 * <p>
 * 这是整个专项测试报告的核心单元。原因很直接：<b>绝对值给不出结论</b>——
 * 冷启动 1.2 秒是好是坏？没人知道。换个机型、换个场景，阈值就不适用了。
 * 但「上版 0.9 秒，这版 1.2 秒，涨了 33%」是能直接拿去问开发的。
 */
@Data
public class MetricBaseline {

    /** 变化等级 */
    public enum Level {
        /** 明显变差，要去问开发 */
        WORSE,
        /** 明显变好 */
        BETTER,
        /** 在噪声范围内 */
        STABLE,
        /** 没有上个版本的数据，第一次测 */
        NO_BASELINE
    }

    private String metricKey;
    /** 中文名，给人看的 */
    private String label;
    private String unit;

    private Double current;
    private Double baseline;
    /** 基线取自哪个版本 */
    private String baselineVersion;
    /** 同机型才可比——不同机型的 CPU、FPS 根本不是一个量级 */
    private String deviceModel;

    /** 相对基线的变化百分比；基线为 0 或缺失时为 null */
    private Double deltaPercent;
    private Level level;
    private MetricDirection direction;

    /** 一句话结论，直接可以贴进报告 */
    private String summary;
}
