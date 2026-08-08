package com.mobile.standalone.service;

import com.mobile.standalone.dto.CompatibilitySummaryDto;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.ResultDistributionItemDto;
import com.mobile.standalone.dto.TaskOverviewDto;
import com.mobile.standalone.dto.TestResultItemDto;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.dto.FailureDiagnosisDtos;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskFailureDiagnosis;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.entity.Device;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.util.ExecutionLogJson;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务详情页数据组装，与前端截图一致。
 * 包含：任务 ID/标题/备注、测试类型、应用信息、开始/持续时间、执行人、
 * 概览（兼容性满足度、测试结果分布）、检测规则表格、报告路径。
 * 支持功能基础质量测试、兼容性测试、性能基础质量测试。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TaskOverviewService {

    private static final String EXECUTOR_FIXED = "杨俊俊";
    private static final String RESULT_PASS = "通过";
    private static final String RESULT_FAIL = "不通过";
    private static final String RESULT_WARN = "告警";
    private static final String RESULT_NOT_RUN = "未执行";
    private static final String RESULT_DEVICE_DEPENDENT = "设备依赖";
    /** 兼容性测试时，概览统计区块标题 */
    private static final String SUMMARY_TITLE_COMPATIBILITY = "兼容性满足度";

    private static final ObjectMapper JSON = ExecutionLogJson.mapper();
    private static final TypeReference<Map<String, String>> MAP_STRING_STRING = new TypeReference<>() {};

    private final TaskMapper taskMapper;
    private final TaskExecutionMapper executionMapper;
    private final ApplicationMapper applicationMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceManager deviceManager;
    private final FunctionalTestScenarioService scenarioService;
    private final FailureDiagnosisService diagnosisService;

    /**
     * 获取任务详情概览，用于前端任务详情页展示。
     * 支持 FUNCTIONAL_QUALITY_TEST、COMPATIBILITY_TEST、PERFORMANCE_BASELINE_TEST、
     * SCENARIO_PERFORMANCE_TEST、UI_FLOW_TEST；其他类型返回 null。
     */
    public TaskOverviewDto getOverview(String taskId) {
        Task task = taskMapper.findById(taskId);
        if (task == null) {
            return null;
        }
        Task.TaskType type = task.getTaskType();
        if (type != Task.TaskType.FUNCTIONAL_QUALITY_TEST
                && type != Task.TaskType.COMPATIBILITY_TEST
                && type != Task.TaskType.PERFORMANCE_BASELINE_TEST
                && type != Task.TaskType.SCENARIO_PERFORMANCE_TEST
                && type != Task.TaskType.UI_FLOW_TEST) {
            return null;
        }

        TaskOverviewDto dto = new TaskOverviewDto();
        dto.setTaskId(task.getId());
        dto.setTitle(task.getName() != null ? task.getName() : type.getDisplayName());
        dto.setRemarks(task.getDescription());
        dto.setScenarioKey(task.getScenarioKey());
        dto.setScenarioName(scenarioService.getScenarioDisplayNameOrFallback(task));
        dto.setTestType(type == Task.TaskType.COMPATIBILITY_TEST ? "应用" : type.getDisplayName());
        dto.setExecutor(EXECUTOR_FIXED);
        dto.setApiVersion(null);

        Application app = task.getAppId() != null ? applicationMapper.findById(task.getAppId()) : null;
        if (app != null) {
            dto.setAppPackageName(app.getPackageName());
            String version = resolveAppVersion(task, app);
            dto.setAppVersion(version);
        } else {
            dto.setAppPackageName(null);
            dto.setAppVersion(null);
        }

        List<TaskExecution> executions = executionMapper.findByTaskIdOrderByStartTimeDesc(taskId);
        TaskExecution latest = executions.isEmpty() ? null : executions.get(0);

        if (latest != null) {
            dto.setStartTime(latest.getStartTime());
            dto.setDuration(formatDuration(latest.getStartTime(), latest.getEndTime()));
            dto.setReportPath(latest.getReportPath());
            dto.setStatus(latest.getStatus() != null ? latest.getStatus().name() : null);
            dto.setExecutionStatus(dto.getStatus());
            dto.setErrorMessage(latest.getErrorMessage());
            List<ExecutionLogEntryDto> discovery = ScreenshotDiscoveryService.discoverStepScreenshots(taskId);
            List<ExecutionLogEntryDto> fromDb = parseExecutionLog(latest.getExecutionLog());
            // 优先用库内步骤日志（含 status/message）；磁盘发现仅补截图。旧逻辑「有截图就整表替换」会导致步骤结果丢失。
            List<ExecutionLogEntryDto> executionLog = mergeExecutionLogWithDiscovery(fromDb, discovery);
            if (executionLog != null) {
                fillScreenshotUrls(executionLog, taskId);
            }
            dto.setExecutionLog(executionLog != null ? executionLog : java.util.Collections.emptyList());
        } else {
            dto.setStartTime(null);
            dto.setDuration(null);
            dto.setReportPath(null);
            // 无 execution 行时仍展示磁盘上的流程截图（此前会整页「暂无截图」）
            List<ExecutionLogEntryDto> discovery = ScreenshotDiscoveryService.discoverStepScreenshots(taskId);
            if (!discovery.isEmpty()) {
                fillScreenshotUrls(discovery, taskId);
                dto.setExecutionLog(discovery);
            } else {
                dto.setExecutionLog(java.util.Collections.emptyList());
            }
            dto.setStatus(task.getStatus() != null ? task.getStatus().name() : null);
            dto.setExecutionStatus(dto.getStatus());
            dto.setErrorMessage(null);
        }

        String environment = readEnvironment(task.getConfig());
        dto.setEnvironment(environment);
        dto.setEnvironmentLabel(TestEnvironment.label(environment));

        TaskFailureDiagnosis diagnosis = safeCurrentDiagnosis(taskId);
        dto.setFailureDiagnosis(diagnosis);

        if (type == Task.TaskType.UI_FLOW_TEST) {
            dto.setSummarySectionTitle("流程步骤通过率");
            dto.setTestType(type.getDisplayName());
            List<ExecutionLogEntryDto> executionLog = dto.getExecutionLog();
            if (executionLog == null || executionLog.isEmpty()) {
                executionLog = parseExecutionLog(latest != null ? latest.getExecutionLog() : null);
            }
            List<TestResultItemDto> items = buildUiFlowResultItems(executionLog);
            dto.setTestResultItems(items);
            dto.setCompatibilitySummary(withFunctionalHealth(buildSummaryFromItems(items), isFlaky(diagnosis)));
            dto.setResultDistribution(buildDistributionFromItems(items));
            Map<String, String> executionVariables = parseExecutionVariables(latest != null ? latest.getExecutionVariables() : null);
            dto.setExecutionVariables(executionVariables);
        } else if (type == Task.TaskType.FUNCTIONAL_QUALITY_TEST) {
            // 硬编码场景已下线：历史任务仅按执行日志通用展示
            dto.setSummarySectionTitle("功能基础质量(已下线)");
            dto.setTestType(type.getDisplayName());
            List<ExecutionLogEntryDto> executionLog = dto.getExecutionLog();
            if (executionLog == null || executionLog.isEmpty()) {
                executionLog = parseExecutionLog(latest != null ? latest.getExecutionLog() : null);
            }
            List<TestResultItemDto> items = buildUiFlowResultItems(executionLog);
            if (items == null || items.isEmpty()) {
                String sk = task.getScenarioKey() != null ? task.getScenarioKey() : "";
                items = List.of(new TestResultItemDto("已下线",
                        sk.isBlank() ? "请改用流程编排查看新报告" : "场景 " + sk + " 已下线",
                        RESULT_NOT_RUN, null, "功能基础质量硬编码已移除"));
            }
            dto.setTestResultItems(items);
            dto.setCompatibilitySummary(buildSummaryFromItems(items));
            dto.setResultDistribution(buildDistributionFromItems(items));
            Map<String, String> executionVariables = parseExecutionVariables(latest != null ? latest.getExecutionVariables() : null);
            dto.setExecutionVariables(executionVariables);
        } else if (type == Task.TaskType.PERFORMANCE_BASELINE_TEST
                || type == Task.TaskType.SCENARIO_PERFORMANCE_TEST) {
            dto.setSummarySectionTitle("性能测试结果");
            Boolean passed = latest == null ? null : (latest.getStatus() == TaskExecution.ExecutionStatus.COMPLETED);
            List<ExecutionLogEntryDto> executionLog = parseExecutionLog(latest != null ? latest.getExecutionLog() : null);
            List<TestResultItemDto> items = buildPerformanceResultItems(passed, executionLog);
            dto.setTestResultItems(items);
            dto.setCompatibilitySummary(buildSummaryFromItems(items));
            dto.setResultDistribution(buildDistributionFromItems(items));
        } else {
            dto.setSummarySectionTitle(SUMMARY_TITLE_COMPATIBILITY);
            boolean completed = latest != null && latest.getStatus() == TaskExecution.ExecutionStatus.COMPLETED;
            List<TestResultItemDto> items = buildCompatibilityResultItems(completed);
            dto.setTestResultItems(items);
            dto.setCompatibilitySummary(buildSummaryFromItems(items));
            dto.setResultDistribution(buildDistributionFromItems(items));
        }

        return dto;
    }

    /** 旧功能基础质量场景拼装已移除；历史任务走 buildUiFlowResultItems。 */

    private String resolveAppVersion(Task task, Application app) {
        String deviceId = task.getDeviceId();
        String packageName = app.getPackageName();
        if (deviceId == null || packageName == null || packageName.isBlank()) {
            return app.getVersion();
        }
        Device device = deviceMapper.findById(deviceId);
        if (device == null) return app.getVersion();
        if (device.getPlatform() == Device.Platform.ANDROID || device.getPlatform() == Device.Platform.HARMONYOS
                || device.getPlatform() == Device.Platform.POG_ANDROID) {
            String fromDevice = deviceManager.getAndroidAppVersion(deviceId, packageName);
            if (fromDevice != null && !fromDevice.isBlank()) return fromDevice;
        }
        return app.getVersion();
    }

    private List<ExecutionLogEntryDto> parseExecutionLog(String executionLogJson) {
        return ExecutionLogJson.parse(executionLogJson);
    }

    /**
     * 库内日志优先；若无库日志则回退磁盘发现。有库日志时不要把磁盘图按序塞进无截图步骤。
     */
    private List<ExecutionLogEntryDto> mergeExecutionLogWithDiscovery(
            List<ExecutionLogEntryDto> fromDb,
            List<ExecutionLogEntryDto> discovery
    ) {
        if (fromDb != null && !fromDb.isEmpty()) {
            return fromDb;
        }
        return discovery != null ? discovery : List.of();
    }

    private void fillScreenshotUrls(List<ExecutionLogEntryDto> log, String taskId) {
        if (log == null || taskId == null) return;
        for (int i = 0; i < log.size(); i++) {
            ExecutionLogEntryDto entry = log.get(i);
            if (entry != null && entry.getScreenshotPath() != null && !entry.getScreenshotPath().isBlank()) {
                entry.setScreenshotUrl("functional-test/tasks/" + taskId + "/screenshots/" + i);
            }
        }
    }

    /** 性能基础质量测试：按执行日志生成测项；无日志时单条「性能遍历与资源采样」。 */

    private Map<String, String> parseExecutionVariables(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return JSON.readValue(json, MAP_STRING_STRING);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 补上功能健康度。
     * <p>
     * <b>执行达标率</b>照常把偶现算作不通过——它回答「这轮跑得顺不顺」，脚本不稳就该体现在这个数上，
     * 藏起来等于把自动化自身的健康度也一并藏了。
     * <b>功能健康度</b>把偶现剔除，回答「功能是不是真坏了」。
     * <p>
     * 两个数指向的责任人不同，所以必须并列给出，而不是二选一。
     */
    private CompatibilitySummaryDto withFunctionalHealth(CompatibilitySummaryDto summary, boolean flaky) {
        if (summary == null) {
            return null;
        }
        int passed = summary.getPassedCount();
        int failed = summary.getFailedCount();
        if (!flaky || failed == 0) {
            // 没判偶现，两个口径本来就一样，不必给出第二个数徒增困惑
            summary.setFunctionalHealthPercent(null);
            summary.setFlakyExcludedCount(0);
            return summary;
        }
        // 整条任务被判为偶现，其失败项不计入功能健康度的分母
        int denominator = passed;
        summary.setFlakyExcludedCount(failed);
        summary.setFunctionalHealthPercent(denominator == 0 ? 0 : 100);
        return summary;
    }

    /** 存量任务 config 里没有 environment，一律按生产——行为要跟改动前完全一致 */
    private String readEnvironment(String config) {
        if (config == null || config.isBlank()) {
            return TestEnvironment.PROD;
        }
        try {
            return TestEnvironment.normalize(
                    ExecutionLogJson.mapper().readTree(config).path("environment").asText(null));
        } catch (Exception e) {
            return TestEnvironment.PROD;
        }
    }

    /**
     * 归因结论出问题不能拖垮整个概览页。
     * <p>
     * 概览是排查失败时最常打开的页面，为了一个附加信息把主视图弄挂了得不偿失。
     */
    private TaskFailureDiagnosis safeCurrentDiagnosis(String taskId) {
        try {
            return diagnosisService.current(taskId);
        } catch (Exception e) {
            log.warn("读取失败归因出错 taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    private boolean isFlaky(TaskFailureDiagnosis diagnosis) {
        return diagnosis != null
                && FailureDiagnosisDtos.Category.FLAKY.name().equalsIgnoreCase(diagnosis.getCategory());
    }

    private boolean isLogMetaStep(String step) {
        if (step == null) return true;
        String s = step.trim();
        return "开始测试".equals(s) || "测试场景".equals(s) || "测试应用".equals(s) || "测试设备".equals(s) || "汇总".equals(s);
    }

    private List<TestResultItemDto> buildUiFlowResultItems(List<ExecutionLogEntryDto> executionLog) {
        return buildUiFlowResultItems(executionLog, false);
    }

    /**
     * @param flakyDowngrade 保留给内部调用；对外的两个口径见 {@link #withFunctionalHealth}。
     */
    private List<TestResultItemDto> buildUiFlowResultItems(List<ExecutionLogEntryDto> executionLog,
                                                           boolean flakyDowngrade) {
        if (executionLog == null || executionLog.isEmpty()) {
            return List.of(new TestResultItemDto("流程", "暂无执行日志", RESULT_NOT_RUN, null, null));
        }
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            if (entry == null) continue;
            String step = entry.getStep() != null ? entry.getStep().trim() : "步骤";
            if (isUiFlowNoiseStep(step)) continue;
            String category = inferUiFlowCategory(step);
            // 中立事件（用户停止、重试前的失败尝试）归告警：看得见，但不拉低达标率
            boolean neutral = "neutral".equalsIgnoreCase(entry.getStatus())
                    || "Warn".equalsIgnoreCase(entry.getLevel());
            boolean fail = !neutral && ("fail".equalsIgnoreCase(entry.getStatus())
                    || "Error".equalsIgnoreCase(entry.getLevel()));
            String result = (neutral || (fail && flakyDowngrade))
                    ? RESULT_WARN : (fail ? RESULT_FAIL : RESULT_PASS);
            String detail = entry.getMessage();
            // 返回提示：把返回文案前置，概览「详细日志」一眼可见
            if (!fail && (step.contains("返回提示") || "流程返回".equals(step))
                    && detail != null && !detail.isBlank()) {
                detail = "返回：" + detail;
            }
            if (entry.getSelector() != null && !entry.getSelector().isBlank()) {
                detail = (detail != null && !detail.isBlank() ? detail + " | " : "") + "selector=" + entry.getSelector();
            }
            list.add(new TestResultItemDto(category, step, result, null, detail));
        }
        if (list.isEmpty()) {
            return List.of(new TestResultItemDto("流程", "暂无有效步骤日志", RESULT_NOT_RUN, null, null));
        }
        return list;
    }

    private boolean isUiFlowNoiseStep(String step) {
        if (step == null || step.isBlank()) return true;
        String s = step.trim();
        if ("截图".equals(s) || s.startsWith("截图 ")) return true;
        if (s.contains("连续截图")) return true;
        if (s.startsWith("开始 ·")) return true;
        if (s.contains("Appium") && !s.contains("启动应用程序")) return true;
        if (s.contains("启动回退")) return true;
        return false;
    }

    private String inferUiFlowCategory(String step) {
        if (step == null) return "流程步骤";
        String s = step.trim();
        if ("开始执行流程".equals(s) || "流程结束".equals(s) || "流程返回".equals(s) || "流程中断".equals(s)) {
            return "流程结果";
        }
        if (s.contains("返回提示")) return "返回结果";
        if (s.contains("元素存在判断") || s.contains("判断")) return "分支判断";
        if (s.contains("启动")) return "启动";
        if (s.contains("卸载") || s.contains("商店安装")) return "安装";
        if (s.contains("点击") || s.contains("输入") || s.contains("清空") || s.contains("滑动") || s.contains("等待")) {
            return "操作";
        }
        if (s.contains("断言") || s.contains("组件")) return "校验";
        // 大纲号 1 / 1.1 → 取首段归类展示
        int dot = s.indexOf('·');
        if (dot > 0) {
            String no = s.substring(0, dot).trim();
            if (!no.isEmpty() && Character.isDigit(no.charAt(0))) {
                return "步骤 " + no.split("\\.")[0];
            }
        }
        return "流程步骤";
    }

    /** 性能基础质量测试：按执行日志生成测项；无日志时单条「性能遍历与资源采样」。 */
    private List<TestResultItemDto> buildPerformanceResultItems(Boolean passed, List<ExecutionLogEntryDto> executionLog) {
        String resultWhenNoLog = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        if (executionLog == null || executionLog.isEmpty()) {
            return List.of(new TestResultItemDto("性能测试", "性能遍历与资源采样", resultWhenNoLog, null, "详见执行日志与 result_data"));
        }
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if ("开始测试".equals(step) || "测试场景".equals(step)) continue;
            String result;
            if ("neutral".equalsIgnoreCase(entry.getStatus()) || "Warn".equalsIgnoreCase(entry.getLevel())) {
                result = RESULT_WARN;
            } else {
                result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            }
            list.add(new TestResultItemDto("性能测试", step, result, null, entry.getMessage()));
        }
        return list.isEmpty() ? List.of(new TestResultItemDto("性能测试", "性能遍历与资源采样", resultWhenNoLog, null, null)) : list;
    }

    /** 兼容性测试：OS兼容 2 项 + 设备兼容 3 项（截图结构）。 */
    private List<TestResultItemDto> buildCompatibilityResultItems(boolean completed) {
        List<TestResultItemDto> list = new ArrayList<>();
        String osResult = completed ? RESULT_PASS : RESULT_FAIL;
        list.add(new TestResultItemDto("OS兼容", "应用/元服务启动无兼容性问题", osResult, null, null));
        list.add(new TestResultItemDto("OS兼容", "应用/元服务运行无兼容性问题", osResult, null, null));
        list.add(new TestResultItemDto("设备兼容", "应用/元服务在折叠屏状态切换时无兼容性问题", RESULT_DEVICE_DEPENDENT, null, null));
        list.add(new TestResultItemDto("设备兼容", "应用/元服务在折叠屏横竖屏切换时无兼容性问题", RESULT_DEVICE_DEPENDENT, null, null));
        list.add(new TestResultItemDto("设备兼容", "应用/元服务在折叠屏支架态切换时无兼容性问题", RESULT_DEVICE_DEPENDENT, null, null));
        return list;
    }

    /** 根据检测规则列表汇总：兼容性满足度。 */
    private CompatibilitySummaryDto buildSummaryFromItems(List<TestResultItemDto> items) {
        int actual = items.size();
        int passed = 0, failed = 0, warning = 0;
        for (TestResultItemDto item : items) {
            switch (item.getTestResult() == null ? "" : item.getTestResult()) {
                case RESULT_PASS -> passed++;
                case RESULT_FAIL -> failed++;
                case RESULT_WARN -> warning++;
                default -> { /* 设备依赖、未执行等不计入通过/不通过/告警 */ }
            }
        }
        return new CompatibilitySummaryDto(actual, passed, failed, warning);
    }

    /**
     * 根据检测规则列表按分类汇总：测试结果分布（柱状图）。
     * <p>
     * 按分类名归并，而不是按「连续相同分类」分段——同一个分类在列表里断开后再出现
     * （如「流程步骤」中间夹了「步骤 1」）会画出两行同名的柱子，看起来像有两个不同的分组。
     * 保持首次出现的顺序。
     */
    private List<ResultDistributionItemDto> buildDistributionFromItems(List<TestResultItemDto> items) {
        Map<String, int[]> byCategory = new LinkedHashMap<>();
        for (TestResultItemDto item : items) {
            String cat = item.getTestItemCategory() != null ? item.getTestItemCategory() : "";
            int[] counts = byCategory.computeIfAbsent(cat, k -> new int[4]);
            switch (item.getTestResult() == null ? "" : item.getTestResult()) {
                case RESULT_PASS -> counts[0]++;
                case RESULT_WARN -> counts[1]++;
                case RESULT_FAIL -> counts[2]++;
                case RESULT_DEVICE_DEPENDENT -> counts[3]++;
                default -> { }
            }
        }
        List<ResultDistributionItemDto> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byCategory.entrySet()) {
            int[] c = e.getValue();
            out.add(new ResultDistributionItemDto(e.getKey(), c[0], c[1], c[2], c[3]));
        }
        return out;
    }

    private static String formatDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return null;
        }
        Duration d = Duration.between(start, end);
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return seconds + "秒";
        }
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (secs == 0) {
            return minutes + "分";
        }
        return minutes + "分" + secs + "秒";
    }
}
