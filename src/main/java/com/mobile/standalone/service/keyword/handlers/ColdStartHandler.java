package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 冷启动：Android force-stop / iOS terminateApp 后再启动，并记录耗时。
 * <p>
 * 合并了原 COLD_START / COLD_START_IOS——原实现本就是同一个方法加一个 iosKeyword 布尔，
 * 区别只在平台校验的报错文案。现在平台直接由任务决定，编排时不用再选端，也不会再选错。
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
                ParamDef.string("appId", "应用字典ID")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String pkg = ctx.packageName();
        boolean ios = ctx.isIos();
        long t0 = System.currentTimeMillis();

        if (ios) {
            AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
            if (appium == null || !ctx.getRuntime().appiumServerManager().ensureRunning()) {
                throw ctx.fail("冷启动 · iOS", "Appium 不可用", null, "冷启动 · iOS 需要 Appium");
            }
            if (!appium.coldStartIosApp(ctx.getDeviceId(), pkg)) {
                throw ctx.fail("冷启动 · iOS", "冷启动失败: " + pkg, pkg, "iOS 冷启动失败: " + pkg);
            }
            ctx.ok("冷启动 · iOS", "BundleId:" + pkg);
            ctx.screenshotWhenSettled("启动");
        } else {
            LaunchAppHandler.launchAndroid(ctx, "冷启动 · Android");
        }

        long cost = System.currentTimeMillis() - t0;
        ctx.ok(ios ? "冷启动耗时 · iOS" : "冷启动耗时 · Android",
                (ios ? "BundleId:" : "包名:") + pkg + "，约 " + cost + " ms");
    }
}
