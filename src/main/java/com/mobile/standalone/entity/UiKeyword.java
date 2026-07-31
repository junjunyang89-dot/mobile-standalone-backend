package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可拖拽关键字（点击/输入/延迟/断言等）。引擎按 code 分发。
 */
@Data
public class UiKeyword {
    private String id;
    private String code;
    private String nameZh;
    private String description;
    /** ACTION / WAIT / ASSERT / DEVICE / FLOW */
    private String category;
    private Boolean needsElement;
    /** JSON 字符串：参数表单定义 */
    private String paramSchema;
    private Boolean builtin;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
