package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.AiTestCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiTestCaseMapper {

    AiTestCase findById(@Param("id") Long id);

    /** 挂在某条流程上的用例 */
    List<AiTestCase> findByFlowId(@Param("flowId") String flowId);

    /**
     * 可以挂到这条流程上的用例：同平台、且还没被别的流程占用（已挂本流程的也算）。
     * 一条流程覆盖多条用例是常态，所以不限制数量。
     */
    List<AiTestCase> findLinkable(@Param("flowId") String flowId,
                                  @Param("platform") String platform,
                                  @Param("keyword") String keyword);

    List<AiTestCase> findByFilters(
            @Param("category") String category,
            @Param("pageName") String pageName,
            @Param("priority") String priority,
            @Param("status") String status,
            @Param("platform") String platform,
            @Param("batchId") String batchId,
            @Param("automationAdvice") String automationAdvice,
            @Param("releaseStage") String releaseStage,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    int countByFilters(
            @Param("category") String category,
            @Param("pageName") String pageName,
            @Param("priority") String priority,
            @Param("status") String status,
            @Param("platform") String platform,
            @Param("batchId") String batchId,
            @Param("automationAdvice") String automationAdvice,
            @Param("releaseStage") String releaseStage,
            @Param("keyword") String keyword
    );

    int insert(AiTestCase row);

    int update(AiTestCase row);

    int deleteById(@Param("id") Long id);
}
