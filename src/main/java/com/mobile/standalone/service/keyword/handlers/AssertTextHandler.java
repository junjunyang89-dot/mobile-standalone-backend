package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AssertTextHandler implements KeywordHandler {

    @Override
    public String code() {
        return "ASSERT_TEXT";
    }

    @Override
    public String nameZh() {
        return "断言文案";
    }

    @Override
    public String category() {
        return "ASSERT";
    }

    @Override
    public String description() {
        return "断言元素文案包含期望值；性能采样模式下断言失败只记录不中断";
    }

    @Override
    public int sortOrder() {
        return 90;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public String shortLabel() {
        return "断言";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "元素英文名"),
                ParamDef.string("expected", "期望包含")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("断言文案");
        String expected = ctx.substituted("expected");
        String actual = ctx.getDriver().getElementText(ctx.getDeviceId(), selector);

        if (actual == null || expected == null || !actual.contains(expected)) {
            if (ctx.isPerformanceMode()) {
                // 性能模式：功能对错交给 UI_FLOW_TEST，这里继续走路径采样
                ctx.warn("断言文案(性能跳过中断)",
                        "期望包含「" + expected + "」，实际「" + actual + "」——性能模式继续执行",
                        selector);
                return;
            }
            throw ctx.fail("断言文案",
                    "期望包含「" + expected + "」，实际「" + actual + "」", selector,
                    "断言文案失败: " + ctx.getElementKey());
        }
        ctx.ok("断言文案", ctx.getElementKey() + " 包含「" + expected + "」");
    }
}
