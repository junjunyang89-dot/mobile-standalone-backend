package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识手册短记：现象 / 原因 / 处理。
 */
@Data
public class KnowledgeHandbook {
    private Long id;
    /** 叶子分类 code，见 HandbookCategories */
    private String category;
    /** 归属项目，对应 api_projects.code；业务类建议必填 */
    private String project;
    private String title;
    private String symptom;
    private String cause;
    private String fix;
    private String tags;
    /** 可选配图相对路径，如 handbook-images/xxx.png */
    private String imageUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
