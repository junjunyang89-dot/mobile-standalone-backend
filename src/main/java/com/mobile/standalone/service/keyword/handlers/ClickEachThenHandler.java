package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 依次点名单上的每一项；每点完一项，执行本步下面挂的同一套步骤，跑完再点下一项。
 * <p>
 * 和「调用组件」不是一回事：行为直接写在本步里（点击、输入、滑动、断言都可以加），
 * 不需要先另存一条组件，也不改「依次点击」。
 */
@Component
public class ClickEachThenHandler implements KeywordHandler {

    @Override
    public String code() {
        return "CLICK_EACH_THEN";
    }

    @Override
    public String nameZh() {
        return "依次点击后执行";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "依次点名单上的文案，每点完一项就执行下面挂的步骤（点击、输入、滑动等可任意加），"
                + "整套跑完再点下一项。取值如：26年TOP指数,2025年,2024年";
    }

    @Override
    public int sortOrder() {
        return 14;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public List<String> aliases() {
        return List.of("CLICK_EACH_THEN_COMPONENT");
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.SINGLE;
    }

    @Override
    public String shortLabel() {
        return "依次点后做";
    }

    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "要点的元素"),
                ParamDef.string("varName", "文案变量名")
                        .withHelp("必须和定位里 ${} 一致。你这个 Tab 是 ${tabText}，这里填 tabText。默认 year"),
                ParamDef.requiredText("sequence", "依次取值")
                        .withHelp("要依次点的文案，逗号或一行一项：26年TOP指数,历年选手回顾"),
                ParamDef.enumOf("clickMethod", "点击方式",
                        List.of("AUTO", "ELEMENT", "COORDINATE", "ADB", "ELEMENT_THEN_COORD"), "AUTO"),
                ParamDef.number("waitAfterMs", "点完后停留毫秒", 300)
                        .withHelp("点进这一项后先停一下，再跑下面的步骤"),
                ParamDef.steps("thenSteps", "点完后的步骤")
                        .withHelp("每次点完名单项之后要做的事：再点、输入、滑动、断言都可以加，条数不限。每项都跑同一套")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<Map<String, Object>> thenSteps = ctx.getRuntime().asStepList(ctx.rawParam("thenSteps"));
        if (thenSteps == null || thenSteps.isEmpty()) {
            throw ctx.fail(shortLabel(), "请在本步下面添加点完后要执行的步骤（点击、输入等）", null);
        }
        ClickEachLoop.runAfterEach(ctx, shortLabel(), (once, item) -> {
            once.ok(shortLabel(), item + " 之后执行 " + thenSteps.size() + " 步");
            once.runNested(thenSteps, once.getDepth() + 1);
        });
    }
}
