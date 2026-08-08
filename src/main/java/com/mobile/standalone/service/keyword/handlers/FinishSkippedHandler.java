package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.FlowOutcome;
import org.springframework.stereotype.Component;

/**
 * 跳过结束：本次不适用，既不算通过也不算失败，不计入通过率。
 */
@Component
public class FinishSkippedHandler extends AbstractFinishHandler {

    @Override
    public String code() {
        return "FINISH_SKIPPED";
    }

    @Override
    public String nameZh() {
        return "跳过结束";
    }

    @Override
    public String description() {
        return "本次不适用（前置条件不满足），就此结束并判跳过，不计入通过率";
    }

    @Override
    public int sortOrder() {
        return 59;
    }

    @Override
    public String shortLabel() {
        return "跳过结束";
    }

    @Override
    protected FlowOutcome outcome() {
        return FlowOutcome.SKIP;
    }

    @Override
    protected String defaultMessage() {
        return "本次不适用，已跳过";
    }
}
