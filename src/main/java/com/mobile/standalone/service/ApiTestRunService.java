package com.mobile.standalone.service;

import com.mobile.standalone.dto.ApiTestDtos.CaseResult;
import com.mobile.standalone.dto.ApiTestDtos.RunResult;
import com.mobile.standalone.entity.ApiTestRun;
import com.mobile.standalone.entity.ApiTestRunCase;
import com.mobile.standalone.mapper.ApiTestRunMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 接口测试执行历史落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTestRunService {

    private final ApiTestRunMapper runMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTables() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_test_runs (
                        id VARCHAR(64) PRIMARY KEY,
                        environment VARCHAR(16) NOT NULL,
                        total INT NOT NULL DEFAULT 0,
                        passed INT NOT NULL DEFAULT 0,
                        failed INT NOT NULL DEFAULT 0,
                        duration_ms BIGINT NOT NULL DEFAULT 0,
                        ok TINYINT(1) NOT NULL DEFAULT 0,
                        message VARCHAR(512) DEFAULT NULL,
                        created_by VARCHAR(100) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_test_run_cases (
                        id VARCHAR(64) PRIMARY KEY,
                        run_id VARCHAR(64) NOT NULL,
                        case_id VARCHAR(64) DEFAULT NULL,
                        name VARCHAR(255) DEFAULT NULL,
                        method VARCHAR(16) DEFAULT NULL,
                        path VARCHAR(1024) DEFAULT NULL,
                        url VARCHAR(2048) DEFAULT NULL,
                        status VARCHAR(32) NOT NULL,
                        http_status INT DEFAULT NULL,
                        duration_ms BIGINT DEFAULT NULL,
                        error VARCHAR(1024) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_run (run_id),
                        INDEX idx_created (created_at),
                        INDEX idx_status_created (status, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[api-test-runs] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-test-runs] 建表失败: {}", e.getMessage());
        }
    }

    /**
     * 执行结束后写入历史；失败只打日志，不挡返回。
     *
     * @return runId，失败返回 null
     */
    @Transactional
    public String save(RunResult result, String createdBy) {
        if (result == null || result.getCases() == null || result.getCases().isEmpty()) {
            return null;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            String runId = "atr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            ApiTestRun run = new ApiTestRun();
            run.setId(runId);
            run.setEnvironment(result.getEnvironment());
            run.setTotal(result.getTotal());
            run.setPassed(result.getPassed());
            run.setFailed(result.getFailed());
            run.setDurationMs(result.getDurationMs());
            run.setOk(result.isOk());
            run.setMessage(truncate(result.getMessage(), 512));
            run.setCreatedBy(blankToNull(createdBy));
            run.setCreatedAt(now);
            runMapper.insertRun(run);

            for (CaseResult cr : result.getCases()) {
                if (cr == null) {
                    continue;
                }
                ApiTestRunCase row = new ApiTestRunCase();
                row.setId("atrc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                row.setRunId(runId);
                row.setCaseId(cr.getId());
                row.setName(cr.getName());
                row.setMethod(cr.getMethod());
                row.setUrl(truncate(cr.getUrl(), 2048));
                row.setPath(truncate(extractPath(cr.getUrl()), 1024));
                row.setStatus(cr.getStatus() != null ? cr.getStatus() : "FAILED");
                row.setHttpStatus(cr.getHttpStatus());
                row.setDurationMs(cr.getDurationMs());
                row.setError(truncate(cr.getError(), 1024));
                row.setCreatedAt(now);
                runMapper.insertRunCase(row);
            }
            result.setRunId(runId);
            log.info("[api-test-runs] 已保存 runId={} total={} failed={}",
                    runId, result.getTotal(), result.getFailed());
            return runId;
        } catch (Exception e) {
            log.warn("[api-test-runs] 保存失败（不影响本次结果）: {}", e.getMessage());
            return null;
        }
    }

    private static String extractPath(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String p = uri.getPath();
            if (p == null || p.isBlank()) {
                return url.trim();
            }
            String q = uri.getRawQuery();
            return q == null || q.isBlank() ? p : p + "?" + q;
        } catch (Exception e) {
            return url.trim();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
