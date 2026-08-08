package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.FlowOutcome;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;

import java.util.List;

/**
 * 「提前结束流程」系列关键字的公共实现，判定写在关键字名字上。
 * <p>
 * 取代原来「一个 RETURN_MESSAGE + 一个 endStatus 参数」的写法。那种写法把「结束流程」
 * 和「这次算成功还是失败」拆成了看得见的步骤和看不见的参数，存量步骤没有该参数时
 * 只能取默认值——而任何默认值都会猜错一半：定 FAIL 则「成功登录XXX」被记成失败，
 * 定 PASS 则「元素不存在就退出」又被记成通过。
 * <p>
 * 拆成独立关键字后不存在可漏配的参数，拖哪个就是什么语义。
 */
abstract class AbstractFinishHandler implements KeywordHandler {

    /** 本关键字对应的判定 */
    protected abstract FlowOutcome outcome();

    /** 未填文案时写进报告的默认结论 */
    protected abstract String defaultMessage();

    @Override
    public String category() {
        return "FLOW";
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    /** 不重试：结束类关键字不会失败 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(ParamDef.string("message", "结论文案")
                .withHelp("会显示在任务详情与回归报告里，写清楚「为什么在这里结束」"));
    }

    @Override
    public void execute(KeywordContext ctx) {
        String message = ctx.substituted("message");
        if (message == null || message.isBlank()) {
            message = defaultMessage();
        }
        ctx.getState().returnEarly(message, outcome());

        String detail = message + "（本次" + outcome().getDisplayName() + "）";
        if (outcome() == FlowOutcome.FAIL) {
            ctx.warn(shortLabel(), detail, null);
        } else {
            ctx.ok(shortLabel(), detail);
        }
    }
}
