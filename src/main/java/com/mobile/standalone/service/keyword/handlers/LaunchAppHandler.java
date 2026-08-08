package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动应用。
 * <p>
 * 合并了原 LAUNCH_APP / LAUNCH_APP_IOS：平台由任务决定，编排时不必再选端。
 * Android 走 appPackage/appActivity（Appium 失败可回退 ADB），iOS 走 Appium forceAppLaunch。
 */
@Component
public class LaunchAppHandler implements KeywordHandler {

    @Override
    public String code() {
        return "LAUNCH_APP";
    }

    @Override
    public String nameZh() {
        return "启动应用";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "启动目标应用；Android 可填 appActivity，iOS 用 BundleId。包名缺省取应用字典。"
                + "Android 底层会先 force-stop，行为偏冷启";
    }

    @Override
    public int sortOrder() {
        return 5;
    }

    @Override
    public List<String> aliases() {
        return List.of("LAUNCH_APP_IOS");
    }

    /** 启动过程自己连拍，不再由执行器前后各叠一张 */
    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    @Override
    public String shortLabel() {
        return "启动";
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
                ParamDef.string("activity", "appActivity")
                        .withHelp("仅 Android 生效"),
                ParamDef.string("appId", "应用字典ID")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (ctx.isHarmony()) {
            launchHarmony(ctx);
        } else if (ctx.isIos()) {
            launchIos(ctx);
        } else {
            launchAndroid(ctx, "启动应用");
        }
    }

    static void launchHarmony(KeywordContext ctx) throws Exception {
        String pkg = ctx.packageName();
        String ability = KeywordContext.firstNonBlank(
                ctx.str("activity"),
                ctx.getExecParams() != null ? ctx.getExecParams().get("activity") : null);
        String target = (ability != null && !ability.isBlank()) ? (pkg + "/" + ability) : pkg;
        boolean ok = ctx.getDriver().launchApp(ctx.getDeviceId(), target);
        if (!ok) {
            throw ctx.fail("启动应用 · 鸿蒙",
                    "启动失败: " + target + "。请确认 hdc 已授权、设备已安装该 bundle（可用 bundle 或 bundle/Ability）。",
                    target, "鸿蒙启动应用失败: " + target);
        }
        ctx.ok("启动应用 · 鸿蒙", "包名:" + target + " (hdc/hmdriver2)");
        ctx.screenshotWhenSettled("启动");
    }

    /** 冷启动关键字复用这套 Android 启动逻辑，只是日志标题不同 */
    static void launchAndroid(KeywordContext ctx, String logTitle) throws Exception {
        String pkg = ctx.packageName();
        String activity = KeywordContext.firstNonBlank(
                ctx.str("activity"), ctx.getExecParams() != null ? ctx.getExecParams().get("activity") : null);
        boolean androidLike = ctx.isAndroidLike();
        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();

        boolean ok = false;
        String launchMode = null;

        if (appium != null) {
            if (ctx.getRuntime().appiumServerManager().ensureRunning()) {
                ctx.ok("Appium", "Server 已就绪，使用 Appium 启动应用");
                if (androidLike && activity != null && !activity.isBlank()) {
                    ok = appium.launchAndroidWithPackageAndRelativeActivity(ctx.getDeviceId(), pkg, activity);
                } else {
                    ok = appium.launchApp(ctx.getDeviceId(), pkg);
                }
                if (ok) {
                    launchMode = "Appium";
                } else {
                    ctx.warn("Appium启动",
                            "已连通 Appium，但创建会话/启动失败。请确认设备已连接且已安装: " + pkg
                                    + (activity != null ? " / " + activity : ""), pkg);
                }
            } else {
                ctx.warn("Appium",
                        "无法拉起或连通 Appium Server（请检查 appium 是否已安装，日志 ~/.mobile-standalone/logs/appium-server.log）",
                        null);
            }
        }

        if (!ok && androidLike) {
            ctx.ok("启动回退", "Appium 不可用，临时用 ADB am start（能力受限）");
            ok = ctx.getRuntime().adbLaunchAndroid(ctx.getDeviceId(), pkg, activity);
            if (ok) {
                launchMode = "ADB";
            }
        }

        if (!ok) {
            ok = ctx.getDriver().launchApp(ctx.getDeviceId(), pkg);
            if (ok) {
                launchMode = launchMode != null ? launchMode : "driver";
            }
        }

        if (!ok) {
            throw ctx.fail(logTitle,
                    "启动失败: " + pkg
                            + "。请确认：1) Appium 可自动启动或手动 appium -p 4723；2) 设备已安装该包；3) Activity 正确。",
                    pkg, "启动应用失败: " + pkg);
        }
        ctx.ok(logTitle, "包名:" + pkg
                + (activity != null && !activity.isBlank() ? " activity:" + activity : "")
                + " (" + launchMode + ")");
        ctx.screenshotWhenSettled("启动");
    }

    private void launchIos(KeywordContext ctx) throws Exception {
        String pkg = ctx.packageName();
        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();

        boolean ok = false;
        if (appium != null && ctx.getRuntime().appiumServerManager().ensureRunning()) {
            ctx.ok("Appium", "Server 已就绪，使用 Appium 启动 iOS 应用");
            ok = appium.launchApp(ctx.getDeviceId(), pkg);
        }
        if (!ok) {
            ok = ctx.getDriver().launchApp(ctx.getDeviceId(), pkg);
        }
        if (!ok) {
            throw ctx.fail("启动应用 · iOS",
                    "启动失败: " + pkg + "。请确认 WDA/Appium 可用且 BundleId 已安装。",
                    pkg, "iOS 启动应用失败: " + pkg);
        }
        ctx.ok("启动应用 · iOS", "BundleId:" + pkg);
        ctx.screenshotWhenSettled("启动");
    }
}
