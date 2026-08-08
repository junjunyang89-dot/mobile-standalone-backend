package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;

import java.util.List;

/**
 * 流程被用户主动停止。
 * <p>
 * 继承 {@link UiFlowExecutionException}，已有的 catch 分支无需改动即可正常收尾；
 * 上游按类型区分后应把任务标为 STOPPED 而不是 FAILED。
 */
public class UiFlowCancelledException extends UiFlowExecutionException {

    public UiFlowCancelledException(String message, List<ExecutionLogEntryDto> executionLog) {
        super(message, executionLog);
    }
}
