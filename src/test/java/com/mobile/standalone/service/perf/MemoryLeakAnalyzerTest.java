package com.mobile.standalone.service.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存泄漏判据的单测。
 *
 * <p>这组测试守的两件事，重要性排序是：
 * <ol>
 *   <li><b>不能误报</b>——报三次假的，第四次真的也没人信了</li>
 *   <li><b>样本不足时不能说「没泄漏」</b>——不知道和没问题是两回事</li>
 * </ol>
 * 「能抓到真泄漏」反而是最容易做到的那条。
 */
class MemoryLeakAnalyzerTest {

    private static final long MB = 1024L;

    private static List<Long> mb(double... values) {
        List<Long> out = new ArrayList<>();
        for (double v : values) {
            out.add((long) (v * MB));
        }
        return out;
    }

    // ---------- 抓得到真泄漏 ----------

    @Test
    @DisplayName("稳定上涨的曲线判为泄漏")
    void 稳定上涨判为泄漏() {
        // 2 轮预热 + 8 轮每轮涨 5MB
        List<Long> series = mb(100, 120, 140, 145, 150, 155, 160, 165, 170, 175);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.LEAKING, a.verdict(), a.summary());
        assertTrue(a.slopeKbPerRound() > 0);
        assertTrue(a.summary().contains("MB"), "结论里要有具体数字，光说泄漏没人信");
    }

    @Test
    @DisplayName("漏得不均匀也要抓到——真实泄漏不会每轮一样多")
    void 漏得不均匀也抓得到() {
        List<Long> series = mb(100, 110, 120, 128, 131, 140, 146, 152, 161, 168);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.LEAKING, a.verdict(), a.summary());
    }

    // ---------- 不能误报 ----------

    @Test
    @DisplayName("上下抖但不涨——不能报泄漏")
    void 抖动不报泄漏() {
        // 末值比首值高，但只是抖：朴素的「末值>首值」会在这里误报
        List<Long> series = mb(150, 148, 152, 145, 158, 144, 155, 147, 156, 153);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.STABLE, a.verdict(), a.summary());
    }

    @Test
    @DisplayName("涨得很直但总量很小——不值得报")
    void 小幅上涨不报泄漏() {
        // 完美直线，但 8 轮总共才涨 2MB，属于噪声量级
        List<Long> series = mb(100, 100, 100.2, 100.5, 100.7, 101, 101.3, 101.6, 101.9, 102.2);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.STABLE, a.verdict(), a.summary());
        assertTrue(a.summary().contains("门槛"), "要说清楚是因为没到门槛，而不是没在涨");
    }

    @Test
    @DisplayName("一路下降不能报泄漏")
    void 下降不报泄漏() {
        List<Long> series = mb(200, 190, 180, 170, 160, 150, 140, 130);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.STABLE, a.verdict(), a.summary());
        assertTrue(a.slopeKbPerRound() < 0);
    }

    @Test
    @DisplayName("完全不变不能报泄漏，也不能因为 R² 算不出来就出错")
    void 恒定值() {
        List<Long> series = mb(150, 150, 150, 150, 150, 150, 150, 150);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.STABLE, a.verdict(), a.summary());
    }

    // ---------- 预热轮 ----------

    @Test
    @DisplayName("预热轮的上涨不算泄漏——首次进页面加载资源是正常的")
    void 只有预热轮在涨不算泄漏() {
        // 前两轮猛涨（加载资源），之后完全平稳
        List<Long> series = mb(60, 110, 150, 150, 151, 150, 150, 151, 150, 150);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.STABLE, a.verdict(),
                "把预热轮算进趋势，等于给每次分析塞一个向上的偏置：" + a.summary());
    }

    @Test
    @DisplayName("预热轮要被排除在参与计算的数据之外")
    void 预热轮不参与计算() {
        List<Long> series = mb(60, 110, 150, 151, 150, 151, 150, 151);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(6, a.effective().size());
        assertEquals(150 * MB, a.effective().get(0));
    }

    // ---------- 样本不足：不知道 ≠ 没问题 ----------

    @Test
    @DisplayName("点太少必须返回「样本不足」，不能说没泄漏")
    void 样本太少不下结论() {
        List<Long> series = mb(100, 120, 140, 160, 180);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.INSUFFICIENT, a.verdict(),
                "预热后只剩 3 个点，任何斜率都拟合得出来");
        assertNotEquals(MemoryLeakAnalyzer.Verdict.STABLE, a.verdict(),
                "把「没测出来」说成「没问题」是这里最不该犯的错");
    }

    @Test
    @DisplayName("空数据返回样本不足")
    void 空数据() {
        assertEquals(MemoryLeakAnalyzer.Verdict.INSUFFICIENT,
                MemoryLeakAnalyzer.analyze(List.of()).verdict());
        assertEquals(MemoryLeakAnalyzer.Verdict.INSUFFICIENT,
                MemoryLeakAnalyzer.analyze(null).verdict());
    }

    @Test
    @DisplayName("样本不足的结论里必须写明「不等于没泄漏」")
    void 样本不足要说清楚() {
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(mb(100, 110, 120));
        String all = a.summary() + String.join("", a.notes());
        assertTrue(all.contains("不等于没泄漏"), "报告上必须看得出这是「没测出来」而不是「没问题」");
    }

    // ---------- 采样缺失 ----------

    @Test
    @DisplayName("没采到的轮次要排除，不能当成 0 参与回归")
    void 缺失值不当成零() {
        // 要留足 10 轮：剔掉 2 轮缺失、再丢 2 轮预热，剩 6 个点才够下结论。
        // （第一版这里只写了 8 轮，剔完只剩 4 个点，判据如实返回了「样本不足」——
        //   那是判据对了、测试数据写错了）
        List<Long> series = new ArrayList<>(mb(150, 150, 150, 150, 150, 150, 150, 150, 150, 150));
        series.set(4, null);
        series.set(6, 0L);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.STABLE, a.verdict(),
                "把没采到当成内存为 0，会凭空造出一个陡峭的斜率：" + a.summary());
        assertTrue(String.join("", a.notes()).contains("没采到"));
    }

    @Test
    @DisplayName("涨够了量但曲线在乱抖——R² 守卫必须拦住")
    void 幅度够但不成趋势不报泄漏() {
        // 这组数据专门隔离 R² 这一条：总增长 15MB（过了绝对门槛）、斜率为正，
        // 但 R²≈0.00——曲线在 148~190 之间乱跳，根本不是持续上涨。
        // 只看「斜率为正 + 涨够了量」的话，这里会报一个假泄漏
        List<Long> series = mb(150, 150, 150, 185, 148, 190, 152, 188, 155, 165);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertTrue(a.growthKb() >= MemoryLeakAnalyzer.DEFAULT_MIN_GROWTH_KB,
                "这组数据必须先过绝对门槛，才能隔离出 R² 的作用");
        assertTrue(a.slopeKbPerRound() > 0, "斜率也必须为正，否则拦住它的是斜率不是 R²");
        assertEquals(MemoryLeakAnalyzer.Verdict.STABLE, a.verdict(),
                "乱抖被当成了泄漏：" + a.summary());
    }

    @Test
    @DisplayName("把没采到当成 0，会凭空造出一个泄漏结论")
    void 缺失值当成零会造出假泄漏() {
        // 两轮没采到。当成 0 参与回归的话：总增长 150MB、斜率陡峭、R²=0.63，
        // 三个条件全中，直接报泄漏——而真实内存一直稳定在 150MB
        List<Long> series = new ArrayList<>(mb(150, 150, 150, 150, 150, 150, 150, 150, 150));
        series.set(2, 0L);
        series.set(3, 0L);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertEquals(MemoryLeakAnalyzer.Verdict.STABLE, a.verdict(),
                "没采到的轮次被当成「内存为 0」，造出了一条不存在的上涨趋势：" + a.summary());
    }

    // ---------- 系统回收的干扰 ----------

    @Test
    @DisplayName("中途骤降要标出来——它会把真泄漏的趋势压平")
    void 骤降要留旁注() {
        List<Long> series = mb(100, 120, 200, 220, 240, 120, 140, 160, 180, 200);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertTrue(String.join("", a.notes()).contains("系统回收"),
                "内存被 trim 过之后，真泄漏的斜率会被压平成「看着没事」");
    }

    // ---------- 结论可读性 ----------

    @Test
    @DisplayName("泄漏结论要给出每轮的值，让人能自己看")
    void 结论要带数据() {
        List<Long> series = mb(100, 120, 140, 145, 150, 155, 160, 165, 170, 175);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(series);
        assertTrue(a.effective().size() >= 5, "一个只说「泄漏了」的结论没人信，得能看到曲线");
    }

    // ---------- 参数可调 ----------

    @Test
    @DisplayName("门槛调低后小幅上涨也能报出来")
    void 门槛可调() {
        List<Long> series = mb(100, 100, 100.2, 100.5, 100.7, 101, 101.3, 101.6, 101.9, 102.2);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(
                series, 2, 1024L, MemoryLeakAnalyzer.DEFAULT_MIN_R_SQUARED);
        assertEquals(MemoryLeakAnalyzer.Verdict.LEAKING, a.verdict(), a.summary());
    }

    @Test
    @DisplayName("不丢预热轮时，预热的上涨会被算进去——这正是默认要丢掉它的原因")
    void 不丢预热轮会被带偏() {
        List<Long> series = mb(60, 110, 150, 150, 151, 150, 150, 151, 150, 150);
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(
                series, 0, MemoryLeakAnalyzer.DEFAULT_MIN_GROWTH_KB,
                MemoryLeakAnalyzer.DEFAULT_MIN_R_SQUARED);
        assertTrue(a.growthKb() > 80 * MB, "带上预热轮，总增长凭空多出 90MB");
    }

    // ---------- 数学部分 ----------

    @Test
    @DisplayName("斜率算对")
    void 斜率() {
        assertEquals(10.0, MemoryLeakAnalyzer.slope(Arrays.asList(0L, 10L, 20L, 30L)), 0.001);
        assertEquals(-5.0, MemoryLeakAnalyzer.slope(Arrays.asList(20L, 15L, 10L, 5L)), 0.001);
        assertEquals(0.0, MemoryLeakAnalyzer.slope(Arrays.asList(7L, 7L, 7L)), 0.001);
    }

    @Test
    @DisplayName("完美直线 R² 为 1")
    void r平方() {
        List<Long> line = Arrays.asList(0L, 10L, 20L, 30L, 40L);
        assertEquals(1.0, MemoryLeakAnalyzer.rSquared(line, MemoryLeakAnalyzer.slope(line)), 0.001);
    }
}
