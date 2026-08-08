package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApiTestRun {
    private String id;
    private String environment;
    private Integer total;
    private Integer passed;
    private Integer failed;
    private Long durationMs;
    private Boolean ok;
    private String message;
    private String createdBy;
    private LocalDateTime createdAt;
}
