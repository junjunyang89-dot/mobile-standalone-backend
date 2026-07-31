package com.mobile.standalone.entity;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 测试报告实体
 */
@Data
public class TestReport {
    private String id;
    private String planId;
    private String caseId;
    private String deviceId;
    private String appId;
    private String executionId;  // 关联 task_executions 表
    private ExecutionStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long duration;  // 执行时长（毫秒）
    private List<String> screenshotPaths;  // 截图路径列表
    private String errorMessage;
    private String testData;  // JSON格式的测试数据
    private String reportPath;
    private LocalDateTime createdAt;
    
    public enum ExecutionStatus {
        PASSED, FAILED, SKIPPED, RUNNING
    }
}
