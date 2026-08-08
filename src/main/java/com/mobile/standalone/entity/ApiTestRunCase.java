package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApiTestRunCase {
    private String id;
    private String runId;
    private String caseId;
    private String name;
    private String method;
    private String path;
    private String url;
    private String status;
    private Integer httpStatus;
    private Long durationMs;
    private String error;
    private LocalDateTime createdAt;
}
