package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 整屏小幅滑动。普通「滑动」一次大约半屏，短列表/锚点附近会把目标直接划过去。
 */
@Component
public class SwipeShortHandler implements KeywordHandler {

    /** 没填幅度时的默认值：只挪一点点 */
    static final double DISTANCE_PERCENT = 4;

    static ParamDef distanceParam() {
        return ParamDef.number("distancePercent", "滑动幅度(%)", DISTANCE_PERCENT)
                .withHelp("屏幕高/宽的百分比，支持小数，步进 0.5。7 和 8 之间可以填 7.5。普通「滑动」大约半屏");
    }

    static double distancePercent(KeywordContext ctx) {
        return Math.min(70, Math.max(1, ctx.doubleParam("distancePercent", DISTANCE_PERCENT)));
    }

    @Override
    public String code() {
        return "SWIPE_SHORT";
    }

    @Override
    public String nameZh() {
        return "小幅滑动";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "整屏小幅滑动，幅度按屏幕百分比。资讯列表若整屏中心是大卡片、滑不动，改用「元素处小幅滑动」绑列表";
    }

    @Override
    public int sortOrder() {
        return 61;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.enumOf("direction", "方向", SwipeHandler.DIRECTIONS, "up"),
                distanceParam(),
                ParamDef.number("durationMs", "单次滑动毫秒", 220)
                        .withHelp("短距离要甩，不要拖。拖 500ms 容易被资讯卡片当成长按，页面看起来没滑"),
                ParamDef.number("times", "次数", 1),
                ParamDef.number("intervalMs", "滑动间隔毫秒", 300)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String direction = KeywordContext.firstNonBlank(ctx.str("direction"), "up");
        int duration = (int) ctx.longParam("durationMs", 220);
        int times = (int) Math.min(30, Math.max(1, ctx.longParam("times", 1)));
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", 300));
        double distancePercent = distancePercent(ctx);

        for (int i = 1; i <= times; i++) {
            ctx.ensureNotCancelled();
            if (!ctx.getRuntime().directionalSwipe(ctx.getDriver(), ctx.getDeviceId(),
                    direction, duration, distancePercent)) {
                throw ctx.fail("小幅滑动",
                        "滑动失败: " + direction + " #" + i + "（" + distancePercent
                                + "%）。Appium/W3C 没发出去，ADB 回退也失败——先看设备是否还在线",
                        null, "小幅滑动失败");
            }
            if (i < times && intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
        }
        ctx.ok("小幅滑动", direction + " ×" + times + "（" + distancePercent + "%）");
    }
}
