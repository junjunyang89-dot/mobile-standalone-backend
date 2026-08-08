package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.FlowOutcome;
import org.springframework.stereotype.Component;

/**
 * 失败结束：关键前提不成立、走不下去，判失败。
 */
@Component
public class FinishFailedHandler extends AbstractFinishHandler {

    @Override
    public String code() {
        return "FINISH_FAILED";
    }

    @Override
    public String nameZh() {
        return "失败结束";
    }

    @Override
    public String description() {
        return "流程走不下去了，就此结束并判失败。常放在「关键元素不存在」的分支里";
    }

    @Override
    public int sortOrder() {
        return 58;
    }

    @Override
    public String shortLabel() {
        return "失败结束";
    }

    @Override
    protected FlowOutcome outcome() {
        return FlowOutcome.FAIL;
    }

    @Override
    protected String defaultMessage() {
        return "流程无法继续";
    }
}
