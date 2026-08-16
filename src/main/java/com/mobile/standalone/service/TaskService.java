package com.mobile.standalone.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FunctionalTestExecutionResult;
import com.mobile.standalone.dto.PageResult;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.config.NodeIdentity;
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

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 用于异步线程内通过代理调用，保证 executeTestTask 在独立事务中提交，前端能及时读到完成状态与日志/截图 */
    @Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private TaskService self;

    private final TaskMapper taskMapper;
    private final TaskExecutionMapper executionMapper;
    private final TaskNameGenerator taskNameGenerator;
    private final CompatibilityTestService compatibilityTestService;
    private final PerformanceTestService performanceTestService;
    private final UiFlowExecutor uiFlowExecutor;
    private final DeviceMapper deviceMapper;
    private final DeviceLockService deviceLockService;
    private final ExecutionCancelRegistry cancelRegistry;
    private final FailureDiagnosisRunner diagnosisRunner;
    private final NodeIdentity nodeIdentity;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, Process> runningTasks = new ConcurrentHashMap<>();

    /** 功能测试任务列表包含的任务类型（与 getFunctionalTestTasksFiltered 一致） */
    private static final List<Task.TaskType> FUNCTIONAL_TEST_TASK_TYPES = List.of(
            Task.TaskType.FUNCTIONAL_QUALITY_TEST,
            Task.TaskType.COMPATIBILITY_TEST,
            Task.TaskType.INSTALL_UNINSTALL_UPGRADE_TEST,
            Task.TaskType.UI_ADAPTATION_TEST,
            Task.TaskType.UI_FLOW_TEST
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
        if (task.getTaskType() == Task.TaskType.FUNCTIONAL_QUALITY_TEST) {
            throw new IllegalArgumentException(
                    "功能基础质量硬编码场景已下线，请改用「流程编排」创建 UI_FLOW_TEST 任务");
        }
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
        fillFlowColumns(task);
        if (task.getWorkerId() == null || task.getWorkerId().isBlank()) {
            task.setWorkerId(nodeIdentity.id());
        }
        taskMapper.insert(task);
        return task;
    }

    /**
     * 把 config 里的 flowId / runTrack 抽成列。
     * <p>
     * 只在这一处做：让每个建任务的地方各写一遍，迟早有人漏，
     * 而漏掉的现象是「这条用例永远显示未执行」——很难联想到是建任务时少塞了个字段。
     */
    static void fillFlowColumns(Task task) {
        if (task == null || task.getConfig() == null || task.getConfig().isBlank()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(task.getConfig());
            if (task.getFlowId() == null && n.hasNonNull("flowId")) {
                task.setFlowId(n.get("flowId").asText(null));
            }
            if (task.getRunTrack() == null && n.hasNonNull("runTrack")) {
                task.setRunTrack(n.get("runTrack").asText(null));
            }
        } catch (Exception e) {
            // config 不是合法 JSON 不该挡住建任务——这两列只是给查询用的冗余
            log.debug("解析 task.config 取 flowId/runTrack 失败: {}", e.getMessage());
        }
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
        return runTask(taskId, null);
    }

    /**
     * 提交任务执行。
     *
     * @param parentRefId 上级执行链标识（如线上回归的 runId）。父级已持有该设备时子任务可重入，
     *                    不会被自己所属的回归挡住，也不会在子任务结束时提前释放父锁。
     */
    @Transactional
    public boolean runTask(String taskId, String parentRefId) {
        Task task = taskMapper.findById(taskId);
        if (task == null || task.getStatus() == Task.TaskStatus.RUNNING) {
            return false;
        }

        // 同一设备互斥：功能 / 性能 / 流程 / 采集不能同时占一台机，避免互相干扰
        String deviceId = task.getDeviceId() != null && !task.getDeviceId().isBlank()
                ? task.getDeviceId().trim() : null;
        if (deviceId != null) {
            // 让锁服务能判断「这个持有者是不是已经被请求停止了」——
            // 停止请求发出后超过宽限期还不释放，说明执行线程已经死了，锁该被抢过来
            deviceLockService.setCancelledCheck(cancelRegistry::isCancelled);
            DeviceLockService.Holder blocker = deviceLockService.tryAcquire(
                    deviceId, DeviceLockService.Kind.TASK, taskId,
                    task.getName() != null ? task.getName() : taskId, parentRefId);
            if (blocker != null) {
                // 已收到停止请求但线程还在收尾时给出更准确的提示，避免用户以为「明明已停止却说被占用」
                String hint = cancelRegistry.isCancelled(blocker.refId())
                        ? "正在停止中，请稍候重试。"
                        : "请先等其结束或停止后再跑（功能与性能不要并行抢同一台机）。";
                throw new IllegalStateException(
                        "设备 " + deviceId + " 正被占用：" + blocker.describe() + "。" + hint);
            }
        }

        try {
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

            cancelRegistry.register(taskId);

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
                } finally {
                    cancelRegistry.clear(taskId);
                    if (deviceId != null) {
                        deviceLockService.release(deviceId, taskId);
                    }
                    // 放在设备锁释放之后：环境问题判定要自动重跑时，得能立刻拿到这台机。
                    // parentRefId 非空说明是线上回归编排在驱动，那边有自己的状态机，不能背着它重跑。
                    diagnosisRunner.onTaskFinished(taskId, parentRefId == null);
                }
            });
        } catch (RuntimeException e) {
            // 提交失败时别把设备锁留在原地
            cancelRegistry.clear(taskId);
            if (deviceId != null) {
                deviceLockService.release(deviceId, taskId);
            }
            throw e;
        }

        return true;
    }
    
    /**
     * 停止任务：下发取消标记让执行线程在下一步中断，同时销毁外部进程（若有）。
     * <p>
     * 流程/性能任务跑在应用线程里，只改数据库状态是停不掉的——必须走
     * {@link ExecutionCancelRegistry}，由 {@link UiFlowExecutor} 在每步前检查。
     * 设备锁由执行线程收尾时释放，这里不主动放，避免误放给下一个任务后两条流程同时操作同一台机。
     */
    @Transactional
    public boolean stopTask(String taskId) {
        Task task = taskMapper.findById(taskId);
        if (task == null) {
            return false;
        }
        boolean signalled = cancelRegistry.cancel(taskId);
        Process process = runningTasks.get(taskId);
        if (process != null && process.isAlive()) {
            process.destroy();
            runningTasks.remove(taskId);
        }
        if (task.getStatus() == Task.TaskStatus.RUNNING) {
            if (!signalled && process == null) {
                // 没有在跑的执行线程（服务重启等残留 RUNNING），直接置为已停止
                log.warn("任务标记为 RUNNING 但无在跑的执行线程，直接置为 STOPPED: taskId={}", taskId);
            }
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
        // 流程提前返回（如「元素不存在，结束流程」）时的判定，决定任务算完成/失败/跳过
        String earlyReturn = null;
        String earlyReturnMessage = null;
        boolean executionAlreadySaved = false; // 性能测试分支内已单独更新状态并保存 execution 时置为 true，避免下方重复更新
        try {
            // 若 DB 未存 task_type 或为 null，按流程任务处理（旧 FUNCTIONAL 硬编码已下线）
            Task.TaskType taskType = normalizeTaskType(task.getTaskType());
            if (taskType == null) {
                taskType = Task.TaskType.UI_FLOW_TEST;
                log.debug("任务 taskId={} 的 taskType 为空，按 UI_FLOW_TEST 执行", task.getId());
            }
            if (taskType == Task.TaskType.UI_FLOW_TEST) {
                FunctionalTestExecutionResult result = uiFlowExecutor.execute(task, device);
                reportPath = result != null ? result.getReportPath() : null;
                earlyReturn = result != null ? result.getEarlyReturnOutcome() : null;
                earlyReturnMessage = result != null ? result.getEarlyReturnMessage() : null;
                if (result != null) {
                    if (result.getExecutionLog() != null) {
                        try {
                            execution.setExecutionLog(JSON.writeValueAsString(result.getExecutionLog()));
                            log.info("流程任务执行完成: taskId={}, 日志条数={}", task.getId(), result.getExecutionLog().size());
                        } catch (JsonProcessingException e) {
                            log.warn("序列化流程执行日志失败", e);
                        }
                    }
                    if (result.getExecutionVariables() != null && !result.getExecutionVariables().isEmpty()) {
                        try {
                            execution.setExecutionVariables(JSON.writeValueAsString(result.getExecutionVariables()));
                        } catch (JsonProcessingException e) {
                            log.warn("序列化流程执行变量失败", e);
                        }
                    }
                }
            } else if (taskType == Task.TaskType.FUNCTIONAL_QUALITY_TEST) {
                String msg = "功能基础质量硬编码已下线，请改用流程编排（UI_FLOW_TEST）";
                log.warn("拒绝执行已下线任务类型: taskId={}, {}", task.getId(), msg);
                List<ExecutionLogEntryDto> retired = new ArrayList<>();
                retired.add(ExecutionLogEntryDto.fail("功能基础质量", msg, null));
                try {
                    execution.setExecutionLog(JSON.writeValueAsString(retired));
                } catch (JsonProcessingException e) {
                    log.warn("序列化下线提示日志失败", e);
                }
                throw new IllegalStateException(msg);
            } else if (taskType == Task.TaskType.STABILITY_QUALITY_TEST) {
                // 稳定性质量测试
                // TODO: 实现稳定性测试
                log.warn("稳定性质量测试尚未实现");
            } else if (taskType == Task.TaskType.PERFORMANCE_BASELINE_TEST
                    || taskType == Task.TaskType.SCENARIO_PERFORMANCE_TEST
                    || taskType == Task.TaskType.PERFORMANCE_QUALITY_TEST) {
                PerformanceTestService.PerformanceTestExecutionResult perfResult =
                        taskType == Task.TaskType.SCENARIO_PERFORMANCE_TEST
                                ? performanceTestService.runScenarioPerformanceTest(task, device)
                                : performanceTestService.runPerformanceTest(task, device);
                if (perfResult.getExecutionLog() != null) {
                    try {
                        execution.setExecutionLog(JSON.writeValueAsString(perfResult.getExecutionLog()));
                        log.info("性能测试执行完成，已写入执行日志: taskId={}, type={}, 条数={}",
                                task.getId(), taskType, perfResult.getExecutionLog().size());
                    } catch (JsonProcessingException e) {
                        log.warn("序列化性能测试执行日志失败", e);
                    }
                }
                if (perfResult.getResultDataJson() != null) {
                    execution.setResultData(perfResult.getResultDataJson());
                }
                // 性能测试结果 JSON 可能很大（长遍历+采样+截图时间轴），若 result_data 仍为 TEXT(64KB) 会写入失败导致整次被标为失败
                // 中途被停止时保留已采到的样本，但状态不能记成「完成」；
                // 流程没跑通时同理——采样只覆盖了一半路径，记「完成」会让性能数据失去可信度
                boolean perfCancelled = cancelRegistry.isCancelled(task.getId());
                Task.TaskStatus perfStatus;
                if (perfCancelled) {
                    perfStatus = Task.TaskStatus.STOPPED;
                } else if (perfResult.isFailed()) {
                    perfStatus = Task.TaskStatus.FAILED;
                } else {
                    perfStatus = Task.TaskStatus.COMPLETED;
                }
                task.setStatus(perfStatus);
                task.setUpdatedAt(LocalDateTime.now());
                taskMapper.update(task);
                execution.setStatus(TaskExecution.ExecutionStatus.valueOf(perfStatus.name()));
                execution.setEndTime(LocalDateTime.now());
                if (perfResult.isFailed() && perfResult.getErrorMessage() != null) {
                    execution.setErrorMessage(perfResult.getErrorMessage());
                }
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
                // 流程没跑完就提前返回时，按编排里声明的判定收尾，而不是一律算通过
                Task.TaskStatus finalStatus = resolveEarlyReturnStatus(earlyReturn);
                task.setStatus(finalStatus);
                task.setUpdatedAt(LocalDateTime.now());
                taskMapper.update(task);
                execution.setStatus(TaskExecution.ExecutionStatus.valueOf(finalStatus.name()));
                execution.setEndTime(LocalDateTime.now());
                if (finalStatus != Task.TaskStatus.COMPLETED && earlyReturnMessage != null) {
                    execution.setErrorMessage(earlyReturnMessage);
                }
                if (reportPath != null) {
                    execution.setReportPath(reportPath);
                }
                try {
                    executionMapper.update(execution);
                } catch (Exception saveEx) {
                    log.warn("保存执行记录失败，尝试去掉超长日志再存: taskId={}, err={}",
                            task.getId(), saveEx.getMessage());
                    // 截图已在磁盘，详情页可按 taskId 扫描；日志过大时保底状态
                    execution.setExecutionLog(compactExecutionLog(execution.getExecutionLog()));
                    try {
                        executionMapper.update(execution);
                    } catch (Exception ignore) {
                        log.error("二次保存执行记录仍失败: taskId={}", task.getId(), ignore);
                    }
                }
            }
            
        } catch (Exception e) {
            if (e instanceof UiFlowCancelledException) {
                log.info("任务已按用户请求停止: taskId={}", task.getId());
            } else {
                log.error("测试执行失败", e);
            }
            Task.TaskType t = normalizeTaskType(task.getTaskType());
            if (t == null) t = Task.TaskType.UI_FLOW_TEST;
            if (e instanceof UiFlowExecutionException ufe && ufe.getExecutionLog() != null) {
                try {
                    execution.setExecutionLog(JSON.writeValueAsString(ufe.getExecutionLog()));
                } catch (JsonProcessingException ex) {
                    log.warn("序列化流程失败日志失败", ex);
                }
                String flowShot = null;
                for (int i = ufe.getExecutionLog().size() - 1; i >= 0; i--) {
                    var entry = ufe.getExecutionLog().get(i);
                    if (entry != null && entry.getScreenshotPath() != null && !entry.getScreenshotPath().isBlank()) {
                        flowShot = entry.getScreenshotPath();
                        break;
                    }
                }
                if (flowShot != null) {
                    execution.setReportPath(flowShot);
                }
            }
            // 用户主动停止不是失败：状态归 STOPPED，避免污染通过率统计
            boolean cancelled = e instanceof UiFlowCancelledException || cancelRegistry.isCancelled(task.getId());
            task.setStatus(cancelled ? Task.TaskStatus.STOPPED : Task.TaskStatus.FAILED);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.update(task);
            execution.setStatus(cancelled
                    ? TaskExecution.ExecutionStatus.STOPPED
                    : TaskExecution.ExecutionStatus.FAILED);
            execution.setEndTime(LocalDateTime.now());
            execution.setErrorMessage(e.getMessage());
            try {
                executionMapper.update(execution);
            } catch (Exception saveEx) {
                log.warn("保存失败态执行记录失败，压缩日志重试: taskId={}, err={}",
                        task.getId(), saveEx.getMessage());
                execution.setExecutionLog(compactExecutionLog(execution.getExecutionLog()));
                try {
                    executionMapper.update(execution);
                } catch (Exception ignore) {
                    log.error("失败态执行记录仍无法保存: taskId={}", task.getId(), ignore);
                }
            }
            // 不再 rethrow，保证本事务提交，前端能拿到 FAILED 状态与部分日志
        }
    }

    /**
     * 流程提前返回的判定 → 任务状态。
     * <p>
     * 「判断元素存在才往下走」是主力编排写法，元素没出现时流程会提前返回。默认按 FAIL 处理：
     * 必现页面没出来就是缺陷，不该记成通过。可选弹窗请在该步骤把「判定」改成 PASS。
     */
    private static Task.TaskStatus resolveEarlyReturnStatus(String earlyReturnOutcome) {
        if (earlyReturnOutcome == null || earlyReturnOutcome.isBlank()) {
            return Task.TaskStatus.COMPLETED;
        }
        return switch (earlyReturnOutcome.trim().toUpperCase()) {
            case "PASS" -> Task.TaskStatus.COMPLETED;
            case "SKIP" -> Task.TaskStatus.SKIPPED;
            default -> Task.TaskStatus.FAILED;
        };
    }

    /** 日志过大无法入库时：去掉截图绝对路径（磁盘仍可按 taskId 扫描），尽量保住步骤文案 */
    private static String compactExecutionLog(String executionLogJson) {
        if (executionLogJson == null || executionLogJson.isBlank()) {
            return "[{\"step\":\"截图\",\"status\":\"success\",\"level\":\"Info\",\"message\":\"日志过长已省略路径，请按任务截图列表查看\"}]";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr = JSON.readTree(executionLogJson);
            if (!arr.isArray()) {
                return "[{\"step\":\"执行日志\",\"status\":\"success\",\"level\":\"Info\",\"message\":\"日志已压缩\"}]";
            }
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                if (n instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                    obj.remove("screenshotPath");
                    obj.remove("screenshotUrl");
                }
            }
            String compact = JSON.writeValueAsString(arr);
            if (compact.length() > 12_000_000) {
                return "[{\"step\":\"执行日志\",\"status\":\"success\",\"level\":\"Info\",\"message\":\"日志过长，请查看任务截图列表\"}]";
            }
            return compact;
        } catch (Exception e) {
            return "[{\"step\":\"执行日志\",\"status\":\"success\",\"level\":\"Info\",\"message\":\"日志压缩失败，请查看任务截图列表\"}]";
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
