package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import lombok.Getter;

import java.util.List;

@Getter
public class UiFlowExecutionException extends Exception {
    private final List<ExecutionLogEntryDto> executionLog;

    /**
     * 这次失败是「根本没跑成」而不是「跑了没通过」。
     *
     * <p>抛出的那一刻最清楚这是哪一种——设备没连上、会话没了、控件树抓不到，
     * 抛的人知道，事后靠文案去猜就只能猜个大概。所以能在源头标的一律在源头标，
     * {@link ExecutionBlockers} 的文案匹配只用来兜底那些从驱动层直接冒上来的异常。
     */
    private final boolean blocked;

    public UiFlowExecutionException(String message, List<ExecutionLogEntryDto> executionLog) {
        this(message, executionLog, false);
    }

    public UiFlowExecutionException(String message, List<ExecutionLogEntryDto> executionLog, boolean blocked) {
        super(message);
        this.executionLog = executionLog;
        this.blocked = blocked;
    }

    /** 环境／基础设施原因导致没跑成 */
    public static UiFlowExecutionException blocked(String message, List<ExecutionLogEntryDto> log) {
        return new UiFlowExecutionException(message, log, true);
    }
}
