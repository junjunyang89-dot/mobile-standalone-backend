package com.mobile.standalone.service.perf;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基线聚合从「最近一次」改成「中位数」。
 *
 * <p>为什么非改不可：告警阈值是 15%，而 CPU、冷启动的单次波动本来就能到这个量级。
 * 靠单次采样去比，这套报告会经常报假劣化——<b>而报三次假的之后，第四次真的也没人信了</b>。
 *
 * <p>原来选「最近一次」的理由是「平均会把异常采样摊进去」——那个理由对，
 * 但中位数同时解决了两件事：它不受离群值影响，也不取决于哪一轮碰巧跑在最后。
 */
class MetricBaselineMedianTest {

    @Test
    void 跑过一次时等于那一次() {
        assertEquals(1800.0, MetricBaselineService.median(List.of(1800.0)));
    }

    /** 关键性质：一次异常采样不该动摇结论 */
    @Test
    void 一次异常采样拽不动结论() {
        List<Double> runs = List.of(1800.0, 1830.0, 5400.0);
        assertEquals(1830.0, MetricBaselineService.median(runs));
    }

    /**
     * 「最近一次」的毛病：结论取决于哪轮跑在最后。
     *
     * <p>同样三次运行，只是顺序不同，「最近一次」会给出 1800 或 5400 两个天差地别的基线，
     * 而中位数两种顺序下都是 1830。
     */
    @Test
    void 顺序不影响中位数() {
        assertEquals(MetricBaselineService.median(Arrays.asList(1800.0, 1830.0, 5400.0)),
                MetricBaselineService.median(Arrays.asList(5400.0, 1830.0, 1800.0)));
    }

    @Test
    void 偶数个取中间两个的平均() {
        assertEquals(1825.0, MetricBaselineService.median(List.of(1800.0, 1850.0)));
    }

    @Test
    void 空和null() {
        assertNull(MetricBaselineService.median(List.of()));
        assertNull(MetricBaselineService.median(null));
        assertNull(MetricBaselineService.median(Arrays.asList((Double) null, null)));
    }

    /** 和 MetricComparator 串起来：中位数稳住之后，同样的数据不该再报劣化 */
    @Test
    void 单次噪声不再触发假劣化() {
        // 上版三次：1800 / 1830 / 1810，中位 1810
        Double base = MetricBaselineService.median(List.of(1800.0, 1830.0, 1810.0));
        // 这版三次：1820 / 1840 / 2150（最后一次碰上后台任务），中位 1840
        Double cur = MetricBaselineService.median(List.of(1820.0, 1840.0, 2150.0));
        MetricBaseline r = MetricComparator.compare("coldStartMs", cur, base, "4.1.6", "Pixel 7");
        assertEquals(MetricBaseline.Level.STABLE, r.getLevel(),
                "中位数之后仍被判成劣化：" + r.getSummary());

        // 而如果按「最近一次」比，2150 vs 1810 涨了 18.8%，越过 15% 阈值 → 假劣化
        MetricBaseline wrong = MetricComparator.compare("coldStartMs", 2150.0, 1810.0, "4.1.6", "Pixel 7");
        assertEquals(MetricBaseline.Level.WORSE, wrong.getLevel());
        assertTrue(wrong.getDeltaPercent() > 15.0);
    }
}
