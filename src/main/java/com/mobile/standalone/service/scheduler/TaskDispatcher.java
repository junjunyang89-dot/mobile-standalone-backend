package com.mobile.standalone.service.scheduler;

import com.mobile.standalone.entity.*;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.FunctionalTestScenarioService;
import com.mobile.standalone.service.TestPlanService;
import com.mobile.standalone.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 任务分发器
 * 接收 Quartz 调度器的触发：按场景创建任务并运行，支持批量设备(deviceIds)、批量场景(scenarioIds)。
 * <p>
 * 定时与手动触发走同一条执行链（{@link TaskService#runTask}），不再有第二套用例引擎——
 * 旧的 caseIds 分支依赖已下线的 CaseEngine/PageObject，且前端从未提供入口，已移除。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskDispatcher {

    private final TestPlanService testPlanService;
    private final DeviceMapper deviceMapper;
    private final FunctionalTestScenarioService scenarioService;
    private final TaskService taskService;
    private final com.mobile.standalone.mapper.TaskMapper taskMapper;

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
                    // 只写一行 warn 的话，「定时回归昨晚没跑」在界面上完全没有痕迹。
                    // 这里不建任务（还没建就没什么可标记的），但日志要说清是计划级的跳过
                    log.warn("[定时计划] 设备未连接，本轮跳过该设备上的全部场景: planId={} deviceId={} status={}",
                            planId, deviceId, device.getStatus());
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
            log.error("测试计划配置了已下线的 caseIds，未执行：请改为配置 scenarioIds（流程编排）。planId={}, caseIds={}",
                    planId, plan.getCaseIds());
        } else {
            log.warn("测试计划未配置 scenarioId/scenarioIds: {}", planId);
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
        try {
            taskService.runTask(created.getId());
        } catch (Exception e) {
            // 设备被占时 runTask 会抛 IllegalStateException。
            //
            // 原先这个异常被外层 catch 吞掉，只留一行 log.error，而任务已经建好了、
            // 状态是 PENDING、device_id 也写死了 —— 于是它<b>既不在排队队列里</b>
            // （队列条件是 device_id IS NULL），<b>也没人重试</b>，界面上不是失败也不是完成。
            //
            // 6 台设备被 50 个人占满是常态，所以这条路径几乎每次定时计划触发都会走到：
            // 无人值守回归静默失效，而通过率报表上看不出任何异常。
            //
            // 记成 BLOCKED 是有意的：它不进通过率的分母（Task.TaskStatus.BLOCKED 的注释
            // 解释了为什么），但会在任务列表里显示出来，人能看到「昨晚这条没跑成，原因是设备被占」。
            blockTask(created, "定时计划触发时设备不可用：" + e.getMessage());
            log.warn("[定时计划] 任务已记为 BLOCKED（设备不可用）: planId={} deviceId={} scenarioId={} taskId={} 原因={}",
                    plan.getId(), device.getId(), scenarioId, created.getId(), e.getMessage());
            return;
        }
        log.info("定时计划已创建并提交任务: planId={}, deviceId={}, scenarioId={}, taskId={}", plan.getId(), device.getId(), scenarioId, created.getId());
    }

    /**
     * 把任务记成 BLOCKED。
     * <p>
     * 和 {@code DeviceTaskScheduler.blockTask} 同一个意思：<b>没跑成不等于失败</b>。
     * 这里只改任务状态、不写 task_executions —— 写执行记录需要 executionMapper，
     * 而这条路径上任务压根没开始执行，一条「开始时间=结束时间」的空执行记录
     * 反而会让人以为它跑过了。
     */
    private void blockTask(Task task, String reason) {
        try {
            task.setStatus(Task.TaskStatus.BLOCKED);
            // Task 上没有存失败原因的字段，所以原因只能进任务名。
            // 不好看，但比「状态是 BLOCKED，为什么不知道」有用 ——
            // 人在任务列表里一眼能看到是设备被占，不用去翻日志。
            // 真要做干净就得给 tasks 加一列 block_reason，那是 DDL，单独安排
            String name = task.getName() == null ? "" : task.getName();
            if (!name.contains("[未跑成]")) {
                task.setName(name + " [未跑成：设备被占]");
            }
            task.setUpdatedAt(java.time.LocalDateTime.now());
            taskMapper.update(task);
        } catch (Exception e) {
            // 连状态都写不进去时，至少把原因留在日志里，别让它彻底无声
            log.error("[定时计划] 写 BLOCKED 状态失败，任务会停在 PENDING: taskId={} 原因={}",
                    task.getId(), reason, e);
        }
    }
}
