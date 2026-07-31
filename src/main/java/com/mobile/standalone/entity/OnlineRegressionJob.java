package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OnlineRegressionJob {
    private String id;
    private String runId;
    private String jobKey;
    private String appId;
    private String appName;
    private String deviceId;
    private String deviceName;
    private String devicePlatform;
    private String endpoint;
    private String status;
    private Integer progress;
    private String currentStepLabel;
    private String accountPhone;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        PENDING, RUNNING, SUCCESS, FAILED, STOPPED
    }
}
