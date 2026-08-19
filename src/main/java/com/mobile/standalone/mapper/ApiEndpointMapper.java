package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiEndpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiEndpointMapper {

    /** 带 caseCount 的清单，页面直接用 */
    List<ApiEndpoint> search(@Param("project") String project,
                             @Param("keyword") String keyword,
                             @Param("changeFlag") String changeFlag,
                             @Param("status") String status);

    ApiEndpoint findById(@Param("id") String id);

    ApiEndpoint findByKey(@Param("project") String project,
                          @Param("httpMethod") String httpMethod,
                          @Param("path") String path);

    int countByProject(@Param("project") String project);

    int insert(ApiEndpoint endpoint);

    int update(ApiEndpoint endpoint);

    /** 同步时把这一批之外的标成已废弃，而不是删——挂在下面的用例不能变孤儿 */
    int markDeprecatedNotIn(@Param("project") String project, @Param("keepIds") List<String> keepIds);

    /** 同步开始前清掉上一轮的变更标记，否则「上次新增」会一直显示成新增 */
    int clearChangeFlags(@Param("project") String project);

    int deleteById(@Param("id") String id);

    /** 迁移用：现有用例里所有不重复的 项目+方法+路径 */
    List<ApiEndpoint> distinctEndpointsFromCases();

    /** 把用例挂到接口上 */
    int linkCasesToEndpoint(@Param("endpointId") String endpointId,
                            @Param("project") String project,
                            @Param("httpMethod") String httpMethod,
                            @Param("path") String path);

    int countUnlinkedCases();
}
