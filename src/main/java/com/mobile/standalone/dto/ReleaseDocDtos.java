package com.mobile.standalone.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

public final class ReleaseDocDtos {
    private ReleaseDocDtos() {}

    @Data
    public static class GenerateRequest {
        /** 发版文档标题，如「7月13日 PogPog1.2.9客户端版本」 */
        private String title;
        /** 版本定位文案，如「7.20 海外H5」 */
        private String version;
        /** true=只预览抽取结果不建 Wiki 页 */
        private Boolean previewOnly;
        /**
         * 可选：前端预览后人工改过的行。生成 Wiki 时若传入则直接用，不再重新拉 JIRA。
         */
        private List<IssueRow> rows;
    }

    @Data
    public static class IssueRow {
        private String issueKey;
        private String summary;
        private String issueUrl;
        private String branches;
        private String dbChanges;
        private String configChanges;
        private String qaAssignee;
        private String remarks;
    }

    @Data
    public static class GenerateResult {
        private boolean previewOnly;
        private String title;
        private String version;
        private String wikiPageId;
        private String wikiPageUrl;
        private String message;
        private List<IssueRow> rows = new ArrayList<>();
        private List<String> logs = new ArrayList<>();
    }

    @Data
    public static class MailDraftRequest {
        /** 发版文档标题（新建 Wiki 标题） */
        private String title;
        /** 新建 Wiki 完整 URL */
        private String wikiPageUrl;
        /** 校对后的表格行；有则优先用，避免再拉 Wiki */
        private List<IssueRow> rows;
    }

    @Data
    public static class MailDraftResult {
        private String subject;
        private String htmlBody;
        private boolean hasDb;
        private String datePhrase;
        private String readyPhrase;
        private String defaultTo;
        private String wikiPageUrl;
        private String message;
    }

    @Data
    public static class SendMailRequest {
        private String to;
        private String cc;
        private String subject;
        private String htmlBody;
    }

    @Data
    public static class SendMailResult {
        private boolean ok;
        private String message;
    }
}
