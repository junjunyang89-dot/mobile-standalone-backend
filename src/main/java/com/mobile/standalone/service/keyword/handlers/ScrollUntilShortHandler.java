package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenVisibility;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 小幅反复滑动直到目标露出来。
 * <p>
 * 「滑动直到元素」一次走大约半屏，列表项刚露头就被下一次大滑带出屏幕。
 * 这里每次只挪一小截，多滑几次，不容易划过。
 */
@Component
public class ScrollUntilShortHandler implements KeywordHandler {

    @Override
    public String code() {
        return "SCROLL_UNTIL_SHORT";
    }

    @Override
    public String nameZh() {
        return "小幅滑动直到元素";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "按方向每次只挪一小截，直到元素出现。普通「滑动直到元素」幅度太大、目标被划过去时用它";
    }

    @Override
    public int sortOrder() {
        return 66;
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
    public String shortLabel() {
        return "小幅滑到";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "目标元素"),
                ParamDef.enumOf("direction", "方向", SwipeHandler.DIRECTIONS, "up"),
                SwipeShortHandler.distanceParam(),
                ParamDef.number("maxAttempts", "最大滑动次数", 30),
                ParamDef.number("durationMs", "单次滑动毫秒", 400),
                ParamDef.number("intervalMs", "滑动间隔毫秒", 400),
                ParamDef.bool("failIfNotFound", "未找到是否失败", true)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("小幅滑动直到元素");
        String direction = KeywordContext.firstNonBlank(ctx.str("direction"), "up");
        int maxAttempts = (int) Math.min(80, Math.max(1, ctx.longParam("maxAttempts", 30)));
        int duration = (int) ctx.longParam("durationMs", 400);
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", 400));
        boolean failIfNotFound = ctx.boolParam("failIfNotFound", true);
        double distancePercent = SwipeShortHandler.distancePercent(ctx);
        String elementKey = ctx.getElementKey();

        int[] screen = ScreenVisibility.screenSize(ctx);
        if (usableOnScreen(ctx, selector, screen)) {
            ctx.ok("小幅滑动直到元素", elementKey + " 已在屏上，无需滑动");
            return;
        }

        boolean found = false;
        int swiped = 0;
        for (int i = 1; i <= maxAttempts; i++) {
            ctx.ensureNotCancelled();
            if (!ctx.getRuntime().directionalSwipe(ctx.getDriver(), ctx.getDeviceId(),
                    direction, duration, distancePercent)) {
                throw ctx.fail("小幅滑动直到元素", "第 " + i + " 次滑动失败: " + direction, selector,
                        "小幅滑动直到元素失败: " + elementKey);
            }
            swiped = i;
            if (intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
            if (usableOnScreen(ctx, selector, screen)) {
                found = true;
                break;
            }
        }

        if (found) {
            ctx.ok("小幅滑动直到元素",
                    elementKey + " 在小幅滑动 " + swiped + " 次后出现 [" + direction + " · " + distancePercent + "%]");
        } else if (failIfNotFound) {
            throw ctx.fail("小幅滑动直到元素", "小幅滑动 " + maxAttempts + " 次仍未找到: " + elementKey, selector,
                    "小幅滑动直到元素未找到: " + elementKey);
        } else {
            ctx.ok("小幅滑动直到元素", elementKey + " 小幅滑动 " + maxAttempts + " 次仍未找到（已忽略失败）");
        }
    }

    /**
     * 树里有节点不算到了：拿不到范围时 {@link ScreenVisibility#isOnScreen} 会放行，
     * 下一步区域内滑动就会「拿不到滑动区域」。
     */
    private static boolean usableOnScreen(KeywordContext ctx, String selector, int[] screen) {
        try {
            if (!ctx.getDriver().isElementPresent(ctx.getDeviceId(), selector)) {
                return false;
            }
            int[] box = ctx.getDriver().getElementBounds(ctx.getDeviceId(), selector);
            if (box == null || box.length < 4) {
                return false;
            }
            int w = Math.abs(box[2] - box[0]);
            int h = Math.abs(box[3] - box[1]);
            if (w < 40 || h < 40) {
                return false;
            }
            if (screen == null || screen.length < 2) {
                return true;
            }
            return ScreenVisibility.boxVisibleInRegion(box, 0, 0, screen[0], screen[1]);
        } catch (Exception e) {
            return false;
        }
    }
}
