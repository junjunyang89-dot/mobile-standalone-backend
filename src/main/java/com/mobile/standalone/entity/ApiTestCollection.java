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
    private String id;
    /** 归属项目，与 api_projects.code 对应 */
    private String project;
    private String name;
    private String description;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 以下为查询时拼上的统计，不落库 ----
    /** 集合里有多少条用例 */
    private Integer caseCount;
    /** 最近一次执行的通过率（0-100），从没跑过为 null */
    private Integer passRate;
    private LocalDateTime lastRunAt;
    private String lastRunId;
}
