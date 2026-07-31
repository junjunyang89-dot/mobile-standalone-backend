package com.mobile.standalone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "release-doc")
public class ReleaseDocProperties {
    private boolean enabled = true;
    private String wikiBaseUrl = "https://wiki.shdev.net";
    private String jiraBaseUrl = "";
    private String username = "";
    private String password = "";
    private String parentPageId = "112535757";
    private String issueListPageId = "68892294";
    private List<String> branchKeywords = new ArrayList<>();
    /**
     * 发版表格按 QA 排序（同一个人的单子集中在一起，便于检查）。
     * 可写 Jira 用户名或中文名，匹配时忽略大小写、支持包含关系。
     */
    private List<String> qaSortOrder = new ArrayList<>();
    /**
     * 仅统计这些 Jira 状态的单子（匹配 status.name，忽略大小写）。
     * 默认：已解决、TESTING；已关闭、开放等不在名单内则跳过。
     */
    private List<String> includeStatuses = new ArrayList<>(List.of("已解决", "TESTING"));
    /** 发版邮件 SMTP（Exchange / OWA 同账号） */
    private Mail mail = new Mail();

    @Data
    public static class Mail {
        private String host = "mail.mx.wanmei.net";
        private int port = 587;
        private String username = "";
        private String password = "";
        private String from = "";
        private String defaultTo = "";
        private boolean smtpAuth = true;
        private boolean starttls = true;
        private boolean ssl = false;
    }
}
