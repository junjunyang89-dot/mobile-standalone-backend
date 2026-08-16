package com.mobile.standalone.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class VersionArchiveDtos {
    private VersionArchiveDtos() {}

    @Data
    public static class SyncRequest {
        /** APP 版本号，如 4.1.4 */
        private String appVersion;
        /** 与 Wiki 汇总页标题一致，如 8.6 国内客户端 */
        private String releaseLabel;
        /** 可选：国内客户端 / 海外客户端；空则从 label 推断 */
        private String productLine;
        /** 可选：上线日；空则从 label 解析 M.D */
        private LocalDate releaseDate;
    }

    @Data
    public static class ArchiveSummary {
        private String id;
        private String appVersion;
        private String releaseLabel;
        private String productLine;
        private LocalDate releaseDate;
        private boolean released;
        private int issueCount;
        private int openCount;
        private int closedCount;
        private LocalDateTime lastSyncedAt;
        private LocalDateTime createdAt;
    }

    @Data
    public static class ArchiveDetail extends ArchiveSummary {
        private List<IssueItem> issues = new ArrayList<>();
    }

    @Data
    public static class IssueItem {
        private Long id;
        private String issueKey;
        private String summary;
        private String issueUrl;
        private String statusName;
        private String assignee;
        private String qaAssignee;
        private boolean closable;
        private LocalDateTime closedAt;
        private String closeError;
    }

    @Data
    public static class CloseRequest {
        /** true=只预览将关闭哪些，不真正关 */
        private Boolean dryRun;
        /** 真正关闭时必须 true，防止误触 */
        private Boolean confirm;
        /** 只关这些 key；空=关本版所有未关闭单 */
        private List<String> issueKeys;
        private String comment;
    }

    @Data
    public static class CloseResult {
        private boolean dryRun;
        private int attempted;
        private int closed;
        private int skipped;
        private int failed;
        private List<String> logs = new ArrayList<>();
        private List<IssueItem> preview = new ArrayList<>();
    }
}
