package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 先向上滑若干次，再向下滑若干次。
 * <p>
 * 资讯流、社区这类「先往下翻几屏看看，再滑回来」不必拆成两条滑动。
 */
@Component
public class SwipeUpThenDownHandler implements KeywordHandler {

    @Override
    public String code() {
        return "SWIPE_UP_THEN_DOWN";
    }

    @Override
    public String nameZh() {
        return "先上滑再下滑";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "整屏先向上滑动若干次，再向下滑动若干次（浏览列表后再回到附近位置）";
    }

    @Override
    public int sortOrder() {
        return 63;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public String shortLabel() {
        return "上滑再下滑";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.number("upTimes", "上滑次数", 3)
                        .withHelp("内容往上走，等于往列表后面翻。填 0 则跳过上滑"),
                ParamDef.number("downTimes", "下滑次数", 3)
                        .withHelp("内容往下走，等于往回翻。填 0 则跳过下滑"),
                ParamDef.number("durationMs", "单次滑动毫秒", 500),
                ParamDef.number("intervalMs", "滑动间隔毫秒", 300)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        int upTimes = (int) Math.min(30, Math.max(0, ctx.longParam("upTimes", 3)));
        int downTimes = (int) Math.min(30, Math.max(0, ctx.longParam("downTimes", 3)));
        if (upTimes == 0 && downTimes == 0) {
            throw ctx.fail("先上滑再下滑",
                    "上滑次数和下滑次数都是 0，这一步什么都不会做",
                    null, "先上滑再下滑次数都为 0");
        }
        int duration = (int) ctx.longParam("durationMs", 500);
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", 300));

        swipeTimes(ctx, "up", upTimes, duration, intervalMs);
        if (upTimes > 0 && downTimes > 0 && intervalMs > 0) {
            ctx.sleep(intervalMs);
        }
        swipeTimes(ctx, "down", downTimes, duration, intervalMs);
        ctx.ok("先上滑再下滑", "up ×" + upTimes + " → down ×" + downTimes);
    }

    private static void swipeTimes(KeywordContext ctx, String direction, int times,
                                   int duration, int intervalMs) throws Exception {
        for (int i = 1; i <= times; i++) {
            ctx.ensureNotCancelled();
            if (!ctx.getRuntime().directionalSwipe(ctx.getDriver(), ctx.getDeviceId(), direction, duration)) {
                throw ctx.fail("先上滑再下滑",
                        "滑动失败: " + direction + " #" + i, null, "先上滑再下滑失败");
            }
            if (i < times && intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
        }
    }
}
