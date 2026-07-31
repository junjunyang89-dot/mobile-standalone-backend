package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OnlineRegressionStep {
    private String id;
    private String jobId;
    private String stepId;
    private String stepLabel;
    private Integer sortOrder;
    private String status;
    private String taskId;
    private String screenshotPath;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        PENDING, RUNNING, SUCCESS, FAILED, SKIPPED
    }
}
