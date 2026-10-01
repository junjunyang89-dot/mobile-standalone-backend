package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.inspect.H5RenderVerdict;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 判断 H5 页面到底渲染出东西没有。
 *
 * <p>为什么要有这一步：线上出过「某些机型上 H5 打开就是白屏，同一地址在浏览器里正常」，
 * 根在 X5 内核。这类问题<b>不抛异常、不报错</b>——WebView 心安理得地渲染了一片空白，
 * 所有断言照常通过。只有一个人去看那一屏，才会发现什么都没有。
 *
 * <p>判据问 DOM 不问像素：白屏的本质是「那块 WebView 里没东西」，
 * 而不是「屏幕偏白」。按像素判的话，登录页这种合法白底页会误报，
 * 而留着状态栏和底部导航的真白屏反而漏报。
 */
@Component
public class AssertH5RenderedHandler implements KeywordHandler {

    @Override
    public String code() {
        return "ASSERT_H5_RENDERED";
    }

    @Override
    public String nameZh() {
        return "H5断言已渲染";
    }

    @Override
    public String shortLabel() {
        return "H5渲染";
    }

    @Override
    public List<String> aliases() {
        return List.of("ASSERT_H5_NOT_BLANK", "H5_ASSERT_RENDERED");
    }

    @Override
    public String category() {
        return "ASSERT";
    }

    @Override
    public String description() {
        return "看 WebView 里是不是真的渲染出了内容（问 DOM，不看像素）。"
                + "专门抓「页面白屏但一步不报错」这种——X5 内核在部分机型上会这样。"
                + "失败时会打印可见文字长度、已加载的图片数、readyState 和当前地址";
    }

    @Override
    public int sortOrder() {
        return 15;
    }

    @Override
    public boolean needsElement() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.number("minTextLength", "至少多少个可见文字", H5RenderVerdict.DEFAULT_MIN_TEXT)
                        .withHelp("防止把「加载中…」这种骨架文案当成渲染成功。"
                                + "纯图片页面调到 0：不要求文字，但仍要求页面上至少有一个字或一张加载出来的图——"
                                + "一条永远为真的断言比没有断言更糟"),
                ParamDef.number("timeoutSec", "等待超时秒", 10)
                        .withHelp("轮询到渲染出来或超时。H5 首屏常常要等接口，别设太短"),
                ParamDef.enumOf("onUnverifiable", "验不了的时候怎么算",
                        List.of("BLOCKED", "WARN", "FAIL"), "BLOCKED")
                        .withHelp("切不进 WebView context（多见于没开调试通道的 release 包）时的处理。"
                                + "默认 BLOCKED＝没跑成，不进通过率；"
                                + "绝不会当成通过——那样这条断言会常年挂绿灯却什么都没验")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String title = "H5断言已渲染";
        if (ctx.isHarmony()) {
            throw ctx.fail(title, "鸿蒙暂不支持读 WebView 内容", null);
        }
        int minText = (int) Math.max(0, ctx.longParam("minTextLength", H5RenderVerdict.DEFAULT_MIN_TEXT));
        long timeoutMs = Math.min(120_000L, Math.max(0L, ctx.longParam("timeoutSec", 10) * 1000L));
        String onUnverifiable = upper(ctx.str("onUnverifiable"), "BLOCKED");

        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        if (appium == null) {
            throw ctx.fail(title, "需要 Appium。确认 appium.enabled=true 且已启动应用", null);
        }
        appium.attachAndroidUiSession(ctx.getDeviceId());

        long deadline = System.currentTimeMillis() + timeoutMs;
        H5RenderVerdict.Verdict last = null;
        while (true) {
            Map<String, Object> raw = appium.probeWebViewRender(ctx.getDeviceId());
            last = H5RenderVerdict.judge(toProbe(raw), minText, null);
            if (last.passed()) {
                ctx.ok(title, last.summary());
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            ctx.sleep(500);
        }

        // 留一张现场图。白屏这种事，事后回看截图是最快确认「是不是真的白」的方式
        ctx.screenshot(title + "-现场");

        String detail = last.summary()
                + (last.advice() == null ? "" : "\n→ " + last.advice());

        if (last.level() == H5RenderVerdict.Level.UNVERIFIABLE) {
            if ("WARN".equals(onUnverifiable)) {
                ctx.warn(title, detail, null);
                return;
            }
            if ("BLOCKED".equals(onUnverifiable)) {
                // 「没跑成」不是「跑了没过」：它不该进通过率的分母，
                // 但也绝不能悄悄算通过 —— 那就是一条常年绿灯、什么都没验的断言
                throw com.mobile.standalone.service.UiFlowExecutionException.blocked(
                        detail, ctx.getLogs());
            }
            throw ctx.fail(title, detail, null, "H5 渲染无法验证");
        }

        if (ctx.isPerformanceMode()) {
            ctx.warn(title, detail, null);
            return;
        }
        throw ctx.fail(title, detail, null,
                last.level() == H5RenderVerdict.Level.BLANK_RENDER
                        ? "H5 白屏（内容在但没画出来，疑似内核渲染问题）"
                        : "H5 白屏（页面没渲染出内容）");
    }

    static H5RenderVerdict.Probe toProbe(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        return new H5RenderVerdict.Probe(
                Boolean.TRUE.equals(raw.get("available")),
                str(raw.get("message")),
                str(raw.get("readyState")),
                raw.get("bodyPresent") == null ? null : Boolean.valueOf(Boolean.TRUE.equals(raw.get("bodyPresent"))),
                num(raw.get("textLength")),
                num(raw.get("mediaCount")),
                num(raw.get("elementCount")),
                str(raw.get("url")),
                str(raw.get("error")));
    }

    /** JS 回来的数字在 Java 侧可能是 Long、Integer 或 Double，统一收口 */
    private static Integer num(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String upper(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
