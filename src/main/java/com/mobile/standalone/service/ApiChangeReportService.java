package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.ApiChangeReport;
import com.mobile.standalone.mapper.ApiChangeReportMapper;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口变更分析报告快照：分析成功后落库，便于复看。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiChangeReportService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiChangeReportMapper reportMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_change_reports (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        project VARCHAR(64) NULL,
                        base_ref VARCHAR(255) NULL,
                        head_ref VARCHAR(255) NULL,
                        summary VARCHAR(512) NULL,
                        report_json LONGTEXT NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_project_created (project, created_at),
                        INDEX idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[api-change-reports] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-change-reports] 建表失败: {}", e.getMessage());
        }
    }

    /**
     * 分析成功后写入快照；失败只打日志，不挡返回报告。
     *
     * @return 新报告 id，失败返回 null
     */
    @Transactional
    public Long saveSnapshot(String projectCode, ApiChangeAnalyzer.Report report) {
        if (report == null) {
            return null;
        }
        try {
            ApiChangeReport row = new ApiChangeReport();
            row.setProject(blankToNull(projectCode != null ? projectCode : report.getProject()));
            row.setBaseRef(blankToNull(report.getBaseRef()));
            row.setHeadRef(blankToNull(report.getHeadRef()));
            row.setSummary(buildSummary(report));
            row.setReportJson(JSON.writeValueAsString(report));
            row.setCreatedAt(LocalDateTime.now());
            reportMapper.insert(row);
            report.setReportId(row.getId());
            log.info("[api-change-reports] 已保存 id={} project={} {}",
                    row.getId(), row.getProject(), row.getSummary());
            return row.getId();
        } catch (Exception e) {
            log.warn("[api-change-reports] 保存失败（不影响本次展示）: {}", e.getMessage());
            return null;
        }
    }

    public List<ApiChangeReport> list(String project, int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        return reportMapper.search(blankToNull(project), lim);
    }

    public Map<String, Object> getDetail(Long id) {
        ApiChangeReport row = reportMapper.findById(id);
        if (row == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("project", row.getProject());
        out.put("baseRef", row.getBaseRef());
        out.put("headRef", row.getHeadRef());
        out.put("summary", row.getSummary());
        out.put("createdAt", row.getCreatedAt());
        try {
            Object report = JSON.readValue(row.getReportJson(), Object.class);
            out.put("report", report);
        } catch (Exception e) {
            throw new IllegalStateException("报告 JSON 解析失败: " + e.getMessage());
        }
        return out;
    }

    @Transactional
    public void delete(Long id) {
        if (reportMapper.findById(id) == null) {
            throw new IllegalArgumentException("报告不存在: " + id);
        }
        reportMapper.deleteById(id);
    }

    static String buildSummary(ApiChangeAnalyzer.Report report) {
        int ep = report.getEndpoints() == null ? 0 : report.getEndpoints().size();
        int breaking = 0;
        if (report.getEndpoints() != null) {
            for (ApiChangeAnalyzer.EndpointChange e : report.getEndpoints()) {
                if ("BREAKING".equalsIgnoreCase(e.getRisk())) {
                    breaking++;
                }
            }
        }
        int tables = report.getTables() == null ? 0 : report.getTables().size();
        int other = report.getOtherChanges() == null ? 0 : report.getOtherChanges().size();
        int findings = report.getFindings() == null ? 0 : report.getFindings().size();
        return String.format("接口 %d · 高风险 %d · 表 %d · 其它 %d · 风险点 %d",
                ep, breaking, tables, other, findings);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
