package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiEndpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiEndpointMapper {

    /** 带 caseCount 的清单，页面直接用 */
    List<ApiEndpoint> search(@Param("projectCodes") List<String> projectCodes,
                             @Param("keyword") String keyword,
                             @Param("changeFlag") String changeFlag,
                             @Param("status") String status);

    ApiEndpoint findById(@Param("id") String id);

    /** 不含用例统计；ids 不能为空 */
    List<ApiEndpoint> findByIds(@Param("ids") List<String> ids);

    ApiEndpoint findByKey(@Param("project") String project,
                          @Param("httpMethod") String httpMethod,
                          @Param("path") String path);

    /** 跨项目按 方法+路径 找，抓包认不出归属时用 */
    List<ApiEndpoint> findByMethodPath(@Param("httpMethod") String httpMethod,
                                       @Param("path") String path);

    int updateProject(@Param("id") String id, @Param("project") String project);

    int countByProject(@Param("project") String project);

    int insert(ApiEndpoint endpoint);

    int update(ApiEndpoint endpoint);

    /** 同步时把这一批之外的标成已废弃，而不是删——挂在下面的用例不能变孤儿 */
    int markDeprecatedNotIn(@Param("project") String project, @Param("keepIds") List<String> keepIds);

    /** 同步开始前清掉上一轮的变更标记，否则「上次新增」会一直显示成新增 */
    int clearChangeFlags(@Param("project") String project);

    /** 分支上线：该分支接口并入 master（更新覆盖、新增挂上，变更标记清掉） */
    int promoteBranchToMaster(@Param("branch") String branch);

    int updateAuthRequired(@Param("id") String id, @Param("authRequired") Boolean authRequired);

    int updateCallers(@Param("id") String id,
                      @Param("callersJson") String callersJson,
                      @Param("callerStatus") String callerStatus);

    int updateRetired(@Param("id") String id,
                      @Param("retiredAt") java.time.LocalDateTime retiredAt,
                      @Param("retiredBy") String retiredBy);

    int updateLevel(@Param("id") String id,
                    @Param("level") String level,
                    @Param("setBy") String setBy);

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
