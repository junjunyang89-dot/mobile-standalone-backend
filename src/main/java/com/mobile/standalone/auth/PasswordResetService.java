package com.mobile.standalone.auth;

import com.mobile.standalone.config.AuthMailProperties;
import com.mobile.standalone.mapper.PasswordResetMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 忘记密码 / 绑定邮箱的验证码流程。
 * <p>
 * 6 位数字只有 10⁶ 种可能，安全性<b>全部由限流提供</b>：一条码最多试 5 次、10 分钟过期、
 * 同账号一小时最多 3 条。合起来一小时内最多 15 次猜测，命中概率约 0.0015%。
 * 任何一条限制被放松，这个功能就不成立了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    /** 一条验证码最多试几次，满了作废这条——<b>作废验证码，不锁账号</b>：
     *  锁账号意味着任何人拿别人的用户名就能把人锁死 */
    private static final int MAX_ATTEMPTS = 5;
    private static final int RESEND_INTERVAL_SECONDS = 60;
    private static final int PER_ACCOUNT_PER_HOUR = 3;
    private static final int PER_IP_PER_HOUR = 10;

    private static final Pattern CODE = Pattern.compile("\\d{6}");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 验证码错误统一用这句：区分「没有这个账号」「码过期了」「码不对」等于送出账号枚举 */
    private static final String GENERIC_FAILURE = "验证码不对或已失效，请重新获取";

    private final PasswordResetMapper resetMapper;
    private final PlatformUserService userService;
    private final PasswordResetMailer mailer;
    private final AuthTokenService tokenService;
    private final AuthMailProperties mailProps;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS platform_password_resets (
                        id VARCHAR(64) PRIMARY KEY,
                        user_id VARCHAR(64) NOT NULL,
                        purpose VARCHAR(16) NOT NULL DEFAULT 'RESET',
                        code_hash CHAR(64) NOT NULL,
                        target_email VARCHAR(255) DEFAULT NULL,
                        expires_at DATETIME NOT NULL,
                        attempts INT NOT NULL DEFAULT 0,
                        used_at DATETIME DEFAULT NULL,
                        invalidated_at DATETIME DEFAULT NULL,
                        request_ip VARCHAR(64) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        KEY idx_pwd_reset_user (user_id, purpose, created_at),
                        KEY idx_pwd_reset_ip (request_ip, created_at),
                        KEY idx_pwd_reset_expire (expires_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[auth] platform_password_resets 表已就绪");
        } catch (Exception e) {
            log.warn("[auth] 建验证码表失败: {}", e.getMessage());
        }
    }

    // ==================== 忘记密码 ====================

    /**
     * 申请重置验证码。
     * <p>
     * <b>永远不抛异常、永远不返回任何区分性信息。</b>账号不存在、被停用、没绑邮箱、撞上限流——
     * 一律静默返回，由 controller 回同一句话。分开报就等于送了一个免费的账号枚举接口。
     */
    public void requestResetCode(String account, String ip) {
        PlatformUser user = findByAccount(account);
        if (user == null) {
            log.info("[auth-reset] 申请重置：账号不存在 account={} ip={}", mask(account), ip);
            return;
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            log.info("[auth-reset] 申请重置：账号已停用 userId={} ip={}", user.getId(), ip);
            return;
        }
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            log.info("[auth-reset] 申请重置：账号未绑定邮箱，只能走管理员重置 userId={}", user.getId());
            return;
        }
        if (!withinRateLimit(user.getId(), ip)) {
            return;
        }
        String code = issue(user.getId(), PasswordReset.PURPOSE_RESET, email, ip);
        mailer.sendResetCode(user, email, code, ip);
    }

    /**
     * 用验证码把密码改掉。
     * <p>
     * 这里可以报错——调用方已经拿着一个验证码了，报「验证码不对」不泄露任何东西。
     * 但账号不存在时也要报同一句，否则又成了枚举接口。
     */
    @Transactional
    public void resetPassword(String account, String code, String newPassword) {
        PlatformUser user = findByAccount(account);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new IllegalArgumentException(GENERIC_FAILURE);
        }
        consume(user.getId(), PasswordReset.PURPOSE_RESET, code);
        // 校验放在消费之后：密码不合规时验证码已经作废，用户要重新取一次。
        // 反过来（先校验密码）会让人拿一个已知不合规的密码去无限次试验证码
        userService.applyNewPassword(user.getId(), newPassword);
        log.info("[auth-reset] 密码已重置 userId={} username={}", user.getId(), user.getUsername());
    }

    // ==================== 绑定 / 更换邮箱 ====================

    /**
     * 往新邮箱发绑定验证码。
     * <p>
     * <b>必须验当前密码。</b>少了这一步，一个被借用的登录态就能把邮箱改成自己的，
     * 再走忘记密码永久夺号——那这个功能就从「找回密码」变成了「送出账号」。
     * <p>
     * 限流也照打：这个接口能往<b>任意</b>地址发信，不限流就是一个现成的邮件轰炸器。
     */
    public void requestBindCode(PlatformUser user, String email, String currentPassword) {
        String target = normalizeEmail(email);
        if (!EMAIL.matcher(target).matches()) {
            throw new IllegalArgumentException("邮箱格式不对");
        }
        if (!userService.passwordMatches(user.getId(), currentPassword)) {
            throw new IllegalArgumentException("当前密码不对");
        }
        PlatformUser owner = userService.findByEmail(target);
        if (owner != null && !owner.getId().equals(user.getId())) {
            throw new IllegalArgumentException("这个邮箱已经绑在另一个账号上了");
        }
        if (!withinRateLimit(user.getId(), null)) {
            throw new IllegalArgumentException("发得太频繁了，请 " + RESEND_INTERVAL_SECONDS + " 秒后再试");
        }
        String code = issue(user.getId(), PasswordReset.PURPOSE_BIND, target, null);
        mailer.sendBindCode(user, target, code);
    }

    @Transactional
    public void confirmBind(PlatformUser user, String email, String code) {
        String target = normalizeEmail(email);
        PasswordReset record = consume(user.getId(), PasswordReset.PURPOSE_BIND, code);
        // 验证码是发到某个具体邮箱的，只能用来绑那个邮箱。
        // 不比对的话，可以拿发到自己邮箱的码去绑别人的邮箱
        if (!target.equalsIgnoreCase(normalizeEmail(record.getTargetEmail()))) {
            throw new IllegalArgumentException("验证码与邮箱对不上，请重新获取");
        }
        userService.updateEmail(user.getId(), target);
        log.info("[auth-reset] 邮箱已绑定 userId={} email={}", user.getId(), mask(target));
    }

    // ==================== 内部 ====================

    private PlatformUser findByAccount(String account) {
        if (account == null || account.isBlank()) return null;
        String a = account.trim();
        PlatformUser user = userService.findByUsername(a);
        // 用户名找不到再按邮箱找：用户不用记自己当初填的是哪个
        return user != null ? user : userService.findByEmail(normalizeEmail(a));
    }

    /** 三档限流，任何一档不过都静默拒绝 */
    private boolean withinRateLimit(String userId, String ip) {
        LocalDateTime now = LocalDateTime.now();
        try {
            LocalDateTime last = resetMapper.lastRequestAt(userId);
            if (last != null && last.isAfter(now.minusSeconds(RESEND_INTERVAL_SECONDS))) {
                log.info("[auth-reset] 限流：{} 秒内重复申请 userId={}", RESEND_INTERVAL_SECONDS, userId);
                return false;
            }
            if (resetMapper.countByUserSince(userId, now.minusHours(1)) >= PER_ACCOUNT_PER_HOUR) {
                log.warn("[auth-reset] 限流：账号一小时内超过 {} 次 userId={}", PER_ACCOUNT_PER_HOUR, userId);
                return false;
            }
            if (ip != null && !ip.isBlank()
                    && resetMapper.countByIpSince(ip, now.minusHours(1)) >= PER_IP_PER_HOUR) {
                log.warn("[auth-reset] 限流：IP 一小时内超过 {} 次 ip={}", PER_IP_PER_HOUR, ip);
                return false;
            }
            return true;
        } catch (Exception e) {
            // 限流查不了就不放行：这个接口不设防等于把爆破成本降到零
            log.error("[auth-reset] 限流检查失败，本次拒绝 userId={}: {}", userId, e.getMessage());
            return false;
        }
    }

    private String issue(String userId, String purpose, String targetEmail, String ip) {
        resetMapper.invalidateActive(userId, purpose);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        PasswordReset record = new PasswordReset();
        record.setId("pwr-" + UUID.randomUUID());
        record.setUserId(userId);
        record.setPurpose(purpose);
        record.setCodeHash(hash(userId, purpose, code));
        record.setTargetEmail(targetEmail);
        record.setExpiresAt(LocalDateTime.now().plusMinutes(Math.max(1, mailProps.getCodeTtlMinutes())));
        record.setRequestIp(ip);
        resetMapper.insert(record);
        return code;
    }

    /** 校验并消费一条验证码，返回被消费的记录 */
    private PasswordReset consume(String userId, String purpose, String code) {
        String c = code == null ? "" : code.trim();
        if (!CODE.matcher(c).matches()) {
            throw new IllegalArgumentException(GENERIC_FAILURE);
        }
        PasswordReset record = resetMapper.findActive(userId, purpose, LocalDateTime.now());
        if (record == null) {
            throw new IllegalArgumentException(GENERIC_FAILURE);
        }
        int attempts = record.getAttempts() == null ? 0 : record.getAttempts();
        if (attempts >= MAX_ATTEMPTS) {
            resetMapper.invalidate(record.getId());
            throw new IllegalArgumentException("验证码错误次数过多，已失效，请重新获取");
        }
        byte[] expected = hash(userId, purpose, c).getBytes(StandardCharsets.UTF_8);
        byte[] actual = String.valueOf(record.getCodeHash()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            resetMapper.bumpAttempts(record.getId());
            int left = MAX_ATTEMPTS - (attempts + 1);
            if (left <= 0) {
                resetMapper.invalidate(record.getId());
                throw new IllegalArgumentException("验证码错误次数过多，已失效，请重新获取");
            }
            throw new IllegalArgumentException("验证码不对，还可以试 " + left + " 次");
        }
        resetMapper.markUsed(record.getId());
        return record;
    }

    /** 复用登录令牌那把落库的密钥做 HMAC，见 {@link PasswordReset#getCodeHash()} 的说明 */
    private String hash(String userId, String purpose, String code) {
        return tokenService.hmacHex(userId + ":" + purpose + ":" + code);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String mask(String s) {
        return PasswordResetMailer.mask(s);
    }
}
