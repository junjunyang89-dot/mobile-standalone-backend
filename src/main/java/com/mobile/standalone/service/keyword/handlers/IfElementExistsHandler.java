package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.FlowOutcome;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 元素存在判断。嵌套分支的编辑交互不是参数表能描述的，前端保留专用编辑器。
 */
@Component
public class IfElementExistsHandler implements KeywordHandler {

    @Override
    public String code() {
        return "IF_ELEMENT_EXISTS";
    }

    @Override
    public String nameZh() {
        return "元素存在判断";
    }

    @Override
    public String category() {
        return "FLOW";
    }

    @Override
    public String description() {
        return "判断元素是否存在：存在执行 thenSteps；不存在时若有 elseSteps 则执行，"
                + "否则按「不存在时判定」结束流程并返回 elseMessage";
    }

    @Override
    public int sortOrder() {
        return 55;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public boolean customUi() {
        return true;
    }

    @Override
    public String shortLabel() {
        return "判断";
    }

    /** 不重试：分支里的步骤已经执行过，重试会重复执行 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "判断的元素"),
                ParamDef.number("timeoutSec", "探测超时秒", 3),
                ParamDef.steps("thenSteps", "存在时步骤"),
                ParamDef.steps("elseSteps", "不存在时步骤"),
                ParamDef.string("elseMessage", "不存在时返回提示"),
                ParamDef.enumOf("endStatus", "不存在时判定", FlowOutcome.optionNames(), FlowOutcome.FAIL.name())
                        .withHelp("必现页面选 FAIL（默认）；可选弹窗选 PASS；前置条件不满足选 SKIP。"
                                + "仅在走「返回提示」结束流程时生效")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("元素存在判断");
        int timeout = (int) ctx.longParam("timeoutSec", 3);
        boolean exists = ctx.getDriver().waitForElement(ctx.getDeviceId(), selector, Math.max(1, timeout));
        ctx.ok("元素存在判断", ctx.getElementKey() + " → " + (exists ? "存在" : "不存在"));

        if (exists) {
            ctx.runNested(ctx.getRuntime().asStepList(ctx.rawParam("thenSteps")));
            return;
        }

        List<Map<String, Object>> elseSteps = ctx.getRuntime().asStepList(ctx.rawParam("elseSteps"));
        // 有 else 步骤时优先执行步骤；仅当没有步骤时，「返回提示」才直接结束流程
        if (elseSteps != null && !elseSteps.isEmpty()) {
            ctx.runNested(elseSteps);
            return;
        }
        String elseMessage = ctx.substituted("elseMessage");
        if (elseMessage != null && !elseMessage.isBlank()) {
            FlowOutcome outcome = FlowOutcome.from(ctx.str("endStatus"));
            ctx.getState().returnEarly(elseMessage, outcome);
            String detail = elseMessage + "（" + outcome.getDisplayName() + "）";
            if (outcome == FlowOutcome.FAIL) {
                ctx.warn("返回提示", detail, ctx.getElementKey());
            } else {
                ctx.ok("返回提示", detail);
            }
        }
    }
}
