package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 屏幕滑动。
 * <p>
 * 原先拆成 SWIPE / SWIPE_IOS 两个关键字，但执行器里本就是同一个 case 分支——
 * 平台差异早已在 {@code PlatformDriver} 层消化，编排层不该再分一次。
 * 旧 code 作为别名保留，存量流程无需迁移。
 */
@Component
public class SwipeHandler implements KeywordHandler {

    static final List<String> DIRECTIONS = List.of("up", "down", "left", "right");

    @Override
    public String code() {
        return "SWIPE";
    }

    @Override
    public String nameZh() {
        return "滑动";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "屏幕滑动，Android / iOS 通用（驱动层自动适配）";
    }

    @Override
    public int sortOrder() {
        return 60;
    }

    @Override
    public List<String> aliases() {
        return List.of("SWIPE_IOS");
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.enumOf("direction", "方向", DIRECTIONS, "up"),
                ParamDef.number("durationMs", "单次滑动毫秒", 500),
                ParamDef.number("times", "次数", 1),
                ParamDef.number("intervalMs", "滑动间隔毫秒", 300)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String direction = KeywordContext.firstNonBlank(ctx.str("direction"), "up");
        int duration = (int) ctx.longParam("durationMs", 500);
        int times = (int) Math.min(30, Math.max(1, ctx.longParam("times", 1)));
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", 300));

        for (int i = 1; i <= times; i++) {
            ctx.ensureNotCancelled();
            if (!ctx.getRuntime().directionalSwipe(ctx.getDriver(), ctx.getDeviceId(), direction, duration)) {
                throw ctx.fail("滑动", "滑动失败: " + direction + " #" + i, null, "滑动失败");
            }
            if (i < times && intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
        }
        ctx.ok("滑动", direction + " ×" + times);
    }
}
