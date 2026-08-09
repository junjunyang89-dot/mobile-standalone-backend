package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OnlineRegressionAccount {
    private String id;
    /** DOMESTIC_ESPORTS / OVERSEAS_POGPOG */
    private String appId;
    private String label;
    /**
     * 这个账号属于哪个环境：PROD / TEST，空＝通用。
     * <p>
     * 账号只区分测试和线上（数据不同），之前靠名字里带「线上」来认。
     * 命名约定唯一的失效方式是「有人建账号时没按约定命名」——那一定会发生，
     * 而结果是测试环境回归选到线上账号，登不上，你得从登录失败一路查回来。
     */
    private String environment;
    private String phoneNumber;
    private String verificationCode;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
