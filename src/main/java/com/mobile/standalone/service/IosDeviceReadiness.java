package com.mobile.standalone.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 接一台新 iOS 设备前的体检。
 * <p>
 * iOS 接新机必然有一段人工步骤（配对信任、开发者模式、证书信任），这是 Apple 有意为之，
 * 平台绕不过去。<b>但「不知道卡在哪一步」是可以消掉的</b>——
 * 现在的表现是「WDA 装完就没了」，从这个现象反查到「签名 team 里没有这台设备」
 * 中间隔了十万八千里，人只能一条条试。
 * <p>
 * 这里把四个前提逐条查出来，直接说缺哪个、去哪儿点。
 */
@Slf4j
@Service
public class IosDeviceReadiness {

    /** Appium 默认的 WDA bundle 前缀，自定义签名时会是别的 */
    private static final String WDA_HINT = "WebDriverAgent";

    @Data
    public static class Check {
        /** PASS / FAIL / UNKNOWN */
        private String status;
        private String name;
        /** 查到了什么 */
        private String detail;
        /** 没过的话该怎么做——必须具体到点哪里，「请检查配置」这种等于没说 */
        private String howToFix;
    }

    @Data
    public static class Report {
        private String udid;
        private boolean ready;
        private List<Check> checks = new ArrayList<>();
        /** 一句话结论，直接可以贴给同事 */
        private String summary;
    }

    public Report check(String udid) {
        Report r = new Report();
        r.setUdid(udid);

        String info = exec("ideviceinfo", "-u", udid);
        r.getChecks().add(pairing(info));
        r.getChecks().add(developerMode(udid, info));
        r.getChecks().add(wdaInstalled(udid));
        r.getChecks().add(iosVersionNote(info));

        List<Check> failed = r.getChecks().stream()
                .filter(c -> "FAIL".equals(c.getStatus())).toList();
        r.setReady(failed.isEmpty());
        r.setSummary(summarize(failed));
        return r;
    }

    static String summarize(List<Check> failed) {
        if (failed.isEmpty()) {
            return "四项前提都满足，可以直接跑 iOS 自动化";
        }
        // 只报第一条：这几步是有先后的（没配对就查不了开发者模式），
        // 一次列四条会让人不知道从哪下手
        Check first = failed.get(0);
        return "还差 " + failed.size() + " 项，先解决：" + first.getName() + " —— " + first.getHowToFix();
    }

    private Check pairing(String info) {
        Check c = new Check();
        c.setName("设备已配对信任");
        if (info == null || info.isBlank() || info.contains("No device found")
                || info.contains("Could not connect")) {
            c.setStatus("FAIL");
            c.setDetail("ideviceinfo 读不到设备");
            c.setHowToFix("用数据线连上，手机上会弹「信任此电脑」，点信任并输入锁屏密码；"
                    + "然后在执行机上跑 idevicepair pair");
            return c;
        }
        c.setStatus("PASS");
        c.setDetail(valueOf(info, "DeviceName") + " · iOS " + valueOf(info, "ProductVersion"));
        return c;
    }

    /**
     * iOS 16+ 必须手动打开开发者模式，而且要重启手机。
     * 关着的话 WDA 装得上但起不来，报错跟签名问题长得很像。
     */
    private Check developerMode(String udid, String info) {
        Check c = new Check();
        c.setName("开发者模式已开启");
        String ver = valueOf(info, "ProductVersion");
        if (ver != null && majorOf(ver) > 0 && majorOf(ver) < 16) {
            c.setStatus("PASS");
            c.setDetail("iOS " + ver + " 无需开发者模式");
            return c;
        }
        String out = exec("ideviceinfo", "-u", udid, "-q", "com.apple.security.mac.amfi",
                "-k", "DeveloperModeStatus");
        if (out == null || out.isBlank()) {
            c.setStatus("UNKNOWN");
            c.setDetail("查不到（libimobiledevice 版本可能不支持这个 key）");
            c.setHowToFix("手机上确认：设置 → 隐私与安全性 → 开发者模式，打开后需重启手机");
            return c;
        }
        if (out.trim().toLowerCase(Locale.ROOT).startsWith("true")) {
            c.setStatus("PASS");
            c.setDetail("已开启");
        } else {
            c.setStatus("FAIL");
            c.setDetail("未开启");
            c.setHowToFix("手机上：设置 → 隐私与安全性 → 开发者模式 → 打开 → 重启手机。"
                    + "找不到这一项的话，先用数据线连一次 Xcode，这个开关才会出现");
        }
        return c;
    }

    /**
     * WDA 在不在。
     * <p>
     * 「装完就被卸载」正是这一项时有时无——免费个人账号签的描述文件只有 7 天，
     * 而且这台设备必须先注册进签名 team，否则装上去立刻被系统移除。
     */
    private Check wdaInstalled(String udid) {
        Check c = new Check();
        c.setName("WebDriverAgent 已安装且未过期");
        String out = exec("ideviceinstaller", "-u", udid, "list");
        if (out == null || out.isBlank()) {
            c.setStatus("UNKNOWN");
            c.setDetail("列不出已装应用");
            c.setHowToFix("确认 ideviceinstaller 可用（brew install ideviceinstaller），且设备已配对");
            return c;
        }
        if (out.contains(WDA_HINT) || out.toLowerCase(Locale.ROOT).contains("wda")) {
            c.setStatus("PASS");
            c.setDetail("已安装");
            return c;
        }
        c.setStatus("FAIL");
        c.setDetail("设备上没有 WDA");
        c.setHowToFix("跑一次 iOS 任务会自动装。如果<b>装完立刻消失</b>，是签名问题："
                + "免费个人账号的描述文件只有 7 天，且这台设备的 UDID 必须先注册进签名 team——"
                + "用 Xcode 打开 WebDriverAgent 工程，选上这台设备 Run 一次即可注册；"
                + "要一劳永逸就换付费开发者账号或企业证书（profile 一年）");
        return c;
    }

    /** iOS 17+ 的一些路径 libimobiledevice 支持滞后，提前说一声省得白查 */
    private Check iosVersionNote(String info) {
        Check c = new Check();
        c.setName("工具链与系统版本匹配");
        String ver = valueOf(info, "ProductVersion");
        int major = majorOf(ver);
        if (major >= 17) {
            c.setStatus("UNKNOWN");
            c.setDetail("iOS " + ver);
            c.setHowToFix("iOS 17+ 建议装包走 xcrun devicectl（Xcode 15+），"
                    + "libimobiledevice 对新系统的支持常滞后。平台已优先用 devicectl，装不上再退回");
            return c;
        }
        c.setStatus("PASS");
        c.setDetail(ver == null ? "—" : "iOS " + ver);
        return c;
    }

    /** 从 ideviceinfo 输出里取一行 {@code Key: Value} */
    static String valueOf(String info, String key) {
        if (info == null || key == null) {
            return null;
        }
        for (String line : info.split("\r?\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equals(key)) {
                return line.substring(i + 1).trim();
            }
        }
        return null;
    }

    /** 「17.4.1」→ 17；取不到返回 0，调用方据此跳过版本相关判断 */
    static int majorOf(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        String head = version.trim().split("\\.")[0];
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String exec(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < 200_000) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor(20, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            log.debug("[ios-readiness] {} 执行失败: {}", cmd[0], e.getMessage());
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
