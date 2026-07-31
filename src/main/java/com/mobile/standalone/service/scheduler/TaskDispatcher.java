package com.mobile.standalone.service.scheduler;

import com.mobile.standalone.entity.*;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.CaseEngine;
import com.mobile.standalone.service.FunctionalTestScenarioService;
import com.mobile.standalone.service.TestPlanService;
import com.mobile.standalone.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 任务分发器
 * 接收 Quartz 调度器的触发：按场景执行（创建任务并运行）或按用例列表执行。
 * 支持批量设备(deviceIds)、批量场景(scenarioIds)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskDispatcher {

    private final CaseEngine caseEngine;
    private final TestPlanService testPlanService;
    private final DeviceMapper deviceMapper;
    private final FunctionalTestScenarioService scenarioService;
    private final TaskService taskService;

    /**
     * 执行测试计划
     * 由 Quartz Job 调用。支持批量设备+批量场景：对每台设备执行每个场景。
     */
    public void executePlan(String planId) {
        log.info("开始执行测试计划: {}", planId);

        TestPlan plan = testPlanService.getPlanById(planId);
        if (plan == null) {
            log.error("测试计划不存在: {}", planId);
            return;
        }

        if (!plan.getEnabled()) {
            log.info("测试计划已禁用: {}", planId);
            return;
        }

        List<String> deviceIds = resolveDeviceIds(plan);
        List<String> scenarioIds = resolveScenarioIds(plan);

        if (deviceIds.isEmpty()) {
            log.error("测试计划未配置设备: deviceId 或 deviceIds 为空, planId={}", planId);
            return;
        }

        if (scenarioIds != null && !scenarioIds.isEmpty()) {
            for (String deviceId : deviceIds) {
                Device device = deviceMapper.findById(deviceId);
                if (device == null) {
                    log.warn("设备不存在: {}", deviceId);
                    continue;
                }
                if (device.getStatus() != Device.DeviceStatus.CONNECTED) {
                    log.warn("设备未连接: {}", deviceId);
                    continue;
                }
                for (String scenarioId : scenarioIds) {
                    try {
                        executeScenario(plan, device, scenarioId);
                    } catch (Exception e) {
                        log.error("执行场景失败: planId={}, deviceId={}, scenarioId={}", planId, deviceId, scenarioId, e);
                    }
                }
            }
        } else if (plan.getCaseIds() != null && !plan.getCaseIds().isEmpty()) {
            for (String deviceId : deviceIds) {
                Device device = deviceMapper.findById(deviceId);
                if (device == null || device.getStatus() != Device.DeviceStatus.CONNECTED) continue;
                for (String caseId : plan.getCaseIds()) {
                    try {
                        executeCase(plan, caseId, device);
                    } catch (Exception e) {
                        log.error("执行用例失败: planId={}, caseId={}", planId, caseId, e);
                    }
                }
            }
        } else {
            log.warn("测试计划既未配置 scenarioId/scenarioIds 也未配置 caseIds: {}", planId);
        }

        testPlanService.updateLastRunTime(planId);
        testPlanService.incrementRunCount(planId);
        log.info("测试计划执行完成: {}", planId);
    }

    private List<String> resolveDeviceIds(TestPlan plan) {
        if (plan.getDeviceIds() != null && !plan.getDeviceIds().isEmpty()) {
            return plan.getDeviceIds();
        }
        if (plan.getDeviceId() != null && !plan.getDeviceId().isBlank()) {
            return List.of(plan.getDeviceId());
        }
        return Collections.emptyList();
    }

    private List<String> resolveScenarioIds(TestPlan plan) {
        if (plan.getScenarioIds() != null && !plan.getScenarioIds().isEmpty()) {
            return plan.getScenarioIds();
        }
        if (plan.getScenarioId() != null && !plan.getScenarioId().isBlank()) {
            return List.of(plan.getScenarioId());
        }
        return Collections.emptyList();
    }

    /**
     * 按场景执行：创建任务并提交运行（与「一键执行」同一逻辑）
     */
    private void executeScenario(TestPlan plan, Device device, String scenarioId) {
        var def = scenarioService.getScenario(scenarioId);
        if (def == null) {
            log.error("未知场景: {}", scenarioId);
            return;
        }
        Task task = new Task();
        task.setName(def.getName() + "-定时");
        task.setPlatform(Task.Platform.valueOf(def.getPlatform()));
        task.setDeviceId(device.getId());
        task.setAppId(plan.getAppId());
        task.setTaskType(def.getTaskType());
        if (def.getScenarioKey() != null) task.setScenarioKey(def.getScenarioKey());
        task.setStatus(Task.TaskStatus.PENDING);
        if (plan.getScenarioConfig() != null && !plan.getScenarioConfig().isBlank()) {
            task.setConfig(plan.getScenarioConfig().trim());
        }
        Task created = taskService.createTask(task);
        taskService.runTask(created.getId());
        log.info("定时计划已创建并提交任务: planId={}, deviceId={}, scenarioId={}, taskId={}", plan.getId(), device.getId(), scenarioId, created.getId());
    }
    
    /**
     * 执行单个用例
     */
    private void executeCase(TestPlan plan, String caseId, Device device) {
        TestCase testCase = testPlanService.getCaseById(caseId);
        if (testCase == null) {
            log.warn("测试用例不存在: {}", caseId);
            return;
        }
        
        log.info("执行用例: {}, 计划: {}", testCase.getName(), plan.getName());
        
        // 执行用例
        TestReport report = caseEngine.executeCase(testCase, device, plan.getAppId());
        
        // 关联计划ID和执行ID
        report.setPlanId(plan.getId());
        
        // 创建任务执行记录
        TaskExecution execution = new TaskExecution();
        execution.setId(UUID.randomUUID().toString());
        execution.setTaskId(plan.getId()); // 使用计划ID作为任务ID
        execution.setStatus(TaskExecution.ExecutionStatus.valueOf(report.getStatus().name()));
        execution.setStartTime(report.getStartTime());
        execution.setEndTime(report.getEndTime());
        execution.setErrorMessage(report.getErrorMessage());
        
        report.setExecutionId(execution.getId());
        
        // 保存报告和执行记录
        testPlanService.saveReport(report);
        // TODO: 保存 execution 到数据库
        
        log.info("用例执行完成: {}, 结果: {}", testCase.getName(), report.getStatus());
    }
}
