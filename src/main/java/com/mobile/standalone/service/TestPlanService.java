package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.TestCase;
import com.mobile.standalone.entity.TestPlan;
import com.mobile.standalone.entity.TestReport;
import com.mobile.standalone.mapper.TestCaseMapper;
import com.mobile.standalone.mapper.TestPlanMapper;
import com.mobile.standalone.mapper.TestReportMapper;
import com.mobile.standalone.service.scheduler.QuartzSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 测试计划服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestPlanService {
    
    private final TestPlanMapper testPlanMapper;
    private final TestReportMapper testReportMapper;
    private final QuartzSchedulerService quartzSchedulerService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public List<TestPlan> getAllPlans() {
        List<TestPlan> plans = testPlanMapper.findAll();
        plans.forEach(this::deserializeCaseIds);
        return plans;
    }
    
    public TestPlan getPlanById(String id) {
        TestPlan plan = testPlanMapper.findById(id);
        if (plan != null) {
            deserializeCaseIds(plan);
        }
        return plan;
    }
    
    public List<TestPlan> getPlansByDeviceId(String deviceId) {
        List<TestPlan> plans = testPlanMapper.findByDeviceId(deviceId);
        plans.forEach(this::deserializeCaseIds);
        return plans;
    }
    
    public List<TestPlan> getPlansByAppId(String appId) {
        List<TestPlan> plans = testPlanMapper.findByAppId(appId);
        plans.forEach(this::deserializeCaseIds);
        return plans;
    }

    public List<TestPlan> getPlansByPlanType(TestPlan.PlanType planType) {
        if (planType == null) return List.of();
        List<TestPlan> plans = testPlanMapper.findByPlanType(planType);
        plans.forEach(this::deserializeCaseIds);
        return plans;
    }
    
    public List<TestPlan> getEnabledPlans() {
        List<TestPlan> plans = testPlanMapper.findEnabledPlans();
        plans.forEach(this::deserializeCaseIds);
        return plans;
    }
    
    @Transactional
    public TestPlan createPlan(TestPlan plan) {
        if (plan.getId() == null) {
            plan.setId(UUID.randomUUID().toString());
        }
        
        serializeCaseIds(plan);
        testPlanMapper.insert(plan);
        
        // 添加到 Quartz 调度器
        try {
            quartzSchedulerService.schedulePlan(plan);
            // 计算下次执行时间
            LocalDateTime nextRunTime = quartzSchedulerService.getNextRunTime(plan.getId());
            if (nextRunTime != null) {
                plan.setNextRunTime(nextRunTime);
                testPlanMapper.updateNextRunTime(plan.getId(), nextRunTime);
            }
        } catch (Exception e) {
            log.error("添加测试计划到调度器失败", e);
        }
        
        return plan;
    }
    
    @Transactional
    public TestPlan updatePlan(String id, TestPlan planData) {
        TestPlan plan = testPlanMapper.findById(id);
        if (plan != null) {
            if (planData.getName() != null) plan.setName(planData.getName());
            if (planData.getDescription() != null) plan.setDescription(planData.getDescription());
            if (planData.getCronExpression() != null) plan.setCronExpression(planData.getCronExpression());
            if (planData.getTimezone() != null) plan.setTimezone(planData.getTimezone());
            if (planData.getDeviceId() != null) plan.setDeviceId(planData.getDeviceId());
            if (planData.getDeviceIds() != null) plan.setDeviceIds(planData.getDeviceIds());
            if (planData.getAppId() != null) plan.setAppId(planData.getAppId());
            if (planData.getScenarioId() != null) plan.setScenarioId(planData.getScenarioId());
            if (planData.getScenarioIds() != null) plan.setScenarioIds(planData.getScenarioIds());
            if (planData.getPlanType() != null) plan.setPlanType(planData.getPlanType());
            if (planData.getScenarioConfig() != null) plan.setScenarioConfig(planData.getScenarioConfig());
            if (planData.getCaseIds() != null) plan.setCaseIds(planData.getCaseIds());
            if (planData.getEnabled() != null) plan.setEnabled(planData.getEnabled());
            if (planData.getStatus() != null) plan.setStatus(planData.getStatus());
            
            serializeCaseIds(plan);
            testPlanMapper.update(plan);
            
            // 更新 Quartz 调度
            try {
                quartzSchedulerService.updatePlanSchedule(plan);
                LocalDateTime nextRunTime = quartzSchedulerService.getNextRunTime(plan.getId());
                if (nextRunTime != null) {
                    plan.setNextRunTime(nextRunTime);
                    testPlanMapper.updateNextRunTime(plan.getId(), nextRunTime);
                }
            } catch (Exception e) {
                log.error("更新测试计划调度失败", e);
            }
            
            return plan;
        }
        return null;
    }
    
    @Transactional
    public boolean deletePlan(String id) {
        TestPlan plan = testPlanMapper.findById(id);
        if (plan != null) {
            // 从 Quartz 调度器移除
            try {
                quartzSchedulerService.unschedulePlan(id);
            } catch (Exception e) {
                log.error("从调度器移除测试计划失败", e);
            }
            
            testPlanMapper.deleteById(id);
            return true;
        }
        return false;
    }
    
    @Transactional
    public void updateLastRunTime(String planId) {
        testPlanMapper.updateLastRunTime(planId, LocalDateTime.now());
    }
    
    @Transactional
    public void incrementRunCount(String planId) {
        testPlanMapper.incrementRunCount(planId);
    }
    
    @Transactional
    public void saveReport(TestReport report) {
        if (report.getId() == null) {
            report.setId(UUID.randomUUID().toString());
        }
        testReportMapper.insert(report);
    }
    
    /**
     * 序列化用例ID列表（List -> JSON String）
     */
    private void serializeCaseIds(TestPlan plan) {
        // 序列化逻辑在 Mapper XML 中处理
        // 这里只是占位，实际序列化在 MyBatis 的 TypeHandler 中完成
    }
    
    /**
     * 反序列化用例ID列表（JSON String -> List）
     */
    private void deserializeCaseIds(TestPlan plan) {
        // 从数据库读取 JSON 字符串，转换为 List
        // 需要在 Mapper XML 中处理
        // 这里假设 case_ids 字段在数据库中是 JSON 格式的字符串
    }
    
    private final TestCaseMapper testCaseMapper;
    
    // 为了兼容，添加获取用例的方法
    public TestCase getCaseById(String caseId) {
        return testCaseMapper.findById(caseId);
    }
}
