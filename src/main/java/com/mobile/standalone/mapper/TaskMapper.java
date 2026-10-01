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

    /** 排队中：已创建、还没分到设备。按先到先得取 */
    List<Task> findQueued(@Param("limit") int limit);

    /** 排队总数，给界面显示「前面还有几个」 */
    int countQueued();

    /**
     * 某条流程最近一次执行。
     * <p>
     * flowId 存在 config 的 JSON 里，没有独立列——单跑和批量执行都会建 Task，
     * 查这一张就能覆盖两种入口，不用分别去查回归批次表。
     */
    Task findLatestByFlowId(@Param("flowId") String flowId);

    /** 试跑轨的最近一次；「我刚跑的那次怎么样了」只能问它 */
    Task findLatestDebugByFlowId(@Param("flowId") String flowId);
    
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
