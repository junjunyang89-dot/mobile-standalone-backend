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
    /** 客户端实际调的路径（抓包到的，带网关前缀）；用例按它发请求 */
    private String path;
    /**
     * 源码 Controller 里声明的路径，不带网关前缀。源码扫描、对比分析按它认接口——
     * 只按 {@code path} 认的话，抓包的 {@code /steamcn/a/b} 和代码的 {@code /a/b} 会变成两条。
     */
    private String codePath;
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

    /** 抓包解析出的样本：顶层字段、样本值、请求预览 */
    private String captureMeta;

    /** 接口文档导入的说明、依赖等——仅参考，不参与断言 */
    private String docMeta;

    /**
     * 涉及的表，JSON 数组。来自对比分析的依赖解析（Controller→Service→Mapper）。
     * 存下来才能做「表变→反查接口→反查用例」，也让清单能展开看接口动了哪些表。
     */
    private String tablesJson;

    /**
     * 要不要登录：源码里方法或类上挂了 {@code @Auth} 这类注解为 true；
     * 仓库用注解鉴权但这个接口没挂为 false；空＝看不出来（走拦截器的仓库）
     */
    private Boolean authRequired;

    /** 客户端 / H5 / 其他服务里调它的位置（JSON 数组），全量扫描时顺带搜出来 */
    private String callersJson;
    /**
     * {@code USED} 有人调 / {@code DEF_ONLY} 只在 API 封装里写了路径、封装没人用 /
     * {@code NONE} 哪都没找到 / 空＝没搜过。后两种多半已下线，抓包也抓不到。
     */
    private String callerStatus;
    private LocalDateTime callersScannedAt;

    /** 人工标记废弃的时间，空＝没标。标了就不再生成用例、不算未覆盖 */
    private LocalDateTime retiredAt;
    private String retiredBy;

    /** 人工定的自动化级别（CORE/IMPORTANT/NORMAL/UNUSED），空＝按调用方自动算 */
    private String levelManual;
    private String levelSetBy;

    /** 按基线分支全量扫描时代码里已经没有了：什么时候发现的、在哪个分支 */
    private LocalDateTime goneAt;
    private String goneRef;

    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 查询时拼上的统计，不落库 ----
    /** 这个接口下有多少条用例 */
    private Integer caseCount;
    /**
     * 其中真正验了返回字段的有几条。
     * <p>
     * 和 {@link #caseCount} 分开统计，是因为「有用例」和「验过了」不是一回事：
     * 只断言 HTTP 200 的用例，在接口返回 {@code {"code":500}} 时照样是绿的。
     */
    private Integer strongCaseCount;
    /**
     * 其中<b>启用中</b>的有几条。
     * <p>
     * 停用的用例什么都不验——有人把一条不稳定的用例停掉，清单如果还算它覆盖，
     * 就会立刻变绿，而实际上这个接口从此没人验了。
     */
    private Integer enabledCaseCount;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isRetired() {
        return retiredAt != null;
    }
}
