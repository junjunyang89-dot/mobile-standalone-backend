package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiTestRun;
import com.mobile.standalone.entity.ApiTestRunCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ApiTestRunMapper {

    int insertRun(ApiTestRun row);

    int insertRunCase(ApiTestRunCase row);

    /**
     * 一次运行的结果按 {@code case_kind} 分组计数。
     * <p>
     * 返回 {@code [{caseKind, total, passed, blocked}]}，
     * 其中 {@code caseKind} 可能为 null（没记录的历史行）。
     * <b>分桶不在这里做</b>——交给
     * {@link com.mobile.standalone.service.apitest.RunPassRateBuckets#fold}，
     * 那样分桶规则只有一份实现。
     */
    List<java.util.Map<String, Object>> countByCaseKind(@Param("runId") String runId);

    List<ApiTestRunCase> findRecentCases(@Param("limit") int limit);

    /** 报告页的执行历史列表 */
    List<Map<String, Object>> findRuns(@Param("collectionId") String collectionId,
                                       @Param("limit") int limit);

    /** 报告浏览：带上集合来源与项目 */
    List<Map<String, Object>> findRunsEnriched(@Param("limit") int limit);

    /** 每条执行涉及哪些接口（去重） */
    List<Map<String, Object>> findRunInterfaces();

    int deleteRunCases(@Param("runId") String runId);

    int deleteRun(@Param("runId") String runId);

    int deleteRuns(@Param("ids") List<String> ids);

    int deleteRunCasesByRunIds(@Param("ids") List<String> ids);

    ApiTestRun findRunById(@Param("id") String id);

    List<ApiTestRunCase> findCasesByRun(@Param("runId") String runId);

    /** 近 N 天按天聚合，给趋势图 */
    List<Map<String, Object>> dailyTrend(@Param("since") LocalDateTime since);

    long countCasesCreatedSince(@Param("since") LocalDateTime since);

    /** status 聚合：period 内 case 结果 */
    Map<String, Object> aggregateCaseResults(
            @Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until
    );

    List<Map<String, Object>> aggregateByProject(@Param("since") LocalDateTime since);
}
