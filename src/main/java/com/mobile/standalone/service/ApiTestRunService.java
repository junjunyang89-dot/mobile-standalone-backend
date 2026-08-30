package com.mobile.standalone.service;

import com.mobile.standalone.dto.ApiTestDtos.CaseResult;
import com.mobile.standalone.dto.ApiTestDtos.RunResult;
import com.mobile.standalone.entity.ApiTestRun;
import com.mobile.standalone.entity.ApiTestRunCase;
import com.mobile.standalone.mapper.ApiTestRunMapper;
import com.mobile.standalone.service.apitest.ApiAppCatalog;
import com.mobile.standalone.service.apitest.RequirementEndpointProjectResolver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            // 集合执行要能在报告里认出来（「XX 集合 · 第 12 次」），否则历史就是一串没有上下文的记录
            ensureColumn("api_test_runs", "collection_id", "VARCHAR(64) NULL COMMENT '按集合执行时记下是哪个集合'");
            // 断言明细和实际收发的内容。不存的话，报告页只能给一句「失败」，
            // 人还得拿 Postman 再手工重放一遍才知道发生了什么
            ensureColumn("api_test_run_cases", "assertion_results", "TEXT NULL COMMENT '每条断言的 OK/FAIL'");
            ensureColumn("api_test_run_cases", "body_preview", "MEDIUMTEXT NULL COMMENT '响应体（截断）'");
            ensureColumn("api_test_run_cases", "req_headers", "TEXT NULL");
            ensureColumn("api_test_run_cases", "req_body", "TEXT NULL");
            ensureColumn("api_test_run_cases", "resp_headers", "TEXT NULL");
            log.info("[api-test-runs] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-test-runs] 建表失败: {}", e.getMessage());
        }
    }

    // ---------- 报告页查询 ----------

    public List<Map<String, Object>> listRuns(String collectionId, int limit) {
        return runMapper.findRuns(collectionId, Math.max(1, Math.min(limit, 200)));
    }

    /**
     * 报告浏览：执行记录 + 集合来源 + 每条执行涉及的接口。
     * 前端按 环境 → 来源 → 项目 → 接口 建树，右侧挂报告列表。
     */
    public List<Map<String, Object>> browseRuns(int limit) {
        int cap = Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> runs = runMapper.findRunsEnriched(cap);
        List<Map<String, Object>> ifaceRows = runMapper.findRunInterfaces();
        Map<String, List<Map<String, Object>>> byRun = new LinkedHashMap<>();
        for (Map<String, Object> row : ifaceRows) {
            String runId = String.valueOf(row.get("runId"));
            String path = stripQuery(String.valueOf(row.getOrDefault("path", "")));
            if (path.isBlank()) {
                continue;
            }
            String caseProject = blankToNull(String.valueOf(row.getOrDefault("caseProject", "")));
            String collectionProject = blankToNull(String.valueOf(row.getOrDefault("collectionProject", "")));
            String project = resolveReportProject(caseProject, collectionProject, path);
            byRun.computeIfAbsent(runId, k -> new java.util.ArrayList<>()).add(Map.of(
                    "project", project,
                    "method", String.valueOf(row.getOrDefault("method", "GET")),
                    "path", path
            ));
        }
        for (Map<String, Object> run : runs) {
            String id = String.valueOf(run.get("id"));
            List<Map<String, Object>> ifaces = dedupeInterfaces(byRun.getOrDefault(id, List.of()));
            run.put("interfaces", ifaces);
        }
        return runs;
    }

    /** 用例 project → 集合 project → 路径前缀推断；都认不出才进「未分组」 */
    private static String resolveReportProject(String caseProject, String collectionProject, String path) {
        String explicit = caseProject != null ? caseProject : collectionProject;
        String resolved = RequirementEndpointProjectResolver.resolve(explicit, path);
        if (resolved != null && !resolved.isBlank()) {
            return ApiAppCatalog.canonicalCode(resolved);
        }
        return "未分组";
    }

    private static List<Map<String, Object>> dedupeInterfaces(List<Map<String, Object>> raw) {
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (Map<String, Object> it : raw) {
            String key = String.valueOf(it.get("project")) + "\0"
                    + String.valueOf(it.get("method")) + "\0"
                    + String.valueOf(it.get("path"));
            uniq.putIfAbsent(key, it);
        }
        return List.copyOf(uniq.values());
    }

    private static String stripQuery(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String p = path.trim();
        int q = p.indexOf('?');
        return q >= 0 ? p.substring(0, q) : p;
    }

    @Transactional
    public boolean deleteRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        runMapper.deleteRunCases(runId.trim());
        return runMapper.deleteRun(runId.trim()) > 0;
    }

    @Transactional
    public int deleteRuns(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> clean = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (clean.isEmpty()) {
            return 0;
        }
        runMapper.deleteRunCasesByRunIds(clean);
        return runMapper.deleteRuns(clean);
    }

    /** 一份报告的详情：概要 + 用例明细（失败在前） */
    public Map<String, Object> runDetail(String runId) {
        ApiTestRun run = runMapper.findRunById(runId);
        if (run == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("cases", runMapper.findCasesByRun(runId));
        return out;
    }

    /**
     * 近 N 天趋势。
     * <p>
     * 按天聚合而不是把每次执行都画上去：一天里手动重跑十几次很正常，
     * 那种锯齿看不出任何走向，只会让人以为质量在剧烈波动。
     */
    public List<Map<String, Object>> trend(int days) {
        int d = Math.max(1, Math.min(days, 90));
        return runMapper.dailyTrend(LocalDateTime.now().minusDays(d));
    }

    /** 列不存在才加，免得每加一列就要人记着去跑一份 SQL */
    private void ensureColumn(String table, String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n != null && n == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("[api-test-runs] 已补充 {}.{} 列", table, column);
            }
        } catch (Exception e) {
            log.debug("[api-test-runs] {}.{} 列检查跳过: {}", table, column, e.getMessage());
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
            run.setCollectionId(result.getCollectionId());
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
                // 断言逐条存下来。只存一句 error 的话，四条断言挂了三条也只看得到一条
                row.setAssertionResults(truncate(
                        cr.getAssertionResults() == null ? null
                                : String.join("\n", cr.getAssertionResults()), 8000));
                row.setBodyPreview(cr.getBodyPreview());
                row.setReqHeaders(truncate(kvJson(cr.getReqHeaders()), 8000));
                row.setReqBody(truncate(cr.getReqBody(), 8000));
                row.setRespHeaders(truncate(kvJson(cr.getRespHeaders()), 8000));
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
            // 分组和左侧树只认 path，query 留在 url 列给详情页看
            return p;
        } catch (Exception e) {
            return url.trim();
        }
    }

    private static String kvJson(java.util.Map<String, String> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            return null;
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
