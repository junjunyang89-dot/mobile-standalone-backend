package com.mobile.standalone.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次验证码申请。RESET＝忘记密码，BIND＝绑定/更换邮箱 */
@Data
public class PasswordReset {

    public static final String PURPOSE_RESET = "RESET";
    public static final String PURPOSE_BIND = "BIND";

    private String id;
    private String userId;
    private String purpose;

    /**
     * {@code HMAC-SHA256(登录令牌密钥, userId:purpose:code)} 的 hex。
     * <p>
     * 不能直接存 SHA-256：6 位数字只有 100 万种可能，谁读到库都能秒算一张彩虹表反查回来。
     * 加了密钥就查不了。也不用 PBKDF2——那是给低熵且可字典撞的<b>口令</b>准备的，
     * 验证码只活 10 分钟且限次，慢哈希只会拖慢每次校验。
     */
    @JsonIgnore
    private String codeHash;

    /** BIND 时是待绑定的新邮箱；RESET 时是当时账号上的邮箱，用于事后对账 */
    private String targetEmail;

    private LocalDateTime expiresAt;
    /** 已尝试次数，满 5 次即作废这条（作废验证码，不锁账号） */
    private Integer attempts;
    private LocalDateTime usedAt;
    /** 被后发的验证码顶掉 */
    private LocalDateTime invalidatedAt;
    private String requestIp;
    private LocalDateTime createdAt;
}
