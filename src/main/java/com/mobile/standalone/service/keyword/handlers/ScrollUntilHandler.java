package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按方向反复滑动直至目标元素出现。
 * <p>
 * 与 {@link SwipeHandler} 同理，原 SCROLL_UNTIL / SCROLL_UNTIL_IOS 在执行器里就是同一分支，已合并。
 */
@Component
public class ScrollUntilHandler implements KeywordHandler {

    @Override
    public String code() {
        return "SCROLL_UNTIL";
    }

    @Override
    public String nameZh() {
        return "滑动直到元素";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "按方向反复滑动直至元素出现，Android / iOS 通用";
    }

    @Override
    public int sortOrder() {
        return 65;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public List<String> aliases() {
        return List.of("SCROLL_UNTIL_IOS");
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public String shortLabel() {
        return "滑动直到";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "目标元素"),
                ParamDef.enumOf("direction", "方向", SwipeHandler.DIRECTIONS, "up"),
                ParamDef.number("maxAttempts", "最大滑动次数", 20),
                ParamDef.number("durationMs", "单次滑动毫秒", 400),
                ParamDef.number("intervalMs", "滑动间隔毫秒", 500),
                ParamDef.bool("failIfNotFound", "未找到是否失败", true)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("滑动直到元素");
        String direction = KeywordContext.firstNonBlank(ctx.str("direction"), "up");
        int maxAttempts = (int) Math.min(50, Math.max(1, ctx.longParam("maxAttempts", 20)));
        int duration = (int) ctx.longParam("durationMs", 400);
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", 500));
        boolean failIfNotFound = ctx.boolParam("failIfNotFound", true);
        String elementKey = ctx.getElementKey();

        if (ctx.getDriver().waitForElement(ctx.getDeviceId(), selector, 1)) {
            ctx.ok("滑动直到元素", elementKey + " 已在屏上，无需滑动");
            return;
        }

        boolean found = false;
        int swiped = 0;
        for (int i = 1; i <= maxAttempts; i++) {
            ctx.ensureNotCancelled();
            if (!ctx.getRuntime().directionalSwipe(ctx.getDriver(), ctx.getDeviceId(), direction, duration)) {
                throw ctx.fail("滑动直到元素", "第 " + i + " 次滑动失败: " + direction, selector,
                        "滑动直到元素失败: " + elementKey);
            }
            swiped = i;
            if (intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
            if (ctx.getDriver().waitForElement(ctx.getDeviceId(), selector, 1)) {
                found = true;
                break;
            }
        }

        if (found) {
            ctx.ok("滑动直到元素", elementKey + " 在滑动 " + swiped + " 次后出现 [" + direction + "]");
        } else if (failIfNotFound) {
            throw ctx.fail("滑动直到元素", "滑动 " + maxAttempts + " 次仍未找到: " + elementKey, selector,
                    "滑动直到元素未找到: " + elementKey);
        } else {
            ctx.ok("滑动直到元素", elementKey + " 滑动 " + maxAttempts + " 次仍未找到（已忽略失败）");
        }
    }
}
