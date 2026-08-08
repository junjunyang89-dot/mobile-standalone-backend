package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 热启动：不杀进程，Home 进后台后再拉回前台，记录耗时。
 * <p>
 * 合并了原 HOT_START / HOT_START_IOS，两者本就调同一个 {@code hotStartApp}。
 */
@Component
public class HotStartHandler implements KeywordHandler {

    @Override
    public String code() {
        return "HOT_START";
    }

    @Override
    public String nameZh() {
        return "热启动";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "App 性能：不杀进程，Home 进后台停留后再拉回前台，记录热启动耗时。Android / iOS 通用";
    }

    @Override
    public int sortOrder() {
        return 4;
    }

    @Override
    public List<String> aliases() {
        return List.of("HOT_START_IOS");
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    @Override
    public String shortLabel() {
        return "热启";
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
                ParamDef.string("appId", "应用字典ID"),
                ParamDef.number("backgroundMs", "切后台停留毫秒", 2000)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String pkg = ctx.packageName();
        boolean ios = ctx.isIos();
        String title = ios ? "热启动 · iOS" : "热启动 · Android";

        if (!ios && !ctx.isAndroidLike()) {
            throw ctx.fail(title, "热启动仅支持 Android / Pog-Android / iOS / Pog-iOS，当前平台: " + ctx.getPlatform(),
                    pkg, "热启动平台不支持: " + ctx.getPlatform());
        }

        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        if (appium == null) {
            throw ctx.fail(title, "Appium 驱动未注入", pkg, "热启动需要 Appium");
        }
        if (!ctx.getRuntime().appiumServerManager().ensureRunning()) {
            throw ctx.fail("Appium", "无法拉起 Appium Server", null, "Appium 不可用");
        }

        long backgroundMs = ctx.longParam("backgroundMs", 2000);
        ctx.ok(title, "不杀进程；必要时先冷启准备，再 Home 后台 " + backgroundMs + " ms 后拉回: " + pkg);

        long cost = appium.hotStartApp(ctx.getDeviceId(), pkg, backgroundMs);
        if (cost < 0) {
            throw ctx.fail(title, "热启动失败或超时: " + pkg, pkg, title + "失败: " + pkg);
        }
        ctx.ok(title + "耗时", (ios ? "BundleId:" : "包名:") + pkg + "，约 " + cost + " ms");
    }
}
