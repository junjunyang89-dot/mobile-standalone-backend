package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OnlineRegressionAccount {
    private String id;
    /** DOMESTIC_ESPORTS / OVERSEAS_POGPOG */
    private String appId;
    private String label;
    private String phoneNumber;
    private String verificationCode;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
