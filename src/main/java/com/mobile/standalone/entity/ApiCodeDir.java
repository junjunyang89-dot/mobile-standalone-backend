package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一份本地代码目录（通常对应一个分支的快照）。
 * <p>
 * 远程办公拿不到内网、切不了分支，只能一个分支拷一份目录。路径存下来，
 * 分析时从下拉里选，不用每次手打。
 */
@Data
public class ApiCodeDir {
    private Long id;
    private String project;
    /** 别名，如 master / APP-8928 */
    private String label;
    private String dirPath;
    /** 1=默认作为基线 */
    private Boolean isBaseline;
    private String remark;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
