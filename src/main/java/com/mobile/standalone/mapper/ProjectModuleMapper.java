package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ProjectModule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProjectModuleMapper {

    /** category 为空则不过滤 */
    List<ProjectModule> findByCategory(@Param("category") String category,
                                       @Param("enabledOnly") Boolean enabledOnly);

    ProjectModule findById(@Param("id") String id);

    /**
     * 同一父下的重名检测。
     * <p>
     * 不用唯一索引：MySQL 唯一索引里 NULL 互不相等，而顶层模块的 parentId 恰好全是 NULL，
     * 索引挡不住最需要挡的那一类重复。
     */
    ProjectModule findSibling(@Param("category") String category,
                              @Param("parentId") String parentId,
                              @Param("name") String name,
                              @Param("excludeId") String excludeId);

    /** 直接子节点数——删除前判断用 */
    int countChildren(@Param("id") String id);

    int insert(ProjectModule row);

    int update(ProjectModule row);

    int deleteById(@Param("id") String id);
}
