package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行级 UI 元素：测试/线上默认 SHARED 共用，改定位只改这一行。
 */
@Data
public class UiElement {
    private String id;
    private String appId;
    private String platform;
    /** AUTOMATION / ONLINE / SHARED */
    private String catalog;
    private String nameZh;
    /** 流程 JSON 里引用的 key */
    private String nameEn;
    private String description;
    /** 仅分组标签，如「登录页」，避免再塞进大 JSON */
    private String moduleTag;
    private String locatorType;
    private String locatorValue;
    /** 可选预览图相对路径，如 ui-element-previews/xxx.png */
    private String previewImage;
    private Integer defaultTimeoutSec;
    private Boolean enabled;
    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
