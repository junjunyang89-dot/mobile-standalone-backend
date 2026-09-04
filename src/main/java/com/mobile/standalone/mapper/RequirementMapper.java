package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ProjectModule;
import com.mobile.standalone.entity.Requirement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RequirementMapper {

    /** 过滤条件都可为空 */
    List<Requirement> findByFilters(@Param("projectCode") String projectCode,
                                    @Param("versionId") String versionId,
                                    @Param("status") String status,
                                    @Param("keyword") String keyword);

    Requirement findByNo(@Param("requirementNo") String requirementNo);

    int insert(Requirement row);

    int update(Requirement row);

    int deleteByNo(@Param("requirementNo") String requirementNo);

    /** 标记上线：status→DONE，记录合并时间与分支。分支为空时不覆盖已有值 */
    int markShipped(@Param("requirementNo") String requirementNo,
                    @Param("sourceBranch") String sourceBranch);

    // ---- 需求 ↔ 模块 ----

    List<ProjectModule> findModules(@Param("requirementNo") String requirementNo);

    int clearModules(@Param("requirementNo") String requirementNo);

    int addModules(@Param("requirementNo") String requirementNo,
                   @Param("moduleIds") List<String> moduleIds);
}
