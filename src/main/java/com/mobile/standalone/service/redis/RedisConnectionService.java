package com.mobile.standalone.service.redis;

import com.mobile.standalone.entity.RedisConnection;
import com.mobile.standalone.service.EnvVariableService;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Redis 查询工具的连接配置。密码只进不出：列表里一律打码，编辑时传回掩码表示「不改」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisConnectionService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void ensureTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS redis_connections (
                        id VARCHAR(32) PRIMARY KEY,
                        environment VARCHAR(16) NOT NULL,
                        name VARCHAR(64) NOT NULL,
                        host VARCHAR(255) NOT NULL,
                        port INT NULL,
                        username VARCHAR(128) NULL,
                        password VARCHAR(255) NULL,
                        db_index INT NULL,
                        remark VARCHAR(255) NULL,
                        updated_at DATETIME NULL,
                        UNIQUE KEY uk_env_name (environment, name)
                    ) DEFAULT CHARSET=utf8mb4 COMMENT='Redis 查询工具连接'
                    """);
        } catch (Exception e) {
            log.warn("[redis-conn] 建表失败: {}", e.getMessage());
        }
    }

    public List<RedisConnection> list() {
        List<RedisConnection> out = jdbcTemplate.query(
                "SELECT * FROM redis_connections ORDER BY environment DESC, name", this::map);
        out.forEach(RedisConnectionService::mask);
        return out;
    }

    /** 带真实密码，只给服务端连 Redis 用 */
    public RedisConnection get(String id) {
        List<RedisConnection> l = jdbcTemplate.query("SELECT * FROM redis_connections WHERE id = ?", this::map, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public RedisConnection save(RedisConnection body) {
        RedisConnection c = normalize(body);
        RedisConnection old = c.getId() == null ? null : get(c.getId());
        // 生产连接只读靠的就是这个字段；允许原地改成测试，只读就形同虚设
        if (old != null && !old.getEnvironment().equals(c.getEnvironment())) {
            throw new IllegalArgumentException("连接建好后不能改环境，要换环境请新建一条");
        }
        Integer dup = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM redis_connections WHERE environment = ? AND name = ? AND id <> ?",
                Integer.class, c.getEnvironment(), c.getName(), old == null ? "" : old.getId());
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException(TestEnvironment.label(c.getEnvironment()) + "已经有叫 " + c.getName() + " 的连接了");
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        if (old == null) {
            String id = "rds-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            jdbcTemplate.update("INSERT INTO redis_connections (id, environment, name, host, port, username, password, "
                            + "db_index, remark, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id, c.getEnvironment(), c.getName(), c.getHost(), c.getPort(), c.getUsername(),
                    c.getPassword(), c.getDbIndex(), c.getRemark(), now);
            return mask(get(id));
        }
        jdbcTemplate.update("UPDATE redis_connections SET environment = ?, name = ?, host = ?, port = ?, username = ?, "
                        + "password = ?, db_index = ?, remark = ?, updated_at = ? WHERE id = ?",
                c.getEnvironment(), c.getName(), c.getHost(), c.getPort(), c.getUsername(),
                keepPassword(c.getPassword(), old), c.getDbIndex(), c.getRemark(), now, old.getId());
        return mask(get(old.getId()));
    }

    public boolean delete(String id) {
        return jdbcTemplate.update("DELETE FROM redis_connections WHERE id = ?", id) > 0;
    }

    /** 弹窗里还没保存也能测：编辑时密码是掩码就用库里那份 */
    public RedisConnection resolveForProbe(RedisConnection body) {
        RedisConnection c = normalize(body);
        RedisConnection old = c.getId() == null ? null : get(c.getId());
        c.setPassword(keepPassword(c.getPassword(), old));
        return c;
    }

    static RedisConnection normalize(RedisConnection body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        RedisConnection c = new RedisConnection();
        c.setId(trim(body.getId()));
        c.setEnvironment(TestEnvironment.normalize(body.getEnvironment()));
        c.setName(trim(body.getName()));
        if (c.getName() == null || c.getName().length() > 32) {
            throw new IllegalArgumentException("名称必填，32 个字以内");
        }
        String host = trim(body.getHost());
        if (host == null) {
            throw new IllegalArgumentException("地址必填");
        }
        Integer port = body.getPort();
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            // 人习惯直接粘 10.0.0.1:6379
            try {
                port = Integer.parseInt(host.substring(colon + 1).trim());
                host = host.substring(0, colon).trim();
            } catch (NumberFormatException ignored) {
                // 不是端口就原样留着，连的时候会报
            }
        }
        c.setHost(host);
        c.setPort(port == null ? 6379 : port);
        if (c.getPort() <= 0 || c.getPort() > 65535) {
            throw new IllegalArgumentException("端口不对：" + c.getPort());
        }
        int db = body.getDbIndex() == null ? 0 : body.getDbIndex();
        if (db < 0 || db > 255) {
            throw new IllegalArgumentException("库号不对：" + db);
        }
        c.setDbIndex(db);
        c.setUsername(trim(body.getUsername()));
        c.setPassword(body.getPassword() == null || body.getPassword().isEmpty() ? null : body.getPassword());
        c.setRemark(trim(body.getRemark()));
        return c;
    }

    private static String keepPassword(String incoming, RedisConnection old) {
        if (old != null && (incoming == null || EnvVariableService.MASK.equals(incoming))) {
            return old.getPassword();
        }
        return incoming;
    }

    private static RedisConnection mask(RedisConnection c) {
        if (c != null && c.getPassword() != null && !c.getPassword().isEmpty()) {
            c.setPassword(EnvVariableService.MASK);
        }
        return c;
    }

    private RedisConnection map(ResultSet rs, int i) throws SQLException {
        RedisConnection c = new RedisConnection();
        c.setId(rs.getString("id"));
        c.setEnvironment(rs.getString("environment"));
        c.setName(rs.getString("name"));
        c.setHost(rs.getString("host"));
        int port = rs.getInt("port");
        c.setPort(rs.wasNull() ? 6379 : port);
        c.setUsername(rs.getString("username"));
        c.setPassword(rs.getString("password"));
        int db = rs.getInt("db_index");
        c.setDbIndex(rs.wasNull() ? 0 : db);
        c.setRemark(rs.getString("remark"));
        Timestamp t = rs.getTimestamp("updated_at");
        c.setUpdatedAt(t == null ? null : t.toLocalDateTime());
        return c;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
