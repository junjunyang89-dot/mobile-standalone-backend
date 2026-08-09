package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApiTestRun {
    private String id;
    private String environment;
    /** 按集合执行时记下是哪个集合，报告里才认得出「XX 集合 · 第 N 次」 */
    private String collectionId;
    private Integer total;
    private Integer passed;
    private Integer failed;
    private Long durationMs;
    private Boolean ok;
    private String message;
    private String createdBy;
    private LocalDateTime createdAt;
}
