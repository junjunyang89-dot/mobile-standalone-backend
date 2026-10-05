package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口变更分析报告快照（提测当时的一份结果，便于复看）。
 */
@Data
public class ApiChangeReport {
    private Long id;
    /** 项目 code；目录对比可能为空或手填名 */
    private String project;
    private String baseRef;
    private String headRef;
    /** 列表用短摘要，如「接口 12 · 高风险 3 · 表 5」 */
    private String summary;
    /** 完整 Report JSON */
    private String reportJson;
    private LocalDateTime createdAt;
    /** null = 接口对比分析页；BATCH = 接口对比分析2（不进接口对比分析页的历史列表） */
    private String source;
}
