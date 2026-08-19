package com.mobile.standalone.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class PasswordAndTokenTest {

    @Test
    void 口令能验通过且每次散列都不同() {
        String a = PasswordHasher.hash("qa-platform-2026");
        String b = PasswordHasher.hash("qa-platform-2026");

        assertThat(PasswordHasher.matches("qa-platform-2026", a)).isTrue();
        assertThat(PasswordHasher.matches("qa-platform-2026", b)).isTrue();
        // 每次盐不同，所以两次散列结果不能一样——一样就说明没加盐，
        // 那样一张彩虹表能把全组的口令一起还原出来
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 错口令验不过() {
        String stored = PasswordHasher.hash("correct-horse");
        assertThat(PasswordHasher.matches("wrong-horse", stored)).isFalse();
        assertThat(PasswordHasher.matches("", stored)).isFalse();
        assertThat(PasswordHasher.matches(null, stored)).isFalse();
    }

    /** 库里存了脏数据时返回 false，而不是抛异常把登录接口打成 500 */
    @Test
    void 散列串损坏时不抛异常() {
        assertThat(PasswordHasher.matches("x", "垃圾数据")).isFalse();
        assertThat(PasswordHasher.matches("x", "pbkdf2$abc$def")).isFalse();
        assertThat(PasswordHasher.matches("x", null)).isFalse();
    }

    /** 密钥落库失败时退回进程内密钥——这时候登录还能用，只是重启要重登 */
    private AuthTokenService tokenServiceWithoutDb() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("no db")).when(jdbc).execute(anyString());
        return new AuthTokenService(jdbc);
    }

    @Test
    void 令牌能签能验() {
        AuthTokenService svc = tokenServiceWithoutDb();
        String token = svc.issue("usr-42");
        assertThat(svc.verify(token)).isEqualTo("usr-42");
    }

    @Test
    void 改过的令牌验不过() {
        AuthTokenService svc = tokenServiceWithoutDb();
        String token = svc.issue("usr-42");

        // 把 payload 换成另一个用户 id——没有签名的话这一步就能直接冒充别人
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("usr-admin:" + (System.currentTimeMillis() + 100000)).getBytes())
                + token.substring(token.lastIndexOf('.'));

        assertThat(svc.verify(forged)).isNull();
        assertThat(svc.verify(token + "x")).isNull();
        assertThat(svc.verify("乱码")).isNull();
        assertThat(svc.verify(null)).isNull();
        assertThat(svc.verify("")).isNull();
    }

    /** 换一把密钥（相当于另一台服务或重启后）签的令牌，这边不认 */
    @Test
    void 别的密钥签的令牌验不过() {
        String token = tokenServiceWithoutDb().issue("usr-42");
        assertThat(tokenServiceWithoutDb().verify(token)).isNull();
    }
}
