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
    /**
     * 每条断言的结果、以及实际发出去/收到的内容。
     * <p>
     * 不存的话，报告页只能给一句「失败」——人还得自己猜发了什么、收到了什么，
     * 于是拿 Postman 再手工重放一遍。那一步正是这套东西本该省掉的。
     */
    private String assertionResults;
    private String bodyPreview;
    private String reqHeaders;
    private String reqBody;
    private String respHeaders;
    private LocalDateTime createdAt;
}
