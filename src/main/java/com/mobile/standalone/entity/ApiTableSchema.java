package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一张表的建表语句与测试注意事项。
 * <p>
 * 之所以要人工录入：项目代码里一个 .sql 文件都没有（实测 data-center 为 0），
 * 建表语句只在数据库里。录一次之后，每次分析都能自动给出该表的测试注意事项。
 */
@Data
public class ApiTableSchema {
    private Long id;
    /** 所属接口项目 code；留空表示全局通用 */
    private String project;
    private String tableName;
    /** 从数据库导出后原样粘贴的建表语句 */
    private String ddl;
    /** 人工补充的注意事项，跟自动推导的一起展示 */
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
