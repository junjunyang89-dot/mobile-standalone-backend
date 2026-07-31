package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 拖拽编排的流程/组件。steps 只引用 keywordCode + elementKey，不写死 xpath。
 */
@Data
public class UiFlow {
    private String id;
    private String name;
    private String description;
    private String appId;
    private String platform;
    private String catalog;
    private Boolean isComponent;
    /** JSON 数组字符串 */
    private String steps;
    private Boolean enabled;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
