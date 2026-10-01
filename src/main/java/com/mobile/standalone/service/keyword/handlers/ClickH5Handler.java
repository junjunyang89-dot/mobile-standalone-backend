package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 通用 H5 点击：不绑某个 App / 某一页。底层自己选通道。
 */
@Component
public class ClickH5Handler implements KeywordHandler {

    static final String BY_TEXT = "文案";
    static final String BY_CSS = "CSS选择器";

    @Override
    public String code() {
        return "CLICK_H5";
    }

    @Override
    public String nameZh() {
        return "H5点文案";
    }

    @Override
    public String shortLabel() {
        return "H5点文案";
    }

    @Override
    public List<String> aliases() {
        return List.of("CLICK_H5_TEXT", "H5_CLICK");
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "通用：在当前已打开的 H5 上按页面上的字或 CSS 点击，可选再写入。"
                + "换页只改文案/选择器，不用为每个 H5 加关键字。"
                + "须先启动应用并打开目标页";
    }

    @Override
    public int sortOrder() {
        return 12;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.enumOf("by", "定位方式", List.of(BY_TEXT, BY_CSS), BY_TEXT)
                        .withHelp("用页面上已有的字找到要点的东西。要往框里打字请填下面的「输入内容」"),
                ParamDef.requiredString("text", "页面上的字（找控件）")
                        .optional()
                        .withHelp("这是用来找的，不是要打进去的。例如搜索框上的：请输入搜索关键词")
                        .showWhen("by", BY_TEXT),
                ParamDef.requiredString("selector", "CSS 选择器")
                        .optional()
                        .withHelp("例如 input[placeholder*=\"搜索\"]")
                        .showWhen("by", BY_CSS),
                ParamDef.string("value", "输入内容（点中后写入）")
                        .withHelp("要打进框里的字。只想点按钮就留空"),
                ParamDef.number("waitAfterMs", "点击后等待毫秒", 400)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (ctx.isHarmony()) {
            throw ctx.fail("H5点文案", "鸿蒙暂不支持 H5 CDP 点选", null);
        }
        String by = KeywordContext.firstNonBlank(ctx.str("by"), BY_TEXT);
        String text = "";
        String css = "";
        if (BY_CSS.equals(by)) {
            css = KeywordContext.firstNonBlank(ctx.str("selector"), ctx.str("css"));
        } else {
            text = KeywordContext.firstNonBlank(ctx.str("text"), ctx.str("label"));
        }
        if (text == null) {
            text = "";
        }
        if (css == null) {
            css = "";
        }
        if ((text == null || text.isBlank()) && (css == null || css.isBlank())) {
            throw ctx.fail("H5点文案",
                    BY_CSS.equals(by) ? "请填写 CSS 选择器" : "请填写页面上用来找控件的字",
                    null);
        }

        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        if (appium == null) {
            throw ctx.fail("H5点文案", "需要 Appium（CDP）。确认 appium.enabled=true 且已启动应用", text);
        }

        String typeValue = ctx.substituted("value");
        if (typeValue == null) {
            typeValue = ctx.substituted("input");
        }
        Map<String, Object> hit = appium.clickH5(ctx.getDeviceId(), text, css, typeValue);
        boolean ok = Boolean.TRUE.equals(hit.get("ok"));
        String detail = String.valueOf(hit.getOrDefault("message", ""));
        String url = String.valueOf(hit.getOrDefault("url", ""));
        if (!ok) {
            throw ctx.fail("H5点文案", detail, url.isBlank() ? text : url);
        }
        ctx.ok("H5点文案", detail + (url.isBlank() ? "" : " | " + url));
        long waitAfterMs = Math.min(60_000L, Math.max(0L, ctx.longParam("waitAfterMs", 400)));
        if (waitAfterMs > 0) {
            ctx.sleep(waitAfterMs);
        }
        ctx.screenshotWhenSettled("H5点文案");
    }
}
