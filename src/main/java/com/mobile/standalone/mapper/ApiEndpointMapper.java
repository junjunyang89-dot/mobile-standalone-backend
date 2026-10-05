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

    /** 不含用例统计；httpMethod 为空时取全部 */
    List<ApiEndpoint> listByProject(@Param("project") String project, @Param("httpMethod") String httpMethod);

    int updatePath(@Param("id") String id, @Param("path") String path);

    int updateGone(@Param("id") String id,
                   @Param("goneAt") java.time.LocalDateTime goneAt,
                   @Param("goneRef") String goneRef);

    int backfillCodePath();

    int moveCases(@Param("fromId") String fromId, @Param("toId") String toId,
                  @Param("fromPath") String fromPath, @Param("toPath") String toPath);

    int moveRequirementLinks(@Param("fromId") String fromId, @Param("toId") String toId);

    int moveChanges(@Param("fromId") String fromId, @Param("toId") String toId);

    int deleteChangesOf(@Param("endpointId") String endpointId);

    /** project 为空时删这个分支在所有项目下的记录 */
    int deleteChangesByBranch(@Param("project") String project, @Param("branch") String branch);

    int upsertChange(@Param("endpointId") String endpointId,
                     @Param("project") String project,
                     @Param("branch") String branch,
                     @Param("changeFlag") String changeFlag,
                     @Param("changeNote") String changeNote,
                     @Param("changeType") String changeType);

    /** 带分支版本：上线时把 handler / 出参快照等写回全量那条 */
    int upsertChangeWithSnapshot(@Param("endpointId") String endpointId,
                                 @Param("project") String project,
                                 @Param("branch") String branch,
                                 @Param("changeFlag") String changeFlag,
                                 @Param("changeNote") String changeNote,
                                 @Param("changeType") String changeType,
                                 @Param("handler") String handler,
                                 @Param("summary") String summary,
                                 @Param("sourceFile") String sourceFile,
                                 @Param("fieldsSnapshot") String fieldsSnapshot,
                                 @Param("tablesJson") String tablesJson);

    /** project 为空时取所有项目 */
    List<java.util.Map<String, Object>> listBranchSnapshots(@Param("project") String project,
                                                             @Param("branch") String branch);

    List<java.util.Map<String, Object>> listChangesOf(@Param("ids") java.util.Collection<String> endpointIds);

    int clearSourceBranchOf(@Param("project") String project, @Param("branch") String branch);

    int markDeprecated(@Param("id") String id);

    /** projects 不能为空 */
    int recomputeChangeFlags(@Param("projects") java.util.Collection<String> projects);

    List<String> projectsOfBranch(@Param("branch") String branch);

    List<String> endpointIdsOfBranch(@Param("project") String project, @Param("branch") String branch);

    List<java.util.Map<String, Object>> listChanges(@Param("projectCodes") List<String> projectCodes);

    List<java.util.Map<String, Object>> summaryChangesByBranch();

    int countChanges();

    int migrateColumnFlags();

    int clearSourceBranch(@Param("branch") String branch);

    int updateProject(@Param("id") String id, @Param("project") String project);

    int countByProject(@Param("project") String project);

    int insert(ApiEndpoint endpoint);

    int update(ApiEndpoint endpoint);

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
