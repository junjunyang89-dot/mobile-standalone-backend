package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.util.TextAssertion;
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
        return "断言元素文案：包含 / 等于 / 匹配正则 / 不包含；性能采样模式下断言失败只记录不中断";
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
                ParamDef.string("expected", "期望值"),
                ParamDef.enumOf("mode", "判定方式",
                                List.of("contains", "equals", "regex", "notContains"), "contains")
                        .withHelp("金额、数量、比分这类务必用 equals：contains 判定下"
                                + "「余额 100」在页面显示「余额 1000」时会照样通过，缺陷就这么溜过去了")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("断言文案");
        String expected = ctx.substituted("expected");
        TextAssertion.Mode mode = TextAssertion.Mode.from(ctx.str("mode"));
        String actual = ctx.getDriver().getElementText(ctx.getDeviceId(), selector);

        boolean pass;
        try {
            pass = TextAssertion.matches(mode, actual, expected);
        } catch (TextAssertion.BadPatternException e) {
            // 正则写错和断言不通过是两件完全不同的事：一个要改用例，一个可能是缺陷。
            // 混成同一种失败会让人拿着「断言失败」去查产品，查半天发现是自己正则打错了
            throw ctx.fail("断言文案", e.getMessage() + "：" + expected, selector,
                    "断言正则语法错误: " + ctx.getElementKey());
        }

        if (!pass) {
            String detail = TextAssertion.describeFailure(mode, actual, expected);
            if (ctx.isPerformanceMode()) {
                // 性能模式：功能对错交给 UI_FLOW_TEST，这里继续走路径采样
                ctx.warn("断言文案(性能跳过中断)", detail + "——性能模式继续执行", selector);
                return;
            }
            throw ctx.fail("断言文案", detail, selector,
                    "断言文案失败: " + ctx.getElementKey());
        }
        ctx.ok("断言文案", ctx.getElementKey() + " " + mode.label() + "「" + expected + "」");
    }
}
