package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.util.TextAssertion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用当前 H5 的 location（含 hash）判断是否到了目标页。不看原生树。
 */
@Component
public class AssertH5UrlHandler implements KeywordHandler {

    @Override
    public String code() {
        return "ASSERT_H5_URL";
    }

    @Override
    public String nameZh() {
        return "H5断言页面";
    }

    @Override
    public String shortLabel() {
        return "H5断言页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("WAIT_H5_URL", "H5_ASSERT_URL");
    }

    @Override
    public String category() {
        return "ASSERT";
    }

    @Override
    public String description() {
        return "读当前 H5 完整地址（含 # 路由），按包含/等于/正则判断是否到了这一页。"
                + "换页只改期望片段，例如 gameMall.html 或 #/index?tagId=3829。"
                + "可设超时，等 SPA 跳转完再判";
    }

    @Override
    public int sortOrder() {
        return 14;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.requiredString("expected", "期望地址片段")
                        .withHelp("不必写完整 URL。商城首页可写 gameMall.html 或 #/index?tagId=3829"),
                ParamDef.enumOf("mode", "判定方式",
                        List.of("contains", "equals", "regex", "notContains"), "contains")
                        .withHelp("contains=当前地址包含这段；equals=整段 URL 完全一致"),
                ParamDef.number("timeoutSec", "等待超时秒", 8)
                        .withHelp("0=只判一次。大于 0 则轮询到匹配或超时（打开页后 hash 常会晚一点才变）")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (ctx.isHarmony()) {
            throw ctx.fail("H5断言页面", "鸿蒙暂不支持读 H5 URL", null);
        }
        String expected = ctx.substituted("expected");
        if (expected == null || expected.isBlank()) {
            throw ctx.fail("H5断言页面", "请填写期望地址片段", null);
        }
        TextAssertion.Mode mode = TextAssertion.Mode.from(ctx.str("mode"));
        long timeoutMs = Math.min(120_000L, Math.max(0L, ctx.longParam("timeoutSec", 8) * 1000L));

        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        if (appium == null) {
            throw ctx.fail("H5断言页面", "需要 Appium。确认 appium.enabled=true 且已启动应用", expected);
        }
        appium.attachAndroidUiSession(ctx.getDeviceId());

        long deadline = System.currentTimeMillis() + timeoutMs;
        String actual = "";
        String readMsg = "";
        while (true) {
            Map<String, Object> hit = appium.readWebViewUrl(ctx.getDeviceId());
            boolean available = Boolean.TRUE.equals(hit.get("available"));
            actual = String.valueOf(hit.getOrDefault("url", ""));
            readMsg = String.valueOf(hit.getOrDefault("message", ""));
            boolean pass = available && TextAssertion.matches(mode, actual, expected);
            if (pass) {
                ctx.ok("H5断言页面", mode.label() + "「" + expected + "」 | " + actual);
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            ctx.sleep(400);
        }
        String detail = TextAssertion.describeFailure(mode, actual, expected);
        if (!readMsg.isBlank() && (actual == null || actual.isBlank())) {
            detail = readMsg;
        }
        if (ctx.isPerformanceMode()) {
            ctx.warn("H5断言页面", detail, actual);
            return;
        }
        throw ctx.fail("H5断言页面", detail, actual, "未进入期望 H5 页: " + expected);
    }
}
