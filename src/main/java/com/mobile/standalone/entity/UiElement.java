package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行级 UI 元素：测试/线上默认 SHARED 共用，改定位只改这一行。
 * 人机协同与源码解析同表，用 source 区分，便于对比；status=ACTIVE 为选用。
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
    /** 视觉说明，如「左上角三横线汉堡」，方便人眼对上截图 */
    private String visualHint;
    /** 仅分组标签，如「登录页」，避免再塞进大 JSON */
    private String moduleTag;
    /** 页面 ID，如 P08_MINE_SETTINGS，与采集账本 pageId 对齐 */
    private String pageId;
    /** HUMAN_AI / SOURCE_CODE / IMPORT_LEGACY / INSPECTOR_XML / MANUAL */
    private String source;
    /** CANDIDATE / ACTIVE / DEPRECATED */
    private String status;
    private LocalDateTime lastVerifiedAt;
    private String locatorType;
    private String locatorValue;
    /**
     * 备用定位 JSON：[{"locatorType":"xpath","locatorValue":"..."}, ...]
     * 执行时主定位失败再依次尝试。
     */
    private String locatorFallbacks;
    /** 可选预览图相对路径，如 ui-element-previews/xxx.png */
    private String previewImage;
    private Integer defaultTimeoutSec;
    private Boolean enabled;
    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
