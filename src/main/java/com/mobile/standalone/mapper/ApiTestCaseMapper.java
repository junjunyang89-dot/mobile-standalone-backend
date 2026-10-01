package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiTestCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiTestCaseMapper {
    /**
     * 这个签名已经太长了：调用点长成
     * {@code findByFilters(project, null, null, null, null, null, null, null)}，
     * 没人看得出哪个 null 是什么。<b>下一个要加筛选条件的人，请先把它换成一个
     * filter 对象</b>（{@code caseKind} 是最后一个该用位置参数加进来的）。
     * <p>
     * 这次还是加参数而不是顺手重构：改签名要动 6 个调用点，和本次改动混在一起，
     * 出问题时分不清是哪一边引起的。
     *
     * @param caseKind 可空；BRANCH / REGRESSION / UNCLASSIFIED，
     *                 见 {@link com.mobile.standalone.service.apitest.ApiCaseKind}
     */
    List<ApiTestCase> findByFilters(
            @Param("project") String project,
            @Param("collectionId") String collectionId,
            @Param("environment") String environment,
            @Param("enabledOnly") Boolean enabledOnly,
            @Param("keyword") String keyword,
            @Param("caseKind") String caseKind,
            @Param("offset") Integer offset,
            @Param("limit") Integer limit
    );

    /** 条件必须和 {@link #findByFilters} 完全一致，否则分页的总数是错的 */
    long countByFilters(
            @Param("project") String project,
            @Param("collectionId") String collectionId,
            @Param("environment") String environment,
            @Param("enabledOnly") Boolean enabledOnly,
            @Param("keyword") String keyword,
            @Param("caseKind") String caseKind
    );

    ApiTestCase findById(@Param("id") String id);

    int insert(ApiTestCase row);

    int update(ApiTestCase row);

    int deleteById(@Param("id") String id);

    long countAll();

    long countByProject(@Param("project") String project);

    List<String> findDistinctProjects();

    /** 归组用：拿一个项目下的全部用例（不分页） */
    List<ApiTestCase> findByProject(@Param("project") String project);

    /** 挂在这个接口上的用例——「这条接口还差什么」要逐条看用例内容才能算 */
    List<ApiTestCase> findByEndpointId(@Param("endpointId") String endpointId);

    /**
     * 分支用例按来源分支分组计数——待分诊队列的入口。
     * <p>
     * 返回 {@code [{sourceBranch, cnt}]}。<b>单独一个方法是因为形状不同</b>：
     * 它要的是分组计数，不是用例列表。按种类筛选用例走
     * {@link #findByFilters} 的 {@code caseKind} 参数，分页在那边。
     * <p>
     * 只要计数不要内容：分诊的第一步是「哪些分支还挂着用例」，
     * 一个项目上千条用例全查出来只为数个数，列表页会卡。
     */
    List<java.util.Map<String, Object>> countBranchCasesByBranch(
            @Param("project") String project);
}
