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
 * 在当前 H5 页找到输入框并写入。找框用页面上的字，写入用另一栏。
 */
@Component
public class InputH5Handler implements KeywordHandler {

    static final String BY_TEXT = ClickH5Handler.BY_TEXT;
    static final String BY_CSS = ClickH5Handler.BY_CSS;

    @Override
    public String code() {
        return "INPUT_H5";
    }

    @Override
    public String nameZh() {
        return "H5输入";
    }

    @Override
    public String shortLabel() {
        return "H5输入";
    }

    @Override
    public List<String> aliases() {
        return List.of("TYPE_H5", "H5_INPUT");
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "通用 H5 输入：上面用页面上的字找框，下面填要打进去的内容。"
                + "换搜索词/换页只改这两栏。不要用原生 EditText输入";
    }

    @Override
    public int sortOrder() {
        return 13;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.enumOf("by", "定位方式", List.of(BY_TEXT, BY_CSS), BY_TEXT),
                ParamDef.requiredString("text", "页面上的字（找框）")
                        .optional()
                        .withHelp("框上印着的字，用来找到它。例如：请输入搜索关键词")
                        .showWhen("by", BY_TEXT),
                ParamDef.requiredString("selector", "CSS 选择器")
                        .optional()
                        .showWhen("by", BY_CSS),
                ParamDef.requiredString("value", "输入内容")
                        .withHelp("真正要打进框里的字，例如：AK47"),
                ParamDef.number("waitAfterMs", "输入后等待毫秒", 400)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (ctx.isHarmony()) {
            throw ctx.fail("H5输入", "鸿蒙暂不支持 H5 CDP 输入", null);
        }
        String by = KeywordContext.firstNonBlank(ctx.str("by"), BY_TEXT);
        String find = "";
        String css = "";
        if (BY_CSS.equals(by)) {
            css = KeywordContext.firstNonBlank(ctx.str("selector"), ctx.str("css"));
        } else {
            find = KeywordContext.firstNonBlank(ctx.str("text"), ctx.str("label"));
        }
        if (find == null) {
            find = "";
        }
        if (css == null) {
            css = "";
        }
        if (find.isBlank() && css.isBlank()) {
            throw ctx.fail("H5输入", "请先填「页面上的字」，用来找到输入框", null);
        }
        String typeValue = ctx.substituted("value");
        if (typeValue == null || typeValue.isBlank()) {
            typeValue = ctx.substituted("input");
        }
        if (typeValue == null || typeValue.isBlank()) {
            throw ctx.fail("H5输入", "请填写要打进框里的「输入内容」", find);
        }

        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        if (appium == null) {
            throw ctx.fail("H5输入", "需要 Appium（CDP）。确认 appium.enabled=true 且已启动应用", find);
        }
        Map<String, Object> hit = appium.clickH5(ctx.getDeviceId(), find, css, typeValue);
        boolean ok = Boolean.TRUE.equals(hit.get("ok"));
        String detail = String.valueOf(hit.getOrDefault("message", ""));
        String url = String.valueOf(hit.getOrDefault("url", ""));
        if (!ok) {
            throw ctx.fail("H5输入", detail, url.isBlank() ? find : url);
        }
        ctx.ok("H5输入", detail + (url.isBlank() ? "" : " | " + url));
        long waitAfterMs = Math.min(60_000L, Math.max(0L, ctx.longParam("waitAfterMs", 400)));
        if (waitAfterMs > 0) {
            ctx.sleep(waitAfterMs);
        }
        ctx.screenshotWhenSettled("H5输入");
    }
}
