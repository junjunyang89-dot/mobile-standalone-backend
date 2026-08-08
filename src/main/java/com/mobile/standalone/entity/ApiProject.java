package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口相关项目（集中管理；URL / 接口用例归属此编码）。
 */
@Data
public class ApiProject {
    /** 编码，如 data-center */
    private String code;
    /** 展示名 */
    private String name;
    private String description;
    /** 本地 git 仓库绝对路径；填了才能做接口变更分析 */
    private String repoPath;
    /** 对比基线分支，留空按 master */
    private String baseBranch;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
