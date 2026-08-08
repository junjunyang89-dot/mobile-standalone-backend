package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.ToggleStateReader;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 断言元素状态（勾选 / 可用 / 选中）。
 * <p>
 * 核心规则：<b>读到已是期望状态就通过且绝不点击</b>。勾选类控件不幂等——「只点一次」的点击
 * 对已勾选的框会变成取消勾选，而现场极具迷惑性：日志显示「点击成功」，下一步却没反应，
 * 人会去查下一步的元素。
 * <p>
 * {@code fixElementKey} 支持「读 A、点 B」：协议勾选框往往是自定义 ImageView，
 * {@code checked} 属性不可信；而「发送验证码」按钮的 {@code enabled} 语义清晰。
 * 于是可以读发码按钮是否点亮、点协议框来纠正——读可靠的东西，点需要幂等的东西，
 * 而且断言的是最终目标（能不能发码），比分别断言手机号和协议更贴近真实。
 */
@Component
public class AssertElementStateHandler implements KeywordHandler {

    private static final List<String> STATES =
            List.of("checked", "unchecked", "enabled", "disabled", "selected", "unselected");

    @Override
    public String code() {
        return "ASSERT_ELEMENT_STATE";
    }

    @Override
    public String nameZh() {
        return "断言元素状态";
    }

    @Override
    public String category() {
        return "ASSERT";
    }

    @Override
    public String description() {
        return "断言元素处于某个状态（已勾选/未勾选/可用/禁用/已选中）；"
                + "已是期望状态不会点击，仅明确未达标且开启纠正时才点一下";
    }

    @Override
    public int sortOrder() {
        return 92;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public String shortLabel() {
        return "断言状态";
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.SINGLE;
    }

    /** 点击纠正有副作用，重试会反复切换状态，必须自己控制节奏 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "要判断状态的元素"),
                ParamDef.enumOf("expectState", "期望状态", STATES, "checked"),
                ParamDef.bool("fixByClick", "不符时点一下再判", false)
                        .withHelp("已是期望状态不会点；仅明确未达标时才点一下纠正"),
                // 下面两项只在开了纠正之后才有意义，关着时不该占位置
                ParamDef.element("fixElementKey", "不符时点哪个元素").optional()
                        .withHelp("留空则点自己。也可以点别的元素："
                                + "比如判断「发送验证码」是否点亮，不亮就去点「协议勾选」")
                        .showWhen("fixByClick", true),
                ParamDef.number("recheckDelayMs", "点击后复判前等待", 800)
                        .withHelp("自定义控件点完属性/动画常滞后一拍，立刻复判会读到旧值")
                        .showWhen("fixByClick", true),
                ParamDef.bool("visualCheck", "用截图判断勾选态", false)
                        .withHelp("自定义控件读不到 checked 时才开。依赖勾号颜色，"
                                + "换主题/深色模式/改版都可能失效，能读属性就别开")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("断言状态");
        String expect = normalizeState(ctx.str("expectState"));
        boolean fix = ctx.boolParam("fixByClick", false);
        boolean want = wantTrue(expect);

        Boolean actual = readState(ctx, selector, expect);

        if (actual != null && actual == want) {
            ctx.ok("断言状态", "已是「" + labelOf(expect) + "」");
            return;
        }

        if (!fix) {
            if (actual == null) {
                throw ctx.fail("断言状态",
                        "读不到「" + labelOf(expect) + "」状态"
                                + (isCheckedState(expect) ? "（自定义控件可能不暴露该属性，"
                                        + "可开启「用截图判断勾选态」，或改为判断另一个语义清晰的元素）" : ""),
                        selector, "读不到元素状态");
            }
            throw ctx.fail("断言状态",
                    "期望「" + labelOf(expect) + "」，实际是「" + labelOf(opposite(expect)) + "」",
                    selector, "元素状态不符: 期望 " + labelOf(expect));
        }

        // 读不准时绝不点：万一它其实已经是期望状态，点下去就变成了反向操作
        if (actual == null) {
            throw ctx.fail("断言状态",
                    "状态读不准，已跳过点击（贸然点击可能把已达标的状态改回去）。"
                            + "请改用能读到状态的元素，或关闭「不符时点一下再判」由上一步负责",
                    selector, "元素状态读不准");
        }

        String fixSelector = resolveFixSelector(ctx, selector);
        ctx.ok("断言状态", "当前是「" + labelOf(opposite(expect)) + "」，点一下再判");
        if (!clickToFix(ctx, fixSelector)) {
            throw ctx.fail("断言状态", "状态不符且点击未成功，无法纠正", fixSelector, "点击纠正失败");
        }
        // 自定义控件点完后属性/动画常滞后一拍，立刻复判会读到旧值
        ctx.sleep(ctx.longParam("recheckDelayMs", 800));

        Boolean after = readState(ctx, selector, expect);
        if (after != null && after == want) {
            ctx.ok("断言状态", "点击后已变为「" + labelOf(expect) + "」");
            return;
        }
        throw ctx.fail("断言状态",
                "点击后仍不是「" + labelOf(expect) + "」（实际："
                        + (after == null ? "读不到" : labelOf(opposite(expect))) + "）",
                selector, "元素状态无法纠正为 " + labelOf(expect));
    }

    /** 「不符时点哪个元素」：留空则点自己 */
    private String resolveFixSelector(KeywordContext ctx, String selfSelector) throws Exception {
        String fixKey = ctx.str("fixElementKey");
        if (fixKey == null || fixKey.isBlank()) {
            return selfSelector;
        }
        List<String> selectors = ctx.getRuntime().requireSelectors(
                ctx.getPlatform(), ctx.getUiAppId(), fixKey.trim(),
                ctx.getExecParams(), ctx.getLogs(), ctx.sn("断言状态·纠正目标"));
        return selectors.get(0);
    }

    private Boolean readState(KeywordContext ctx, String selector, String expect) {
        String attr = attributeOf(expect);
        if ("enabled".equals(attr)) {
            return readByAttribute(ctx, selector, "enabled", ToggleStateReader::readEnabledFromPageSource);
        }
        Boolean toggle = readByAttribute(ctx, selector, attr, ToggleStateReader::readCheckedAttrFromPageSource);
        if (toggle != null) {
            return toggle;
        }
        // 属性读不到时才考虑看图：颜色判断脆，必须由使用者显式开启
        return ctx.boolParam("visualCheck", false) ? readVisual(ctx, selector) : null;
    }

    /**
     * 先从 pageSource 读，再退回 WebDriver 属性。
     * <p>
     * 顺序不能反：自定义控件上 {@code getAttribute} 常返回空串或恒为 false，
     * 而 pageSource 里的属性是无障碍树的原值，可信得多。属性这一层<b>只采信 true</b>，
     * 单独一个 false 不足以断定——很多自定义控件压根没实现这个属性。
     */
    private Boolean readByAttribute(KeywordContext ctx, String selector, String attr,
                                    PageSourceReader fromPageSource) {
        try {
            String pageSource = ctx.getDriver().getPageSource(ctx.getDeviceId());
            Boolean fromXml = fromPageSource.read(pageSource, selector);
            if (fromXml != null) {
                return fromXml;
            }
        } catch (Exception ignored) {
            // 控件树取不到就退回属性，不该因此中断
        }
        for (String a : candidateAttributes(attr)) {
            Boolean parsed = ToggleStateReader.parseToggleValue(
                    ctx.getDriver().getElementAttribute(ctx.getDeviceId(), selector, a));
            if (Boolean.TRUE.equals(parsed)) {
                return true;
            }
            if (parsed != null && "enabled".equals(attr)) {
                // enabled 是标准属性，false 可信
                return false;
            }
        }
        return null;
    }

    /** iOS 的勾选态常挂在 value 上（"1"/"0"） */
    private static List<String> candidateAttributes(String attr) {
        return "enabled".equals(attr) ? List.of("enabled") : List.of(attr, "selected", "value");
    }

    private Boolean readVisual(KeywordContext ctx, String selector) {
        AppiumDriverAdapter appium = ctx.getRuntime() != null ? ctx.getRuntime().appiumDriverAdapter() : null;
        if (appium == null) {
            return null;
        }
        try {
            return appium.readCheckboxVisual(ctx.getDeviceId(), selector);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean clickToFix(KeywordContext ctx, String selector) {
        if (ctx.isHarmony()) {
            return ctx.getDriver().clickElement(ctx.getDeviceId(), selector);
        }
        AppiumDriverAdapter appium = ctx.getRuntime() != null ? ctx.getRuntime().appiumDriverAdapter() : null;
        if (appium != null) {
            return appium.clickElementPreferCoordinates(
                    ctx.getDeviceId(), selector, "AUTO", ctx.getElementKey());
        }
        return ctx.getDriver().clickElement(ctx.getDeviceId(), selector);
    }

    @FunctionalInterface
    private interface PageSourceReader {
        Boolean read(String pageSource, String selector);
    }

    private static String normalizeState(String raw) {
        String s = raw == null || raw.isBlank() ? "checked" : raw.trim().toLowerCase(Locale.ROOT);
        return STATES.contains(s) ? s : "checked";
    }

    private static boolean isCheckedState(String state) {
        return state.startsWith("check") || state.startsWith("unchec");
    }

    private static boolean wantTrue(String state) {
        return !state.startsWith("un") && !"disabled".equals(state);
    }

    private static String attributeOf(String state) {
        if (state.contains("select")) {
            return "selected";
        }
        if ("enabled".equals(state) || "disabled".equals(state)) {
            return "enabled";
        }
        return "checked";
    }

    private static String opposite(String state) {
        return switch (state) {
            case "checked" -> "unchecked";
            case "unchecked" -> "checked";
            case "enabled" -> "disabled";
            case "disabled" -> "enabled";
            case "selected" -> "unselected";
            default -> "selected";
        };
    }

    private static String labelOf(String state) {
        return switch (state) {
            case "checked" -> "已勾选";
            case "unchecked" -> "未勾选";
            case "enabled" -> "可用";
            case "disabled" -> "禁用";
            case "selected" -> "已选中";
            default -> "未选中";
        };
    }
}
