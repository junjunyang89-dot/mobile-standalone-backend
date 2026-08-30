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
            @Param("appVersion") String appVersion,
            @Param("requirementNo") String requirementNo,
            @Param("side") String side,
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
            @Param("appVersion") String appVersion,
            @Param("requirementNo") String requirementNo,
            @Param("side") String side,
            @Param("keyword") String keyword
    );

    /** 这个集合下出现过哪些版本，以及各自多少条。版本下拉用 */
    List<java.util.Map<String, Object>> distinctVersions(@Param("category") String category);

    /** 某一版下每个单号有多少条用例。「这个单子测完了没有」靠它回答 */
    List<java.util.Map<String, Object>> countByRequirement(
            @Param("category") String category, @Param("appVersion") String appVersion);

    int insert(AiTestCase row);

    int update(AiTestCase row);

    int deleteById(@Param("id") Long id);
}
