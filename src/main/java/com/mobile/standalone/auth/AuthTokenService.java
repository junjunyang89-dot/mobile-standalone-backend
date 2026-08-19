package com.mobile.standalone.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 登录令牌：HMAC 签名，服务端不存。
 * <p>
 * 不用「内存里存一张 token 表」是因为后端重启会把所有人踢下线，
 * 而这套东西改代码就要重启，一天好几次——每次都让全组重新登录，两天之后就会有人要求把鉴权关掉。
 * <p>
 * 签名密钥落库而不是每次启动随机生成，也是同一个原因：随机生成等于重启即失效。
 */
@Slf4j
@Service
public class AuthTokenService {

    /** 令牌有效期。内部平台，取一周：短了天天登录，长了丢了也是一周内的事 */
    private static final Duration TTL = Duration.ofDays(7);

    private final JdbcTemplate jdbcTemplate;
    private volatile byte[] secret;

    public AuthTokenService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 签一个令牌 */
    public String issue(String userId) {
        long expiresAt = System.currentTimeMillis() + TTL.toMillis();
        String payload = userId + ":" + expiresAt;
        return enc(payload.getBytes(StandardCharsets.UTF_8)) + "." + enc(sign(payload));
    }

    /**
     * 验令牌，返回 userId。
     *
     * @return null＝无效、过期、或被改过
     */
    public String verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            int dot = token.lastIndexOf('.');
            if (dot <= 0) {
                return null;
            }
            String payload = new String(dec(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] given = dec(token.substring(dot + 1));
            // 先验签再看内容：内容是调用方给的，没验签之前它说什么都不算数
            if (!MessageDigest.isEqual(sign(payload), given)) {
                return null;
            }
            int sep = payload.lastIndexOf(':');
            if (sep <= 0) {
                return null;
            }
            long expiresAt = Long.parseLong(payload.substring(sep + 1));
            if (System.currentTimeMillis() > expiresAt) {
                return null;
            }
            return payload.substring(0, sep);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    /** 密钥：库里有就用库里的，没有就生成一把存进去 */
    private byte[] secret() {
        byte[] s = secret;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (secret != null) {
                return secret;
            }
            secret = loadOrCreateSecret();
            return secret;
        }
    }

    private byte[] loadOrCreateSecret() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS platform_auth_secret (
                        id VARCHAR(32) PRIMARY KEY,
                        secret_value VARCHAR(255) NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            var rows = jdbcTemplate.queryForList(
                    "SELECT secret_value FROM platform_auth_secret WHERE id = 'default'", String.class);
            if (!rows.isEmpty() && rows.get(0) != null && !rows.get(0).isBlank()) {
                return Base64.getDecoder().decode(rows.get(0));
            }
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            jdbcTemplate.update(
                    "INSERT IGNORE INTO platform_auth_secret (id, secret_value) VALUES ('default', ?)",
                    Base64.getEncoder().encodeToString(fresh));
            // 并发首启时可能是别人先插进去的，回读一次以所有节点用同一把密钥为准
            var after = jdbcTemplate.queryForList(
                    "SELECT secret_value FROM platform_auth_secret WHERE id = 'default'", String.class);
            if (!after.isEmpty() && after.get(0) != null) {
                return Base64.getDecoder().decode(after.get(0));
            }
            return fresh;
        } catch (Exception e) {
            // 库不可用时退回进程内随机密钥：这时候登录还能用，只是重启后要重登，
            // 比整个平台打不开要好
            log.warn("[auth] 令牌密钥落库失败，退回进程内密钥（重启后需重新登录）: {}", e.getMessage());
            byte[] fallback = new byte[32];
            new SecureRandom().nextBytes(fallback);
            return fallback;
        }
    }

    private static String enc(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] dec(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
