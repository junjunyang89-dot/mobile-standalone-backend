package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * H5 页面目录项：列表展示 URL，点开后内嵌预览（后续可接 DOM 采集）。
 */
@Data
public class UiH5Page {
    private String id;
    private String name;
    private String url;
    private String appId;
    private String moduleTag;
    private String description;
    /** NONE | TOKEN_QUERY | MANUAL */
    private String authType;
    private String authKey;
    private String authToken;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
