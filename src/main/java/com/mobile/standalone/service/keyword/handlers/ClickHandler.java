package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.ToggleStateReader;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ClickHandler implements KeywordHandler {

    private static final List<String> CLICK_METHODS =
            List.of("AUTO", "ELEMENT", "COORDINATE", "ADB", "ELEMENT_THEN_COORD");

    @Override
    public String code() {
        return "CLICK";
    }

    @Override
    public String nameZh() {
        return "点击";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "点击指定元素；可按步骤选择点击策略与点击后等待";
    }

    @Override
    public int sortOrder() {
        return 10;
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
                ParamDef.enumOf("clickMethod", "点击方式", CLICK_METHODS, "AUTO"),
                ParamDef.number("offsetXPercent", "横向点击位置", 50)
                        .withHelp("相对本元素宽度，不是整屏。50=中心。"
                                + "换机不用手算：点「真机点选算出位置」，在截图上点你要点的字，会自动回填。"),
                ParamDef.number("offsetYPercent", "纵向点击位置", 50)
                        .withHelp("相对本元素高度。一般保持 50。"),
                ParamDef.number("waitAfterMs", "点击后等待毫秒", 0),
                // 只在「仅元素 click」下露出：别的点击方式要么会补点（勾选框连点两次，断言注定不稳），
                // 要么是坐标/ADB 兜底，都不是拿来点勾选框的。默认 AUTO 时这个开关根本不该占位置。
                ParamDef.bool("assertChecked", "点后断言已勾选", false)
                        .withHelp("勾选框用：点完后读 checked/selected，未勾上则本步失败。"
                                + "若勾选框读不到 checked，改用「断言元素状态」")
                        .showWhen("clickMethod", "ELEMENT")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<String> selectors = ctx.selectors("点击");
        String clickMethod = KeywordContext.firstNonBlank(ctx.str("clickMethod"), "AUTO");
        int offsetX = (int) Math.min(100L, Math.max(0L, ctx.longParam("offsetXPercent", 50)));
        int offsetY = (int) Math.min(100L, Math.max(0L, ctx.longParam("offsetYPercent", 50)));
        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();

        boolean ok = false;
        String used = selectors.get(0);
        boolean hdc = ctx.isHarmony()
                || (ctx.getDevice() != null
                && com.mobile.standalone.entity.Device.usesHdcAutomation(ctx.getDevice().getPlatform()));
        for (String selector : selectors) {
            used = selector;
            if (hdc) {
                // 纯鸿蒙：必须走 HarmonyOSDriver / hmdriver2，禁止进 Appium Android 会话
                if (selector != null && (selector.startsWith("coordinate:") || selector.startsWith("tap:"))) {
                    String coord = selector.contains(":") ? selector.substring(selector.indexOf(':') + 1) : selector;
                    String[] xy = coord.split("[,\\s]+");
                    if (xy.length >= 2) {
                        ok = ctx.getDriver().click(ctx.getDeviceId(),
                                Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()));
                    }
                } else {
                    ok = ctx.getDriver().clickElement(ctx.getDeviceId(), selector);
                }
            } else if (appium != null) {
                ok = appium.clickElementPreferCoordinates(
                        ctx.getDeviceId(), selector, clickMethod, ctx.getElementKey(),
                        offsetX, offsetY);
            } else {
                ok = ctx.getDriver().clickElement(ctx.getDeviceId(), selector);
            }
            if (ok) break;
        }
        if (!ok) {
            throw ctx.fail("点击",
                    "点击失败: " + ctx.getElementKey()
                            + " (method=" + clickMethod + ", tried=" + selectors.size() + ")",
                    used, "点击失败: " + ctx.getElementKey());
        }

        long waitAfterMs = Math.min(60_000L, Math.max(0L, ctx.longParam("waitAfterMs", 0)));
        if (waitAfterMs > 0) {
            ctx.sleep(waitAfterMs);
        }

        if (ctx.boolParam("assertChecked", false)) {
            // 点完立刻读可能还是旧状态，给一帧渲染时间（已有 waitAfter 则不再叠太多）
            if (waitAfterMs < 200) {
                ctx.sleep(200);
            }
            Boolean checked = readChecked(ctx, used);
            if (checked == null) {
                // 读不到 ≠ 未勾选。自定义控件（如 checkable=false 的 ImageView）从不暴露状态，
                // 当成未勾选就会报一条查不出所以然的假红
                throw ctx.fail("点击",
                        "已点击，但读不到勾选状态（该控件不暴露 checked/selected）: " + ctx.getElementKey()
                                + "。请关掉本开关，改用「断言元素状态」并开启「用截图判断勾选态」",
                        used, "读不到勾选状态: " + ctx.getElementKey());
            }
            if (!checked) {
                throw ctx.fail("点击",
                        "已点击，但元素仍未勾选（checked/selected 未变为 true）: " + ctx.getElementKey(),
                        used, "勾选未成功: " + ctx.getElementKey());
            }
            ctx.ok("点击", ctx.getElementKey() + " [" + clickMethod + "] 已勾选"
                    + (waitAfterMs > 0 ? " waitAfter=" + waitAfterMs + "ms" : ""));
            return;
        }

        ctx.ok("点击", ctx.getElementKey() + " [" + clickMethod + "]"
                + (selectors.size() > 1 ? " locators=" + selectors.size() : "")
                + (waitAfterMs > 0 ? " waitAfter=" + waitAfterMs + "ms" : ""));
    }

    /**
     * Android 多看 checked；iOS 勾选常落在 value / selected。
     * <p>
     * 返回 {@code null} 表示<b>读不到</b>——跟「未勾选」是两回事。属性层只采信 true：
     * 自定义控件的 getAttribute 常返回空串或恒为 false，把那当未勾选就是在造假红。
     * 控件树优先，那里的 checkable 能证明这个 false 到底可不可信。
     */
    private Boolean readChecked(KeywordContext ctx, String selector) {
        try {
            Boolean fromXml = ToggleStateReader.readCheckedAttrFromPageSource(
                    ctx.getDriver().getPageSource(ctx.getDeviceId()), selector);
            if (fromXml != null) {
                return fromXml;
            }
        } catch (Exception ignored) {
            // 控件树取不到就退回属性
        }
        for (String attr : new String[]{"checked", "selected", "value"}) {
            try {
                String v = ctx.getDriver().getElementAttribute(ctx.getDeviceId(), selector, attr);
                if (isTruthyChecked(v)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 换下一个属性
            }
        }
        return null;
    }

    private static boolean isTruthyChecked(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim().toLowerCase();
        return "true".equals(v) || "1".equals(v) || "yes".equals(v)
                || "checked".equals(v) || "on".equals(v);
    }
}
