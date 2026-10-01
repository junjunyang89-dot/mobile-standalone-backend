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
        PENDING, RUNNING, SUCCESS, FAILED, SKIPPED,
        /** 首次失败、重跑通过：功能是好的，但这条用例不稳定，需要治理 */
        UNSTABLE,
        /**
         * 没跑成：设备、会话、控件树、装包这些environment原因，一条断言都没跑到。
         * <p>不进通过率的分母。设备池紧张的时候通过率不该跟着掉——
         * 那会让人去查一个根本没执行过的用例。
         */
        BLOCKED
    }
}
