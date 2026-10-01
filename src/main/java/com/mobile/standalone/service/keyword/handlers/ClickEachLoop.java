package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.util.SequenceValues;

import java.util.List;

/** 依次取值后的一轮操作：点列表项，可选再滑 / 再跳转并返回。 */
final class ClickEachLoop {

    record AfterSwipe(String direction, int times, int durationMs, boolean afterLast) {
        static AfterSwipe from(KeywordContext ctx) {
            String dir = KeywordContext.firstNonBlank(ctx.str("afterSwipe"), "up").trim().toLowerCase();
            if ("none".equals(dir) || dir.isBlank()) {
                dir = "up";
            }
            return new AfterSwipe(
                    dir,
                    (int) Math.min(30, Math.max(1, ctx.longParam("afterSwipeTimes", 1))),
                    (int) ctx.longParam("afterSwipeDurationMs", 500),
                    ctx.boolParam("swipeAfterLast", true));
        }
    }

    private ClickEachLoop() {
    }

    @FunctionalInterface
    interface AfterEach {
        void run(KeywordContext once, String item) throws Exception;
    }

    static void run(KeywordContext ctx, String label, AfterSwipe swipe) throws Exception {
        run(ctx, label, swipe, null, null);
    }

    static void runAfterEach(KeywordContext ctx, String label, AfterEach afterEach) throws Exception {
        run(ctx, label, null, null, afterEach);
    }

    private static String blankToEmpty(String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }

    static void runOpenBack(KeywordContext ctx, String label) throws Exception {
        String jumpKey = ctx.str("jumpElementKey");
        String backKey = ctx.str("backElementKey");
        if (jumpKey == null || jumpKey.isBlank()) {
            throw ctx.fail(label, "请选择进入页面后要点的元素（跳转）", null);
        }
        if (backKey == null || backKey.isBlank()) {
            throw ctx.fail(label, "请选择返回按钮", null);
        }
        run(ctx, label, null, new OpenBack(
                jumpKey.trim(),
                backKey.trim(),
                // 不能写成 firstNonBlank(x, "")："" 本身是 blank，firstNonBlank 会跳过它返回
                // null，接着 .trim() 就是 NPE。而 landedElementKey 是<b>可选</b>参数，
                // 也就是说不填它——最常见的用法——这一步必然崩，
                // 而且崩成一个光秃秃的 NullPointerException，人完全看不出该填什么
                blankToEmpty(ctx.str("landedElementKey")),
                (int) Math.max(1, ctx.longParam("landedTimeoutSec", 8)),
                Math.min(60_000L, Math.max(0L, ctx.longParam("waitAfterJumpMs", 300))),
                Math.min(60_000L, Math.max(0L, ctx.longParam("waitAfterBackMs", 300)))),
                null);
    }

    private record OpenBack(String jumpKey, String backKey, String landedKey,
                            int landedTimeoutSec, long waitAfterJumpMs, long waitAfterBackMs) {
        boolean hasLanded() {
            return landedKey != null && !landedKey.isBlank();
        }
    }

    private static void run(KeywordContext ctx, String label, AfterSwipe swipe, OpenBack openBack,
                            AfterEach afterEach)
            throws Exception {
        List<String> items;
        try {
            items = SequenceValues.parse(ctx.str("sequence"));
        } catch (IllegalArgumentException e) {
            throw ctx.fail(label, e.getMessage(), null);
        }
        String varName = KeywordContext.firstNonBlank(ctx.str("varName"), "year");
        long waitAfterMs = Math.min(60_000L, Math.max(0L, ctx.longParam("waitAfterMs", 300)));

        int n = 0;
        for (String item : items) {
            ctx.ensureNotCancelled();
            n++;
            KeywordContext once = ctx.overlayStepParam(varName, item);
            clickTarget(once, label, n + "/" + items.size() + " 进入 " + item, once.selectors(label), item);
            if (waitAfterMs > 0) {
                ctx.sleep(waitAfterMs);
            }
            if (openBack != null) {
                openThenBack(once, label, item, openBack);
            }
            if (afterEach != null) {
                afterEach.run(once, item);
            }
            boolean last = n == items.size();
            if (swipe != null && (!last || swipe.afterLast())) {
                swipeAfter(ctx, label, swipe, item);
            }
        }
        String extra = "";
        if (afterEach != null) {
            extra = "，每项点完后执行同一套动作";
        } else if (openBack != null) {
            extra = "，每项进入后跳转并返回";
        } else if (swipe != null) {
            extra = "，每次点完后滑 " + swipe.direction() + " ×" + swipe.times();
        }
        ctx.ok(label, "已处理 " + items.size() + " 项（" + items.get(0) + " → " + items.get(items.size() - 1) + "）" + extra);
    }

    private static void openThenBack(KeywordContext ctx, String label, String item, OpenBack open)
            throws Exception {
        clickTarget(ctx, label, item + " 页内跳转", ctx.selectorsFor(open.jumpKey(), label), "跳转");
        if (open.hasLanded()) {
            List<String> landed = ctx.selectorsFor(open.landedKey(), label);
            String sel = landed.get(0);
            if (!ctx.getDriver().waitForElement(ctx.getDeviceId(), sel, open.landedTimeoutSec())) {
                throw ctx.fail(label, item + " 跳转后未出现成功标志: " + open.landedKey(), sel,
                        "跳转未成功: " + item);
            }
            ctx.ok(label, item + " 已跳转");
        } else if (open.waitAfterJumpMs() > 0) {
            ctx.sleep(open.waitAfterJumpMs());
        }
        clickTarget(ctx, label, item + " 返回", ctx.selectorsFor(open.backKey(), label), "返回");
        if (open.waitAfterBackMs() > 0) {
            ctx.sleep(open.waitAfterBackMs());
        }
    }

    static void clickTarget(KeywordContext ctx, String label, String detail, List<String> selectors, String failHint)
            throws Exception {
        if (selectors == null || selectors.isEmpty()) {
            throw ctx.fail(label, "没有可用定位: " + failHint, null);
        }
        String clickMethod = KeywordContext.firstNonBlank(ctx.str("clickMethod"), "AUTO");
        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        boolean ok = false;
        String used = selectors.get(0);
        boolean hdc = ctx.isHarmony()
                || (ctx.getDevice() != null
                && com.mobile.standalone.entity.Device.usesHdcAutomation(ctx.getDevice().getPlatform()));
        for (String selector : selectors) {
            used = selector;
            if (hdc) {
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
                        ctx.getDeviceId(), selector, clickMethod, ctx.getElementKey());
            } else {
                ok = ctx.getDriver().clickElement(ctx.getDeviceId(), selector);
            }
            if (ok) {
                break;
            }
        }
        if (!ok) {
            throw ctx.fail(label, detail + " 点击失败（method=" + clickMethod + "）", used, label + "失败: " + failHint);
        }
        ctx.ok(label, detail);
    }

    private static void swipeAfter(KeywordContext ctx, String label, AfterSwipe swipe, String item)
            throws Exception {
        for (int i = 1; i <= swipe.times(); i++) {
            ctx.ensureNotCancelled();
            if (!ctx.getRuntime().directionalSwipe(
                    ctx.getDriver(), ctx.getDeviceId(), swipe.direction(), swipe.durationMs())) {
                throw ctx.fail(label, "点完 " + item + " 后滑动失败: " + swipe.direction() + " #" + i, null, "滑动失败");
            }
            if (i < swipe.times()) {
                ctx.sleep(200);
            }
        }
        ctx.ok(label, item + " 之后滑动 " + swipe.direction() + " ×" + swipe.times());
    }
}
