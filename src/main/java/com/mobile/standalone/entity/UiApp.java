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
    /**
     * 蒲公英 Android appKey（如 pwesports，就是应用短链里那一段）。
     * <p>
     * 配了它之后，安装步骤按「应用 + 平台」自动取最新版；不配就得每次发版改流程里的 buildKey。
     */
    private String pgyerAppKeyAndroid;
    /** 蒲公英 iOS appKey */
    private String pgyerAppKeyIos;
    /**
     * 渠道详情页 URL JSON，key 为 {@code AndroidStoreOem} 名（如 XIAOMI、HUAWEI），
     * value 为 https/深链。安装 CHANNEL_PAGE 时优先于厂商默认 URI。
     */
    private String channelUrls;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
