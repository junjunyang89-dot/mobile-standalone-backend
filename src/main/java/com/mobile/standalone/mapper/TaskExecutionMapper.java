package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.TaskExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskExecutionMapper {
    
    List<TaskExecution> findAll();
    
    TaskExecution findById(@Param("id") String id);
    
    List<TaskExecution> findByTaskId(@Param("taskId") String taskId);
    
    List<TaskExecution> findByTaskIdOrderByStartTimeDesc(@Param("taskId") String taskId);
    
    int insert(TaskExecution execution);
    
    int update(TaskExecution execution);

    int updateExecutionLog(@Param("id") String id, @Param("executionLog") String executionLog);
    
    int deleteById(@Param("id") String id);
}
