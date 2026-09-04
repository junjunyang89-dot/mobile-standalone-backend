package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 需求登记。
 * <p>
 * 单号来自 JIRA，是<b>外部系统的事实</b>——这里只登记不同步，平台不做它的主数据。
 * 建这张表是为了回答原先答不了的问题：<b>这个需求覆盖了哪些模块、属于哪个版本、
 * 测到什么程度</b>。在此之前 {@code requirement_no} 只是散在
 * {@code api_test_collections} / {@code requirement_endpoints} / {@code requirement_bugs}
 * 三张表里的字符串。
 */
@Data
public class Requirement {

    public static final String STATUS_PLANNING = "PLANNING";
    public static final String STATUS_TESTING = "TESTING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** JIRA 单号，如 APP-8999 */
    private String requirementNo;

    private String title;

    /** 主责项目；一个需求可能横跨多个项目，这里记主要那个 */
    private String projectCode;

    /** 归属版本，对应 {@code release_version_archives.id} */
    private String versionId;

    /**
     * 开发分支，如 {@code fch/20260804/APP-8928}。
     * <p>
     * 记在需求上而不是集合上：一个需求对应一个分支，而一个需求可能有多个集合，
     * 记在集合上等于同一个事实存好几份。分支名里本来就带着单号，
     * 接口对比分析时 {@code BranchIssueKey} 靠它自动把变更接口挂到需求上。
     */
    private String sourceBranch;

    /** 分支合并到 master 的时间；「本单上线」时写入。空＝尚未上线 */
    private LocalDateTime mergedAt;

    private String status;
    private String owner;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 关联查询回填，非表字段 ----

    private transient String projectName;
    /** 版本对外叫法，如「9.3 国内客户端」 */
    private transient String versionLabel;
    /** 覆盖的模块。一个需求经常横跨几个模块，所以是多对多 */
    private transient List<ProjectModule> modules = new ArrayList<>();
}
