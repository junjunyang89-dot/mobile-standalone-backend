package com.mobile.standalone.service.kafka;

import com.mobile.standalone.entity.KafkaConnection;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Kafka 查询工具的集群连接。密码只进不出：列表里打码，编辑时传回掩码表示「不改」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaConnectionService {

    static final Set<String> PROTOCOLS = Set.of("PLAINTEXT", "SASL_PLAINTEXT", "SASL_SSL", "SSL");
    static final Set<String> MECHANISMS = Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void ensureTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS kafka_connections (
                        id VARCHAR(32) PRIMARY KEY,
                        environment VARCHAR(16) NOT NULL,
                        name VARCHAR(64) NOT NULL,
                        bootstrap_servers VARCHAR(1000) NOT NULL,
                        security_protocol VARCHAR(32) NULL,
                        sasl_mechanism VARCHAR(32) NULL,
                        username VARCHAR(128) NULL,
                        password VARCHAR(255) NULL,
                        remark VARCHAR(255) NULL,
                        updated_at DATETIME NULL,
                        UNIQUE KEY uk_env_name (environment, name)
                    ) DEFAULT CHARSET=utf8mb4 COMMENT='Kafka 查询工具连接'
                    """);
        } catch (Exception e) {
            log.warn("[kafka-conn] 建表失败: {}", e.getMessage());
        }
    }

    public List<KafkaConnection> list() {
        List<KafkaConnection> out = jdbcTemplate.query(
                "SELECT * FROM kafka_connections ORDER BY environment DESC, name", this::map);
        out.forEach(KafkaConnectionService::mask);
        return out;
    }

    /** 带真实密码，只给服务端连 Kafka 用 */
    public KafkaConnection get(String id) {
        List<KafkaConnection> l = jdbcTemplate.query("SELECT * FROM kafka_connections WHERE id = ?", this::map, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public KafkaConnection save(KafkaConnection body) {
        KafkaConnection c = normalize(body);
        KafkaConnection old = c.getId() == null ? null : get(c.getId());
        // 生产连接只能看靠的就是这个字段；允许原地改成测试，只读就形同虚设
        if (old != null && !old.getEnvironment().equals(c.getEnvironment())) {
            throw new IllegalArgumentException("连接建好后不能改环境，要换环境请新建一条");
        }
        Integer dup = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM kafka_connections WHERE environment = ? AND name = ? AND id <> ?",
                Integer.class, c.getEnvironment(), c.getName(), old == null ? "" : old.getId());
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException(TestEnvironment.label(c.getEnvironment()) + "已经有叫 " + c.getName() + " 的连接了");
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        if (old == null) {
            String id = "kfk-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            jdbcTemplate.update("INSERT INTO kafka_connections (id, environment, name, bootstrap_servers, "
                            + "security_protocol, sasl_mechanism, username, password, remark, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id, c.getEnvironment(), c.getName(), c.getBootstrapServers(), c.getSecurityProtocol(),
                    c.getSaslMechanism(), c.getUsername(), c.getPassword(), c.getRemark(), now);
            return mask(get(id));
        }
        jdbcTemplate.update("UPDATE kafka_connections SET name = ?, bootstrap_servers = ?, security_protocol = ?, "
                        + "sasl_mechanism = ?, username = ?, password = ?, remark = ?, updated_at = ? WHERE id = ?",
                c.getName(), c.getBootstrapServers(), c.getSecurityProtocol(), c.getSaslMechanism(),
                c.getUsername(), keepPassword(c.getPassword(), old), c.getRemark(), now, old.getId());
        return mask(get(old.getId()));
    }

    public boolean delete(String id) {
        return jdbcTemplate.update("DELETE FROM kafka_connections WHERE id = ?", id) > 0;
    }

    /** 弹窗里还没保存也能测：编辑时密码是掩码就用库里那份 */
    public KafkaConnection resolveForProbe(KafkaConnection body) {
        KafkaConnection c = normalize(body);
        KafkaConnection old = c.getId() == null ? null : get(c.getId());
        c.setPassword(keepPassword(c.getPassword(), old));
        return c;
    }

    static KafkaConnection normalize(KafkaConnection body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        KafkaConnection c = new KafkaConnection();
        c.setId(trim(body.getId()));
        c.setEnvironment(TestEnvironment.normalize(body.getEnvironment()));
        c.setName(trim(body.getName()));
        if (c.getName() == null || c.getName().length() > 32) {
            throw new IllegalArgumentException("名称必填，32 个字以内");
        }
        c.setBootstrapServers(normalizeServers(body.getBootstrapServers()));
        String proto = trim(body.getSecurityProtocol());
        proto = proto == null ? "PLAINTEXT" : proto.toUpperCase(Locale.ROOT);
        if (!PROTOCOLS.contains(proto)) {
            throw new IllegalArgumentException("不认识的安全协议：" + proto);
        }
        c.setSecurityProtocol(proto);
        if (proto.startsWith("SASL")) {
            String mech = trim(body.getSaslMechanism());
            mech = mech == null ? "PLAIN" : mech.toUpperCase(Locale.ROOT);
            if (!MECHANISMS.contains(mech)) {
                throw new IllegalArgumentException("不认识的认证方式：" + mech);
            }
            c.setSaslMechanism(mech);
            c.setUsername(trim(body.getUsername()));
            if (c.getUsername() == null) {
                throw new IllegalArgumentException("SASL 认证要填用户名");
            }
            c.setPassword(body.getPassword() == null || body.getPassword().isEmpty() ? null : body.getPassword());
        }
        c.setRemark(trim(body.getRemark()));
        return c;
    }

    /**
     * 人会从配置文件里直接粘：逗号、空格、换行分隔都有，有的不带端口。统一成 host:port,host:port。
     */
    static String normalizeServers(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("集群地址必填");
        }
        List<String> out = new ArrayList<>();
        for (String s : raw.split("[,;\\s]+")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            t = t.replaceFirst("^[a-zA-Z]+://", "");
            int colon = t.lastIndexOf(':');
            if (colon < 0) {
                t = t + ":9092";
            } else {
                try {
                    int port = Integer.parseInt(t.substring(colon + 1));
                    if (port <= 0 || port > 65535) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("地址格式不对：" + s + "，应为 host:port");
                }
            }
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("集群地址必填");
        }
        return String.join(",", out);
    }

    private static String keepPassword(String incoming, KafkaConnection old) {
        if (old != null && (incoming == null || EnvVariableService.MASK.equals(incoming))) {
            return old.getPassword();
        }
        return incoming;
    }

    private static KafkaConnection mask(KafkaConnection c) {
        if (c != null && c.getPassword() != null && !c.getPassword().isEmpty()) {
            c.setPassword(EnvVariableService.MASK);
        }
        return c;
    }

    private KafkaConnection map(ResultSet rs, int i) throws SQLException {
        KafkaConnection c = new KafkaConnection();
        c.setId(rs.getString("id"));
        c.setEnvironment(rs.getString("environment"));
        c.setName(rs.getString("name"));
        c.setBootstrapServers(rs.getString("bootstrap_servers"));
        c.setSecurityProtocol(rs.getString("security_protocol"));
        c.setSaslMechanism(rs.getString("sasl_mechanism"));
        c.setUsername(rs.getString("username"));
        c.setPassword(rs.getString("password"));
        c.setRemark(rs.getString("remark"));
        Timestamp t = rs.getTimestamp("updated_at");
        c.setUpdatedAt(t == null ? null : t.toLocalDateTime());
        return c;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
