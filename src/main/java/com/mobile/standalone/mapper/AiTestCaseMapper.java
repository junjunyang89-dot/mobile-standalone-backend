package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.AiTestCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiTestCaseMapper {

    AiTestCase findById(@Param("id") Long id);

    List<AiTestCase> findByFilters(
            @Param("category") String category,
            @Param("pageName") String pageName,
            @Param("priority") String priority,
            @Param("status") String status,
            @Param("platform") String platform,
            @Param("batchId") String batchId,
            @Param("automationAdvice") String automationAdvice,
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
            @Param("automationAdvice") String automationAdvice
    );

    int insert(AiTestCase row);

    int update(AiTestCase row);

    int deleteById(@Param("id") Long id);
}
