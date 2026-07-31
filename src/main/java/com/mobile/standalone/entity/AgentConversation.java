package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentConversation {
    private String sessionId;
    /** 消息数组 JSON 字符串 */
    private String messages;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
