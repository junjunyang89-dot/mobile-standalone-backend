package com.mobile.standalone.mapper;

import com.mobile.standalone.auth.PasswordReset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface PasswordResetMapper {

    int insert(PasswordReset record);

    /** 当前还能用的那一条：未用、未作废、未过期。同一 (user, purpose) 最多一条 */
    PasswordReset findActive(@Param("userId") String userId,
                             @Param("purpose") String purpose,
                             @Param("now") LocalDateTime now);

    /** 发新码前把旧的全部作废，避免同时存在多个有效验证码 */
    int invalidateActive(@Param("userId") String userId, @Param("purpose") String purpose);

    int invalidate(@Param("id") String id);

    int bumpAttempts(@Param("id") String id);

    int markUsed(@Param("id") String id);

    /** 限流：某账号在 since 之后发了几次 */
    int countByUserSince(@Param("userId") String userId, @Param("since") LocalDateTime since);

    /** 限流：某 IP 在 since 之后发了几次 */
    int countByIpSince(@Param("ip") String ip, @Param("since") LocalDateTime since);

    /** 最近一次申请时间，用于「60 秒内不重复发」 */
    LocalDateTime lastRequestAt(@Param("userId") String userId);
}
