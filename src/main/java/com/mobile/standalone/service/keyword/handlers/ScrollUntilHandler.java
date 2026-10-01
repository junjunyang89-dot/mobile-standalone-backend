package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenVisibility;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 按方向反复滑动直至目标元素出现。
 * <p>
 * 默认半屏幅度；页面内慢慢找元素用 {@code SCROLL_UNTIL_SHORT}（或参数 distance=short），
 * 避免一次滑过头把目标带出屏幕。
 */
@Component
public class ScrollUntilHandler implements KeywordHandler {

    static final List<String> DISTANCES = List.of("short", "normal", "long");

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
        return "按方向反复滑动直至元素出现；可选小幅/半屏/大幅，Android / iOS 通用";
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
        // SHORT：小幅度翻页找元素；IOS：历史别名，行为与主 code 相同
        return List.of("SCROLL_UNTIL_IOS", "SCROLL_UNTIL_SHORT");
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
                ParamDef.enumOf("distance", "单次幅度", DISTANCES, "normal")
                        .withHelp("每次滑多远。上下按屏高，左右按屏宽。"
                                + "short≈12%；normal≈一半；long≈大幅。"
                                + "仍偏大时用下面「幅度百分比」覆盖"),
                ParamDef.number("distancePercent", "幅度百分比", null)
                        .withHelp("按滑动方向的屏高或屏宽百分比自定义，填了就覆盖上面的档位。"
                                + "建议 3～15；0 或不填则用档位。小幅步骤默认建议 5"),
                ParamDef.number("maxAttempts", "最大滑动次数", 20),
                ParamDef.number("durationMs", "单次滑动毫秒", 400)
                        .withHelp("手指划过这段距离花的时间；越大越慢。小幅默认 700，仍快可调到 1000"),
                ParamDef.number("intervalMs", "滑动间隔毫秒", 500)
                        .withHelp("两次滑动之间的停顿，给列表加载/惯性留时间"),
                ParamDef.bool("failIfNotFound", "未找到是否失败", true)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("滑动直到元素");
        String direction = KeywordContext.firstNonBlank(ctx.str("direction"), "up");
        String distance = resolveDistance(ctx);
        double ratio = resolveRatio(ctx, distance);
        boolean customAmp = ctx.longParam("distancePercent", 0) > 0;
        int defaultMax = customAmp || "short".equals(distance) ? 35 : 20;
        int maxAttempts = (int) Math.min(80, Math.max(1, ctx.longParam("maxAttempts", defaultMax)));
        // duration 越大 Appium 划得越慢；旧 short 默认 280 反而更快，改为 700
        boolean slowDefault = customAmp || "short".equals(distance);
        int duration = (int) ctx.longParam("durationMs", slowDefault ? 700 : 400);
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", slowDefault ? 700 : 500));
        boolean failIfNotFound = ctx.boolParam("failIfNotFound", true);
        String elementKey = ctx.getElementKey();

        // 只问「控件树里有没有」会一次都不滑：资讯列表这种 RecyclerView
        // 会把屏幕外的「专栏推荐」也留在树上，标题刚露一条边也算存在。
        // 和区域内滑动同一套：中心点必须落在屏幕内才算到。
        int[] screen = ScreenVisibility.screenSize(ctx);
        if (ScreenVisibility.isOnScreen(ctx, selector, screen)) {
            ctx.ok("滑动直到元素", elementKey + " 已在屏上，无需滑动");
            return;
        }

        boolean found = false;
        int swiped = 0;
        for (int i = 1; i <= maxAttempts; i++) {
            ctx.ensureNotCancelled();
            if (!ctx.getRuntime().directionalSwipe(
                    ctx.getDriver(), ctx.getDeviceId(), direction, duration, ratio)) {
                throw ctx.fail("滑动直到元素", "第 " + i + " 次滑动失败: " + direction, selector,
                        "滑动直到元素失败: " + elementKey);
            }
            swiped = i;
            // Appium 滑动本身拦不住，滑完立刻再查一次，别等 interval 才响应停止
            ctx.ensureNotCancelled();
            if (intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
            ctx.ensureNotCancelled();
            if (ScreenVisibility.isOnScreen(ctx, selector, screen)) {
                found = true;
                break;
            }
        }

        String amp = amplitudeLabel(ctx, distance);
        if (found) {
            ctx.ok("滑动直到元素",
                    elementKey + " 在" + amp + "滑动 " + swiped + " 次后出现 [" + direction + "]");
        } else if (failIfNotFound) {
            throw ctx.fail("滑动直到元素",
                    amp + "滑动 " + maxAttempts + " 次仍未找到: " + elementKey, selector,
                    "滑动直到元素未找到: " + elementKey);
        } else {
            ctx.ok("滑动直到元素",
                    elementKey + " " + amp + "滑动 " + maxAttempts + " 次仍未找到（已忽略失败）");
        }
    }

    /** SHORT 别名默认小幅；显式传了 distance 以参数为准 */
    static String resolveDistance(KeywordContext ctx) {
        String explicit = ctx.str("distance");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        String raw = ctx.getRawCode() == null ? "" : ctx.getRawCode().trim().toUpperCase(Locale.ROOT);
        if ("SCROLL_UNTIL_SHORT".equals(raw)) {
            return "short";
        }
        return "normal";
    }

    /**
     * 有「幅度百分比」时优先用（真机轻轻一滑场景）；否则用 short/normal/long 档位。
     * 下限 3%，避免几乎不动；上限与执行器一致。
     */
    static double resolveRatio(KeywordContext ctx, String distance) {
        return resolveRatio(ctx.longParam("distancePercent", 0), distance);
    }

    static double resolveRatio(long distancePercent, String distance) {
        if (distancePercent > 0) {
            return Math.min(0.8, Math.max(0.03, distancePercent / 100.0));
        }
        return distanceRatio(distance);
    }

    static String amplitudeLabel(KeywordContext ctx, String distance) {
        long pct = ctx.longParam("distancePercent", 0);
        if (pct > 0) {
            return "自定义" + pct + "%";
        }
        return "short".equals(distance) ? "小幅" : "long".equals(distance) ? "大幅" : "半屏";
    }

    static double distanceRatio(String distance) {
        return switch (distance == null ? "normal" : distance.toLowerCase(Locale.ROOT)) {
            case "short" -> 0.12;
            case "long" -> 0.65;
            default -> 0.44;
        };
    }
}
