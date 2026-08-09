package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时执行。
 * <p>
 * 存在的理由是「不用人记着去点」：提测期间每天早上跑一轮冒烟，接口挂了当天就知道，
 * 而不是等到回归那天才发现，那时已经分不清是哪次改动引入的。
 */
@Data
public class ApiTestSchedule {
    private String id;
    private String name;
    /** Spring 六段 cron，如 {@code 0 0 8 * * ?}（每天 8 点） */
    private String cron;
    /** 跑哪个集合；留空＝该项目下全部启用用例 */
    private String collectionId;
    private String project;
    /** TEST | PROD */
    private String environment;
    private Boolean enabled;

    /** 下次触发时间。落库而不是每次现算，重启后不会漏跑也不会立刻补跑一堆 */
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    private String lastRunId;
    /** PASSED / FAILED / ERROR */
    private String lastStatus;
    private String lastMessage;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 查询时拼上 ----
    private String collectionName;
    private Integer caseCount;
}
