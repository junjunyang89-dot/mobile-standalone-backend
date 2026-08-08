package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口 URL / 网关前缀（按项目维护；接口用例下拉选用）。
 */
@Data
public class ApiUrlEntry {
    private String id;
    /** 如 data-center */
    private String project;
    /** 展示名：测试网关、线上网关… */
    private String name;
    /** TEST | PROD | ANY */
    private String environment;
    private String baseUrl;
    private String description;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
