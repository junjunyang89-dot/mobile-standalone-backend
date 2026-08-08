package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.FlowOutcome;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReturnMessageHandler implements KeywordHandler {

    @Override
    public String code() {
        return "RETURN_MESSAGE";
    }

    @Override
    public String nameZh() {
        return "返回提示（旧）";
    }

    @Override
    public String category() {
        return "FLOW";
    }

    @Override
    public String description() {
        return "【已由「成功结束 / 失败结束 / 跳过结束」取代】结束流程并返回文案，判定取决于「结束判定」参数——"
                + "参数没配时只能按失败处理，容易把「成功登录」这类正常收尾记成失败。"
                + "存量流程可继续运行，新建流程请改用三个明确的结束关键字";
    }

    /** 排到最后：面板上不再作为首选写法 */
    @Override
    public int sortOrder() {
        return 900;
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
        return List.of(
                ParamDef.string("message", "提示文案"),
                ParamDef.enumOf("endStatus", "结束判定", FlowOutcome.optionNames(), FlowOutcome.FAIL.name())
                        .withHelp("因为缺元素/走不下去而结束选 FAIL（默认）；正常提前收尾选 PASS；本次不适用选 SKIP")
        );
    }

    @Override
    public void execute(KeywordContext ctx) {
        String message = ctx.substituted("message");
        FlowOutcome outcome = FlowOutcome.from(ctx.str("endStatus"));
        ctx.getState().returnEarly(message, outcome);

        String detail = ctx.getState().returnMessage + "（" + outcome.getDisplayName() + "）";
        if (outcome == FlowOutcome.FAIL) {
            ctx.warn("返回提示", detail, null);
        } else {
            ctx.ok("返回提示", detail);
        }
    }
}
