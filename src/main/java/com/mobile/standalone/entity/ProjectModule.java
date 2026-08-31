package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 功能模块。
 * <p>
 * 挂在<b>分类（产品线）</b>而不是每个项目下：实测模块值全是客户端功能
 * （数据壳／赛事壳／我的个人页），{@code app-seckill} 这种后端项目根本没有「数据壳」。
 * 挂到项目下的话，{@code app-esports-ios} 与 {@code app-esports-android} 会各维护
 * 一份完全重复的模块树——两端功能本来就一样。
 * <p>
 * 它要替换的是散在各处的自由文本：{@code ui_elements.module_tag}、
 * {@code ui_pages.module_tag}、{@code ai_test_cases.module}。那些字段已经漂成
 * 「数据壳」「DOTA2 个人数据壳」「异锁 / Deadlock 数据壳」这样——层级压在一个
 * 字段里、斜杠拼复合名、混进 {@code inspector} 这种工具名。
 */
@Data
public class ProjectModule {

    private String id;

    /** 归属产品线，对应 {@code api_project_categories.cat_key} */
    private String category;

    /** 父模块；null 表示顶层 */
    private String parentId;

    private String name;
    private String description;
    private Integer sortOrder;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 组装成树时回填，非表字段 */
    private transient List<ProjectModule> children = new ArrayList<>();

    /** 有多少需求覆盖了这个模块——树上直接看得见哪些模块没人测 */
    private transient Integer requirementCount;
}
