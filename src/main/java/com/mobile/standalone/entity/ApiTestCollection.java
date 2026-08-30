package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用例集合：一次可执行的单元。
 * <p>
 * 不只是分组。它同时是<b>执行顺序的作用域</b>——集合内按 sortOrder 顺序跑、共享一份运行时变量，
 * 登录用例 extract 出来的 token 才轮得到后面的用例用。没有这个作用域，extract 就没有意义。
 */
@Data
public class ApiTestCollection {
    public static final String SCOPE_TEST = "TEST";
    public static final String SCOPE_LIVE = "LIVE";
    public static final String LIFECYCLE_ACTIVE = "ACTIVE";
    public static final String LIFECYCLE_ARCHIVED = "ARCHIVED";

    private String id;
    /** 归属项目，与 api_projects.code 对应 */
    private String project;
    private String name;
    private String description;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** TEST＝迭代/单号临时集合；LIVE＝master 回归（生产视图） */
    private String scope;
    /** ACTIVE / ARCHIVED；ARCHIVED 在测试视图默认隐藏 */
    private String lifecycle;
    /** 关联需求单，如 APP-8999 */
    private String requirementNo;
    /** 版本迭代标签，如 9.3 国内客户端 */
    private String appVersion;
    /**
     * 分支对比来的集合关联的分支，如 backend-0816。
     * <p>
     * 和 {@link #requirementNo} 是二选一：单号是「这一个需求」，分支是「这一批改动」。
     * 一个分支上可能合了好几个单，硬塞一个单号进去，另外几个单就在链路上消失了。
     */
    private String sourceBranch;

    // ---- 以下为查询时拼上的统计，不落库 ----
    /** 集合里有多少条用例 */
    private Integer caseCount;
    /** 最近一次执行的通过率（0-100），从没跑过为 null */
    private Integer passRate;
    private LocalDateTime lastRunAt;
    private String lastRunId;
}
