package com.mobile.standalone.service;

import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import com.mobile.standalone.dto.VersionArchiveDtos.ArchiveDetail;
import com.mobile.standalone.dto.VersionArchiveDtos.ArchiveSummary;
import com.mobile.standalone.dto.VersionArchiveDtos.CloseRequest;
import com.mobile.standalone.dto.VersionArchiveDtos.CloseResult;
import com.mobile.standalone.dto.VersionArchiveDtos.IssueItem;
import com.mobile.standalone.dto.VersionArchiveDtos.SyncRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本需求归档：从 Wiki 汇总页快照 JIRA 单到本地，便于历史回查；
 * 上线日已过后可批量通过 JIRA transition 关单。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VersionArchiveService {

    private static final Pattern MD = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})");

    private final JdbcTemplate jdbc;
    private final ReleaseDocService releaseDocService;

    @PostConstruct
    public void ensureTables() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS release_version_archives (
                        id VARCHAR(64) PRIMARY KEY,
                        app_version VARCHAR(64) NOT NULL,
                        release_label VARCHAR(128) NOT NULL,
                        product_line VARCHAR(64) DEFAULT NULL,
                        release_date DATE DEFAULT NULL,
                        issue_count INT DEFAULT 0,
                        closed_count INT DEFAULT 0,
                        last_synced_at DATETIME DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_app_label (app_version, release_label)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS release_version_issues (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        archive_id VARCHAR(64) NOT NULL,
                        issue_key VARCHAR(32) NOT NULL,
                        summary VARCHAR(512) DEFAULT NULL,
                        issue_url VARCHAR(512) DEFAULT NULL,
                        status_name VARCHAR(64) DEFAULT NULL,
                        assignee VARCHAR(128) DEFAULT NULL,
                        qa_assignee VARCHAR(128) DEFAULT NULL,
                        closed_at DATETIME DEFAULT NULL,
                        close_error VARCHAR(512) DEFAULT NULL,
                        UNIQUE KEY uk_arch_key (archive_id, issue_key),
                        KEY idx_archive (archive_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[version-archive] 表已就绪");
        } catch (Exception e) {
            log.warn("[version-archive] 建表失败: {}", e.getMessage());
        }
    }

    public List<ArchiveSummary> list() {
        return jdbc.query("""
                SELECT * FROM release_version_archives
                ORDER BY release_date DESC, created_at DESC
                """, SUMMARY_MAPPER);
    }

    public ArchiveDetail get(String id) {
        List<ArchiveSummary> heads = jdbc.query(
                "SELECT * FROM release_version_archives WHERE id = ?", SUMMARY_MAPPER, id);
        if (heads.isEmpty()) {
            return null;
        }
        ArchiveDetail detail = toDetail(heads.get(0));
        detail.setIssues(jdbc.query(
                "SELECT * FROM release_version_issues WHERE archive_id = ? ORDER BY issue_key ASC",
                ISSUE_MAPPER, id));
        for (IssueItem it : detail.getIssues()) {
            it.setClosable(!isClosedStatus(it.getStatusName()) && it.getClosedAt() == null);
        }
        refreshCounts(detail);
        return detail;
    }

    @Transactional
    public ArchiveDetail sync(SyncRequest req) {
        if (req.getAppVersion() == null || req.getAppVersion().isBlank()) {
            throw new IllegalArgumentException("请填写 APP 版本（如 4.1.4）");
        }
        if (req.getReleaseLabel() == null || req.getReleaseLabel().isBlank()) {
            throw new IllegalArgumentException("请填写版本定位文案（如 8.6 国内客户端）");
        }
        String appVersion = req.getAppVersion().trim();
        String label = req.getReleaseLabel().trim();
        String productLine = req.getProductLine() != null && !req.getProductLine().isBlank()
                ? req.getProductLine().trim()
                : inferProductLine(label);
        LocalDate releaseDate = req.getReleaseDate() != null
                ? req.getReleaseDate()
                : parseReleaseDate(label);

        List<IssueRow> rows = releaseDocService.listIssuesForArchive(label);
        LocalDateTime now = LocalDateTime.now();

        String existingId = findId(appVersion, label);
        String id = existingId != null ? existingId : "rva-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        if (existingId == null) {
            jdbc.update("""
                    INSERT INTO release_version_archives
                    (id, app_version, release_label, product_line, release_date, issue_count, closed_count, last_synced_at, created_at, updated_at)
                    VALUES (?,?,?,?,?,0,0,?,?,?)
                    """,
                    id, appVersion, label, productLine,
                    releaseDate != null ? Date.valueOf(releaseDate) : null,
                    Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));
        } else {
            jdbc.update("""
                    UPDATE release_version_archives
                    SET product_line = ?, release_date = ?, last_synced_at = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    productLine,
                    releaseDate != null ? Date.valueOf(releaseDate) : null,
                    Timestamp.valueOf(now), Timestamp.valueOf(now), id);
        }

        int closed = 0;
        for (IssueRow row : rows) {
            boolean closedStatus = isClosedStatus(row.getStatusName());
            if (closedStatus) closed++;
            jdbc.update("""
                    INSERT INTO release_version_issues
                    (archive_id, issue_key, summary, issue_url, status_name, assignee, qa_assignee, closed_at, close_error)
                    VALUES (?,?,?,?,?,?,?,?,NULL)
                    ON DUPLICATE KEY UPDATE
                      summary = VALUES(summary),
                      issue_url = VALUES(issue_url),
                      status_name = VALUES(status_name),
                      assignee = VALUES(assignee),
                      qa_assignee = VALUES(qa_assignee),
                      closed_at = IF(VALUES(status_name) IS NOT NULL AND (
                        VALUES(status_name) LIKE '%关闭%' OR LOWER(VALUES(status_name)) IN ('closed','done')
                      ), COALESCE(closed_at, NOW()), closed_at),
                      close_error = NULL
                    """,
                    id,
                    row.getIssueKey(),
                    truncate(row.getSummary(), 500),
                    row.getIssueUrl(),
                    row.getStatusName(),
                    truncate(row.getAssignee(), 120),
                    truncate(row.getQaAssignee(), 120),
                    closedStatus ? Timestamp.valueOf(now) : null);
        }

        jdbc.update("""
                UPDATE release_version_archives
                SET issue_count = ?, closed_count = ?, updated_at = ?
                WHERE id = ?
                """, rows.size(), closed, Timestamp.valueOf(now), id);

        log.info("[version-archive] 已同步 {} / {} → {} 单", appVersion, label, rows.size());
        return get(id);
    }

    @Transactional
    /**
     * 手动建一个版本。
     * <p>
     * 归档原本只能从 Wiki 汇总页同步，但汇总页往往是<b>提测之后</b>才建的，
     * 而测试用例要在提测<b>之前</b>就开始写。只能等同步的话，这套东西每次都晚一步。
     */
    public ArchiveDetail createManual(SyncRequest req) {
        String label = req.getReleaseLabel() == null ? "" : req.getReleaseLabel().trim();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("版本名称不能为空，如「9.3 国内客户端」");
        }
        String appVersion = req.getAppVersion() == null || req.getAppVersion().isBlank()
                ? label : req.getAppVersion().trim();
        List<String> existing = jdbc.queryForList(
                "SELECT id FROM release_version_archives WHERE app_version = ? AND release_label = ?",
                String.class, appVersion, label);
        if (!existing.isEmpty()) {
            // 同名版本已存在就直接返回它，不报错——重复点一次「新建」不该变成一个错误弹窗
            return get(existing.get(0));
        }
        String id = "rva-" + UUID.randomUUID();
        jdbc.update("INSERT INTO release_version_archives "
                        + "(id, app_version, release_label, product_line, release_date, issue_count, closed_count) "
                        + "VALUES (?,?,?,?,?,0,0)",
                id, appVersion, label,
                blank(req.getProductLine()) ? inferProductLine(label) : req.getProductLine().trim(),
                req.getReleaseDate() != null ? java.sql.Date.valueOf(req.getReleaseDate()) : parseLabelDate(label));
        log.info("[version-archive] 手动创建版本 {} / {}", appVersion, label);
        return get(id);
    }

    /**
     * 往版本里手动加一个需求单。
     * <p>
     * 同一个单号重复加时更新标题，不插第二条——同一版里一个单号只该有一行。
     */
    public void addIssue(String archiveId, String issueKey, String summary,
                         String statusName, String assignee, String qaAssignee) {
        String key = issueKey == null ? "" : issueKey.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("单号不能为空，如 APP-8873");
        }
        if (jdbc.queryForList("SELECT id FROM release_version_archives WHERE id = ?", String.class, archiveId)
                .isEmpty()) {
            throw new IllegalArgumentException("版本不存在");
        }
        jdbc.update("INSERT INTO release_version_issues "
                        + "(archive_id, issue_key, summary, status_name, assignee, qa_assignee) "
                        + "VALUES (?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE summary = VALUES(summary), "
                        + "status_name = VALUES(status_name), assignee = VALUES(assignee), "
                        + "qa_assignee = VALUES(qa_assignee)",
                archiveId, key, blank(summary) ? null : summary.trim(),
                blank(statusName) ? null : statusName.trim(),
                blank(assignee) ? null : assignee.trim(),
                blank(qaAssignee) ? null : qaAssignee.trim());
        refreshCounts(archiveId);
    }

    public boolean removeIssue(String archiveId, String issueKey) {
        int n = jdbc.update("DELETE FROM release_version_issues WHERE archive_id = ? AND issue_key = ?",
                archiveId, issueKey);
        if (n > 0) {
            refreshCounts(archiveId);
        }
        return n > 0;
    }

    private void refreshCounts(String archiveId) {
        try {
            jdbc.update("UPDATE release_version_archives a SET "
                            + "issue_count = (SELECT COUNT(1) FROM release_version_issues i WHERE i.archive_id = a.id), "
                            + "closed_count = (SELECT COUNT(1) FROM release_version_issues i "
                            + "WHERE i.archive_id = a.id AND i.closed_at IS NOT NULL) WHERE a.id = ?",
                    archiveId);
        } catch (Exception e) {
            log.debug("[version-archive] 计数刷新跳过: {}", e.getMessage());
        }
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 从「9.3 国内客户端」这种名字里解析上线日；解析不出来就留空，不猜 */
    private java.sql.Date parseLabelDate(String label) {
        Matcher m = MD.matcher(label == null ? "" : label);
        if (!m.find()) {
            return null;
        }
        try {
            return java.sql.Date.valueOf(java.time.LocalDate.of(
                    java.time.LocalDate.now().getYear(),
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        } catch (Exception e) {
            return null;
        }
    }

    public CloseResult closeReleased(String archiveId, CloseRequest req) {
        ArchiveDetail detail = get(archiveId);
        if (detail == null) {
            throw new IllegalArgumentException("归档不存在: " + archiveId);
        }
        boolean dryRun = Boolean.TRUE.equals(req.getDryRun()) || !Boolean.TRUE.equals(req.getConfirm());

        if (!detail.isReleased() && !dryRun) {
            throw new IllegalStateException("上线日尚未到（" + detail.getReleaseDate() + "），不允许批量关单；可先 dryRun 预览");
        }

        Set<String> only = new HashSet<>();
        if (req.getIssueKeys() != null) {
            for (String k : req.getIssueKeys()) {
                if (k != null && !k.isBlank()) only.add(k.trim().toUpperCase(Locale.ROOT));
            }
        }

        List<IssueItem> targets = new ArrayList<>();
        for (IssueItem it : detail.getIssues()) {
            if (!only.isEmpty() && !only.contains(it.getIssueKey().toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (!it.isClosable()) {
                continue;
            }
            targets.add(it);
        }

        CloseResult result = new CloseResult();
        result.setDryRun(dryRun);
        result.setPreview(targets);
        result.setAttempted(targets.size());
        String comment = req.getComment() != null && !req.getComment().isBlank()
                ? req.getComment().trim()
                : "版本「" + detail.getReleaseLabel() + "」已于 "
                + detail.getReleaseDate() + " 上线，由 QA 平台批量关闭";

        if (dryRun) {
            result.getLogs().add("预览模式：将关闭 " + targets.size() + " 单（未实际调用 JIRA）");
            return result;
        }

        int closed = 0;
        int failed = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();
        for (IssueItem it : targets) {
            try {
                String transition = releaseDocService.closeIssue(it.getIssueKey(), comment);
                jdbc.update("""
                        UPDATE release_version_issues
                        SET status_name = '已关闭', closed_at = ?, close_error = NULL
                        WHERE archive_id = ? AND issue_key = ?
                        """, Timestamp.valueOf(now), archiveId, it.getIssueKey());
                closed++;
                result.getLogs().add("已关闭 " + it.getIssueKey() + "（" + transition + "）");
            } catch (Exception e) {
                failed++;
                String err = truncate(e.getMessage(), 500);
                jdbc.update("""
                        UPDATE release_version_issues SET close_error = ? WHERE archive_id = ? AND issue_key = ?
                        """, err, archiveId, it.getIssueKey());
                result.getLogs().add("失败 " + it.getIssueKey() + ": " + err);
            }
        }
        result.setClosed(closed);
        result.setFailed(failed);
        result.setSkipped(skipped);

        Integer closedCount = jdbc.queryForObject("""
                SELECT COUNT(1) FROM release_version_issues
                WHERE archive_id = ? AND (closed_at IS NOT NULL
                  OR status_name LIKE '%关闭%' OR LOWER(status_name) IN ('closed','done'))
                """, Integer.class, archiveId);
        jdbc.update("UPDATE release_version_archives SET closed_count = ?, updated_at = ? WHERE id = ?",
                closedCount != null ? closedCount : 0, Timestamp.valueOf(now), archiveId);

        return result;
    }

    @Transactional
    public boolean delete(String id) {
        int n = jdbc.update("DELETE FROM release_version_archives WHERE id = ?", id);
        jdbc.update("DELETE FROM release_version_issues WHERE archive_id = ?", id);
        return n > 0;
    }

    /** 重新打开到 Testing 并分配；同时刷新本地归档行状态 */
    public String reopenAndAssign(String issueKey, String username, String comment) {
        String msg = releaseDocService.reopenAndAssign(issueKey, username, comment);
        try {
            jdbc.update("""
                    UPDATE release_version_issues
                    SET status_name = 'Testing',
                        assignee = ?,
                        qa_assignee = ?,
                        closed_at = NULL,
                        close_error = NULL
                    WHERE issue_key = ?
                    """, username, username, issueKey.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            log.warn("[version-archive] 本地状态刷新失败: {}", e.getMessage());
        }
        return msg;
    }

    public List<String> reopenAndAssignBatch(List<String> issueKeys, String username, String comment) {
        if (issueKeys == null || issueKeys.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个单");
        }
        List<String> logs = new ArrayList<>();
        for (String key : issueKeys) {
            if (key == null || key.isBlank()) continue;
            try {
                String msg = reopenAndAssign(key.trim(), username, comment);
                logs.add("成功 " + key.trim().toUpperCase(Locale.ROOT) + ": " + msg);
            } catch (Exception e) {
                logs.add("失败 " + key.trim().toUpperCase(Locale.ROOT) + ": " + e.getMessage());
            }
        }
        return logs;
    }

    static LocalDate parseReleaseDate(String label) {
        if (label == null) return null;
        Matcher m = MD.matcher(label);
        if (!m.find()) return null;
        int month = Integer.parseInt(m.group(1));
        int day = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        int year = LocalDate.now().getYear();
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    static String inferProductLine(String label) {
        if (label == null) return "";
        if (label.contains("海外") || label.toLowerCase(Locale.ROOT).contains("pog")) {
            return "海外客户端";
        }
        if (label.contains("国内") || label.contains("电竞")) {
            return "国内客户端";
        }
        if (label.toUpperCase(Locale.ROOT).contains("H5")) {
            return "H5";
        }
        return "";
    }

    static boolean isClosedStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.trim().toLowerCase(Locale.ROOT);
        return status.contains("已关闭")
                || s.equals("closed")
                || s.equals("done")
                || (status.contains("关闭") && !status.contains("未关闭"));
    }

    private String findId(String appVersion, String label) {
        List<String> ids = jdbc.query(
                "SELECT id FROM release_version_archives WHERE app_version = ? AND release_label = ?",
                (rs, i) -> rs.getString(1), appVersion, label);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void refreshCounts(ArchiveDetail detail) {
        int open = 0;
        int closed = 0;
        for (IssueItem it : detail.getIssues()) {
            if (isClosedStatus(it.getStatusName()) || it.getClosedAt() != null) {
                closed++;
                it.setClosable(false);
            } else {
                open++;
                it.setClosable(true);
            }
        }
        detail.setIssueCount(detail.getIssues().size());
        detail.setOpenCount(open);
        detail.setClosedCount(closed);
        detail.setReleased(detail.getReleaseDate() != null
                && !detail.getReleaseDate().isAfter(LocalDate.now()));
    }

    private ArchiveDetail toDetail(ArchiveSummary s) {
        ArchiveDetail d = new ArchiveDetail();
        d.setId(s.getId());
        d.setAppVersion(s.getAppVersion());
        d.setReleaseLabel(s.getReleaseLabel());
        d.setProductLine(s.getProductLine());
        d.setReleaseDate(s.getReleaseDate());
        d.setReleased(s.isReleased());
        d.setIssueCount(s.getIssueCount());
        d.setOpenCount(s.getOpenCount());
        d.setClosedCount(s.getClosedCount());
        d.setLastSyncedAt(s.getLastSyncedAt());
        d.setCreatedAt(s.getCreatedAt());
        return d;
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }

    private final RowMapper<ArchiveSummary> SUMMARY_MAPPER = (rs, i) -> {
        ArchiveSummary s = new ArchiveSummary();
        s.setId(rs.getString("id"));
        s.setAppVersion(rs.getString("app_version"));
        s.setReleaseLabel(rs.getString("release_label"));
        s.setProductLine(rs.getString("product_line"));
        Date d = rs.getDate("release_date");
        s.setReleaseDate(d != null ? d.toLocalDate() : null);
        s.setReleased(s.getReleaseDate() != null && !s.getReleaseDate().isAfter(LocalDate.now()));
        s.setIssueCount(rs.getInt("issue_count"));
        s.setClosedCount(rs.getInt("closed_count"));
        s.setOpenCount(Math.max(0, s.getIssueCount() - s.getClosedCount()));
        Timestamp synced = rs.getTimestamp("last_synced_at");
        s.setLastSyncedAt(synced != null ? synced.toLocalDateTime() : null);
        Timestamp created = rs.getTimestamp("created_at");
        s.setCreatedAt(created != null ? created.toLocalDateTime() : null);
        return s;
    };

    private final RowMapper<IssueItem> ISSUE_MAPPER = (rs, i) -> {
        IssueItem it = new IssueItem();
        it.setId(rs.getLong("id"));
        it.setIssueKey(rs.getString("issue_key"));
        it.setSummary(rs.getString("summary"));
        it.setIssueUrl(rs.getString("issue_url"));
        it.setStatusName(rs.getString("status_name"));
        it.setAssignee(rs.getString("assignee"));
        it.setQaAssignee(rs.getString("qa_assignee"));
        Timestamp closed = rs.getTimestamp("closed_at");
        it.setClosedAt(closed != null ? closed.toLocalDateTime() : null);
        it.setCloseError(rs.getString("close_error"));
        return it;
    };
}
