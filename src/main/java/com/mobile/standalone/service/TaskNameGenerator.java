package com.mobile.standalone.service;

import com.mobile.standalone.entity.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 任务名称生成器
 * 根据任务类型自动生成任务名称
 */
@Service
@RequiredArgsConstructor
public class TaskNameGenerator {
    
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    
    /**
     * 根据任务类型自动生成任务名称
     * 格式：Task-{任务类型显示名称}-{时间戳}
     * 例如：Task-功能体验基础质量测试-20240115-153000
     * 
     * @param taskType 任务类型
     * @return 生成的任务名称
     */
    public String generateTaskName(Task.TaskType taskType) {
        if (taskType == null) {
            taskType = Task.TaskType.FUNCTIONAL_QUALITY_TEST; // 默认类型
        }
        
        String typeDisplayName = taskType.getDisplayName();
        String timestamp = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        
        return String.format("Task-%s-%s", typeDisplayName, timestamp);
    }
    
    /**
     * 根据任务类型和自定义后缀生成任务名称
     * 格式：Task-{任务类型显示名称}-{自定义后缀}
     * 
     * @param taskType 任务类型
     * @param suffix 自定义后缀（可选）
     * @return 生成的任务名称
     */
    public String generateTaskName(Task.TaskType taskType, String suffix) {
        if (taskType == null) {
            taskType = Task.TaskType.FUNCTIONAL_QUALITY_TEST;
        }
        
        String typeDisplayName = taskType.getDisplayName();
        
        if (suffix != null && !suffix.trim().isEmpty()) {
            return String.format("Task-%s-%s", typeDisplayName, suffix);
        } else {
            return generateTaskName(taskType);
        }
    }
}
