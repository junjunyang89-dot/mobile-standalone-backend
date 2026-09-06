package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.ToggleStateReader;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 等元素 enabled=true 后再点击。适合「发送验证码」这类灰掉再点亮的按钮。
 */
@Component
public class ClickWhenEnabledHandler implements KeywordHandler {

    private static final List<String> CLICK_METHODS =
            List.of("AUTO", "ELEMENT", "COORDINATE", "ADB", "ELEMENT_THEN_COORD");

    @Override
    public String code() {
        return "CLICK_WHEN_ENABLED";
    }

    @Override
    public String nameZh() {
        return "等可点再点击";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "等待元素 enabled=true（点亮）后再点击；超时失败并提示检查前置步骤";
    }

    @Override
    public int sortOrder() {
        return 11;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "元素英文名"),
                ParamDef.number("timeoutSec", "超时秒", 10)
                        .withHelp("等到按钮点亮的最长时间；超时请检查手机号、协议勾选等前置步骤"),
                ParamDef.enumOf("clickMethod", "点击方式", CLICK_METHODS, "AUTO"),
                ParamDef.number("offsetXPercent", "横向点击位置", 50)
                        .withHelp("相对本元素宽度。换机不用手算：点「真机点选算出位置」，在截图上点你要点的字。"),
                ParamDef.number("offsetYPercent", "纵向点击位置", 50)
                        .withHelp("相对本元素高度。一般保持 50。"),
                ParamDef.number("waitAfterMs", "点击后等待毫秒", 0)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<String> selectors = ctx.selectors("等可点再点击");
        int timeoutSec = (int) Math.min(120L, Math.max(1L, ctx.longParam("timeoutSec", 10)));
        long timeoutMs = timeoutSec * 1000L;

        String ready = waitUntilEnabled(ctx, selectors, timeoutMs);
        if (ready == null) {
            throw ctx.fail("等可点再点击",
                    "等待可点超时（" + timeoutSec + "s）：" + ctx.getElementKey()
                            + "。请确认前置步骤已完成（如手机号已输入、协议已勾选）",
                    selectors.get(0), "元素未点亮: " + ctx.getElementKey());
        }

        String clickMethod = KeywordContext.firstNonBlank(ctx.str("clickMethod"), "AUTO");
        int offsetX = (int) Math.min(100L, Math.max(0L, ctx.longParam("offsetXPercent", 50)));
        int offsetY = (int) Math.min(100L, Math.max(0L, ctx.longParam("offsetYPercent", 50)));
        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        boolean ok = false;
        String used = ready;
        boolean hdc = ctx.isHarmony()
                || (ctx.getDevice() != null
                && com.mobile.standalone.entity.Device.usesHdcAutomation(ctx.getDevice().getPlatform()));
        for (String selector : selectors) {
            used = selector;
            if (hdc) {
                ok = ctx.getDriver().clickElement(ctx.getDeviceId(), selector);
            } else if (appium != null) {
                ok = appium.clickElementPreferCoordinates(
                        ctx.getDeviceId(), selector, clickMethod, ctx.getElementKey(),
                        offsetX, offsetY);
            } else {
                ok = ctx.getDriver().clickElement(ctx.getDeviceId(), selector);
            }
            if (ok) {
                break;
            }
        }
        if (!ok) {
            throw ctx.fail("等可点再点击",
                    "已点亮但点击失败: " + ctx.getElementKey()
                            + " (method=" + clickMethod + ")",
                    used, "点击失败: " + ctx.getElementKey());
        }

        long waitAfterMs = Math.min(60_000L, Math.max(0L, ctx.longParam("waitAfterMs", 0)));
        if (waitAfterMs > 0) {
            ctx.sleep(waitAfterMs);
        }

        ctx.ok("等可点再点击", ctx.getElementKey() + " [" + clickMethod + "]"
                + " timeout=" + timeoutSec + "s"
                + (waitAfterMs > 0 ? " waitAfter=" + waitAfterMs + "ms" : ""));
    }

    private String waitUntilEnabled(KeywordContext ctx, List<String> selectors, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() <= deadline) {
            ctx.ensureNotCancelled();
            for (String selector : selectors) {
                if (isEnabled(ctx, selector)) {
                    return selector;
                }
            }
            ctx.sleep(200);
        }
        return null;
    }

    private boolean isEnabled(KeywordContext ctx, String selector) {
        try {
            String pageSource = ctx.getDriver().getPageSource(ctx.getDeviceId());
            Boolean fromXml = ToggleStateReader.readEnabledFromPageSource(pageSource, selector);
            if (Boolean.TRUE.equals(fromXml)) {
                return true;
            }
            if (Boolean.FALSE.equals(fromXml)) {
                return false;
            }
        } catch (Exception ignored) {
            // fall through
        }
        try {
            String raw = ctx.getDriver().getElementAttribute(ctx.getDeviceId(), selector, "enabled");
            return Boolean.TRUE.equals(ToggleStateReader.parseToggleValue(raw));
        } catch (Exception e) {
            return false;
        }
    }
}
