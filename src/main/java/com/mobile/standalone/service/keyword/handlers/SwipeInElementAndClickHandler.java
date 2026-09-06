package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在某个容器里滑到目标元素，再点它。
 * <p>
 * 专栏推荐、金刚位这类横滑条：随机点第 N 张时，N 往往不在屏上。
 * 拆成「区域内滑动 until」+「点击」两步，人要自己保证两步绑同一个元素；
 * 合成一步之后，目标只解析一次，滑到的就是点的，新流程直接拖这个关键字。
 */
@Component
@RequiredArgsConstructor
public class SwipeInElementAndClickHandler implements KeywordHandler {

    private final SwipeInElementHandler swipeInElement;
    private final ClickHandler click;

    @Override
    public String code() {
        return "SWIPE_IN_ELEMENT_AND_CLICK";
    }

    @Override
    public String nameZh() {
        return "区域内滑到并点击";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "在指定区域内滑动直到目标露出，然后点击该目标。横滑列表随机点一张卡用它，不用再拆成滑动+点击两步";
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
    public String shortLabel() {
        return "滑到并点击";
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "滑动区域元素")
                        .withHelp("横滑/竖滑的那一层容器（专栏 vgList、金刚位那一条），不是要点的那张卡"),
                ParamDef.element("clickElementKey", "要点的元素")
                        .withHelp("可以带 [X] / [X:1-10] 随机下标。本步只解析一次，滑到的就是点的"),
                ParamDef.enumOf("direction", "方向", SwipeHandler.DIRECTIONS, "left")
                        .withHelp("left＝内容往左走（看后面的），right＝往回看前面的"),
                ParamDef.number("maxAttempts", "最多滑几次", 10)
                        .withHelp("兜底上限，露出来就停。滑到头也会提前停"),
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
        String clickKey = ctx.str("clickElementKey");
        if (clickKey == null || clickKey.isBlank()) {
            throw ctx.fail("区域内滑到并点击",
                    "没指定要点的元素。本关键字是「在区域内滑到某张卡再点它」，"
                            + "滑动区域绑在步骤元素上，要点的那张卡填在「要点的元素」",
                    null, "区域内滑到并点击缺少目标元素");
        }

        List<String> targetSelectors = ctx.getRuntime().requireSelectors(
                ctx.getPlatform(), ctx.getUiAppId(), clickKey, ctx.getExecParams(), ctx.getLogs(),
                ctx.sn("区域内滑到并点击·目标"));
        ctx.ok("区域内滑到并点击", "本次要点: " + clickKey + " → " + targetSelectors.get(0));

        Map<String, Object> swipeParams = new HashMap<>();
        swipeParams.put("elementKey", ctx.getElementKey());
        swipeParams.put("direction", ctx.str("direction"));
        swipeParams.put("stopMode", "until");
        swipeParams.put("untilElementKey", clickKey);
        swipeParams.put("untilSelectors", targetSelectors);
        swipeParams.put("maxAttempts", ctx.longParam("maxAttempts", 10));
        swipeParams.put("failIfNotFound", true);
        swipeParams.put("durationMs", ctx.longParam("durationMs", 500));
        swipeParams.put("intervalMs", ctx.longParam("intervalMs", 300));
        swipeParams.put("marginPercent", ctx.longParam("marginPercent", 12));
        swipeParams.put("distancePercent", ctx.doubleParam("distancePercent", SwipeInElementHandler.DEFAULT_DISTANCE_PERCENT));
        swipeParams.put("verifyChanged", ctx.boolParam("verifyChanged", true));
        swipeParams.put("requireScrollable", ctx.boolParam("requireScrollable", true));

        swipeInElement.execute(ctx.toBuilder()
                .stepParams(swipeParams)
                .build());

        Map<String, Object> clickParams = new HashMap<>();
        clickParams.put("elementKey", clickKey);
        clickParams.put("clickMethod", KeywordContext.firstNonBlank(ctx.str("clickMethod"), "AUTO"));
        clickParams.put("offsetXPercent", ctx.longParam("offsetXPercent", 50));
        clickParams.put("offsetYPercent", ctx.longParam("offsetYPercent", 50));
        clickParams.put("waitAfterMs", ctx.longParam("waitAfterMs", 0));
        clickParams.put("assertChecked", false);

        click.execute(ctx.toBuilder()
                .elementKey(clickKey)
                .stepParams(clickParams)
                .resolvedSelectors(targetSelectors)
                .build());
    }
}
