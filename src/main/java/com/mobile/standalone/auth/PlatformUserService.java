package com.mobile.standalone.auth;

import com.mobile.standalone.mapper.PlatformUserMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformUserService {

    private final PlatformUserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS platform_users (
                        id VARCHAR(64) PRIMARY KEY,
                        username VARCHAR(64) NOT NULL,
                        display_name VARCHAR(128) DEFAULT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        role VARCHAR(16) NOT NULL DEFAULT 'VIEWER',
                        enabled TINYINT(1) NOT NULL DEFAULT 1,
                        last_login_at DATETIME DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_platform_users_username (username)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[auth] platform_users 表已就绪");
        } catch (Exception e) {
            log.warn("[auth] 建表失败: {}", e.getMessage());
        }
        // 存量库是先有表后有这几个字段的，逐个补。MySQL 没有 ADD COLUMN IF NOT EXISTS，
        // 靠 try/catch 吞异常的话每次启动都刷一遍 warn，真出问题反而看不见
        addColumnIfMissing("platform_users", "email",
                "ADD COLUMN email VARCHAR(255) DEFAULT NULL COMMENT '找回密码用邮箱，唯一'");
        addColumnIfMissing("platform_users", "password_changed_at",
                "ADD COLUMN password_changed_at DATETIME DEFAULT NULL COMMENT '最近改密时间'");
        addColumnIfMissing("platform_users", "must_change_password",
                "ADD COLUMN must_change_password TINYINT(1) NOT NULL DEFAULT 0 COMMENT '强制下次登录改密'");
        // 唯一索引允许多个 NULL，所以存量账号邮箱全空也能直接加，不用先补数据
        addIndexIfMissing("platform_users", "uk_platform_users_email",
                "ADD UNIQUE KEY uk_platform_users_email (email)");
    }

    private void addColumnIfMissing(String table, String column, String alter) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS"
                            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE " + table + " " + alter);
            log.info("[auth] {} 已补列 {}", table, column);
        } catch (Exception e) {
            log.warn("[auth] 补列 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }

    private void addIndexIfMissing(String table, String index, String alter) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.STATISTICS"
                            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                    Integer.class, table, index);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE " + table + " " + alter);
            log.info("[auth] {} 已加索引 {}", table, index);
        } catch (Exception e) {
            // 存量里已经有重复邮箱时会失败——那是数据问题，得人来看，不能悄悄跳过
            log.warn("[auth] 加索引 {}.{} 失败（可能已有重复邮箱，需人工处理）: {}", table, index, e.getMessage());
        }
    }

    /** 账号服务查不动了。调用方必须当作「不确定」处理，绝不能退化成「还没建账号」 */
    public static class AuthUnavailableException extends RuntimeException {
        public AuthUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 一旦见过账号，就永久记住「鉴权已开启」。
     * <p>
     * 单向闩，只会 false → true，不会退回去。有了它，平台正常运行期间就算 DB 整个挂掉，
     * 也不会有任何一个请求被当成「还没建账号」而放行。
     */
    private volatile boolean authArmed = false;

    /**
     * 一个账号都还没有。
     * <p>
     * 这个状态下鉴权<b>整体放行</b>——否则 filter 一上线、账号还没建，
     * 你连创建第一个管理员的页面都进不去，只能去改数据库。
     * 而这套东西的前提就是「不用手跑 SQL」。
     * <p>
     * <b>但查不动库时抛异常，不返回 true。</b>原来那版是 fail-open：{@code countAll()} 一抛异常就
     * 当作「还没建账号」，于是整套鉴权关闭。这不需要什么 0day——连接池打满、MySQL 主从切换、
     * 表被误锁的那几十秒里，264 个端点、真机控制、抓包和 Jira/Wiki 凭据对内网所有人完全敞开，
     * 而且 {@code /auth/me} 还会主动回 {@code role: ADMIN}。DB 抖动是常态，不是极端假设。
     * <p>
     * 「别把人锁在门外」这个出发点是对的，但方向反了：分不清「服务挂了」和「没账号」的时候，
     * 该回 503 让人知道服务有问题，而不是把门拆掉。
     *
     * @throws AuthUnavailableException 读不到账号数——调用方应当拒绝请求，不是放行
     */
    public boolean isBootstrapping() {
        if (authArmed) {
            return false;
        }
        try {
            boolean none = userMapper.countAll() == 0;
            if (!none) {
                authArmed = true;
            }
            return none;
        } catch (Exception e) {
            log.error("[auth] 读账号数失败，按「服务不可用」处理（不放行）: {}", e.getMessage());
            throw new AuthUnavailableException("账号服务暂时不可用", e);
        }
    }

    public List<PlatformUser> list() {
        return userMapper.findAll();
    }

    public PlatformUser get(String id) {
        return id == null ? null : userMapper.findById(id);
    }

    public PlatformUser findByUsername(String username) {
        return username == null || username.isBlank() ? null : userMapper.findByUsername(username.trim());
    }

    public PlatformUser findByEmail(String email) {
        String e = normalizeEmail(email);
        return e.isEmpty() ? null : userMapper.findByEmail(e);
    }

    public boolean passwordMatches(String id, String rawPassword) {
        PlatformUser user = get(id);
        return user != null && PasswordHasher.matches(rawPassword, user.getPasswordHash());
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验口令。
     * <p>
     * 用户不存在和口令错误<b>返回同一个结果</b>——分开报的话，
     * 「用户不存在」就成了一个免费的账号枚举接口。
     */
    public PlatformUser authenticate(String username, String rawPassword) {
        PlatformUser user = findByUsername(username);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            return null;
        }
        if (!PasswordHasher.matches(rawPassword, user.getPasswordHash())) {
            return null;
        }
        try {
            userMapper.touchLogin(user.getId());
        } catch (Exception e) {
            log.debug("[auth] 更新登录时间失败: {}", e.getMessage());
        }
        return user;
    }

    @Transactional
    public PlatformUser create(String username, String displayName, String rawPassword, String role) {
        return create(username, displayName, null, rawPassword, role);
    }

    @Transactional
    public PlatformUser create(String username, String displayName, String email,
                               String rawPassword, String role) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("登录名不能为空");
        }
        if (rawPassword == null || rawPassword.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        if (userMapper.findByUsername(name) != null) {
            throw new IllegalArgumentException("登录名已存在：" + name);
        }
        String mail = normalizeEmail(email);
        if (!mail.isEmpty() && userMapper.findByEmail(mail) != null) {
            throw new IllegalArgumentException("这个邮箱已经绑在另一个账号上了：" + mail);
        }
        PlatformUser user = new PlatformUser();
        user.setEmail(mail.isEmpty() ? null : mail);
        user.setMustChangePassword(false);
        user.setId("usr-" + UUID.randomUUID());
        user.setUsername(name);
        user.setDisplayName(displayName == null || displayName.isBlank() ? name : displayName.trim());
        user.setPasswordHash(PasswordHasher.hash(rawPassword));
        user.setRole(PlatformRole.from(role).name());
        user.setEnabled(true);
        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    /**
     * 第一个管理员。
     * <p>
     * 只在一个账号都没有时可用——否则它就是一个「谁调谁变管理员」的后门。
     */
    @Transactional
    public PlatformUser bootstrapAdmin(String username, String displayName, String rawPassword) {
        if (!isBootstrapping()) {
            throw new IllegalStateException("平台已经有账号了，创建新账号请用管理员登录后在账号管理页操作");
        }
        return create(username, displayName, rawPassword, PlatformRole.ADMIN.name());
    }

    @Transactional
    public PlatformUser update(String id, String displayName, String role, Boolean enabled) {
        PlatformUser existing = get(id);
        if (existing == null) {
            return null;
        }
        boolean wasActiveAdmin = existing.roleEnum() == PlatformRole.ADMIN
                && Boolean.TRUE.equals(existing.getEnabled());
        boolean losingAdmin = wasActiveAdmin
                && ((role != null && PlatformRole.from(role) != PlatformRole.ADMIN)
                        || Boolean.FALSE.equals(enabled));
        // 把最后一个管理员降级或停用之后，账号管理页就再也进不去了，
        // 而恢复的唯一办法是手改数据库——这正是这套东西要避免的事
        if (losingAdmin && userMapper.countActiveAdmins() <= 1) {
            throw new IllegalArgumentException("这是最后一个启用中的管理员，不能降级或停用");
        }
        PlatformUser patch = new PlatformUser();
        patch.setId(id);
        patch.setDisplayName(displayName == null || displayName.isBlank() ? null : displayName.trim());
        patch.setRole(role == null || role.isBlank() ? null : PlatformRole.from(role).name());
        patch.setEnabled(enabled);
        userMapper.update(patch);
        return get(id);
    }

    /** 只落新密码，不动 must_change_password——三个调用方对这个标记的期望完全相反，交给它们各自决定 */
    @Transactional
    public void resetPassword(String id, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        PlatformUser existing = get(id);
        if (existing == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        userMapper.updatePassword(id, PasswordHasher.hash(rawPassword));
    }

    /**
     * 管理员手工重置。
     * <p>
     * 置 must_change_password：管理员设的这个密码<b>管理员自己知道</b>，而且多半是通过 IM 口头告诉本人的——
     * 这是最常见的一条密码泄露路径。不逼着改掉，它就会一直是那个人的长期密码。
     */
    @Transactional
    public void adminResetPassword(String id, String rawPassword) {
        resetPassword(id, rawPassword);
        userMapper.setMustChangePassword(id, true);
    }

    /** 走完邮箱验证码之后落新密码：不验旧密码（人本来就是因为忘了才走到这一步） */
    @Transactional
    public void applyNewPassword(String id, String rawPassword) {
        resetPassword(id, rawPassword);
        userMapper.setMustChangePassword(id, false);
    }

    @Transactional
    public void updateEmail(String id, String email) {
        String mail = normalizeEmail(email);
        PlatformUser owner = mail.isEmpty() ? null : userMapper.findByEmail(mail);
        if (owner != null && !owner.getId().equals(id)) {
            throw new IllegalArgumentException("这个邮箱已经绑在另一个账号上了");
        }
        userMapper.updateEmail(id, mail.isEmpty() ? null : mail);
    }

    /** 自己改自己的密码：要先验旧密码，否则一个被别人借用的登录态就能永久夺走账号 */
    @Transactional
    public void changeOwnPassword(String id, String oldPassword, String newPassword) {
        PlatformUser existing = get(id);
        if (existing == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        if (!PasswordHasher.matches(oldPassword, existing.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不对");
        }
        applyNewPassword(id, newPassword);
    }

    @Transactional
    public void delete(String id) {
        PlatformUser existing = get(id);
        if (existing == null) {
            return;
        }
        if (existing.roleEnum() == PlatformRole.ADMIN
                && Boolean.TRUE.equals(existing.getEnabled())
                && userMapper.countActiveAdmins() <= 1) {
            throw new IllegalArgumentException("这是最后一个启用中的管理员，不能删除");
        }
        userMapper.deleteById(id);
    }
}
