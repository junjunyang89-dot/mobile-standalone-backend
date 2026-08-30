package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiTestCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiTestCaseMapper {
    List<ApiTestCase> findByFilters(
            @Param("project") String project,
            @Param("collectionId") String collectionId,
            @Param("environment") String environment,
            @Param("enabledOnly") Boolean enabledOnly,
            @Param("keyword") String keyword,
            @Param("offset") Integer offset,
            @Param("limit") Integer limit
    );

    long countByFilters(
            @Param("project") String project,
            @Param("collectionId") String collectionId,
            @Param("environment") String environment,
            @Param("enabledOnly") Boolean enabledOnly,
            @Param("keyword") String keyword
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
}
