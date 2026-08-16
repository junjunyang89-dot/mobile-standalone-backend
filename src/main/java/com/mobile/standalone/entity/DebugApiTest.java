package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口对比分析调试行。改字段后跑对比，应能看到 FIELD_CHANGED。
 */
@Data
public class DebugApiTest {
    private Long id;
    private String name;
    private String status;
    private Integer score;
    private String remark;
    private String extra;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
