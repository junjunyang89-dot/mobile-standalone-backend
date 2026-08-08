package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条失败归因结论。
 * <p>
 * 同一个任务允许有多条：AI 先给一条，人复核后可以再写一条纠正。两条都留着——
 * 「AI 说元素坏了、人判定是真 bug」这种分歧本身就是后面调优归因最有价值的样本，
 * 覆盖掉就没了。取「当前结论」时人工优先。
 */
@Data
public class TaskFailureDiagnosis {
    private String id;
    private String taskId;
    /** 归因针对的那次执行 */
    private String executionId;
    /** ELEMENT_BROKEN / FLOW_OUTDATED / FUNCTION_BROKEN / ENVIRONMENT / FLAKY / UNKNOWN */
    private String category;
    /** 0-100 */
    private Integer confidence;
    /** 失败步骤名，冗余一份便于列表直接展示，不用再解析日志 */
    private String failedStep;
    /** 失败步骤引用的元素 key，元素自愈据此找落点 */
    private String elementKey;
    /**
     * 去重签名：流程 + 失败步骤 + 元素 key。
     * <p>
     * 同一个问题每晚回归都会重现，每次重新调模型是纯浪费——结论一模一样。
     * 同签名的记录条数天然就是「发生了多少次」，正好用来排先修哪个。
     */
    private String signature;
    /** 人工复核时间；非空表示这条结论被人看过（无论认可还是改判） */
    private LocalDateTime reviewedAt;
    private String reviewedBy;
    /** 同签名累计发生次数，查询时算出，不落库 */
    private transient Integer occurrenceCount;
    /** AI 认为正确的定位类型，如 xpath / id */
    private String suggestedLocatorType;
    /** AI 认为正确的定位串。只给结论不给候选，人还得自己回去找，等于没省事 */
    private String suggestedLocatorValue;
    /** 候选定位被采纳的时间，非空表示已写回元素行 */
    private java.time.LocalDateTime appliedAt;
    private String reason;
    private String suggestion;
    /** AI / HUMAN */
    private String source;
    /** 模型标识，人工结论为空 */
    private String model;
    private String createdBy;
    private LocalDateTime createdAt;
}
