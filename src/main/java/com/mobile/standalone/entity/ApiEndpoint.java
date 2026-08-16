package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口清单里的一条——<b>master 上真实存在的一个接口</b>。
 * <p>
 * 和用例是两回事，这一点之前混了：早先从代码扫出来的 257 条直接落进了用例表，
 * 每条只有一个 {@code status=200} 断言。那不是用例，是接口目录——
 * 一个接口天然可以有多条用例（正常 / 异常 / 边界），1:1 之后就没地方放了，
 * 而且「跑通」只说明接口活着，不说明它对。
 * <p>
 * 拆开之后：清单回答「有哪些接口、覆盖到没有」，用例回答「这个接口对不对」。
 */
@Data
public class ApiEndpoint {

    private String id;
    /** 归属项目，与 api_projects.code 对应 */
    private String project;
    private String httpMethod;
    private String path;
    /** Controller 方法，如 {@code AiController.aiTranslate} */
    private String handler;
    /** 源码文件相对路径，定位问题时直接跳 */
    private String sourceFile;
    /** javadoc / @ApiOperation 里的中文说明 */
    private String summary;

    /**
     * ACTIVE＝master 上有；<b>DEPRECATED</b>＝同步时发现 master 上没了。
     * <p>
     * 不直接删：删掉的话挂在它下面的用例会变成孤儿，而「这个接口被删了」本身
     * 就是要确认的事——可能是有意下线，也可能是分支合错了。
     */
    private String status;

    /**
     * 本次迭代相对 master 的变化：{@code NEW}（新增）/ {@code FIELD_CHANGED}（字段增删改）/
     * {@code PARAM_CHANGED}（入参变了）/ 空（没变）。
     * <p>
     * 由「接口对比分析」同步过来。你说的「老接口有新增或删除字段、不用必须测」就靠它——
     * 打上标记进清单，回头按这个状态筛出来补用例，而不是当场逼着写。
     */
    private String changeFlag;
    /** 引入这次变更的分支，如 {@code fch/20260804/APP-8928} */
    private String sourceBranch;
    /** 变更的简要说明，如「新增字段 winRate、删除 maxNetWorth」 */
    private String changeNote;

    /** 返回体字段快照（JSON），用来判断已有用例的断言是不是过期了 */
    private String fieldsSnapshot;

    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 查询时拼上的统计，不落库 ----
    /** 这个接口下有多少条用例 */
    private Integer caseCount;
}
