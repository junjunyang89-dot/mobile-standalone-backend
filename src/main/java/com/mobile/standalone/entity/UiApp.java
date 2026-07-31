package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务应用字典：元素/流程引用；与设备上的 apps 安装记录分离。
 */
@Data
public class UiApp {
    /** 如 DOMESTIC_ESPORTS */
    private String id;
    private String nameZh;
    private String description;
    private String androidPackage;
    private String iosBundleId;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
