package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.FlowOutcome;
import org.springframework.stereotype.Component;

/**
 * 成功结束：达成目标就此收尾，判通过。
 */
@Component
public class FinishSuccessHandler extends AbstractFinishHandler {

    @Override
    public String code() {
        return "FINISH_SUCCESS";
    }

    @Override
    public String nameZh() {
        return "成功结束";
    }

    @Override
    public String description() {
        return "流程已达成目标，就此结束并判通过。常放在「找到成功标志」的分支里，相当于一条断言";
    }

    @Override
    public int sortOrder() {
        return 57;
    }

    @Override
    public String shortLabel() {
        return "成功结束";
    }

    @Override
    protected FlowOutcome outcome() {
        return FlowOutcome.PASS;
    }

    @Override
    protected String defaultMessage() {
        return "流程已达成目标";
    }
}
