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
        RUNNING, COMPLETED, FAILED, STOPPED,
        /** 流程判定为「本次不适用」而提前结束：不算通过也不算失败 */
        SKIPPED,
        /**
         * 没跑成——设备连不上、会话没了、控件树抓不到、应用拉不起来。
         * <p><b>不进通过率的分母。</b> 把它算成失败的话，模拟器卡一晚上，
         * 早上看到的是「通过率 40%」，人会去查用例、查定位、查应用，
         * 而那 60% 压根没跑到断言那一步。
         */
        BLOCKED
    }
}
