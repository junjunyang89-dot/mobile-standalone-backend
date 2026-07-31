package com.mobile.standalone.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TaskExecution {
    private String id;
    private String taskId;
    private ExecutionStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String reportPath;
    private String errorMessage;
    /** 结构化结果 JSON，如兼容性测试的设备/应用/Appium 会话数据 */
    private String resultData;
    /** 执行步骤日志 JSON 数组，用于前端「执行日志」展示 */
    private String executionLog;
    /** 执行变量脱敏 JSON，如 {"phoneNumberMasked":"138****8000","verificationCodeMasked":"****(6位)"}，供前端展示 */
    private String executionVariables;

    public enum ExecutionStatus {
        RUNNING, COMPLETED, FAILED, STOPPED
    }
}
