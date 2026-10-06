package com.mobile.standalone.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 找回密码用的邮箱，全库唯一。
     * <p>
     * 没有它就没有任何办法确认「申请重置的人就是账号本人」，只能靠管理员一个个手工重置。
     * 允许为空：存量账号是先有账号后有这个字段的，空着的那些走管理员重置。
     */
    private String email;

    /** ADMIN / TESTER / VIEWER */
    private String role;

    /**
     * 停用而不是删除。
     * <p>
     * 删了之后这个人做过的操作记录就成了孤儿——「谁跑的这批回归」查不到人。
     * 而人员离职恰恰是最需要能回溯的时候。
     */
    private Boolean enabled;

    /** 最近一次改密时间。留给「改完密码把旧登录态踢掉」用（见忘记密码方案 P1） */
    private LocalDateTime passwordChangedAt;

    /** 管理员手工重置密码后置 1，逼用户下次登录先改掉——管理员设的密码管理员自己知道 */
    private Boolean mustChangePassword;

    /**
     * 能看见的功能模块（{@link PlatformModules} 里的路由，逗号分隔）。
     * <p>
     * NULL = 不限制：存量账号全是 NULL，加这个字段之前能看什么，加了之后还是什么。
     * 空串 = 只剩工作台。
     */
    @JsonIgnore
    private String allowedModules;

    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public PlatformRole roleEnum() {
        return PlatformRole.from(role);
    }

    /** 账号上勾的模块；null = 不限制 */
    public List<String> getModules() {
        return PlatformModules.parse(allowedModules);
    }

    public boolean isAllModules() {
        return allowedModules == null;
    }

    /**
     * 实际生效的模块限制；null = 全部可见。
     * <p>
     * 管理员不受限：模块是给「只该碰某几块」的人用的，管理员被勾掉「设置」之类的入口，
     * 结果只是自己把自己关在门外。
     */
    public List<String> visibleModules() {
        return roleEnum() == PlatformRole.ADMIN ? null : getModules();
    }

    /** 角色的中文名，前端直接显示 */
    public String getRoleLabel() {
        return roleEnum().label();
    }
}
