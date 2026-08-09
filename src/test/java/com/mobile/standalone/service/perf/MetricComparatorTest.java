package com.mobile.standalone.service.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基线对比。
 * <p>
 * 这组里最要紧的是 <b>方向</b>：FPS 涨了是好事，内存涨了是坏事。
 * 不带方向就会把帧率优化报成劣化——人核过一次假的，这份报告的可信度就没了，
 * 而它的全部价值就在可信。
 */
class MetricComparatorTest {

    private MetricBaseline cmp(String key, Double cur, Double base) {
        return MetricComparator.compare(key, cur, base, "3.1.0", "Pixel 6");
    }

    // ==================== 方向 ====================

    @Test
    void 内存涨了算变差() {
        MetricBaseline r = cmp("memoryPss", 380_000d, 280_000d);

        assertEquals(MetricBaseline.Level.WORSE, r.getLevel());
        assertEquals(35.7, r.getDeltaPercent());
        assertTrue(r.getSummary().contains("差"), r.getSummary());
        assertTrue(r.getSummary().contains("3.1.0"), "要说清楚跟哪个版本比：" + r.getSummary());
    }

    @Test
    void 内存降了算变好() {
        assertEquals(MetricBaseline.Level.BETTER, cmp("memoryPss", 200_000d, 280_000d).getLevel());
    }

    /** 本组核心。不带方向的话这条会被报成「恶化 29%」 */
    @Test
    void 帧率涨了算变好而不是变差() {
        MetricBaseline r = cmp("fps", 58d, 40d);

        assertEquals(MetricBaseline.Level.BETTER, r.getLevel(),
                "帧率越高越好，涨了报成劣化会让整份报告失去可信度");
        assertTrue(r.getDeltaPercent() > 0);
    }

    @Test
    void 帧率掉了算变差() {
        assertEquals(MetricBaseline.Level.WORSE, cmp("fps", 30d, 58d).getLevel());
    }

    @Test
    void 冷启动变慢算变差() {
        MetricBaseline r = cmp("coldStartMs", 1200d, 900d);

        assertEquals(MetricBaseline.Level.WORSE, r.getLevel());
        assertEquals(33.3, r.getDeltaPercent());
    }

    /** 操作次数这类只是记录，不判好坏——遍历点了 800 下还是 900 下不说明质量 */
    @Test
    void 中性指标不判好坏() {
        assertEquals(MetricBaseline.Level.STABLE, cmp("totalOperations", 900d, 500d).getLevel());
    }

    // ==================== 阈值 ====================

    /**
     * 真机采样本身有波动，同一个包连跑两次 CPU 差 5~8% 很正常。
     * 阈值太低会天天报警，报到第三次就没人看了。
     */
    @Test
    void 小幅波动算稳定() {
        MetricBaseline r = cmp("cpuApp", 21d, 20d);

        assertEquals(MetricBaseline.Level.STABLE, r.getLevel());
        assertTrue(r.getSummary().contains("波动范围内"), r.getSummary());
    }

    @Test
    void 刚好卡在阈值上算稳定() {
        assertEquals(MetricBaseline.Level.STABLE, cmp("cpuApp", 23d, 20d).getLevel(),
                "正好 15% 不该报警，边界要偏保守");
    }

    /** FPS 受当时在放什么内容影响极大，同一个包两次能差 20%，阈值单独放宽 */
    @Test
    void 帧率阈值比默认宽() {
        assertTrue(MetricComparator.thresholdOf("fps") > MetricComparator.DEFAULT_THRESHOLD_PERCENT);
        assertEquals(MetricBaseline.Level.STABLE, cmp("fps", 48d, 58d).getLevel(),
                "帧率 -17% 还在噪声里，报警会淹没真问题");
    }

    @Test
    void 温度阈值最宽() {
        assertTrue(MetricComparator.thresholdOf("temperature")
                > MetricComparator.thresholdOf("fps"));
    }

    // ==================== 边界 ====================

    @Test
    void 没有上版数据时说明是首次() {
        MetricBaseline r = cmp("memoryPss", 280_000d, null);

        assertEquals(MetricBaseline.Level.NO_BASELINE, r.getLevel());
        assertNull(r.getDeltaPercent());
        assertTrue(r.getSummary().contains("首次"), r.getSummary());
    }

    @Test
    void 本次没采到时不算劣化() {
        MetricBaseline r = cmp("fps", null, 58d);

        assertEquals(MetricBaseline.Level.NO_BASELINE, r.getLevel(),
                "采集失败跟质量变差是两回事，混在一起会误导");
    }

    /** 上版是 0（比如崩溃次数），算百分比没意义，看绝对变化 */
    @Test
    void 基线为零时看绝对变化() {
        MetricBaseline worse = cmp("crashCount", 3d, 0d);
        assertEquals(MetricBaseline.Level.WORSE, worse.getLevel());
        assertNull(worse.getDeltaPercent(), "除以 0 不能算出个百分比来");
        assertTrue(worse.getSummary().contains("新出现"), worse.getSummary());

        assertEquals(MetricBaseline.Level.STABLE, cmp("crashCount", 0d, 0d).getLevel());
    }

    // ==================== 方向推断 ====================

    @Test
    void 没登记过的指标也能推出方向() {
        assertEquals(MetricDirection.LOWER_IS_BETTER, MetricDirection.of("memoryPssPeak"),
                "新加的指标不该因为忘了登记就被静默当成中性——那等于它永远不报警");
        assertEquals(MetricDirection.LOWER_IS_BETTER, MetricDirection.of("launch_duration_ms"));
        assertEquals(MetricDirection.HIGHER_IS_BETTER, MetricDirection.of("avgFps"));
        assertEquals(MetricDirection.LOWER_IS_BETTER, MetricDirection.of("anrCount"));
    }

    @Test
    void 认不出来的按中性处理() {
        assertEquals(MetricDirection.NEUTRAL, MetricDirection.of("someRandomThing"));
        assertEquals(MetricDirection.NEUTRAL, MetricDirection.of(null));
    }
}
