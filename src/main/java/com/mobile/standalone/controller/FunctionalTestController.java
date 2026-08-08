package com.mobile.standalone.controller;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.PageResult;
import com.mobile.standalone.dto.TaskOverviewDto;
import com.mobile.standalone.service.FunctionalTestScenarioService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.MetricKitIngestService;
import com.mobile.standalone.service.ScreenshotDiscoveryService;
import com.mobile.standalone.service.TaskOverviewService;
import com.mobile.standalone.service.TaskService;
import com.mobile.standalone.util.ExecutionLogJson;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/functional-test")
@RequiredArgsConstructor
public class FunctionalTestController {

    private static final Path TMP_SCREENSHOTS_DIR = Paths.get("/tmp/screenshots").normalize().toAbsolutePath();
    private static final Path FLOW_SCREENSHOTS_DIR = Paths.get(System.getProperty("user.home"), ".mobile-standalone", "screenshots")
            .normalize().toAbsolutePath();

    private final TaskService taskService;
    private final FunctionalTestScenarioService scenarioService;
    private final TaskExecutionMapper executionMapper;
    private final DeviceMapper deviceMapper;
    private final TaskOverviewService taskOverviewService;
    private final MetricKitIngestService metricKitIngestService;

    /** 场景化性能测试场景列表（已空：业务路径统一走流程编排 flowId） */
    @GetMapping("/scenarios/scenario-performance")
    public ResponseEntity<List<ScenarioItem>> listScenarioPerformanceScenarios(
            @RequestParam(required = false) String platform) {
        return ResponseEntity.ok(
                scenarioService.listScenarioPerformanceScenarios(platform).stream()
                        .map(s -> new ScenarioItem(s.getId(), s.getName(), s.getPlatform(), s.getTaskType().name(), s.getDescription(), s.getScenarioKey()))
                        .toList()
        );
    }

    /**
     * @deprecated 功能基础质量硬编码已下线；请改用流程编排创建 UI_FLOW_TEST。
     */
    @Deprecated
    @PostMapping("/tasks")
    public ResponseEntity<?> createTask(@RequestBody CreateTaskRequest request) {
        return ResponseEntity.status(410).body(java.util.Map.of(
                "message", "功能基础质量硬编码场景已下线，请改用「流程编排」创建并执行流程任务"));
    }

    /**
     * 获取功能/兼容/流程等任务列表（分页）
     * 返回字段含 deviceName，便于前端卡片展示「设备名称」。
     */
    @GetMapping("/tasks")
    public ResponseEntity<PageResult<TaskResponse>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size
    ) {
        Task.TaskStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = Task.TaskStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) { }
        }
        PageResult<Task> pageResult = taskService.getFunctionalTestTasksFilteredPage(
                statusEnum, keyword, days, page != null ? page : 1, size != null ? size : 10);
        List<TaskResponse> list = pageResult.getContent().stream().map(task -> {
            TaskResponse resp = toResponse(task);
            Device device = deviceMapper.findById(task.getDeviceId());
            resp.setDeviceName(device != null ? device.getName() : null);
            return resp;
        }).toList();
        PageResult<TaskResponse> result = new PageResult<>(
                list, pageResult.getTotalElements(), pageResult.getTotalPages(), pageResult.getPage(), pageResult.getSize());
        return ResponseEntity.ok(result);
    }

    /**
     * 获取功能质量体验测试任务详情
     */
    @GetMapping("/tasks/{id}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable String id) {
        Task task = taskService.getTaskById(id);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        Task.TaskType type = task.getTaskType();
        if (type != Task.TaskType.FUNCTIONAL_QUALITY_TEST && type != Task.TaskType.COMPATIBILITY_TEST
                && type != Task.TaskType.INSTALL_UNINSTALL_UPGRADE_TEST && type != Task.TaskType.UI_ADAPTATION_TEST
                && type != Task.TaskType.UI_FLOW_TEST) {
            return ResponseEntity.notFound().build();
        }
        TaskResponse response = toResponse(task);
        Device device = deviceMapper.findById(task.getDeviceId());
        response.setDeviceName(device != null ? device.getName() : null);
        List<TaskExecution> executions = executionMapper.findByTaskIdOrderByStartTimeDesc(id);
        if (!executions.isEmpty()) {
            TaskExecution latest = executions.get(0);
            response.setExecutionStatus(latest.getStatus() != null ? latest.getStatus().name() : null);
            response.setReportPath(latest.getReportPath());
            response.setErrorMessage(latest.getErrorMessage());
            response.setExecutionEndTime(latest.getEndTime());
            response.setResultData(latest.getResultData());
            List<ExecutionLogEntryDto> executionLog = parseExecutionLog(latest.getExecutionLog());
            if (executionLog != null) {
                fillScreenshotUrls(executionLog, id);
            }
            response.setExecutionLog(executionLog != null ? executionLog : Collections.emptyList());
            response.setExecutionVariables(parseExecutionVariables(latest.getExecutionVariables()));
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 获取任务最近一次执行的截图（用于前端展示）
     * 若无可用的 reportPath 或文件不存在则 404。
     */
    @GetMapping(value = "/tasks/{id}/screenshot", produces = "image/png")
    public ResponseEntity<byte[]> getTaskScreenshot(@PathVariable String id) {
        List<TaskExecution> executions = executionMapper.findByTaskIdOrderByStartTimeDesc(id);
        if (executions.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TaskExecution latest = executions.get(0);
        String reportPath = latest.getReportPath();
        if (reportPath != null && !reportPath.isBlank()) {
            ResponseEntity<byte[]> body = readAllowedScreenshot(Paths.get(reportPath));
            if (body.getStatusCode().is2xxSuccessful()) {
                return body;
            }
        }
        // 流程任务等可能只在步骤里留了截图：取执行日志/磁盘扫描中最后一张
        String fallback = lastScreenshotPathFromLog(latest.getExecutionLog());
        if (fallback == null) {
            List<ExecutionLogEntryDto> discovered = ScreenshotDiscoveryService.discoverStepScreenshots(id);
            if (!discovered.isEmpty()) {
                ExecutionLogEntryDto last = discovered.get(discovered.size() - 1);
                fallback = last != null ? last.getScreenshotPath() : null;
            }
        }
        if (fallback == null || fallback.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return readAllowedScreenshot(Paths.get(fallback));
    }

    private String lastScreenshotPathFromLog(String executionLogJson) {
        List<ExecutionLogEntryDto> log = parseExecutionLog(executionLogJson);
        if (log == null || log.isEmpty()) {
            return null;
        }
        for (int i = log.size() - 1; i >= 0; i--) {
            ExecutionLogEntryDto entry = log.get(i);
            if (entry != null && entry.getScreenshotPath() != null && !entry.getScreenshotPath().isBlank()) {
                return entry.getScreenshotPath();
            }
        }
        return null;
    }

    /**
     * 获取任务某一步骤的截图（功能体验测试每步截图，步骤顺序与 executionLog 数组下标一致）
     * 若该步骤无截图或文件不存在则 404。
     */
    @GetMapping(value = "/tasks/{id}/screenshots/{stepIndex}", produces = "image/png")
    public ResponseEntity<byte[]> getTaskStepScreenshot(@PathVariable String id, @PathVariable int stepIndex) {
        if (stepIndex < 0) {
            return ResponseEntity.badRequest().build();
        }
        String screenshotPath = resolveStepScreenshotPath(id, stepIndex);
        if (screenshotPath == null || screenshotPath.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return readAllowedScreenshot(Paths.get(screenshotPath));
    }

    /**
     * 任务截图列表：按 taskId 扫描 /tmp/screenshots，或回退到执行日志中带截图的步骤。
     * 任务不存在返回 404；无截图返回空数组。
     */
    @GetMapping("/tasks/{id}/screenshot-list")
    public ResponseEntity<List<ScreenshotListItem>> getTaskScreenshotList(@PathVariable String id) {
        Task task = taskService.getTaskById(id);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        List<ScreenshotListItem> items = new ArrayList<>();
        List<ExecutionLogEntryDto> discovered = ScreenshotDiscoveryService.discoverStepScreenshots(id);
        if (!discovered.isEmpty()) {
            for (int i = 0; i < discovered.size(); i++) {
                ExecutionLogEntryDto entry = discovered.get(i);
                items.add(new ScreenshotListItem(i,
                        "functional-test/tasks/" + id + "/screenshots/" + i,
                        entry.getStep() != null ? entry.getStep() : ("步骤 " + i)));
            }
            return ResponseEntity.ok(items);
        }
        List<TaskExecution> executions = executionMapper.findByTaskIdOrderByStartTimeDesc(id);
        if (executions.isEmpty()) {
            return ResponseEntity.ok(items);
        }
        List<ExecutionLogEntryDto> log = parseExecutionLog(executions.get(0).getExecutionLog());
        if (log == null) {
            return ResponseEntity.ok(items);
        }
        for (int i = 0; i < log.size(); i++) {
            ExecutionLogEntryDto entry = log.get(i);
            if (entry == null || entry.getScreenshotPath() == null || entry.getScreenshotPath().isBlank()) {
                continue;
            }
            items.add(new ScreenshotListItem(i,
                    "functional-test/tasks/" + id + "/screenshots/" + i,
                    entry.getStep() != null ? entry.getStep() : ("步骤 " + i)));
        }
        return ResponseEntity.ok(items);
    }

    /**
     * 获取任务最近一次执行的步骤日志（用于「执行日志」面板）
     */
    @GetMapping("/tasks/{id}/execution-log")
    public ResponseEntity<List<ExecutionLogEntryDto>> getTaskExecutionLog(@PathVariable String id) {
        List<TaskExecution> executions = executionMapper.findByTaskIdOrderByStartTimeDesc(id);
        if (executions.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<ExecutionLogEntryDto> log = parseExecutionLog(executions.get(0).getExecutionLog());
        if (log != null) {
            fillScreenshotUrls(log, id);
        }
        return ResponseEntity.ok(log != null ? log : Collections.emptyList());
    }

    /** 优先执行日志中的 screenshotPath，缺失时回退到磁盘扫描结果（与 overview 一致） */
    private String resolveStepScreenshotPath(String taskId, int stepIndex) {
        List<TaskExecution> executions = executionMapper.findByTaskIdOrderByStartTimeDesc(taskId);
        if (!executions.isEmpty()) {
            List<ExecutionLogEntryDto> log = parseExecutionLog(executions.get(0).getExecutionLog());
            if (log != null && stepIndex >= 0 && stepIndex < log.size()) {
                ExecutionLogEntryDto entry = log.get(stepIndex);
                if (entry != null && entry.getScreenshotPath() != null && !entry.getScreenshotPath().isBlank()) {
                    Path path = Paths.get(entry.getScreenshotPath()).normalize().toAbsolutePath();
                    if (isAllowedScreenshotPath(path) && Files.isRegularFile(path)) {
                        return path.toString();
                    }
                }
            }
        }
        List<ExecutionLogEntryDto> discovered = ScreenshotDiscoveryService.discoverStepScreenshots(taskId);
        if (stepIndex >= 0 && stepIndex < discovered.size()) {
            ExecutionLogEntryDto entry = discovered.get(stepIndex);
            if (entry != null && entry.getScreenshotPath() != null && !entry.getScreenshotPath().isBlank()) {
                return entry.getScreenshotPath();
            }
        }
        return null;
    }

    private ResponseEntity<byte[]> readAllowedScreenshot(Path rawPath) {
        Path path = rawPath.normalize().toAbsolutePath();
        if (!isAllowedScreenshotPath(path) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok().body(Files.readAllBytes(path));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private static boolean isAllowedScreenshotPath(Path path) {
        return path.startsWith(TMP_SCREENSHOTS_DIR) || path.startsWith(FLOW_SCREENSHOTS_DIR);
    }

    private static final ObjectMapper JSON = ExecutionLogJson.mapper();
    private static final TypeReference<java.util.Map<String, String>> EXECUTION_VARIABLES_TYPE = new TypeReference<>() {};

    private List<ExecutionLogEntryDto> parseExecutionLog(String executionLogJson) {
        return ExecutionLogJson.parse(executionLogJson);
    }

    /** 为执行日志中带截图的步骤填充 screenshotUrl，供前端直接请求步骤截图 */
    private void fillScreenshotUrls(List<ExecutionLogEntryDto> log, String taskId) {
        if (log == null || taskId == null) return;
        for (int i = 0; i < log.size(); i++) {
            ExecutionLogEntryDto entry = log.get(i);
            if (entry != null && entry.getScreenshotPath() != null && !entry.getScreenshotPath().isBlank()) {
                entry.setScreenshotUrl("functional-test/tasks/" + taskId + "/screenshots/" + i);
            }
        }
    }

    private java.util.Map<String, String> parseExecutionVariables(String executionVariablesJson) {
        if (executionVariablesJson == null || executionVariablesJson.isBlank()) return null;
        try {
            return JSON.readValue(executionVariablesJson, EXECUTION_VARIABLES_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取任务详情概览（任务详情页用）
     * 返回：任务标题、测试类型、应用包名/版本、API版本、开始时间、持续时间、执行人、测试结果列表。
     * 当前仅支持「功能体验基础质量测试」；执行人固定为「杨俊俊」。
     */
    @GetMapping("/tasks/{id}/overview")
    public ResponseEntity<TaskOverviewDto> getTaskOverview(@PathVariable String id) {
        TaskOverviewDto overview = taskOverviewService.getOverview(id);
        if (overview == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(overview);
    }

    /**
     * 获取性能基础质量测试的报告数据（最新一次执行的 result_data JSON）。
     * 仅任务类型为 PERFORMANCE_BASELINE_TEST 时有效；前端用此接口渲染「性能报告」区块，不要当作静态资源路径请求。
     */
    @GetMapping("/tasks/{id}/performance-report")
    public ResponseEntity<?> getPerformanceReport(@PathVariable String id) {
        Task task = taskService.getTaskById(id);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        Task.TaskType type = task.getTaskType();
        if (!isPerformanceReportTaskType(type)) {
            return ResponseEntity.notFound().build();
        }
        List<TaskExecution> executions = executionMapper.findByTaskIdOrderByStartTimeDesc(id);
        if (executions.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String resultData = executions.get(0).getResultData();
        if (resultData == null || resultData.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            Object parsed = JSON.readValue(resultData, Object.class);
            parsed = metricKitIngestService.mergeIntoReport(id, parsed);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(resultData);
        }
    }

    /**
     * iOS 测试包 MetricKit 上报入口。App 在收到 MXMetricPayload 后 POST 原始 JSON。
     * 读 performance-report 时会合并为 metricKitSummary（可晚于任务结束到达）。
     */
    @PostMapping("/tasks/{id}/metrickit")
    public ResponseEntity<?> ingestMetricKit(@PathVariable String id, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        Task task = taskService.getTaskById(id);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isPerformanceReportTaskType(task.getTaskType())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "仅性能任务可上报 MetricKit"));
        }
        try {
            metricKitIngestService.ingest(id, body);
            return ResponseEntity.ok(java.util.Map.of("ok", true, "taskId", id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tasks/{id}/metrickit")
    public ResponseEntity<?> getMetricKit(@PathVariable String id) {
        Task task = taskService.getTaskById(id);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        var node = metricKitIngestService.get(id);
        if (node == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(node);
    }

    private static boolean isPerformanceReportTaskType(Task.TaskType type) {
        return type == Task.TaskType.PERFORMANCE_BASELINE_TEST
                || type == Task.TaskType.SCENARIO_PERFORMANCE_TEST
                || type == Task.TaskType.PERFORMANCE_QUALITY_TEST;
    }

    /**
     * 获取性能测试遍历期间的第 index 张截图（每秒/按间隔截的图）。
     * 仅性能任务、且最新执行有 screenshotCount 时有效；index 从 0 开始。
     */
    @GetMapping(value = "/tasks/{id}/performance-screenshots/{index}", produces = "image/png")
    public ResponseEntity<byte[]> getPerformanceScreenshot(@PathVariable String id, @PathVariable int index) {
        if (index < 0) {
            return ResponseEntity.badRequest().build();
        }
        Task task = taskService.getTaskById(id);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        Task.TaskType type = task.getTaskType();
        if (!isPerformanceReportTaskType(type)) {
            return ResponseEntity.notFound().build();
        }
        List<TaskExecution> executions = executionMapper.findByTaskIdOrderByStartTimeDesc(id);
        if (executions.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String resultData = executions.get(0).getResultData();
        Integer screenshotCount = null;
        if (resultData != null && !resultData.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(resultData);
                if (node != null && node.has("screenshotCount")) {
                    screenshotCount = node.get("screenshotCount").asInt(0);
                }
            } catch (Exception ignored) { }
        }
        if (screenshotCount != null && index >= screenshotCount) {
            return ResponseEntity.notFound().build();
        }
        Path baseDir = Paths.get("/tmp/screenshots/perf_" + id).normalize().toAbsolutePath();
        Path path = baseDir.resolve("img_" + index + ".png").normalize();
        if (!path.startsWith(baseDir)) {
            return ResponseEntity.notFound().build();
        }
        if (!path.toFile().exists() || !path.toFile().isFile()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return ResponseEntity.ok().body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private TaskResponse toResponse(Task task) {
        TaskResponse response = new TaskResponse();
        response.setId(task.getId());
        response.setTaskName(task.getName());
        response.setRemarks(task.getDescription());
        response.setPlatform(task.getPlatform() == null ? null : task.getPlatform().name());
        response.setDeviceId(task.getDeviceId());
        response.setApplicationId(task.getAppId());
        response.setStatus(task.getStatus() == null ? null : task.getStatus().name());
        response.setCreatedAt(task.getCreatedAt());
        response.setTaskType(task.getTaskType() == null ? null : task.getTaskType().name());
        response.setScenarioKey(task.getScenarioKey());
        response.setScenarioName(scenarioService.getScenarioDisplayNameOrFallback(task));
        return response;
    }
    
    /**
     * 解析平台枚举
     */
    private Task.Platform parsePlatform(String platform) {
        try {
            return Task.Platform.valueOf(platform.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("不支持的平台类型: " + platform);
        }
    }
    
    /**
     * 创建任务请求
     */
    @Data
    public static class CreateTaskRequest {
        private String taskName;        // 任务名称（可选，1-60字符）
        private String remarks;        // 备注信息（可选，最多80字符）
        private String platform;        // 平台类型：ANDROID | IOS | HARMONYOS
        private String deviceId;        // 测试设备ID
        private String applicationId;   // 测试应用ID
        /** 测试场景键：launch_app=启动应用程序, login=登录, search=搜索。不传时执行逻辑按应用兼容性走默认（如支持则登录） */
        private String scenarioKey;
        /** 可选 JSON 配置，如登录测试：{"phoneNumber":"13800138000","verificationCode":"666888"} */
        private String config;
    }
    
    /**
     * 创建任务响应
     */
    @Data
    public static class TaskResponse {
        private String id;
        private String taskName;
        private String remarks;
        private String platform;
        private String deviceId;
        private String applicationId;
        /** 设备名称，便于前端卡片展示（如截图中的「设备名称」） */
        private String deviceName;
        private String status;
        /** 任务类型，如 FUNCTIONAL_QUALITY_TEST、COMPATIBILITY_TEST */
        private String taskType;
        /** 测试场景键：launch_app / login / search，用于区分「具体做什么」 */
        private String scenarioKey;
        /** 测试场景展示名，如「启动应用程序(Android)」「登录测试(iOS)」 */
        private String scenarioName;
        private LocalDateTime createdAt;
        /** 最新一次执行的状态（COMPLETED/FAILED 等） */
        private String executionStatus;
        /** 登录完成后的截图路径，便于前端展示执行结果 */
        private String reportPath;
        /** 执行失败时的错误信息 */
        private String errorMessage;
        /** 执行结束时间 */
        private LocalDateTime executionEndTime;
        /** 结构化结果 JSON（如兼容性测试的 device/app/sessionInfo） */
        private String resultData;
        /** 执行步骤日志（功能体验测试等），用于「执行日志」展示 */
        private List<ExecutionLogEntryDto> executionLog;
        /** 执行变量脱敏值，如 phoneNumberMasked、verificationCodeMasked，供检测规则「详细日志」列展示 */
        private java.util.Map<String, String> executionVariables;
    }

    @lombok.Data
    public static class ScenarioItem {
        private String id;
        private String name;
        private String platform;
        private String taskType;
        private String description;
        /** 场景键：launch_app / login / search，创建任务时传此字段给 scenarioKey */
        private String scenarioKey;

        public ScenarioItem(String id, String name, String platform, String taskType, String description, String scenarioKey) {
            this.id = id;
            this.name = name;
            this.platform = platform;
            this.taskType = taskType;
            this.description = description;
            this.scenarioKey = scenarioKey;
        }
    }

    @lombok.Data
    public static class ExecuteRequest {
        private String scenarioId;
        private String deviceId;
        private String applicationId;
        /** 可选 JSON，如 {"phoneNumber":"13800138000","verificationCode":"666888"} */
        private String config;
    }

    @lombok.Data
    public static class ExecuteResponse {
        private String taskId;
        private String status;
        private String message;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ScreenshotListItem {
        private int index;
        private String screenshotUrl;
        private String step;
    }
}
