package com.mobile.standalone.service;

import com.mobile.standalone.entity.DebugApiTest;
import com.mobile.standalone.mapper.DebugApiTestMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 接口对比分析调试数据。表不存在时自动建，并写入两条样例。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DebugApiTestService {

    private final DebugApiTestMapper debugApiTestMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTableAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS debug_api_tests (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        name VARCHAR(128) NOT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                        score INT DEFAULT 0,
                        remark VARCHAR(512) DEFAULT NULL,
                        extra VARCHAR(1024) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_status (status),
                        INDEX idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[debug-api-tests] 表已就绪 debug_api_tests");
            seedIfEmpty();
        } catch (Exception e) {
            log.warn("[debug-api-tests] 建表失败（可手动执行 schema_debug_api_tests.sql）: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        if (debugApiTestMapper.countAll() > 0) {
            return;
        }
        DebugApiTest a = new DebugApiTest();
        a.setName("样例-登录");
        a.setStatus("ACTIVE");
        a.setScore(80);
        a.setRemark("接口对比调试种子");
        a.setExtra("{\"scene\":\"login\"}");
        debugApiTestMapper.insert(a);

        DebugApiTest b = new DebugApiTest();
        b.setName("样例-下单");
        b.setStatus("DISABLED");
        b.setScore(60);
        b.setRemark("改字段后跑对比");
        b.setExtra("{\"scene\":\"order\"}");
        debugApiTestMapper.insert(b);
        log.info("[debug-api-tests] 已写入种子数据");
    }

    public List<DebugApiTest> list(String status, String keyword, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        return debugApiTestMapper.search(blankToNull(status), blankToNull(keyword), lim);
    }

    public DebugApiTest get(Long id) {
        return debugApiTestMapper.findById(id);
    }

    @Transactional
    public DebugApiTest create(DebugApiTest req) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        DebugApiTest row = new DebugApiTest();
        row.setName(req.getName().trim());
        row.setStatus(normalizeStatus(req.getStatus()));
        row.setScore(req.getScore() == null ? 0 : req.getScore());
        row.setRemark(blankToNull(req.getRemark()));
        row.setExtra(blankToNull(req.getExtra()));
        debugApiTestMapper.insert(row);
        return debugApiTestMapper.findById(row.getId());
    }

    @Transactional
    public DebugApiTest update(Long id, DebugApiTest req) {
        DebugApiTest existing = debugApiTestMapper.findById(id);
        if (existing == null) {
            return null;
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            existing.setName(req.getName().trim());
        }
        if (req.getStatus() != null) {
            existing.setStatus(normalizeStatus(req.getStatus()));
        }
        if (req.getScore() != null) {
            existing.setScore(req.getScore());
        }
        if (req.getRemark() != null) {
            existing.setRemark(blankToNull(req.getRemark()));
        }
        if (req.getExtra() != null) {
            existing.setExtra(blankToNull(req.getExtra()));
        }
        debugApiTestMapper.update(existing);
        return debugApiTestMapper.findById(id);
    }

    @Transactional
    public boolean delete(Long id) {
        return debugApiTestMapper.deleteById(id) > 0;
    }

    /**
     * 切换启用态。返回体带 {@code enabled} 布尔字段，供接口测试 extract {@code $.enabled}。
     */
    @Transactional
    public DebugApiTest setEnabled(Long id, Boolean enabled) {
        if (enabled == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        DebugApiTest existing = debugApiTestMapper.findById(id);
        if (existing == null) {
            return null;
        }
        existing.setStatus(enabled ? "ACTIVE" : "DISABLED");
        debugApiTestMapper.update(existing);
        return debugApiTestMapper.findById(id);
    }

    public static boolean isEnabled(DebugApiTest row) {
        return row != null && "ACTIVE".equalsIgnoreCase(row.getStatus());
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ACTIVE";
        }
        String s = status.trim().toUpperCase();
        if (!"ACTIVE".equals(s) && !"DISABLED".equals(s)) {
            throw new IllegalArgumentException("status 须为 ACTIVE 或 DISABLED");
        }
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
