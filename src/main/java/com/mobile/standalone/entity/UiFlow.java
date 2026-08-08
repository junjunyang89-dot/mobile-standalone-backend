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
    /** 轨道：AUTOMATION / ONLINE / SHARED / PERFORMANCE。只说明「哪个入口用」，不表达环境 */
    private String catalog;
    /** 环境：PROD / TEST。跟 catalog 正交——两个环境跑的是两批不同的流程，测的点也不一样 */
    private String environment;
    private Boolean isComponent;
    /** JSON 数组字符串 */
    private String steps;
    /** 前置流程 ID 的 JSON 数组。批量执行时按依赖拓扑排序，被依赖的流程一批里只跑一次 */
    private String dependsOn;
    private Boolean enabled;
    /** 列表/线上回归排序，越小越靠前 */
    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
