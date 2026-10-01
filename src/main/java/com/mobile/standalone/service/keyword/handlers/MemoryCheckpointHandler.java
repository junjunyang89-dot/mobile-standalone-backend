package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.AndroidPerformanceCollector;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内存打点：在每一轮循环结束时记一个内存值，供泄漏分析用。
 *
 * <p><b>为什么不直接用资源采样的时间序列</b>：那条曲线里混着页面加载、动画、
 * 图片解码，同一轮里高低差几十 MB 很正常。判泄漏要比的是<b>每轮同一时刻</b>的值——
 * 页面退出、内存回落之后的那个点。混着采样点做回归，量的是「这一段在干什么」，
 * 不是「有没有漏」。
 *
 * <p>用法是在流程里摆成一个循环：
 * <pre>
 *   进入页面 → 滑动 → 返回 → 内存打点   ← 这四步重复 10 次
 * </pre>
 * 跑完由 {@code MemoryLeakAnalyzer} 分析这 10 个点。
 *
 * <p><b>取值前先催一次 GC 再等一下</b>。不催的话，测到的是「这一轮碰巧还没回收多少」，
 * 而 GC 什么时候跑不由我们决定——同一个包连跑两次能差出十几 MB，
 * 那种数据做出来的趋势线没有意义。
 *
 * <p>iOS 走 Appium 拿不到 PSS 等价物，<b>明确记「不支持」而不是补一个数</b>：
 * 一个缺失的点会让分析器返回「样本不足」，而一个编出来的点会让它给出错误的结论。
 */
@Component
@RequiredArgsConstructor
public class MemoryCheckpointHandler implements KeywordHandler {

    /**
     * 催 GC 之后等多久再取值。
     *
     * <p>{@code am send-trim-memory} 是异步的，命令返回不代表回收做完了。
     * 1.5 秒是「够大多数回收落地、又不至于让 10 轮循环拖太久」的位置。
     */
    private static final long SETTLE_MS = 1500L;

    private final AndroidPerformanceCollector androidPerformanceCollector;

    @Override
    public String code() {
        return "MEMORY_CHECKPOINT";
    }

    @Override
    public String nameZh() {
        return "内存打点";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "记一次当前内存（先催 GC 再取 PSS），配合循环用于内存泄漏分析。仅 Android";
    }

    @Override
    public int sortOrder() {
        return 4;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    @Override
    public String shortLabel() {
        return "内存点";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.string("label", "打点标签"),
                ParamDef.bool("forceGc", "先催 GC（关掉则测到的是「这轮碰巧还没回收多少」）", true));
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String label = ctx.str("label");
        String suffix = (label == null || label.isBlank()) ? "" : "（" + label + "）";

        if (ctx.isIos() || ctx.isHarmony()) {
            // 宁可少一个点，也不给一个来路不明的数：
            // 缺点会让分析器说「样本不足」，编的点会让它给出错误结论
            ctx.warn("内存打点" + suffix, "当前平台拿不到 PSS，本轮不记点（仅 Android 支持）", null);
            return;
        }

        String pkg = ctx.packageName();
        if (pkg == null || pkg.isBlank()) {
            throw ctx.fail("内存打点" + suffix, "包名为空，无法取内存", null,
                    "内存打点需要包名");
        }

        boolean forceGc = ctx.boolParam("forceGc", true);
        if (forceGc) {
            boolean triggered = androidPerformanceCollector.requestGc(ctx.getDeviceId(), pkg);
            if (!triggered) {
                // 催不动就说出来。分析时要知道这批数据的噪声更大
                ctx.warn("内存打点" + suffix, "催 GC 没成功，本点的值会偏高且抖动更大", null);
            }
            ctx.sleep(SETTLE_MS);
        }

        Long pssKb = androidPerformanceCollector.getMemoryPssKbFresh(ctx.getDeviceId(), pkg);
        if (pssKb == null || pssKb <= 0) {
            ctx.warn("内存打点" + suffix, "没取到内存值，本轮不记点", null);
            return;
        }

        // 值攒进 RunState，流程跑完由 PerformanceTestService 交给 MemoryLeakAnalyzer。
        // 日志文案是给人看趋势的，这个列表才是分析器的输入——两者分开，
        // 改文案不会弄坏分析
        ctx.getState().memoryCheckpoints.add(pssKb);
        int idx = ctx.getState().memoryCheckpoints.size();
        ctx.ok("内存打点" + suffix,
                String.format("第 %d 点：PSS %.1f MB%s", idx, pssKb / 1024.0,
                        forceGc ? "（已催 GC）" : "（未催 GC）"));
    }
}
