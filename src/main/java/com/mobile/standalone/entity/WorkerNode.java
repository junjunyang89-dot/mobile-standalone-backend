package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkerNode {
    private String id;
    private String displayName;
    private String hostname;
    private String role;
    private String baseUrl;
    private String devicesJson;
    private LocalDateTime lastSeen;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
