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
    }

    /**
     * 一个账号都还没有。
     * <p>
     * 这个状态下鉴权<b>整体放行</b>——否则 filter 一上线、账号还没建，
     * 你连创建第一个管理员的页面都进不去，只能去改数据库。
     * 而这套东西的前提就是「不用手跑 SQL」。
     */
    public boolean isBootstrapping() {
        try {
            return userMapper.countAll() == 0;
        } catch (Exception e) {
            // 表还没建好时也算未初始化：放行，别把平台锁死
            log.warn("[auth] 读账号数失败，按未初始化处理: {}", e.getMessage());
            return true;
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
        PlatformUser user = new PlatformUser();
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
        resetPassword(id, newPassword);
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
