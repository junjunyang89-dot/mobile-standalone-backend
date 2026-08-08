package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 页面/弹窗字典：元素 pageId 归属；列表按页折叠。
 */
@Data
public class UiPage {
    private String id;
    private String appId;
    /** 稳定编码，如 PRIVACY_WARM_TIP */
    private String pageId;
    /** 展示名，如 温馨提示弹窗 */
    private String nameZh;
    private String description;
    private String moduleTag;
    private Integer sortOrder;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
