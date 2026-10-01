package com.mobile.standalone.service.perf;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存泄漏判据：反复进出同一页面 N 次，看内存是不是只涨不降。
 *
 * <p><b>为什么要专门做这个</b>：泄漏是人工几乎测不到的一类问题——手点十次没人有耐心，
 * 而用户连着用一小时就卡死了。它又恰好是机器最擅长的：重复 N 次、记 N 个数、看趋势。
 *
 * <p><b>但朴素判据会天天误报</b>，而误报三次之后第四次真的也没人信了：
 * <ul>
 *   <li>「末值 &gt; 首值」——两次采样赶上不同的 GC 时机，差个几 MB 是常态</li>
 *   <li>「必须一直上升」——PSS 本来就抖，严格单调几乎不可能出现，于是永远报不出来</li>
 * </ul>
 *
 * <p>所以这里用<b>双条件</b>，两个都满足才算泄漏：
 * <ol>
 *   <li><b>趋势是真的</b>：线性回归斜率 &gt; 0，且 R² 达标——R² 低说明是上下抖，不是持续涨</li>
 *   <li><b>幅度值得管</b>：总增长超过一个绝对阈值——挡掉几 MB 的噪声</li>
 * </ol>
 *
 * <p>外加一条：<b>样本不够就不下结论</b>，返回 {@link Verdict#INSUFFICIENT}，
 * 而不是「没泄漏」。不知道和没问题是两回事——把前者说成后者，
 * 是这套平台里最不该犯的错。
 *
 * <p>纯计算，不碰设备也不碰库，每条规则都能用单测钉住。
 */
public final class MemoryLeakAnalyzer {

    private MemoryLeakAnalyzer() {
    }

    /**
     * 丢掉前几轮。
     *
     * <p>第一次进页面必然要加载图片、建缓存、初始化 WebView，内存涨是<b>正常</b>的，
     * 那不是泄漏。把预热轮算进趋势里，等于给每次分析都塞一个向上的偏置。
     */
    public static final int DEFAULT_WARMUP_ROUNDS = 2;

    /** 预热之后至少要这么多个点才敢下结论。三个点能拟合出任何斜率，说明不了问题 */
    public static final int MIN_EFFECTIVE_ROUNDS = 5;

    /**
     * 总增长的绝对门槛，10 MB。
     *
     * <p>低于这个数的「上涨」大多是采样噪声。定 10 MB 是因为：真实泄漏
     * （一个页面没释放 Bitmap、WebView 没 destroy）单轮就能漏几 MB，
     * 五轮下来远超 10 MB；而噪声累计很难到这个量级。
     */
    public static final long DEFAULT_MIN_GROWTH_KB = 10 * 1024L;

    /**
     * R² 门槛。
     *
     * <p>R² 衡量「这些点有多像一条直线」。持续泄漏的曲线非常直（每轮漏差不多的量），
     * 而噪声的 R² 很低。0.5 是个不苛刻但能挡住随机抖动的位置——
     * 定太高（0.9）会漏掉「漏得不均匀」的真泄漏。
     */
    public static final double DEFAULT_MIN_R_SQUARED = 0.5;

    /**
     * 单轮骤降多少算「系统回收过」。
     *
     * <p>内存涨到系统上限会被 trim 掉一大块，曲线掉下来，斜率随之变小——
     * <b>真泄漏反而可能因此判不出来</b>。检测到骤降就在结论里标出来，
     * 让人知道这个「没泄漏」的置信度是打折的。
     */
    private static final double DROP_RATIO = 0.20;

    public enum Verdict {
        /** 判定为泄漏 */
        LEAKING,
        /** 这些样本里看不出泄漏 */
        STABLE,
        /** 样本不足以下结论——不等于没泄漏 */
        INSUFFICIENT
    }

    /**
     * @param verdict      结论
     * @param slopeKbPerRound 每轮涨多少 KB（负数＝在降）
     * @param rSquared     趋势有多像直线，0~1
     * @param growthKb     有效轮次里的总增长
     * @param effective    参与计算的值（已去掉预热轮）
     * @param summary      一句话结论，直接进报告
     * @param notes        需要人知道的旁注（预热轮、疑似系统回收……）
     */
    public record Analysis(Verdict verdict,
                           double slopeKbPerRound,
                           double rSquared,
                           long growthKb,
                           List<Long> effective,
                           String summary,
                           List<String> notes) {
    }

    public static Analysis analyze(List<Long> pssKbPerRound) {
        return analyze(pssKbPerRound, DEFAULT_WARMUP_ROUNDS, DEFAULT_MIN_GROWTH_KB, DEFAULT_MIN_R_SQUARED);
    }

    /**
     * @param pssKbPerRound 每一轮结束、<b>主动触发 GC 之后</b>采到的 PSS（KB），按轮次顺序
     * @param warmupRounds  丢掉前几轮
     * @param minGrowthKb   总增长的绝对门槛
     * @param minRSquared   R² 门槛
     */
    public static Analysis analyze(List<Long> pssKbPerRound, int warmupRounds,
                                   long minGrowthKb, double minRSquared) {
        List<String> notes = new ArrayList<>();
        List<Long> clean = new ArrayList<>();
        if (pssKbPerRound != null) {
            for (Long v : pssKbPerRound) {
                // null 和非正值都是没采到，不是「内存为 0」。
                // 当成 0 参与回归会凭空造出一个陡峭的斜率
                if (v != null && v > 0) {
                    clean.add(v);
                }
            }
        }
        int dropped = (pssKbPerRound == null ? 0 : pssKbPerRound.size()) - clean.size();
        if (dropped > 0) {
            notes.add("有 " + dropped + " 轮没采到内存，已排除");
        }

        int warmup = Math.max(0, warmupRounds);
        if (clean.size() <= warmup) {
            return insufficient(clean.size(), warmup, notes,
                    "有效轮次 " + clean.size() + " 轮，还不够丢掉 " + warmup + " 轮预热");
        }
        List<Long> effective = new ArrayList<>(clean.subList(warmup, clean.size()));
        if (warmup > 0) {
            notes.add("已丢掉前 " + warmup + " 轮预热（首次进页面要加载资源，涨是正常的）");
        }

        if (effective.size() < MIN_EFFECTIVE_ROUNDS) {
            return insufficient(effective.size(), warmup, notes,
                    "预热后只剩 " + effective.size() + " 个点，少于 " + MIN_EFFECTIVE_ROUNDS
                            + " 个不下结论——点太少，任何斜率都拟合得出来");
        }

        double slope = slope(effective);
        double r2 = rSquared(effective, slope);
        long growth = effective.get(effective.size() - 1) - effective.get(0);

        String drop = detectDrop(effective);
        if (drop != null) {
            notes.add(drop);
        }

        boolean trendReal = slope > 0 && r2 >= minRSquared;
        boolean growthMatters = growth >= minGrowthKb;

        if (trendReal && growthMatters) {
            return new Analysis(Verdict.LEAKING, slope, r2, growth, effective,
                    String.format("疑似内存泄漏：%d 轮里涨了 %.1f MB，平均每轮 +%.2f MB（趋势拟合度 R²=%.2f）",
                            effective.size(), growth / 1024.0, slope / 1024.0, r2),
                    notes);
        }

        String why;
        if (!growthMatters && !trendReal) {
            why = String.format("没看出泄漏：总增长 %.1f MB 未达 %.0f MB 门槛，趋势也不明显（R²=%.2f）",
                    growth / 1024.0, minGrowthKb / 1024.0, r2);
        } else if (!growthMatters) {
            why = String.format("没看出泄漏：虽然在涨，但 %d 轮总共才 %.1f MB，未达 %.0f MB 门槛",
                    effective.size(), growth / 1024.0, minGrowthKb / 1024.0);
        } else {
            why = String.format("没看出泄漏：总增长 %.1f MB，但曲线是上下抖不是持续涨（R²=%.2f，低于 %.2f）",
                    growth / 1024.0, r2, minRSquared);
        }
        return new Analysis(Verdict.STABLE, slope, r2, growth, effective, why, notes);
    }

    private static Analysis insufficient(int have, int warmup, List<String> notes, String why) {
        notes.add("样本不足不等于没泄漏——这次只是没测出来");
        return new Analysis(Verdict.INSUFFICIENT, 0, 0, 0, List.of(), why, notes);
    }

    /**
     * 找有没有明显的骤降。
     *
     * <p>不改结论，只加一条旁注——但这条旁注很重要：内存被系统 trim 过之后，
     * 一个真泄漏的斜率会被压平成「看着没事」。
     */
    private static String detectDrop(List<Long> values) {
        for (int i = 1; i < values.size(); i++) {
            long prev = values.get(i - 1);
            long cur = values.get(i);
            if (prev > 0 && cur < prev * (1 - DROP_RATIO)) {
                return String.format("第 %d 轮内存骤降 %.0f%%（%.1f→%.1f MB），可能发生过系统回收，"
                                + "这会把真泄漏的趋势压平——本次结论的置信度打折",
                        i + 1, (prev - cur) * 100.0 / prev, prev / 1024.0, cur / 1024.0);
            }
        }
        return null;
    }

    /** 最小二乘斜率，x 取轮次序号 0,1,2… */
    static double slope(List<Long> y) {
        int n = y.size();
        double meanX = (n - 1) / 2.0;
        double meanY = 0;
        for (Long v : y) {
            meanY += v;
        }
        meanY /= n;
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            num += dx * (y.get(i) - meanY);
            den += dx * dx;
        }
        return den == 0 ? 0 : num / den;
    }

    /** 决定系数。全部相同时分母为 0，约定返回 0——一条水平线没有「趋势」可言 */
    static double rSquared(List<Long> y, double slope) {
        int n = y.size();
        double meanX = (n - 1) / 2.0;
        double meanY = 0;
        for (Long v : y) {
            meanY += v;
        }
        meanY /= n;
        double intercept = meanY - slope * meanX;
        double ssRes = 0;
        double ssTot = 0;
        for (int i = 0; i < n; i++) {
            double pred = slope * i + intercept;
            ssRes += Math.pow(y.get(i) - pred, 2);
            ssTot += Math.pow(y.get(i) - meanY, 2);
        }
        if (ssTot == 0) {
            return 0;
        }
        return Math.max(0, 1 - ssRes / ssTot);
    }
}
