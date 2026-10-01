package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按区间或列表依次替换文案变量并点击。只要连点、不要滑，用这个。
 * 点完还要滑页面，用 {@link ClickEachSwipeHandler}。
 */
@Component
public class ClickEachHandler implements KeywordHandler {

    static final List<String> CLICK_METHODS =
            List.of("AUTO", "ELEMENT", "COORDINATE", "ADB", "ELEMENT_THEN_COORD");

    @Override
    public String code() {
        return "CLICK_EACH";
    }

    @Override
    public String nameZh() {
        return "依次点击";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "按文案列表或数字区间依次点击，每一项都会点一次，不滑动。"
                + "列表用逗号或换行：没毛病,加油,可以；区间如 2025-2013。点完还要滑用「依次点击并滑动」";
    }

    @Override
    public int sortOrder() {
        return 11;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public List<String> aliases() {
        return List.of("CLICK_SEQUENCE", "CLICK_YEARS");
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.SINGLE;
    }

    @Override
    public String shortLabel() {
        return "依次点";
    }

    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return clickParams();
    }

    static List<ParamDef> clickParams() {
        return List.of(
                ParamDef.element("elementKey", "要点的元素"),
                ParamDef.string("varName", "文案变量名")
                        .withHelp("必须和元素定位里 ${} 的名字一致。默认 year；表态文案可改成 emojiText"),
                ParamDef.requiredText("sequence", "依次取值")
                        .withHelp("任意文案，逗号或一行一项：没毛病,加油,可以。数字区间 2025-2013 会按顺序点完"),
                ParamDef.enumOf("clickMethod", "点击方式", CLICK_METHODS, "AUTO"),
                ParamDef.number("waitAfterMs", "每项点完等待毫秒", 300)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        ClickEachLoop.run(ctx, shortLabel(), null);
    }
}
