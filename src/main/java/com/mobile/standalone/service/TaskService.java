package com.mobile.standalone.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mobile.standalone.dto.FunctionalTestExecutionResult;
import com.mobile.standalone.dto.PageResult;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    /** 用于异步线程内通过代理调用，保证 executeTestTask 在独立事务中提交，前端能及时读到完成状态与日志/截图 */
    @Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private TaskService self;

    private final TaskMapper taskMapper;
    private final TaskExecutionMapper executionMapper;
    private final TaskNameGenerator taskNameGenerator;
    private final TestScriptExecutor testScriptExecutor;
    private final CompatibilityTestService compatibilityTestService;
    private final LoginTestService loginTestService;
    private final PerformanceTestService performanceTestService;
    private final DeviceMapper deviceMapper;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, Process> runningTasks = new ConcurrentHashMap<>();

    /** 功能测试任务列表包含的任务类型（与 getFunctionalTestTasksFiltered 一致） */
    private static final List<Task.TaskType> FUNCTIONAL_TEST_TASK_TYPES = List.of(
            Task.TaskType.FUNCTIONAL_QUALITY_TEST,
            Task.TaskType.COMPATIBILITY_TEST,
            Task.TaskType.INSTALL_UNINSTALL_UPGRADE_TEST,
            Task.TaskType.UI_ADAPTATION_TEST
    );
    
    public List<Task> getAllTasks() {
        return taskMapper.findAll();
    }
    
    public Task getTaskById(String id) {
        return taskMapper.findById(id);
    }

    public List<Task> getTasksByType(Task.TaskType taskType) {
        return taskMapper.findByTaskType(taskType);
    }

    public List<Task> searchTasks(Task.TaskType taskType,
                                  Task.Platform platform,
                                  Task.TaskStatus status,
                                  String deviceId,
                                  String appId) {
        return taskMapper.findByFilters(taskType, platform, status, deviceId, appId);
    }

    /**
     * 通用任务列表分页（支持任务类型、平台、状态、设备ID、应用ID 筛选）
     * @param page 页码，从 1 开始
     * @param size 每页条数，默认 10，最大 100
     */
    public PageResult<Task> searchTasksPage(Task.TaskType taskType,
                                            Task.Platform platform,
                                            Task.TaskStatus status,
                                            String deviceId,
                                            String appId,
                                            int page,
                                            int size) {
        int p = Math.max(1, page);
        int s = size <= 0 ? 10 : Math.min(100, size);
        int total = taskMapper.countByFilters(taskType, platform, status, deviceId, appId);
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / s);
        int offset = (p - 1) * s;
        List<Task> content = taskMapper.findByFiltersPage(taskType, platform, status, deviceId, appId, offset, s);
        return new PageResult<>(content, total, totalPages, p, s);
    }

    /**
     * 功能测试 + 兼容性测试任务列表（支持截图式筛选：状态、关键词、近 N 天）
     * @param status 任务状态，可选
     * @param keyword 任务名或备注关键词，可选
     * @param days 近 N 天（3=近三天，7=近一周），null 或 0=全部
     */
    public List<Task> getFunctionalTestTasksFiltered(Task.TaskStatus status, String keyword, Integer days) {
        String kw = keyword != null && !keyword.isBlank() ? keyword.trim() : null;
        Integer d = days != null && days > 0 ? days : null;
        List<Task> functional = taskMapper.findFunctionalTasksFiltered(
                Task.TaskType.FUNCTIONAL_QUALITY_TEST, status, kw, d);
        List<Task> compatibility = taskMapper.findFunctionalTasksFiltered(
                Task.TaskType.COMPATIBILITY_TEST, status, kw, d);
        List<Task> installUpgrade = taskMapper.findFunctionalTasksFiltered(
                Task.TaskType.INSTALL_UNINSTALL_UPGRADE_TEST, status, kw, d);
        List<Task> uiAdapt = taskMapper.findFunctionalTasksFiltered(
                Task.TaskType.UI_ADAPTATION_TEST, status, kw, d);
        List<Task> merged = new ArrayList<>(functional.size() + compatibility.size() + installUpgrade.size() + uiAdapt.size());
        merged.addAll(functional);
        merged.addAll(compatibility);
        merged.addAll(installUpgrade);
        merged.addAll(uiAdapt);
        merged.sort(Comparator.comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return merged;
    }

    /**
     * 功能测试任务列表分页（支持状态、关键词、近 N 天筛选）
     * @param status 任务状态，可选
     * @param keyword 任务名或备注关键词，可选
     * @param days 近 N 天（3=近三天，7=近一周），null 或 0=全部
     * @param page 页码，从 1 开始，小于 1 时按 1 处理
     * @param size 每页条数，默认 10，最大 100
     */
    public PageResult<Task> getFunctionalTestTasksFilteredPage(Task.TaskStatus status, String keyword, Integer days, int page, int size) {
        String kw = keyword != null && !keyword.isBlank() ? keyword.trim() : null;
        Integer d = days != null && days > 0 ? days : null;
        int p = Math.max(1, page);
        int s = size <= 0 ? 10 : Math.min(100, size);
        int total = taskMapper.countFunctionalTestTasksFiltered(FUNCTIONAL_TEST_TASK_TYPES, status, kw, d);
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / s);
        int offset = (p - 1) * s;
        List<Task> content = taskMapper.findFunctionalTasksFilteredPage(FUNCTIONAL_TEST_TASK_TYPES, status, kw, d, offset, s);
        return new PageResult<>(content, total, totalPages, p, s);
    }

    /**
     * 创建任务。对瞬时锁冲突（如设备扫描短暂更新 devices 导致外键校验等待）自动重试。
     */
    public Task createTask(Task task) {
        if (task.getId() == null || task.getId().isBlank()) {
            task.setId(UUID.randomUUID().toString());
        }
        task.setStatus(Task.TaskStatus.PENDING);
        LocalDateTime now = LocalDateTime.now();
        if (task.getCreatedAt() == null) {
            task.setCreatedAt(now);
        }
        task.setUpdatedAt(now);

        if ((task.getName() == null || task.getName().trim().isEmpty()) && task.getTaskType() != null) {
            task.setName(taskNameGenerator.generateTaskName(task.getTaskType()));
        }

        final int maxAttempts = 4;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return self.insertNewTask(task);
            } catch (TransientDataAccessException e) {
                if (attempt == maxAttempts) {
                    throw e;
                }
                log.warn("createTask 遇到瞬时数据库错误，重试 {}/{}: {}", attempt, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new IllegalStateException("createTask 重试逻辑异常");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Task insertNewTask(Task task) {
        taskMapper.insert(task);
        return task;
    }
    
    @Transactional
    public Task updateTask(String id, Task taskData) {
        Task task = taskMapper.findById(id);
        if (task != null) {
            if (taskData.getName() != null) task.setName(taskData.getName());
            if (taskData.getDescription() != null) task.setDescription(taskData.getDescription());
            if (taskData.getProjectPath() != null) task.setProjectPath(taskData.getProjectPath());
            if (taskData.getScriptPath() != null) task.setScriptPath(taskData.getScriptPath());
            if (taskData.getPlatform() != null) task.setPlatform(taskData.getPlatform());
            if (taskData.getDeviceId() != null) task.setDeviceId(taskData.getDeviceId());
            if (taskData.getAppId() != null) task.setAppId(taskData.getAppId());
            if (taskData.getTaskType() != null) task.setTaskType(taskData.getTaskType());
            if (taskData.getScenarioKey() != null) task.setScenarioKey(taskData.getScenarioKey());
            if (taskData.getStatus() != null) task.setStatus(taskData.getStatus());
            if (taskData.getConfig() != null) task.setConfig(taskData.getConfig());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.update(task);
            return task;
        }
        return null;
    }
    
    /**
     * 删除任务：先停止任务（若正在运行），再删除任务及关联执行记录。
     */
    @Transactional
    public boolean deleteTask(String id) {
        Task task = taskMapper.findById(id);
        if (task == null) {
            return false;
        }
        stopTask(id);
        taskMapper.deleteById(id);
        return true;
    }
    
    @Transactional
    public boolean runTask(String taskId) {
        Task task = taskMapper.findById(taskId);
        if (task == null || task.getStatus() == Task.TaskStatus.RUNNING) {
            return false;
        }
        
        // 更新任务状态
        task.setStatus(Task.TaskStatus.RUNNING);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.update(task);
        
        // 创建执行记录
        TaskExecution execution = new TaskExecution();
        execution.setId(UUID.randomUUID().toString());
        execution.setTaskId(taskId);
        execution.setStatus(TaskExecution.ExecutionStatus.RUNNING);
        execution.setStartTime(LocalDateTime.now());
        executionMapper.insert(execution);
        
        // 异步执行任务（通过 self 调用使 executeTestTask 走 Spring 代理，在独立事务中提交，前端轮询能拿到完成状态与日志/截图）
        executorService.submit(() -> {
            try {
                self.executeTestTask(task, execution);
            } catch (Exception e) {
                log.error("任务执行失败: taskId={}, name={}, error={}", taskId, task.getName(), e.getMessage(), e);
                task.setStatus(Task.TaskStatus.FAILED);
                task.setUpdatedAt(LocalDateTime.now());
                taskMapper.update(task);
                execution.setStatus(TaskExecution.ExecutionStatus.FAILED);
                execution.setEndTime(LocalDateTime.now());
                execution.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.toString());
                executionMapper.update(execution);
            }
        });
        
        return true;
    }
    
    /**
     * 停止任务：若有进程则销毁；若任务状态为 RUNNING 则更新为 STOPPED 并返回成功。
     * 登录测试等任务在异步线程中执行，未存入 runningTasks，仍将任务标记为已停止并返回 true，避免前端收到 400 报「Network error」。
     */
    @Transactional
    public boolean stopTask(String taskId) {
        Task task = taskMapper.findById(taskId);
        if (task == null) {
            return false;
        }
        Process process = runningTasks.get(taskId);
        if (process != null && process.isAlive()) {
            process.destroy();
            runningTasks.remove(taskId);
        }
        if (task.getStatus() == Task.TaskStatus.RUNNING) {
            task.setStatus(Task.TaskStatus.STOPPED);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.update(task);
        }
        return true;
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeTestTask(Task task, TaskExecution execution) throws Exception {
        log.info("Executing task: {}, type: {} (应用将由 PlatformDriver 启动：appium.enabled=true 时使用 Appium WebDriver，否则使用 ADB/idevice)",
                task.getName(), task.getTaskType());

        // 获取设备信息
        Device device = deviceMapper.findById(task.getDeviceId());
        if (device == null) {
            throw new RuntimeException("设备不存在: " + task.getDeviceId());
        }
        
        String reportPath = null;
        boolean executionAlreadySaved = false; // 性能测试分支内已单独更新状态并保存 execution 时置为 true，避免下方重复更新
        try {
            // 若 DB 未存 task_type 或为 null，按功能体验基础质量测试处理，确保能产出执行日志
            Task.TaskType taskType = normalizeTaskType(task.getTaskType());
            if (taskType == null) {
                taskType = Task.TaskType.FUNCTIONAL_QUALITY_TEST;
                log.debug("任务 taskId={} 的 taskType 为空，按 FUNCTIONAL_QUALITY_TEST 执行以产出执行日志", task.getId());
            }
            if (taskType == Task.TaskType.FUNCTIONAL_QUALITY_TEST) {
                FunctionalTestExecutionResult result = testScriptExecutor.executeFunctionalQualityTest(task, device);
                reportPath = result != null ? result.getReportPath() : null;
                if (result != null) {
                    if (result.getExecutionLog() != null) {
                        try {
                            execution.setExecutionLog(JSON.writeValueAsString(result.getExecutionLog()));
                            log.info("任务执行成功，已写入执行日志: taskId={}, 条数={}", task.getId(), result.getExecutionLog().size());
                        } catch (JsonProcessingException e) {
                            log.warn("序列化执行日志失败", e);
                        }
                    }
                    if (result.getExecutionVariables() != null && !result.getExecutionVariables().isEmpty()) {
                        try {
                            execution.setExecutionVariables(JSON.writeValueAsString(result.getExecutionVariables()));
                        } catch (JsonProcessingException e) {
                            log.warn("序列化执行变量失败", e);
                        }
                    }
                }
            } else if (taskType == Task.TaskType.STABILITY_QUALITY_TEST) {
                // 稳定性质量测试
                // TODO: 实现稳定性测试
                log.warn("稳定性质量测试尚未实现");
            } else if (taskType == Task.TaskType.PERFORMANCE_BASELINE_TEST) {
                PerformanceTestService.PerformanceTestExecutionResult perfResult = performanceTestService.runPerformanceTest(task, device);
                if (perfResult.getExecutionLog() != null) {
                    try {
                        execution.setExecutionLog(JSON.writeValueAsString(perfResult.getExecutionLog()));
                        log.info("性能测试执行完成，已写入执行日志: taskId={}, 条数={}", task.getId(), perfResult.getExecutionLog().size());
                    } catch (JsonProcessingException e) {
                        log.warn("序列化性能测试执行日志失败", e);
                    }
                }
                if (perfResult.getResultDataJson() != null) {
                    execution.setResultData(perfResult.getResultDataJson());
                }
                // 性能测试结果 JSON 可能很大（长遍历+采样+截图时间轴），若 result_data 仍为 TEXT(64KB) 会写入失败导致整次被标为失败
                task.setStatus(Task.TaskStatus.COMPLETED);
                task.setUpdatedAt(LocalDateTime.now());
                taskMapper.update(task);
                execution.setStatus(TaskExecution.ExecutionStatus.COMPLETED);
                execution.setEndTime(LocalDateTime.now());
                try {
                    executionMapper.update(execution);
                } catch (Exception saveEx) {
                    log.warn("保存性能测试 result_data 失败（可能超出数据库字段长度）: taskId={}, msg={}", task.getId(), saveEx.getMessage());
                    execution.setResultData("{\"conclusion\":\"执行完成，但结果数据过长无法保存。请执行 db/migration_result_data_mediumtext.sql 将 result_data 改为 MEDIUMTEXT 后重试。\",\"totalOperations\":0}");
                    execution.setErrorMessage("结果保存失败: " + (saveEx.getMessage() != null ? saveEx.getMessage() : saveEx.toString()));
                    try {
                        executionMapper.update(execution);
                    } catch (Exception ignore) { }
                }
                executionAlreadySaved = true;
            } else if (taskType == Task.TaskType.COMPATIBILITY_TEST) {
                CompatibilityTestService.CompatibilityTestResult compatResult = compatibilityTestService.runCompatibilityTest(task, device);
                reportPath = compatResult.getReportPath();
                if (compatResult.getResultData() != null) {
                    execution.setResultData(compatResult.getResultData());
                }
            } else if (taskType == Task.TaskType.INSTALL_UNINSTALL_UPGRADE_TEST) {
                log.info("执行安装卸载升级测试: taskId={}, deviceId={}, appId={}", task.getId(), task.getDeviceId(), task.getAppId());
                // TODO: 实现安装/卸载/升级自动化
            } else if (taskType == Task.TaskType.UI_ADAPTATION_TEST) {
                log.info("执行界面与适配测试: taskId={}, deviceId={}, appId={}", task.getId(), task.getDeviceId(), task.getAppId());
                // TODO: 实现界面与多分辨率适配检查
            } else {
                log.warn("任务类型 {} 尚未实现", taskType);
            }
            
            // 测试执行成功（性能测试已在分支内单独更新并保存，此处跳过）
            if (!executionAlreadySaved) {
                task.setStatus(Task.TaskStatus.COMPLETED);
                task.setUpdatedAt(LocalDateTime.now());
                taskMapper.update(task);
                execution.setStatus(TaskExecution.ExecutionStatus.COMPLETED);
                execution.setEndTime(LocalDateTime.now());
                if (reportPath != null) {
                    execution.setReportPath(reportPath);
                }
                executionMapper.update(execution);
            }
            
        } catch (Exception e) {
            log.error("测试执行失败", e);
            Task.TaskType t = normalizeTaskType(task.getTaskType());
            if (t == null) t = Task.TaskType.FUNCTIONAL_QUALITY_TEST;
            if (t == Task.TaskType.FUNCTIONAL_QUALITY_TEST) {
                var partialLog = loginTestService.getAndClearExecutionLog();
                if (partialLog != null && !partialLog.isEmpty()) {
                    try {
                        execution.setExecutionLog(JSON.writeValueAsString(partialLog));
                    } catch (JsonProcessingException ex) {
                        log.warn("序列化失败时的执行日志失败", ex);
                    }
                }
            }
            task.setStatus(Task.TaskStatus.FAILED);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.update(task);
            execution.setStatus(TaskExecution.ExecutionStatus.FAILED);
            execution.setEndTime(LocalDateTime.now());
            execution.setErrorMessage(e.getMessage());
            executionMapper.update(execution);
            // 不再 rethrow，保证本事务提交，前端能拿到 FAILED 状态与部分日志
        }
    }

    @SuppressWarnings("deprecation")
    private Task.TaskType normalizeTaskType(Task.TaskType taskType) {
        if (taskType == null) {
            return null;
        }
        // 兼容旧枚举值（历史数据）
        if (taskType == Task.TaskType.PERFORMANCE_QUALITY_TEST) {
            return Task.TaskType.PERFORMANCE_BASELINE_TEST;
        }
        if (taskType == Task.TaskType.POWER_CONSUMPTION_QUALITY_TEST) {
            return Task.TaskType.POWER_CONSUMPTION_BASELINE_TEST;
        }
        return taskType;
    }
}
