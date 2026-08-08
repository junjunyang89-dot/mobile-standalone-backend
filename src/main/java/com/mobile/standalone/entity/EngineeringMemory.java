package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工程记忆时间线条目（决策/约定/变更/坑点），非完整对话。
 */
@Data
public class EngineeringMemory {
    private Long id;
    private LocalDateTime occurredAt;
    /** DECISION | CONVENTION | CHANGE | PITFALL | CONTEXT */
    private String memType;
    private String title;
    private String body;
    private String tags;
    private String source;
    private String sessionRef;
    private String createdBy;
    private LocalDateTime createdAt;
}
