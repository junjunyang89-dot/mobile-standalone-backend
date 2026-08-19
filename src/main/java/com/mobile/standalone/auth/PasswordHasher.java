package com.mobile.standalone.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令散列，PBKDF2-HMAC-SHA256。
 * <p>
 * 不用 SHA-256 直接 hash：那东西是<b>为快而设计的</b>，显卡一秒能撞几十亿次，
 * 而人设的口令就那么几种套路，撞出来是分钟级的事。PBKDF2 靠迭代次数把单次校验拖到毫秒级，
 * 撞库的成本跟着涨十万倍，登录时多花的那 1 毫秒没人感觉得到。
 * <p>
 * 不引 BCrypt 是因为它要多拉一个依赖，而 PBKDF2 是 JDK 自带的、同样是正经 KDF。
 */
public final class PasswordHasher {

    private PasswordHasher() {
    }

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 存库格式：{@code pbkdf2$迭代次数$salt$hash}，迭代次数写进去，以后调高了老口令照样能验 */
    public static String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = derive(rawPassword, salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(dk);
    }

    /** 口令对不对。任何异常都返回 false——校验环节不该把细节抛给调用方 */
    public static boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword, salt, iterations);
            // 定长比较：普通的 equals 会在第一个不同的字节就返回，
            // 逐字节试探能从响应耗时里把散列一位一位问出来
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令散列失败", e);
        }
    }
}
