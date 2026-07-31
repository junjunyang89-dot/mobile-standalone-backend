package com.mobile.standalone.service.scheduler;

import com.mobile.standalone.entity.TestPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Quartz 调度器服务
 * 管理测试计划的定时调度
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuartzSchedulerService {
    
    private final Scheduler scheduler;
    
    /**
     * 添加测试计划到调度器
     */
    public void schedulePlan(TestPlan plan) throws SchedulerException {
        if (!plan.getEnabled()) {
            log.info("测试计划已禁用，不添加到调度器: {}", plan.getId());
            return;
        }
        
        // 创建 JobDetail
        JobDetail jobDetail = JobBuilder.newJob(TestPlanJob.class)
                .withIdentity("plan-" + plan.getId(), "test-plans")
                .usingJobData("planId", plan.getId())
                .build();
        
        // 创建 Trigger
        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger-" + plan.getId(), "test-plans")
                .withSchedule(CronScheduleBuilder.cronSchedule(plan.getCronExpression())
                        .inTimeZone(java.util.TimeZone.getTimeZone(plan.getTimezone())))
                .build();
        
        // 调度任务
        Date nextRunTime = scheduler.scheduleJob(jobDetail, trigger);
        log.info("测试计划已添加到调度器: {}, 下次执行时间: {}", plan.getId(), nextRunTime);
    }
    
    /**
     * 更新测试计划的调度
     */
    public void updatePlanSchedule(TestPlan plan) throws SchedulerException {
        // 先删除旧的调度
        unschedulePlan(plan.getId());
        
        // 如果计划启用，添加新的调度
        if (plan.getEnabled()) {
            schedulePlan(plan);
        }
    }
    
    /**
     * 从调度器中移除测试计划
     */
    public void unschedulePlan(String planId) throws SchedulerException {
        JobKey jobKey = new JobKey("plan-" + planId, "test-plans");
        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey);
            log.info("测试计划已从调度器移除: {}", planId);
        }
    }
    
    /**
     * 暂停测试计划
     */
    public void pausePlan(String planId) throws SchedulerException {
        JobKey jobKey = new JobKey("plan-" + planId, "test-plans");
        if (scheduler.checkExists(jobKey)) {
            scheduler.pauseJob(jobKey);
            log.info("测试计划已暂停: {}", planId);
        }
    }
    
    /**
     * 恢复测试计划
     */
    public void resumePlan(String planId) throws SchedulerException {
        JobKey jobKey = new JobKey("plan-" + planId, "test-plans");
        if (scheduler.checkExists(jobKey)) {
            scheduler.resumeJob(jobKey);
            log.info("测试计划已恢复: {}", planId);
        }
    }
    
    /**
     * 立即执行测试计划（不等待定时触发）
     */
    public void triggerPlan(String planId) throws SchedulerException {
        JobKey jobKey = new JobKey("plan-" + planId, "test-plans");
        if (scheduler.checkExists(jobKey)) {
            scheduler.triggerJob(jobKey);
            log.info("立即触发测试计划: {}", planId);
        } else {
            log.warn("测试计划未在调度器中: {}", planId);
        }
    }
    
    /**
     * 获取下次执行时间
     */
    public LocalDateTime getNextRunTime(String planId) throws SchedulerException {
        TriggerKey triggerKey = new TriggerKey("trigger-" + planId, "test-plans");
        Trigger trigger = scheduler.getTrigger(triggerKey);
        if (trigger != null) {
            Date nextFireTime = trigger.getNextFireTime();
            if (nextFireTime != null) {
                return LocalDateTime.ofInstant(nextFireTime.toInstant(), ZoneId.systemDefault());
            }
        }
        return null;
    }
}
