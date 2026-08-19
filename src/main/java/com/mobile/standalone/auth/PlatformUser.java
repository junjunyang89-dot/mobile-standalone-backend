package com.mobile.standalone.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/** 平台账号。和 {@code online_regression_accounts}（被测 APP 的登录账号）是两回事 */
@Data
public class PlatformUser {

    private String id;
    /** 登录名，唯一 */
    private String username;
    /** 显示名，报告和操作记录上给人看的 */
    private String displayName;

    /**
     * PBKDF2 派生值，格式见 {@link PasswordHasher}。
     * <p>
     * {@code @JsonIgnore} 不是装饰：这个类会直接从 controller 返回，
     * 漏出去的话所有人都能拿到全员的口令散列，离线慢慢撞。
     */
    @JsonIgnore
    private String passwordHash;

    /** ADMIN / TESTER / VIEWER */
    private String role;

    /**
     * 停用而不是删除。
     * <p>
     * 删了之后这个人做过的操作记录就成了孤儿——「谁跑的这批回归」查不到人。
     * 而人员离职恰恰是最需要能回溯的时候。
     */
    private Boolean enabled;

    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public PlatformRole roleEnum() {
        return PlatformRole.from(role);
    }

    /** 角色的中文名，前端直接显示 */
    public String getRoleLabel() {
        return roleEnum().label();
    }
}
