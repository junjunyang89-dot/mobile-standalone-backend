package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenVisibility;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import com.mobile.standalone.util.LocatorTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 在某个 LIST 区域内滑动，范围内谁先露出来就点谁。
 * <p>
 * 和「区域内滑到并点击」的差别：那边先抽死一个下标再去找第 N 张；
 * 这边不知道会碰到哪一张，把 [X:1-10] 当成候选集合，滑一次看一次，
 * 屏上落在这个 LIST 里的任意一张都可以点。
 */
@Component
@RequiredArgsConstructor
public class SwipeInElementHitAndClickHandler implements KeywordHandler {

    private final SwipeInElementHandler swipeInElement;
    private final ClickHandler click;

    @Override
    public String code() {
        return "SWIPE_IN_ELEMENT_HIT_AND_CLICK";
    }

    @Override
    public String nameZh() {
        return "区域内命中并点击";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "在指定 LIST 区域内滑动，定位范围里谁露出来就点谁，不必预先指定第几张";
    }

    @Override
    public int sortOrder() {
        return 64;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public String shortLabel() {
        return "区域内命中点击";
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "滑动区域元素")
                        .withHelp("LIST 那一层容器，例如专栏 vgList，手势锁在这个范围内"),
                ParamDef.element("hitElementKey", "范围内的目标元素")
                        .withHelp("定位可写 [X:1-10]，表示第 1～10 张都算命中。"
                                + "不会先随机抽一个下标，谁在屏上、又在这个 LIST 里就点谁"),
                ParamDef.enumOf("direction", "方向", SwipeHandler.DIRECTIONS, "left"),
                ParamDef.number("maxAttempts", "最多滑几次", 10),
                ParamDef.number("durationMs", "单次滑动毫秒", 500),
                ParamDef.number("intervalMs", "滑动间隔毫秒", 300),
                ParamDef.number("marginPercent", "两端留白百分比", 12),
                ParamDef.number("distancePercent", "滑动幅度(%)", SwipeInElementHandler.DEFAULT_DISTANCE_PERCENT)
                        .withHelp("相对这一条区域的百分比，默认 28，拉满容易切到别的页签"),
                ParamDef.bool("verifyChanged", "校验内容是否真的变了", true),
                ParamDef.bool("requireScrollable", "要求元素可滚动", true),
                ParamDef.enumOf("clickMethod", "点击方式",
                        List.of("AUTO", "ELEMENT", "COORDINATE", "ADB", "ELEMENT_THEN_COORD"), "AUTO"),
                ParamDef.number("offsetXPercent", "横向点击位置", 50),
                ParamDef.number("offsetYPercent", "纵向点击位置", 50),
                ParamDef.number("waitAfterMs", "点击后等待毫秒", 0)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String hitKey = ctx.str("hitElementKey");
        if (hitKey == null || hitKey.isBlank()) {
            throw ctx.fail("区域内命中并点击",
                    "没指定范围内的目标元素。滑动区域绑 LIST 容器，目标填带 [X:1-10] 的卡片定位",
                    null, "区域内命中并点击缺少目标元素");
        }

        List<String> templates = ctx.getRuntime().requireSelectorTemplates(
                ctx.getPlatform(), ctx.getUiAppId(), hitKey, ctx.getExecParams(), ctx.getLogs(),
                ctx.sn("区域内命中并点击·目标"));
        List<String> candidates = new ArrayList<>();
        for (String tpl : templates) {
            candidates.addAll(LocatorTemplate.expandAllIndexes(tpl));
        }
        if (candidates.isEmpty()) {
            throw ctx.fail("区域内命中并点击",
                    "目标元素没有可用定位: " + hitKey, null, "区域内命中并点击缺少定位");
        }
        ctx.ok("区域内命中并点击",
                "候选 " + candidates.size() + " 条（" + hitKey + "），范围内谁露出来点谁");

        int maxAttempts = (int) Math.min(50, Math.max(0, ctx.longParam("maxAttempts", 10)));
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", 300));
        int[] screen = ScreenVisibility.screenSize(ctx);

        String hit = firstHit(ctx, candidates, screen);
        if (hit != null) {
            clickHit(ctx, hitKey, hit);
            return;
        }

        Map<String, Object> swipeParams = new HashMap<>();
        swipeParams.put("elementKey", ctx.getElementKey());
        swipeParams.put("direction", ctx.str("direction"));
        swipeParams.put("stopMode", "fixed");
        swipeParams.put("times", 1);
        swipeParams.put("durationMs", ctx.longParam("durationMs", 500));
        swipeParams.put("intervalMs", 0);
        swipeParams.put("marginPercent", ctx.longParam("marginPercent", 12));
        swipeParams.put("distancePercent", ctx.doubleParam("distancePercent", SwipeInElementHandler.DEFAULT_DISTANCE_PERCENT));
        swipeParams.put("verifyChanged", ctx.boolParam("verifyChanged", true));
        swipeParams.put("requireScrollable", ctx.boolParam("requireScrollable", true));
        KeywordContext swipeCtx = ctx.toBuilder().stepParams(swipeParams).build();

        for (int i = 1; i <= maxAttempts; i++) {
            ctx.ensureNotCancelled();
            swipeInElement.execute(swipeCtx);
            if (intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
            hit = firstHit(ctx, candidates, screen);
            if (hit != null) {
                ctx.ok("区域内命中并点击", "滑 " + i + " 次后命中 " + hit);
                clickHit(ctx, hitKey, hit);
                return;
            }
        }

        throw ctx.fail("区域内命中并点击",
                "在 " + ctx.getElementKey() + " 内滑了 " + maxAttempts + " 次，"
                        + hitKey + " 的范围内始终没有元素露在这个 LIST 里",
                candidates.get(0), "区域内未命中可点元素: " + hitKey);
    }

    /**
     * 当前屏上、且中心点落在 LIST 区域内的候选。有多张时随机点一张。
     */
    private static String firstHit(KeywordContext ctx, List<String> candidates, int[] screen) {
        String regionSel;
        try {
            regionSel = ctx.selector("区域内命中并点击");
        } catch (Exception e) {
            regionSel = null;
        }
        int[] region = null;
        if (regionSel != null) {
            try {
                region = ctx.getDriver().getElementBounds(ctx.getDeviceId(), regionSel);
            } catch (Exception ignored) {
                // 取不到区域就不额外限制，只看是否在屏上
            }
        }
        List<String> visible = new ArrayList<>();
        for (String sel : candidates) {
            if (!ScreenVisibility.isOnScreen(ctx, sel, screen)) {
                continue;
            }
            if (region != null && region.length >= 4 && !centerInRegion(ctx, sel, region)) {
                continue;
            }
            visible.add(sel);
        }
        if (visible.isEmpty()) {
            return null;
        }
        return visible.get(ThreadLocalRandom.current().nextInt(visible.size()));
    }

    private static boolean centerInRegion(KeywordContext ctx, String selector, int[] region) {
        try {
            int[] box = ctx.getDriver().getElementBounds(ctx.getDeviceId(), selector);
            if (box == null || box.length < 4) {
                return true;
            }
            int left = Math.min(region[0], region[2]);
            int top = Math.min(region[1], region[3]);
            int right = Math.max(region[0], region[2]);
            int bottom = Math.max(region[1], region[3]);
            int cx = (Math.min(box[0], box[2]) + Math.max(box[0], box[2])) / 2;
            int cy = (Math.min(box[1], box[3]) + Math.max(box[1], box[3])) / 2;
            return cx >= left && cx < right && cy >= top && cy < bottom;
        } catch (Exception e) {
            return true;
        }
    }

    private void clickHit(KeywordContext ctx, String hitKey, String selector) throws Exception {
        Map<String, Object> clickParams = new HashMap<>();
        clickParams.put("elementKey", hitKey);
        clickParams.put("clickMethod", KeywordContext.firstNonBlank(ctx.str("clickMethod"), "AUTO"));
        clickParams.put("offsetXPercent", ctx.longParam("offsetXPercent", 50));
        clickParams.put("offsetYPercent", ctx.longParam("offsetYPercent", 50));
        clickParams.put("waitAfterMs", ctx.longParam("waitAfterMs", 0));
        clickParams.put("assertChecked", false);
        click.execute(ctx.toBuilder()
                .elementKey(hitKey)
                .stepParams(clickParams)
                .resolvedSelectors(List.of(selector))
                .build());
    }
}
