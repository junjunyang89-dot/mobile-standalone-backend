package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 对应表 knowledge（与 CodeWords / Python FastAPI 示例一致） */
@Data
public class AgentKnowledge {
    private Long id;
    private String topic;
    private String content;
    /** 搜索接口截断预览 */
    private String snippet;
    private LocalDateTime createdAt;
}
