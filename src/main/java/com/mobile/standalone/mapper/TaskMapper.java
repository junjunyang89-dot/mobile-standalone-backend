package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskMapper {
    
    List<Task> findAll();
    
    Task findById(@Param("id") String id);
    
    List<Task> findByStatus(@Param("status") String status);
    
    List<Task> findByPlatform(@Param("platform") String platform);

    List<Task> findByTaskType(@Param("taskType") Task.TaskType taskType);

    List<Task> findByFilters(
            @Param("taskType") Task.TaskType taskType,
            @Param("platform") Task.Platform platform,
            @Param("status") Task.TaskStatus status,
            @Param("deviceId") String deviceId,
            @Param("appId") String appId
    );

    /**
     * 通用任务列表筛选后的总条数（与 findByFilters 条件一致）
     */
    int countByFilters(
            @Param("taskType") Task.TaskType taskType,
            @Param("platform") Task.Platform platform,
            @Param("status") Task.TaskStatus status,
            @Param("deviceId") String deviceId,
            @Param("appId") String appId
    );

    /**
     * 通用任务列表分页（与 findByFilters 条件一致，按创建时间倒序）
     */
    List<Task> findByFiltersPage(
            @Param("taskType") Task.TaskType taskType,
            @Param("platform") Task.Platform platform,
            @Param("status") Task.TaskStatus status,
            @Param("deviceId") String deviceId,
            @Param("appId") String appId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 功能测试任务列表筛选：任务类型 + 状态 + 关键词（任务名/备注）+ 时间范围（近 N 天）
     */
    List<Task> findFunctionalTasksFiltered(
            @Param("taskType") Task.TaskType taskType,
            @Param("status") Task.TaskStatus status,
            @Param("keyword") String keyword,
            @Param("days") Integer days
    );

    /**
     * 功能测试任务列表筛选后的总条数（多任务类型合并筛选）
     */
    int countFunctionalTestTasksFiltered(
            @Param("taskTypes") List<Task.TaskType> taskTypes,
            @Param("status") Task.TaskStatus status,
            @Param("keyword") String keyword,
            @Param("days") Integer days
    );

    /**
     * 功能测试任务列表分页（多任务类型合并筛选，按创建时间倒序）
     */
    List<Task> findFunctionalTasksFilteredPage(
            @Param("taskTypes") List<Task.TaskType> taskTypes,
            @Param("status") Task.TaskStatus status,
            @Param("keyword") String keyword,
            @Param("days") Integer days,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    int insert(Task task);
    
    int update(Task task);
    
    int deleteById(@Param("id") String id);
    
    boolean existsById(@Param("id") String id);
}
