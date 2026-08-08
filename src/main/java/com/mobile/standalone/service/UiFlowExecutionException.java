package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import lombok.Getter;

import java.util.List;

@Getter
public class UiFlowExecutionException extends Exception {
    private final List<ExecutionLogEntryDto> executionLog;

    public UiFlowExecutionException(String message, List<ExecutionLogEntryDto> executionLog) {
        super(message);
        this.executionLog = executionLog;
    }
}
