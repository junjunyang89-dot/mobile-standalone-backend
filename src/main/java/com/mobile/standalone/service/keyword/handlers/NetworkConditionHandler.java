package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 切换设备网络状态，用来测弱网/断网下的表现。
 * <p>
 * <b>关于「限速到 2G」：真机上做不到，这里也不假装能做。</b>
 * Android 免 root 拿不到 {@code tc}/{@code netem}，Appium 的 {@code networkSpeed}
 * 只对模拟器有效。真要按带宽/延迟限速，得走一个限速代理（Charles、Proxyman）或路由器——
 * 那是设备外的事，塞进这个关键字里只会得到一个「设置成功但其实没生效」的假绿。
 * <p>
 * 能可靠做到的是<b>通断</b>，而这恰恰是弱网测试里最值钱的一档：
 * 无网提示对不对、缓存还在不在、恢复网络后能不能自愈、重试会不会把请求打爆。
 * 线上真实用户遇到最多的也是这个（地铁、电梯、隧道）。
 */
@Slf4j
@Component
public class NetworkConditionHandler implements KeywordHandler {

    @Override
    public String code() {
        return "SET_NETWORK";
    }

    @Override
    public String nameZh() {
        return "切换网络";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "弱网专项：断网 / 只留 WiFi / 只留数据 / 恢复。仅 Android。"
                + "按带宽限速请走限速代理，设备侧免 root 做不到";
    }

    @Override
    public int sortOrder() {
        return 8;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.enumOf("mode", "网络状态",
                                List.of("OFFLINE", "WIFI_ONLY", "DATA_ONLY", "ALL"), "OFFLINE")
                        .withHelp("OFFLINE＝断网（测无网提示/缓存/重试）；"
                                + "WIFI_ONLY / DATA_ONLY＝只留一种；ALL＝恢复"),
                ParamDef.number("settleMs", "切换后等待毫秒", 3000)
                        .withHelp("切完网络状态不是立刻生效，端上也需要时间感知。默认 3000")
        );
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.SINGLE;
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (ctx.isIos()) {
            // iOS 侧没有等价的免越狱手段。说清楚而不是静默跳过——
            // 静默跳过会让一条「断网下应该报错」的用例在 iOS 上永远绿着
            throw new UnsupportedOperationException(
                    "切换网络暂不支持 iOS：免越狱没有等价手段。iOS 的弱网请用「设置 → 开发者 → Network Link Conditioner」人工配合");
        }
        String mode = ctx.substituted("mode");
        if (mode == null || mode.isBlank()) {
            mode = "OFFLINE";
        }
        String device = ctx.getDeviceId();
        boolean wifi = "WIFI_ONLY".equals(mode) || "ALL".equals(mode);
        boolean data = "DATA_ONLY".equals(mode) || "ALL".equals(mode);

        svc(device, "wifi", wifi);
        svc(device, "data", data);

        long settle = ctx.longParam("settleMs", 3000L);
        if (settle > 0) {
            Thread.sleep(Math.min(settle, 30_000L));
        }
        ctx.ok(ctx.sn("切换网络"), label(mode) + "（WiFi " + onOff(wifi) + " / 数据 " + onOff(data) + "）");
    }

    private static String label(String mode) {
        return switch (mode) {
            case "OFFLINE" -> "断网";
            case "WIFI_ONLY" -> "只留 WiFi";
            case "DATA_ONLY" -> "只留移动数据";
            default -> "恢复全部网络";
        };
    }

    private static String onOff(boolean b) {
        return b ? "开" : "关";
    }

    /**
     * {@code adb shell svc wifi enable|disable}。
     * <p>
     * 部分机型（小米/华为）会因为权限限制静默失败，所以下一步的断言不能只信这条命令的返回值，
     * 要靠界面上的无网提示来验——那本来也才是要测的东西。
     */
    private void svc(String deviceId, String what, boolean enable) throws Exception {
        String[] cmd = {"adb", "-s", deviceId, "shell", "svc", what, enable ? "enable" : "disable"};
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("svc " + what + " 超时");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("svc " + what + " 失败：" + sb.toString().trim()
                    + "（部分机型需要在开发者选项里允许 USB 调试修改权限）");
        }
    }
}
