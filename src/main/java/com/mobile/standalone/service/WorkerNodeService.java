package com.mobile.standalone.service;

import com.mobile.standalone.entity.WorkerNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行节点登记。控制面用它看「谁在线、带着哪些手机」；执行节点心跳写这里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerNodeService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS worker_nodes (
                        id VARCHAR(64) PRIMARY KEY,
                        display_name VARCHAR(128) NOT NULL,
                        hostname VARCHAR(128) DEFAULT NULL,
                        role VARCHAR(16) DEFAULT 'WORKER',
                        base_url VARCHAR(255) DEFAULT NULL,
                        devices_json MEDIUMTEXT,
                        last_seen DATETIME DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            ensureColumn("devices", "worker_id", "VARCHAR(64) NULL COMMENT '哪台执行节点扫到的' AFTER environment");
            ensureColumn("tasks", "worker_id", "VARCHAR(64) NULL COMMENT '在哪台执行节点跑的' AFTER run_track");
            log.info("[worker-nodes] 表已就绪");
        } catch (Exception e) {
            log.warn("[worker-nodes] 建表失败: {}", e.getMessage());
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("[worker-nodes] 补列 {}.{}", table, column);
        } catch (Exception e) {
            log.warn("[worker-nodes] 补列 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }

    public WorkerNode upsertHeartbeat(WorkerNode node) {
        if (node.getId() == null || node.getId().isBlank()) {
            throw new IllegalArgumentException("worker id 必填");
        }
        LocalDateTime now = LocalDateTime.now();
        node.setLastSeen(now);
        node.setUpdatedAt(now);
        if (node.getDisplayName() == null || node.getDisplayName().isBlank()) {
            node.setDisplayName(node.getId());
        }
        WorkerNode existing = findById(node.getId());
        if (existing == null) {
            node.setCreatedAt(now);
            jdbcTemplate.update(
                    "INSERT INTO worker_nodes (id, display_name, hostname, role, base_url, devices_json, last_seen, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    node.getId(), node.getDisplayName(), node.getHostname(),
                    node.getRole() == null ? "WORKER" : node.getRole(),
                    node.getBaseUrl(), node.getDevicesJson(), node.getLastSeen(),
                    node.getCreatedAt(), node.getUpdatedAt());
        } else {
            jdbcTemplate.update(
                    "UPDATE worker_nodes SET display_name=?, hostname=?, role=?, base_url=?, devices_json=?, last_seen=?, updated_at=? WHERE id=?",
                    node.getDisplayName(), node.getHostname(),
                    node.getRole() == null ? existing.getRole() : node.getRole(),
                    node.getBaseUrl(), node.getDevicesJson(), node.getLastSeen(),
                    node.getUpdatedAt(), node.getId());
        }
        return findById(node.getId());
    }

    public WorkerNode findById(String id) {
        List<WorkerNode> rows = jdbcTemplate.query(
                "SELECT * FROM worker_nodes WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<WorkerNode> list() {
        return jdbcTemplate.query("SELECT * FROM worker_nodes ORDER BY last_seen DESC", MAPPER);
    }

    private static final RowMapper<WorkerNode> MAPPER = new RowMapper<>() {
        @Override
        public WorkerNode mapRow(ResultSet rs, int rowNum) throws SQLException {
            WorkerNode n = new WorkerNode();
            n.setId(rs.getString("id"));
            n.setDisplayName(rs.getString("display_name"));
            n.setHostname(rs.getString("hostname"));
            n.setRole(rs.getString("role"));
            n.setBaseUrl(rs.getString("base_url"));
            n.setDevicesJson(rs.getString("devices_json"));
            n.setLastSeen(rs.getObject("last_seen", LocalDateTime.class));
            n.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
            n.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
            return n;
        }
    };
}
