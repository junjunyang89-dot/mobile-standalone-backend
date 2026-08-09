package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiTestRun;
import com.mobile.standalone.entity.ApiTestRunCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Map;

@Mapper
public interface ApiTestRunMapper {

    int insertRun(ApiTestRun row);

    int insertRunCase(ApiTestRunCase row);

    List<ApiTestRunCase> findRecentCases(@Param("limit") int limit);

    /** 报告页的执行历史列表 */
    List<Map<String, Object>> findRuns(@Param("collectionId") String collectionId,
                                       @Param("limit") int limit);

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
