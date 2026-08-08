package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OnlineRegressionRun {
    private String id;
    private String status;
    /** JSON array */
    private String selectedApps;
    /** JSON array */
    private String selectedFeatures;
    /** JSON array of ordered step ids */
    private String orderedSteps;
    private Boolean enableOverseasProxy;
    /** PROD / TEST；存量记录为 NULL，一律按 PROD 解读 */
    private String environment;
    /** REGRESSION / AUTOMATION；存量记录为 NULL，一律按 REGRESSION 解读 */
    private String track;
    private Integer passRate;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED, STOPPED
    }
}
