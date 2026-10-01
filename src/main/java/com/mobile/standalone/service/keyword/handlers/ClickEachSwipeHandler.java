package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 依次点击之后再滑动：点 2025 → 滑 → 点 2024 → 滑 → … → 点 2013。
 * 只要连点、不要滑，用 {@link ClickEachHandler}。
 */
@Component
public class ClickEachSwipeHandler implements KeywordHandler {

    @Override
    public String code() {
        return "CLICK_EACH_SWIPE";
    }

    @Override
    public String nameZh() {
        return "依次点击并滑动";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "按文案列表或数字区间依次点击，每点完一项再滑一下页面。"
                + "列表：没毛病,加油,可以；区间：2025-2013";
    }

    @Override
    public int sortOrder() {
        return 12;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public List<String> aliases() {
        return List.of("CLICK_EACH_AND_SWIPE");
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.SINGLE;
    }

    @Override
    public String shortLabel() {
        return "依次点滑";
    }

    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        List<ParamDef> out = new ArrayList<>(ClickEachHandler.clickParams());
        out.add(ParamDef.enumOf("afterSwipe", "滑动方向",
                List.of("up", "down", "left", "right"), "up")
                .withHelp("看下面内容一般选 up；方向反了改 down"));
        out.add(ParamDef.number("afterSwipeTimes", "每次滑动次数", 1));
        out.add(ParamDef.number("afterSwipeDurationMs", "单次滑动毫秒", 500));
        out.add(ParamDef.bool("swipeAfterLast", "最后一项也滑", true)
                .withHelp("2013 点完还要不要再滑一次。只想看完最后一年就关掉"));
        return List.copyOf(out);
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        ClickEachLoop.run(ctx, shortLabel(), ClickEachLoop.AfterSwipe.from(ctx));
    }
}
