package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WebTestCase {
    private String id;
    private String name;
    private String description;
    private String requirementRef;
    private String channel;
    private String pageUrl;
    private String h5PageId;
    private String authMode;
    private String authToken;
    private String authQueryKey;
    /** JSON 数组 */
    private String steps;
    private Boolean enabled;
    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
