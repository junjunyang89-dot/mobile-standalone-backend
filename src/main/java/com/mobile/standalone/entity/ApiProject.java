package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口相关项目（集中管理；URL / 接口用例归属此编码）。
 */
@Data
public class ApiProject {
    /** BACKEND=后端仓库；FRONTEND=H5/前端站；CLIENT=客户端（后两者不扫接口） */
    public static final String KIND_BACKEND = "BACKEND";
    public static final String KIND_FRONTEND = "FRONTEND";
    public static final String KIND_CLIENT = "CLIENT";

    /** 编码，如 data-center */
    private String code;
    /** 展示名 */
    private String name;
    private String description;
    /** BACKEND / FRONTEND / CLIENT；留空按 BACKEND */
    private String kind;
    /** 所属分类：APP / PVP / WEB / OTHER；留空则按编码自动归类 */
    private String category;
    /** GitLab 仓库地址（clone 用的那个）；本地仓库没配时按它 clone */
    private String gitUrl;
    /** 本地 git 仓库绝对路径；填了才能做接口变更分析 */
    private String repoPath;
    /** 对比基线分支，留空按 master */
    private String baseBranch;
    /**
     * 业务成功码在响应里的位置，如 {@code $.code}。
     * <p>
     * 这几个后端统一用 {@code ResponseResult{code,message,result}} 包一层，<b>业务失败也是 HTTP 200</b>。
     * 配上之后 Runner 会给每条用例自动补一条业务码断言，否则只断言 status=200 的用例形同虚设。
     */
    private String successCodePath;
    /** 业务成功码的值，如 0 */
    private String successCodeValue;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
