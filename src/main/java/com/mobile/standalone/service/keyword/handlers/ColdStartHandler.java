package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 冷启动：Android force-stop / iOS terminateApp 后再启动，并记录耗时。
 * <p>
 * 合并了原 COLD_START / COLD_START_IOS——原实现本就是同一个方法加一个 iosKeyword 布尔，
 * 区别只在平台校验的报错文案。现在平台直接由任务决定，编排时不用再选端，也不会再选错。
 *
 * <p><b>这里的数字和专项性能里的冷启动不是一回事，别混着比。</b>
 * <ul>
 *   <li>专项性能走 {@code ColdStartMeasurer}：Android 用 {@code am start -W} 读系统给的
 *       {@code TotalTime}，五轮取中位数，测的是<b>系统视角的启动耗时</b>；</li>
 *   <li>这个关键字量的是墙钟：从发出启动指令到指令返回。Android 端它约等于启动耗时，
 *       <b>iOS 端它还包含 WDA 的通信往返</b>，会系统性地偏高。</li>
 * </ul>
 * 所以 iOS 的数只能看<b>自己版本之间的趋势</b>，不能和 Android 横比——
 * 报告里会原样写上这句话，因为一个没标口径的数字迟早会被人拿去横比。
 *
 * <p>默认只启一轮（流程里当「复位到冷启状态」最常见）。若要做耗时对比，
 * 步骤参数 {@code rounds} 可设为 3+：多轮取中位数，并丢掉第 1 轮预热——
 * 单轮波动能到 15~20%，和基线告警阈值撞车时容易报假劣化。
 */
@Component
public class ColdStartHandler implements KeywordHandler {

    @Override
    public String code() {
        return "COLD_START";
    }

    @Override
    public String nameZh() {
        return "冷启动";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "App 性能：杀掉进程后再启动，并记录冷启动耗时。Android / iOS 通用";
    }

    @Override
    public int sortOrder() {
        return 3;
    }

    @Override
    public List<String> aliases() {
        return List.of("COLD_START_IOS");
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    @Override
    public String shortLabel() {
        return "冷启";
    }

    /** 前端用应用字典选择器渲染（选应用自动带出包名/Activity），比通用参数表好用得多 */
    @Override
    public boolean customUi() {
        return true;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.string("packageName", "包名 / BundleId")
                        .withHelp("留空则取应用字典中该平台的包名"),
                ParamDef.string("activity", "appActivity").withHelp("仅 Android 生效"),
                ParamDef.string("appId", "应用字典ID"),
                ParamDef.number("rounds", "测几轮（取中位数，默认 1；性能对比建议 3+）", DEFAULT_ROUNDS)
        );
    }

    /** 默认轮数。流程复位只启一次；专项量耗时再把 rounds 调到 3+ */
    public static final int DEFAULT_ROUNDS = 1;

    /** 两轮之间等一下，让系统把进程真的收干净，否则下一轮会变成「切到前台」 */
    private static final long SETTLE_MS = 1500L;

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String pkg = ctx.packageName();
        boolean ios = ctx.isIos();
        int rounds = (int) Math.max(1, Math.min(10, ctx.longParam("rounds", DEFAULT_ROUNDS)));
        String platformLabel = ios ? " · iOS" : " · Android";

        List<Long> costs = new ArrayList<>();
        for (int i = 1; i <= rounds; i++) {
            ctx.ensureNotCancelled();
            if (i > 1) {
                // 不等的话下一轮量到的是「切前台」，那个数会小一个量级，
                // 混进中位数里比没有这轮还糟
                ctx.sleep(SETTLE_MS);
            }
            long t0 = System.currentTimeMillis();
            if (ios) {
                AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
                if (appium == null || !ctx.getRuntime().appiumServerManager().ensureRunning()) {
                    throw ctx.fail("冷启动" + platformLabel, "Appium 不可用", null, "冷启动 · iOS 需要 Appium");
                }
                if (!appium.coldStartIosApp(ctx.getDeviceId(), pkg)) {
                    throw ctx.fail("冷启动" + platformLabel, "第 " + i + " 轮冷启动失败: " + pkg, pkg,
                            "iOS 冷启动失败: " + pkg);
                }
            } else {
                LaunchAppHandler.launchAndroid(ctx, "冷启动" + platformLabel + "（第 " + i + " 轮）");
            }
            costs.add(System.currentTimeMillis() - t0);
        }

        if (ios) {
            ctx.screenshotWhenSettled("启动");
        }

        // 丢掉第 1 轮：那一轮带着系统缓存冷、页面资源没预热的成本
        List<Long> effective = costs.size() > 1 ? costs.subList(1, costs.size()) : costs;
        long median = median(effective);

        String detail = (ios ? "BundleId:" : "包名:") + pkg
                + "，中位数 " + median + " ms（" + effective.size() + " 轮有效，"
                + costs.stream().map(String::valueOf).collect(Collectors.joining("/")) + " ms）";
        if (ios) {
            // 这句话必须跟着数字走。不标口径的数字迟早会被拿去和 Android 横比
            detail += "；口径：墙钟计时，含 WDA 通信往返，偏高，只看 iOS 自己版本间的趋势，勿与 Android 横比";
        }
        ctx.ok("冷启动耗时" + platformLabel, detail);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
