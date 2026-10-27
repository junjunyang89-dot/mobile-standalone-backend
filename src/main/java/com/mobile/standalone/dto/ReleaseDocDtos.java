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
        private String statusName;
        private String assignee;
        private String branches;
        private String dbChanges;
        private String configChanges;
        private String qaAssignee;
        private String remarks;
        /** 标题/备注命中「不跟版本」等话术，默认不进表格，等人工确认 */
        private Boolean deferredCandidate;
        /** 命中话术摘录，弹窗里展示给 QA 看 */
        private String deferredReason;
        /** 从 Wiki 读回时，所在表格上方的小标题（一页多张表时用来分组，如「鸿蒙224单子」） */
        private String section;
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
        /** 疑似不跟本版本的单，前端弹窗让人选是否纳入 */
        private List<IssueRow> deferredCandidates = new ArrayList<>();
        private List<String> logs = new ArrayList<>();
    }

    @Data
    public static class MailDraftRequest {
        /** 发版文档标题（新建 Wiki 标题） */
        private String title;
        /** 新建 Wiki 完整 URL；有则始终从 Wiki 拉最新表格 */
        private String wikiPageUrl;
        /** 仅 Wiki 不可用时的回退；正常流程以 Wiki 为准 */
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
        /** 无 Wiki 刷新时的正文；有 title+wikiPageUrl 时发送前会用 Wiki 最新内容重装 */
        private String htmlBody;
        /** 与 wikiPageUrl 一起传：发送前再拉一次 Wiki，避免草稿过期 */
        private String title;
        private String wikiPageUrl;
    }

    @Data
    public static class SendMailResult {
        private boolean ok;
        private String message;
        /** 实际发出去的正文（可能已按 Wiki 刷新） */
        private String htmlBody;
    }
}
