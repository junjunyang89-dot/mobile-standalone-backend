package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenVisibility;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 手指落在某个元素上，再挪一点点。
 * <p>
 * 整屏「小幅滑动」起点在屏幕中央，列表偏上/偏下时手势会落空或滑到隔壁。
 * 这里用元素中心当落点，默认向上，幅度仍按屏幕百分比填。
 */
@Component
public class SwipeAtElementShortHandler implements KeywordHandler {

    @Override
    public String code() {
        return "SWIPE_AT_ELEMENT_SHORT";
    }

    @Override
    public String nameZh() {
        return "元素处小幅滑动";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "在指定元素的位置挪一点点（默认向上）。用来顶一下某块列表，而不是整屏滑";
    }

    @Override
    public int sortOrder() {
        return 62;
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
        return "元素处小滑";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "落点元素")
                        .withHelp("手指按在这个元素中心再滑。选你想顶一下的那一块，不必是可滚动容器"),
                ParamDef.enumOf("direction", "方向", SwipeHandler.DIRECTIONS, "up"),
                SwipeShortHandler.distanceParam(),
                ParamDef.number("durationMs", "单次滑动毫秒", 400),
                ParamDef.number("times", "次数", 1),
                ParamDef.number("intervalMs", "滑动间隔毫秒", 300)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("元素处小幅滑动");
        String direction = KeywordContext.firstNonBlank(ctx.str("direction"), "up");
        int duration = (int) Math.max(100, ctx.longParam("durationMs", 400));
        int times = (int) Math.min(30, Math.max(1, ctx.longParam("times", 1)));
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", 300));
        double distancePercent = SwipeShortHandler.distancePercent(ctx);

        int[] box = ctx.getDriver().getElementBounds(ctx.getDeviceId(), selector);
        if (box == null || box.length < 4) {
            throw ctx.fail("元素处小幅滑动",
                    "拿不到元素范围，不知道手指按在哪：" + ctx.getElementKey(),
                    selector, "拿不到元素范围: " + ctx.getElementKey());
        }
        int left = Math.min(box[0], box[2]);
        int top = Math.min(box[1], box[3]);
        int right = Math.max(box[0], box[2]);
        int bottom = Math.max(box[1], box[3]);
        int x1 = left + (right - left) / 2;
        int y1 = top + (bottom - top) / 2;

        int[] screen = ScreenVisibility.screenSize(ctx);
        int sw = screen != null ? screen[0] : 1080;
        int sh = screen != null ? screen[1] : 1920;
        int travelX = Math.max(8, (int) Math.round(sw * distancePercent / 100.0));
        int travelY = Math.max(8, (int) Math.round(sh * distancePercent / 100.0));

        int x2 = x1;
        int y2 = y1;
        switch (direction.trim().toLowerCase()) {
            case "down" -> y2 = y1 + travelY;
            case "left" -> x2 = x1 - travelX;
            case "right" -> x2 = x1 + travelX;
            default -> y2 = y1 - travelY;
        }
        x1 = clamp(x1, 8, sw - 8);
        y1 = clamp(y1, 8, sh - 8);
        x2 = clamp(x2, 8, sw - 8);
        y2 = clamp(y2, 8, sh - 8);
        if (Math.abs(x2 - x1) < 4 && Math.abs(y2 - y1) < 4) {
            throw ctx.fail("元素处小幅滑动",
                    "元素太靠边，按 " + distancePercent + "% 挪不动。"
                            + "换一个更靠中间的落点，或把幅度调大一点",
                    selector, "元素处小幅滑动距离太短: " + ctx.getElementKey());
        }

        for (int i = 1; i <= times; i++) {
            ctx.ensureNotCancelled();
            if (!ctx.getDriver().swipe(ctx.getDeviceId(), x1, y1, x2, y2, duration)) {
                throw ctx.fail("元素处小幅滑动",
                        "滑动失败: " + direction + " #" + i
                                + " (" + x1 + "," + y1 + ")->(" + x2 + "," + y2 + ")",
                        selector, "元素处小幅滑动失败: " + ctx.getElementKey());
            }
            if (i < times && intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
        }
        ctx.ok("元素处小幅滑动",
                ctx.getElementKey() + " 处 " + direction + " ×" + times
                        + "（" + distancePercent + "% · " + x1 + "," + y1 + "→" + x2 + "," + y2 + "）");
    }

    private static int clamp(int v, int min, int max) {
        if (max < min) {
            return v;
        }
        return Math.min(max, Math.max(min, v));
    }

}
