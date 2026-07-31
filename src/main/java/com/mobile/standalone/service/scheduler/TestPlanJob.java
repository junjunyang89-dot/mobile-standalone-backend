package com.mobile.standalone.service.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Quartz 任务 Job
 * 定时执行测试计划
 * 注意：Quartz Job 需要通过 Spring 的 AutowiringSpringBeanJobFactory 来注入依赖
 */
@Component
@Slf4j
public class TestPlanJob implements Job {
    
    @Autowired
    private TaskDispatcher taskDispatcher;
    
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        // 从 JobDataMap 中获取计划ID
        String planId = context.getJobDetail().getJobDataMap().getString("planId");
        
        if (planId == null || planId.isEmpty()) {
            log.error("测试计划ID为空");
            throw new JobExecutionException("测试计划ID为空");
        }
        
        log.info("Quartz 触发测试计划执行: {}", planId);
        
        try {
            taskDispatcher.executePlan(planId);
        } catch (Exception e) {
            log.error("执行测试计划失败: {}", planId, e);
            throw new JobExecutionException("执行测试计划失败: " + e.getMessage(), e);
        }
    }
}
