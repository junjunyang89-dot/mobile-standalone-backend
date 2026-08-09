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
    /**
     * 这个页面下有多少个元素，查询时算出来，不落库。
     * <p>
     * 必须由后端给：界面上原来是拿「当前已加载的元素列表」按页分组数出来的，
     * 而那份列表带着场景/来源/状态等筛选条件——于是会出现
     * 「界面显示 0 个元素，删的时候说还有 2 个」。同一个东西两个算法，一定会对不上。
     */
    private Integer elementCount;
    /** 各平台各有多少，如 {ANDROID:2, IOS:22}。页面不分平台、元素分平台，所以这个分布必须给出来 */
    private java.util.Map<String, Integer> elementCountByPlatform;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
