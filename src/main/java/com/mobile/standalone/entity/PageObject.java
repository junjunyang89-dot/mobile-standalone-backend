package com.mobile.standalone.entity;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 页面对象模型（POM）实体
 */
@Data
public class PageObject {
    private String id;
    private String name;
    private String description;
    private Platform platform;
    private String appId;
    private String pagePath;  // 页面路径或标识
    /** 元素目录：AUTOMATION / ONLINE / SHARED */
    private Catalog catalog;
    private Map<String, PageElement> elements;  // 页面元素定义
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public enum Platform {
        ANDROID, IOS, HARMONYOS, POG_ANDROID, POG_IOS
    }

    /**
     * 元素库目录：物理一张表，逻辑分目录，避免线上回归与自动化双轨硬编码。
     */
    public enum Catalog {
        /** 自动化用例 / 页面对象页默认维护 */
        AUTOMATION,
        /** 线上回归专用（商店安装按钮等） */
        ONLINE,
        /** 两边共用（登录/退出/侧边栏） */
        SHARED
    }
    
    /**
     * 页面元素定义（入库 JSON）。创建/更新时必须带 selectorType + selector。
     */
    @Data
    public static class PageElement {
        private String name;  // 元素名称
        private String selector;  // 定位值（xpath 表达式 / resource-id / 文案等）
        /** 定位方式：id / xpath / name / text / accessibilityId / className / other */
        private String selectorType;
        private String description;  // 元素描述
        private Integer timeout = 10;  // 等待超时时间（秒）
    }
}
