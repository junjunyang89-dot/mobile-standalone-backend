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

    List<ApiTestRunCase> findRecentCases(@Param("limit") int limit);

    long countCasesCreatedSince(@Param("since") LocalDateTime since);

    /** status 聚合：period 内 case 结果 */
    Map<String, Object> aggregateCaseResults(
            @Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until
    );

    List<Map<String, Object>> aggregateByProject(@Param("since") LocalDateTime since);
}
