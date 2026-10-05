package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.ApiChangeReport;
import com.mobile.standalone.mapper.ApiChangeReportMapper;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer;
import com.mobile.standalone.service.apichange.MustTestGuide;
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
        try {
            jdbcTemplate.execute("ALTER TABLE api_change_reports ADD COLUMN source VARCHAR(16) NULL");
        } catch (Exception ignored) {
            /* 列已存在 */
        }
        markBatchReports();
    }

    public static final String SOURCE_BATCH = "BATCH";

    private static final java.util.regex.Pattern REPORT_ID = java.util.regex.Pattern.compile("\"reportId\"\\s*:\\s*(\\d+)");

    /** 加 source 列之前，接口对比分析2 存下的报告没打标记：按批次里记的 reportId 补上 */
    private void markBatchReports() {
        try {
            java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
            for (String payload : jdbcTemplate.queryForList("SELECT payload FROM api_change_batches", String.class)) {
                java.util.regex.Matcher m = REPORT_ID.matcher(payload == null ? "" : payload);
                while (m.find()) {
                    ids.add(Long.parseLong(m.group(1)));
                }
            }
            int n = 0;
            for (Long id : ids) {
                n += jdbcTemplate.update("UPDATE api_change_reports SET source = ? WHERE id = ? AND source IS NULL", SOURCE_BATCH, id);
            }
            if (n > 0) {
                log.info("[api-change-reports] 补标了 {} 份接口对比分析2 的报告", n);
            }
        } catch (Exception e) {
            log.debug("[api-change-reports] 补标接口对比分析2 报告跳过: {}", e.getMessage());
        }
    }

    /**
     * 分析成功后写入快照；失败只打日志，不挡返回报告。
     *
     * @return 新报告 id，失败返回 null
     */
    @Transactional
    public Long saveSnapshot(String projectCode, ApiChangeAnalyzer.Report report) {
        return saveSnapshot(projectCode, report, null);
    }

    @Transactional
    public Long saveSnapshot(String projectCode, ApiChangeAnalyzer.Report report, String source) {
        if (report == null) {
            return null;
        }
        try {
            ApiChangeReport row = new ApiChangeReport();
            // 只认显式传进来的 project code，不拿 report.getProject() 兜底。
            // Report.project 是【展示名】不是 code——git 路径传的是 ApiProject.getName()，
            // 目录对比路径传的是「本地目录对比」这个字面量。拿它兜底会把展示名写进
            // project 列，之后既关联不上任何项目，还会挡住外键。
            row.setProject(blankToNull(projectCode));
            row.setBaseRef(blankToNull(report.getBaseRef()));
            row.setHeadRef(blankToNull(report.getHeadRef()));
            row.setSummary(buildSummary(report));
            row.setReportJson(JSON.writeValueAsString(report));
            row.setCreatedAt(LocalDateTime.now());
            row.setSource(source);
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
            ApiChangeAnalyzer.Report report = JSON.readValue(row.getReportJson(), ApiChangeAnalyzer.Report.class);
            if (report.getMustTest() == null) {
                report.setMustTest(MustTestGuide.of(report));
            }
            out.put("report", report);
        } catch (Exception typed) {
            try {
                out.put("report", JSON.readValue(row.getReportJson(), Object.class));
            } catch (Exception e) {
                throw new IllegalStateException("报告 JSON 解析失败: " + e.getMessage());
            }
        }
        return out;
    }

    /** 读回完整报告；不存在或解析不了返回 null */
    public ApiChangeAnalyzer.Report loadReport(Long id) {
        ApiChangeReport row = id == null ? null : reportMapper.findById(id);
        if (row == null) {
            return null;
        }
        try {
            return JSON.readValue(row.getReportJson(), ApiChangeAnalyzer.Report.class);
        } catch (Exception e) {
            log.warn("[api-change-reports] 报告 {} 解析失败: {}", id, e.getMessage());
            return null;
        }
    }

    @Transactional
    public void delete(Long id) {
        if (reportMapper.findById(id) == null) {
            throw new IllegalArgumentException("报告不存在: " + id);
        }
        reportMapper.deleteById(id);
    }

    public static String buildSummary(ApiChangeAnalyzer.Report report) {
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
        int other = report.getOtherChanges() == null ? 0
                : (int) report.getOtherChanges().stream().filter(o -> o.getTrivial() == null).count();
        int findings = report.getFindings() == null ? 0 : report.getFindings().size();
        return String.format("接口 %d · 高风险 %d · 表 %d · 其它 %d · 风险点 %d",
                ep, breaking, tables, other, findings);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
