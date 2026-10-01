package com.mobile.standalone.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FunctionalTestExecutionResult;
import com.mobile.standalone.dto.PerformanceTestResultDto;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.service.perf.MetricBaselineService;
import com.mobile.standalone.service.perf.CrashMonitor;
import com.mobile.standalone.service.perf.IosCrashCollector;
import com.mobile.standalone.service.perf.ColdStartMeasurer;
import com.mobile.standalone.service.perf.MemoryLeakAnalyzer;
import com.mobile.standalone.service.perf.CrashLogParser;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import com.mobile.standalone.service.perf.AdbPerformanceCollector;
import com.mobile.standalone.service.perf.NoOpPerformanceCollector;
import com.mobile.standalone.service.perf.PerformanceCollectorResolver;
import com.mobile.standalone.service.perf.PerformanceMetricCollector;
import com.mobile.standalone.service.perf.PerformanceMetricSample;
import io.appium.java_client.AppiumDriver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.Dimension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 性能测试执行：
 * <ul>
 *   <li>性能基础质量：随机遍历 + 资源采样（探索性，非业务路径）</li>
 *   <li>场景化性能：<b>只跑流程编排</b>（config.flowId → {@link UiFlowExecutor}）并同步采样；
 *       禁止后端写死登录/资讯等业务步骤，业务路径统一在「流程编排」维护</li>
 * </ul>
 * 当前仅支持 Android；需 adb 可用，建议 appium.enabled=true。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceTestService {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @Value("${performance.traversal-minutes:2}")
    private int traversalMinutes;

    @Value("${performance.sample-interval-seconds:3}")
    private int sampleIntervalSeconds;

    @Value("${performance.response-delay-threshold-ms:3000}")
    private long responseDelayThresholdMs;

    @Value("${performance.completion-delay-threshold-ms:5000}")
    private long completionDelayThresholdMs;

    /** 每 N 次操作按一次返回键，便于退出播放页/详情页继续遍历；0 表示不按 */
    @Value("${performance.back-key-interval:5}")
    private int backKeyInterval;

    /** 遍历期间每隔 N 秒截一张图（后台线程执行，尽量不阻塞点击/滑动）；0 表示不截 */
    @Value("${performance.screenshot-interval-seconds:1}")
    private int screenshotIntervalSeconds;

    /** 卡顿判定阈值：采样点 jankPercent ≥ 该值计为一次卡顿问题 */
    @Value("${performance.jank-percent-threshold:16}")
    private double jankPercentThreshold;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final AndroidPerformanceCollector performanceCollector;
    private final PerformanceCollectorResolver collectorResolver;
    private final AdbPerformanceCollector adbPerformanceCollector;
    private final NoOpPerformanceCollector noOpPerformanceCollector;
    private final MetricBaselineService metricBaselineService;
    private final CrashMonitor crashMonitor;
    private final IosCrashCollector iosCrashCollector;

    @Autowired(required = false)
    private MetricKitIngestService metricKitIngestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    /** 场景化性能只跑流程编排；Lazy 避免循环依赖 */
    @Lazy
    @Autowired
    private UiFlowExecutor uiFlowExecutor;

    @Autowired
    private UiFlowService uiFlowService;

    @Autowired(required = false)
    private com.mobile.standalone.service.perf.ColdStartMeasurer coldStartMeasurer;

    /**
     * 从任务 config（JSON）解析执行时长（分钟）。支持前端创建任务时传入 config: {"traversalMinutes": 5}。
     * 范围限制在 1～60，无效或未传则使用配置项 performance.traversal-minutes。
     */
    private int resolveTraversalMinutes(Task task) {
        if (task == null || task.getConfig() == null || task.getConfig().isBlank()) {
            return traversalMinutes;
        }
        try {
            JsonNode node = JSON.readTree(task.getConfig());
            if (node != null && node.has("traversalMinutes")) {
                int minutes = node.get("traversalMinutes").asInt(traversalMinutes);
                return Math.max(1, Math.min(60, minutes));
            }
        } catch (Exception e) {
            log.debug("解析任务 config 中的 traversalMinutes 失败，使用默认值: {}", e.getMessage());
        }
        return traversalMinutes;
    }

    /**
     * 场景化性能测试：必须走流程编排。
     * <p>config 须含 {@code flowId}（与 UI_FLOW_TEST 相同）；执行 {@link UiFlowExecutor}，期间同步采 CPU/内存/FPS。
     * 业务步骤只维护在「流程编排 / 元素库」，后端不再写死登录/资讯等路径。
     */
    public PerformanceTestExecutionResult runScenarioPerformanceTest(Task task, Device device) {
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        String packageName = app.getPackageName();
        if (packageName == null || packageName.isBlank()) {
            packageName = resolvePackageNameFromConfig(task.getConfig());
        }
        if (packageName == null || packageName.isBlank()) {
            throw new RuntimeException("应用包名为空，无法执行场景化性能测试（请在任务应用或 config.packageName 中提供）");
        }
        if (!Device.isAndroidOrPogAndroid(device.getPlatform()) && !Device.usesIosAutomation(device.getPlatform())) {
            List<ExecutionLogEntryDto> logEntries = new ArrayList<>();
            logEntries.add(ExecutionLogEntryDto.fail("场景化性能测试", "当前仅支持 Android / Pog-Android / iOS / Pog-iOS", null));
            return new PerformanceTestExecutionResult(null, logEntries, buildUnsupportedResultJson("仅支持 Android / Pog-Android / iOS / Pog-iOS"))
                    .markFailed("当前平台不支持场景化性能测试");
        }
        if (uiFlowExecutor == null) {
            List<ExecutionLogEntryDto> logEntries = new ArrayList<>();
            logEntries.add(ExecutionLogEntryDto.fail("场景化性能测试", "UiFlowExecutor 未注入", null));
            return new PerformanceTestExecutionResult(null, logEntries, buildUnsupportedResultJson("UiFlowExecutor 未注入"))
                    .markFailed("UiFlowExecutor 未注入");
        }

        String flowId = resolveFlowIdFromConfig(task.getConfig());
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        if (flowId == null || flowId.isBlank()) {
            executionLog.add(ExecutionLogEntryDto.fail("流程编排",
                    "场景化性能必须指定流程：config.flowId 不能为空。请先在「流程编排」维护功能流程，再对此流程做性能测试。", null));
            return new PerformanceTestExecutionResult(null, executionLog,
                    buildUnsupportedResultJson("缺少 config.flowId：业务路径须来自流程编排"))
                    .markFailed("场景化性能缺少 config.flowId");
        }

        UiFlow flow = uiFlowService != null ? uiFlowService.get(flowId) : null;
        String flowName = flow != null && flow.getName() != null ? flow.getName() : flowId;
        executionLog.add(ExecutionLogEntryDto.success("场景化性能测试",
                "流程编排 flowId=" + flowId + "（" + flowName + "），同步采集 CPU/内存/FPS"));
        executionLog.add(ExecutionLogEntryDto.success("环境", "设备:" + device.getId() + ", 包名:" + packageName));

        return runFlowPerformance(task, device, packageName, flowId, flowName, executionLog);
    }

    /** 流程编排路径 + 同步采样（唯一业务路径来源） */
    private PerformanceTestExecutionResult runFlowPerformance(Task task, Device device, String packageName,
                                                              String flowId, String flowName,
                                                              List<ExecutionLogEntryDto> executionLog) {
        String deviceId = device.getId();
        List<PerformanceTestResultDto.ResourceSample> resourceSamples = new CopyOnWriteArrayList<>();
        AtomicBoolean samplingStopped = new AtomicBoolean(false);
        PerformanceCollectorResolver.ResolvedCollector resolved =
                openCollector(task, device, packageName, executionLog);
        // 采样前先清 logcat：环形缓冲里躺着上一轮甚至上个版本的崩溃，
        // 不清就会把别人的账算到这一轮头上，「崩溃次数」直接失真
        if (isAndroid(task)) {
            if (crashMonitor != null) {
                crashMonitor.start(deviceId);
            }
        } else if (iosCrashCollector != null) {
            // iOS 的崩溃是文件不是缓冲，「清」等于把旧的搬走。
            // 不清的话上个版本的崩溃会算到这一轮头上，而崩溃次数正是要跟上版比的
            iosCrashCollector.start(deviceId);
        }
        // 冷启动必须在采样器起来<b>之前</b>测完。
        //
        // 它要反复 force-stop + 重启应用五次，如果和资源采样重叠，
        // 这五次启动的 CPU 尖峰会被算进整条曲线的均值和峰值里——
        // 于是「开了冷启动测量」的那次 CPU 看着就是比没开的高，
        // 而两次跑的明明是同一个包。基线一比，报一条不存在的劣化。
        ColdStartMeasurer.Measurement coldStart =
                measureColdStart(task, device, packageName, flowId, executionLog);

        Thread samplerThread = startSampler(deviceId, packageName, resourceSamples, samplingStopped, resolved.collector());

        List<PerformanceTestResultDto.OperationRecord> operations = new ArrayList<>();
        FunctionalTestExecutionResult flowResult = null;
        long t0 = System.currentTimeMillis();
        long t1;
        boolean ok = true;
        String errMsg = null;
        try {
            boolean flowHasHotStart = flowStepsContain(flowId, "HOT_START")
                    || flowStepsContain(flowId, "HOT_START_IOS");
            boolean flowHasColdOrLaunch = flowStepsContain(flowId, "COLD_START")
                    || flowStepsContain(flowId, "COLD_START_IOS")
                    || flowStepsContain(flowId, "LAUNCH_APP")
                    || flowStepsContain(flowId, "LAUNCH_APP_IOS");
            if (flowHasHotStart) {
                executionLog.add(ExecutionLogEntryDto.success("流程执行",
                        "开始执行（含热启动关键字，不预 force-stop）: " + flowName));
            } else if (flowHasColdOrLaunch) {
                executionLog.add(ExecutionLogEntryDto.success("流程执行",
                        "开始执行（冷启/启动由流程步骤负责）: " + flowName));
            } else if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
                executionLog.add(ExecutionLogEntryDto.success("流程执行",
                        "流程无启动关键字，预 force-stop 后执行: " + flowName));
                forceStopAndroidApp(deviceId, packageName);
                sleep(500);
            } else {
                executionLog.add(ExecutionLogEntryDto.success("流程执行",
                        "iOS 流程无启动关键字，直接执行（请在流程中配置 COLD_START_IOS/LAUNCH_APP_IOS）: " + flowName));
            }
            t0 = System.currentTimeMillis();
            flowResult = uiFlowExecutor.execute(task, device);
            t1 = System.currentTimeMillis();
        } catch (UiFlowExecutionException e) {
            t1 = System.currentTimeMillis();
            ok = false;
            errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
            executionLog.add(ExecutionLogEntryDto.fail("流程执行", "流程失败: " + errMsg, null));
            log.warn("场景化性能(流程编排)失败: flowId={}, err={}", flowId, errMsg);
            if (e.getExecutionLog() != null) {
                executionLog.addAll(e.getExecutionLog());
            }
        } catch (Exception e) {
            t1 = System.currentTimeMillis();
            ok = false;
            errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
            executionLog.add(ExecutionLogEntryDto.fail("流程执行", "流程失败: " + errMsg, null));
            log.warn("场景化性能(流程编排)失败: flowId={}, err={}", flowId, errMsg);
        } finally {
            samplingStopped.set(true);
            joinQuietly(samplerThread, 5000);
        }

        if (flowResult != null && flowResult.getExecutionLog() != null) {
            executionLog.addAll(flowResult.getExecutionLog());
        }

        long durationMs = Math.max(0, t1 - t0);
        PlatformDriver driver = platformRouter.getDriver(device);
        String page = null;
        try {
            if (driver != null) page = driver.getCurrentActivity(deviceId);
        } catch (Exception ignored) { }

        operations.add(new PerformanceTestResultDto.OperationRecord(
                0, "flow", t0, t1, null, null, null, null,
                page != null ? page : flowId, "流程:" + flowName + "（" + durationMs + "ms）", null, null, null));

        PerformanceTestResultDto.ResourceSummary resourceSummary = buildResourceSummary(resourceSamples);
        PerformanceTestResultDto.IssueSummary issueSummary = buildIssueSummary(operations, List.of(), resourceSamples);
        String conclusion = String.format("流程 %s %s，墙钟耗时 %d ms，采样点 %d 个，体验问题 %d 次。",
                flowName, ok ? "完成" : "失败", durationMs, resourceSamples.size(),
                issueSummary != null ? issueSummary.getTotalIssueCount() : 0);
        executionLog.add(ExecutionLogEntryDto.success("测试结论", conclusion));

        int minutes = Math.max(1, (int) Math.ceil(durationMs / 60000.0));
        Object metricKit = null;
        if (metricKitIngestService != null && task.getId() != null) {
            metricKit = metricKitIngestService.get(task.getId());
        }
        PerformanceTestResultDto result = PerformanceTestResultDto.builder()
                .traversalDurationMinutes(minutes)
                .totalOperations(1)
                .launchCount(1)
                .clickCount(0)
                .swipeCount(0)
                .conclusion(conclusion)
                .collectorEngineUsed(resolved.engineUsed())
                .metricKitSummary(metricKit)
                .resourceSummary(resourceSummary)
                .issueSummary(issueSummary)
                .resourceSamples(resourceSamples.isEmpty() ? null : new ArrayList<>(resourceSamples))
                .operations(operations)
                .coldStartMs(coldStart == null ? null : coldStart.medianMs())
                .coldStartDetail(coldStartDetail(coldStart))
                .memoryLeak(analyzeMemoryLeak(flowResult, executionLog))
                .build();

        recordBaseline(task, device, result, collectCrashes(task, device, resolvePackageName(task, device), executionLog));

        PerformanceTestExecutionResult out = toExecutionResult(result, executionLog);
        if (!ok) {
            executionLog.add(ExecutionLogEntryDto.fail("结果", "流程未通过，但性能采样数据已保存: " + errMsg, null));
            // 采样数据保留，但任务必须记失败：流程没跑通的采样只覆盖了一半路径
            out.markFailed("场景化性能流程未通过: " + errMsg);
        }
        return out;
    }

    /**
     * 测冷启动耗时。
     *
     * <p>这是报告里一直缺的那一项：{@code MetricComparator} 从一开始就认识
     * {@code coldStartMs}（有中文名、有单位、方向也判对了），但没有任何采集器产生过它。
     * 也就是说<b>最贴近用户感受的那个指标，报告里一次都没出现过</b>。
     *
     * <p>默认跑 {@link ColdStartMeasurer#DEFAULT_ROUNDS} 轮取中位数，
     * {@code config.coldStartRounds: 0} 可以关掉。
     */
    private ColdStartMeasurer.Measurement measureColdStart(
            Task task, Device device, String packageName, String flowId,
            List<ExecutionLogEntryDto> executionLog) {
        if (coldStartMeasurer == null) {
            return null;
        }
        int rounds = resolveColdStartRounds(task);
        if (rounds <= 0) {
            return null;
        }
        // 流程要的是热启动，就不能在它前面把进程杀五遍——
        // 那样流程一上来面对的是冷的应用，测的根本不是它想测的那条路径。
        // 这种冲突不会报错，只会让热启动那条流程的数据悄悄变成另一回事
        if (flowStepsContain(flowId, "HOT_START") || flowStepsContain(flowId, "HOT_START_IOS")) {
            executionLog.add(ExecutionLogEntryDto.success("冷启动耗时",
                    "跳过：这条流程测的是热启动，先反复冷启会破坏它的前置状态"));
            return null;
        }

        ColdStartMeasurer.Measurement m = coldStartMeasurer.measure(
                device.getPlatform(), device.getId(), packageName,
                resolveLaunchActivityFromConfig(task.getConfig()), rounds);

        if (!m.supported()) {
            // 说清楚是「这个平台还不支持」，不是「这次启动很快」——
            // 后者是假结论，而假结论比缺数据难发现得多
            executionLog.add(ExecutionLogEntryDto.success("冷启动耗时",
                    m.notes().isEmpty() ? "本平台不支持" : m.notes().get(0)));
            return m;
        }
        for (String note : m.notes()) {
            executionLog.add(ExecutionLogEntryDto.success("冷启动耗时", note));
        }
        for (String bad : m.rejected()) {
            executionLog.add(ExecutionLogEntryDto.success("冷启动耗时", "丢弃 " + bad));
        }
        if (m.medianMs() != null) {
            executionLog.add(ExecutionLogEntryDto.success("冷启动耗时",
                    "中位数 " + Math.round(m.medianMs()) + " ms（量到第一帧，不含首页数据加载完）"));
        }
        return m;
    }

    /** 明细给前端：原始值都摆出来，人一眼能看出这次稳不稳 */
    /**
     * 把流程里打下的内存点交给泄漏判据。
     *
     * <p>没打过点就返回 null——报告上「没测」和「没泄漏」必须长得不一样。
     * 把没测显示成绿色的「无泄漏」，是这套平台里最不该犯的错。
     */
    private Map<String, Object> analyzeMemoryLeak(
            com.mobile.standalone.dto.FunctionalTestExecutionResult flowResult,
            List<ExecutionLogEntryDto> executionLog) {
        List<Long> points = flowResult == null ? null : flowResult.getMemoryCheckpoints();
        if (points == null || points.isEmpty()) {
            return null;
        }
        MemoryLeakAnalyzer.Analysis a = MemoryLeakAnalyzer.analyze(points);

        // 结论要进执行日志，不能只躺在 JSON 里：
        // 人打开报告先看的是日志，没人会去翻结构化字段
        String title = "内存泄漏分析";
        if (a.verdict() == MemoryLeakAnalyzer.Verdict.LEAKING) {
            executionLog.add(ExecutionLogEntryDto.fail(title, a.summary(), null));
        } else if (a.verdict() == MemoryLeakAnalyzer.Verdict.INSUFFICIENT) {
            // 用 neutral 而不是 success：样本不足是「没测出来」，
            // 记成通过的话，一条没测成的用例会在报告里显示为绿色
            executionLog.add(ExecutionLogEntryDto.neutral(title, a.summary()));
        } else {
            executionLog.add(ExecutionLogEntryDto.success(title, a.summary()));
        }
        for (String note : a.notes()) {
            executionLog.add(ExecutionLogEntryDto.success(title + " · 说明", note));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", a.verdict().name());
        out.put("summary", a.summary());
        out.put("slopeKbPerRound", a.slopeKbPerRound());
        out.put("rSquared", a.rSquared());
        out.put("growthKb", a.growthKb());
        out.put("checkpointsKb", points);
        out.put("effectiveKb", a.effective());
        out.put("notes", a.notes());
        return out;
    }

    private Map<String, Object> coldStartDetail(ColdStartMeasurer.Measurement m) {
        if (m == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supported", m.supported());
        out.put("medianMs", m.medianMs());
        out.put("samples", m.samples());
        out.put("rejected", m.rejected());
        out.put("notes", m.notes());
        return out;
    }

    /** {@code config.coldStartRounds}；不传按默认轮数，传 0 关掉 */
    private int resolveColdStartRounds(Task task) {
        if (task == null || task.getConfig() == null || task.getConfig().isBlank()) {
            return ColdStartMeasurer.DEFAULT_ROUNDS;
        }
        try {
            JsonNode node = JSON.readTree(task.getConfig());
            if (node != null && node.has("coldStartRounds")) {
                return Math.max(0, Math.min(20, node.get("coldStartRounds").asInt(ColdStartMeasurer.DEFAULT_ROUNDS)));
            }
        } catch (Exception e) {
            log.debug("解析 coldStartRounds 失败，用默认: {}", e.getMessage());
        }
        return ColdStartMeasurer.DEFAULT_ROUNDS;
    }

    /**
     * {@code config.launchActivity}：显式指定从哪个 Activity 启。
     *
     * <p>不传就现场 {@code resolve-activity}，但那个解析出来的未必是用户点图标走的那条——
     * 有些包会解析到别的入口，于是测的是另一条路径，数字还挺好看。
     */
    private String resolveLaunchActivityFromConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(configJson);
            if (node != null && node.hasNonNull("launchActivity")) {
                String v = node.get("launchActivity").asText("");
                return v.isBlank() ? null : v.trim();
            }
        } catch (Exception ignored) {
            // 解析不了就现场 resolve，不该因为 config 格式问题让整次性能跑不起来
        }
        return null;
    }

    private String resolveFlowIdFromConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(configJson);
            if (node != null && node.hasNonNull("flowId")) {
                String id = node.get("flowId").asText("");
                return id.isBlank() ? null : id.trim();
            }
        } catch (Exception e) {
            log.debug("解析 config.flowId 失败: {}", e.getMessage());
        }
        return null;
    }

    private String resolvePackageNameFromConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(configJson);
            if (node != null && node.hasNonNull("packageName")) {
                String p = node.get("packageName").asText("");
                return p.isBlank() ? null : p.trim();
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 任务 config.collectorEngine：AUTO | ADB | SOLOX | TIDEVICE | NONE，默认 AUTO */
    private String resolveCollectorEngine(Task task) {
        if (task == null || task.getConfig() == null || task.getConfig().isBlank()) {
            return "AUTO";
        }
        try {
            JsonNode node = JSON.readTree(task.getConfig());
            if (node != null && node.hasNonNull("collectorEngine")) {
                String e = node.get("collectorEngine").asText("").trim();
                if (!e.isBlank()) return e;
            }
        } catch (Exception ignored) { }
        return "AUTO";
    }

    /**
     * 解析并打开采集会话；Android SoloX 失败回退 ADB；iOS Tidevice 失败回退 NONE。
     */
    private PerformanceCollectorResolver.ResolvedCollector openCollector(
            Task task, Device device, String packageName, List<ExecutionLogEntryDto> executionLog) {
        String deviceId = device.getId();
        String pref = resolveCollectorEngine(task);
        PerformanceCollectorResolver.ResolvedCollector resolved =
                collectorResolver.resolve(pref, device.getPlatform(), deviceId, packageName, executionLog);
        PerformanceMetricCollector collector = resolved.collector();
        String engineUsed = resolved.engineUsed();
        boolean fellBack = resolved.fellBack();
        try {
            collector.startSession(deviceId, packageName);
        } catch (Exception e) {
            log.warn("采集引擎 {} 启动失败: {}", collector.engineId(), e.getMessage());
            try {
                collector.stopSession(deviceId);
            } catch (Exception ignored) { }
            if (Device.usesIosAutomation(device.getPlatform())) {
                if (executionLog != null) {
                    executionLog.add(ExecutionLogEntryDto.success("采集引擎",
                            collector.engineId() + " 启动失败，回退 NONE: " + e.getMessage()));
                }
                noOpPerformanceCollector.startSession(deviceId, packageName);
                return new PerformanceCollectorResolver.ResolvedCollector(noOpPerformanceCollector, "NONE", true);
            }
            if (executionLog != null) {
                executionLog.add(ExecutionLogEntryDto.success("采集引擎",
                        collector.engineId() + " 启动失败，回退 ADB: " + e.getMessage()));
            }
            adbPerformanceCollector.startSession(deviceId, packageName);
            return new PerformanceCollectorResolver.ResolvedCollector(adbPerformanceCollector, "ADB", true);
        }
        return new PerformanceCollectorResolver.ResolvedCollector(collector, engineUsed, fellBack);
    }

    private Thread startSampler(String deviceId, String packageName,
                                List<PerformanceTestResultDto.ResourceSample> resourceSamples,
                                AtomicBoolean samplingStopped,
                                PerformanceMetricCollector collector) {
        Thread samplerThread = new Thread(() -> {
            try {
                while (!samplingStopped.get()) {
                    PerformanceMetricSample s = collector.sample(deviceId, packageName);
                    if (s != null && s.hasAnyMetric()) {
                        resourceSamples.add(new PerformanceTestResultDto.ResourceSample(
                                System.currentTimeMillis(),
                                s.cpuPercent(),
                                s.memoryPssKb(),
                                s.networkRxBytes(),
                                s.networkTxBytes(),
                                s.temperatureTenthsC(),
                                s.fps(),
                                s.jankPercent()
                        ));
                    }
                    for (int i = 0; i < sampleIntervalSeconds && !samplingStopped.get(); i++) {
                        sleep(1000);
                    }
                }
            } finally {
                try {
                    collector.stopSession(deviceId);
                } catch (Exception e) {
                    log.debug("停止采集会话: {}", e.getMessage());
                }
            }
        }, "perf-scenario-sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();
        return samplerThread;
    }

    /**
     * 把这次的关键指标按版本留档，供下个版本做基线对比。
     * <p>
     * 绝对值给不出结论——冷启动 1.2 秒是好是坏没人知道；
     * 「上版 0.9 秒、这版 1.2 秒」才是能拿去问开发的。
     * 而要能这么比，前提是每次都知道测的是哪个版本。
     */
    private void recordBaseline(Task task, Device device, PerformanceTestResultDto result,
                                List<CrashLogParser.Incident> incidents) {
        if (metricBaselineService == null || result == null || result.getResourceSummary() == null) {
            return;
        }
        try {
            PerformanceTestResultDto.ResourceSummary rs = result.getResourceSummary();
            Map<String, Double> metrics = new LinkedHashMap<>();
            putPeak(metrics, "cpuApp", rs.getCpuApp());
            putPeak(metrics, "memoryPss", rs.getMemoryPss());
            putAvg(metrics, "fps", rs.getFps());
            putPeak(metrics, "temperature", rs.getTemperature());
            putPeak(metrics, "networkRxBytes", rs.getNetworkRxBytes());
            putPeak(metrics, "networkTxBytes", rs.getNetworkTxBytes());
            // 冷启动进基线。这一项和别的不一样：它本身已经是多轮的中位数，
            // 所以拿它跟上版比，比拿一条曲线的峰值去比要稳得多
            if (result.getColdStartMs() != null) {
                metrics.put("coldStartMs", result.getColdStartMs());
            }
            String pkg = resolvePackageName(task, device);
            // 崩溃次数也进基线：这是最该跟上个版本比的一项——
            // 上版 0 次这版 3 次，比任何资源曲线都说明问题
            // 崩溃次数进基线：这是最该跟上个版本比的一项——
            // 上版 0 次这版 3 次，比任何资源曲线都说明问题。
            // 用已经采过的那份，不重新采：两次 logcat 之间还可能再崩一次，
            // 那样报告里写 2 次、基线里记 3 次，对不上就没人信了
            if (incidents != null) {
                // iOS 的 watchdog 卡死和 Android 的 ANR 是同一类问题（点得动但没响应），
                // 归到一个指标上，两端的版本基线才能用同一套口径看
                long anrs = incidents.stream()
                        .filter(i -> "ANR".equals(i.getType()) || "WATCHDOG".equals(i.getType())).count();
                metrics.put("crashCount", (double) (incidents.size() - anrs));
                metrics.put("anrCount", (double) anrs);
            }
            if (metrics.isEmpty()) {
                return;
            }
            String version = resolveAppVersion(task, device, pkg);
            metricBaselineService.record(new MetricBaselineService.Snapshot(
                    task.getId(), task.getAppId(), pkg,
                    task.getPlatform() == null ? "ANDROID" : task.getPlatform().name(),
                    // 按具体那台手机比。不同机型的 CPU、帧率不是一个量级，跨机型比出来的百分比没有意义
                    version, device == null ? null : device.getName(), metrics));
        } catch (Exception e) {
            // 留档失败不该让一次真机跑白费——采样结果照常返回
            log.warn("[perf] 指标留档失败: {}", e.getMessage());
        }
    }

    /**
     * 收尾时抓这一轮的崩溃 / ANR，并写进执行日志——<b>报告里看得见才算数</b>。
     * <p>
     * 只塞进基线指标的话，跑完还是只有一条资源曲线，崩了照样不知道；
     * 而遍历随机点几十分钟，最该被抓住的就是这一路点出来的崩溃。
     */
    private List<CrashLogParser.Incident> collectCrashes(Task task, Device device, String packageName,
                                                         List<ExecutionLogEntryDto> executionLog) {
        if (device == null) {
            return List.of();
        }
        try {
            List<CrashLogParser.Incident> incidents;
            if (isAndroid(task)) {
                if (crashMonitor == null) {
                    return List.of();
                }
                incidents = crashMonitor.collect(device.getId(), packageName);
            } else {
                if (iosCrashCollector == null) {
                    return List.of();
                }
                if (!iosCrashCollector.isAvailable()) {
                    // 说清楚是没装工具，而不是「这一轮很稳」——后者是假结论
                    executionLog.add(ExecutionLogEntryDto.success("稳定性",
                            "跳过崩溃采集：执行机未安装 libimobiledevice（brew install libimobiledevice）"));
                    return List.of();
                }
                incidents = iosCrashCollector.collect(device.getId(), packageName);
            }
            if (incidents.isEmpty()) {
                executionLog.add(ExecutionLogEntryDto.success("稳定性", "本轮未发现崩溃 / ANR"));
                return incidents;
            }
            for (CrashLogParser.Incident i : incidents) {
                // 用 fail 而不是 success：崩溃必须在报告里是红的。
                // 混在普通日志里就等于没报——人扫一眼绿色的列表不会停下来看
                executionLog.add(ExecutionLogEntryDto.fail(
                        switch (i.getType() == null ? "" : i.getType()) {
                            case "ANR" -> "ANR";
                            // iOS 没有 ANR，主线程卡死表现为被 watchdog 杀掉
                            case "WATCHDOG" -> "卡死（watchdog）";
                            default -> "崩溃";
                        },
                        i.getTitle() + (i.getProcess() == null ? "" : "（" + i.getProcess() + "）"),
                        null));
                if (i.getStack() != null && !i.getStack().isBlank()) {
                    executionLog.add(ExecutionLogEntryDto.success("堆栈", truncate(i.getStack(), 600)));
                }
            }
            return incidents;
        } catch (Exception e) {
            log.warn("[perf] 崩溃采集失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static boolean isAndroid(Task task) {
        return task.getPlatform() == null || "ANDROID".equalsIgnoreCase(task.getPlatform().name());
    }

    /** 峰值更能反映问题：均值会把一次卡顿摊平，而用户感受到的正是那一次 */
    private static void putPeak(Map<String, Double> out, String key,
                                PerformanceTestResultDto.MetricStat stat) {
        if (stat != null && stat.getPeak() != null) {
            out.put(key, stat.getPeak());
        }
    }

    /** 帧率反过来看均值：偶尔掉一帧不说明问题，整体流畅度才说明 */
    private static void putAvg(Map<String, Double> out, String key,
                               PerformanceTestResultDto.MetricStat stat) {
        if (stat != null && stat.getAvg() != null) {
            out.put(key, stat.getAvg());
        }
    }

    private String resolvePackageName(Task task, Device device) {
        if (task.getAppId() == null || applicationMapper == null) {
            return null;
        }
        try {
            Application app = applicationMapper.findById(task.getAppId());
            return app == null ? null : app.getPackageName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 版本号。优先从设备上已装的那个应用读——那才是这次真正跑的包，
     * 比任何配置里填的都可信。
     */
    private String resolveAppVersion(Task task, Device device, String packageName) {
        if (packageName == null || device == null) {
            return null;
        }
        // 直接问设备。应用字典里那份 version 是刷新设备时顺带写的，
        // 可能是空串、可能是上一次装的包留下的旧值——而版本号一旦记错，
        // 基线就会跟错误的版本比，数字看着都对、结论全是错的，且看不出错在哪
        String fromDevice = readVersionFromDevice(device.getId(), packageName);
        if (fromDevice != null && !fromDevice.isBlank()) {
            return fromDevice;
        }
        if (applicationMapper == null) {
            return null;
        }
        try {
            // 设备上已装应用那份版本是刷新设备时从包里读出来的，
            // 比应用字典里手填的可信——手填的经常忘了改
            for (Application installed : applicationMapper.findByDeviceId(device.getId())) {
                if (packageName.equals(installed.getPackageName())
                        && installed.getVersion() != null && !installed.getVersion().isBlank()) {
                    return installed.getVersion();
                }
            }
            Application dict = task.getAppId() == null ? null : applicationMapper.findById(task.getAppId());
            if (dict != null && dict.getVersion() != null && !dict.getVersion().isBlank()) {
                return dict.getVersion();
            }
        } catch (Exception e) {
            log.debug("[perf] 读已装版本失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * {@code adb shell dumpsys package <pkg> | grep versionName}。
     * <p>
     * 这是唯一能确定「这次真正跑的是哪个包」的来源。拿不到就返回 null，
     * 上层会跳过留档——记成 unknown 的话，几次之后基线会指向一个混了好几个版本的 unknown。
     */
    private String readVersionFromDevice(String deviceId, String packageName) {
        Process p = null;
        try {
            p = new ProcessBuilder("adb", "-s", deviceId, "shell",
                    "dumpsys", "package", packageName)
                    .redirectErrorStream(true).start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int i = line.indexOf("versionName=");
                    if (i >= 0) {
                        String v = line.substring(i + "versionName=".length()).trim();
                        // 同一行后面可能还跟着别的字段
                        int sp = v.indexOf(' ');
                        if (sp > 0) {
                            v = v.substring(0, sp);
                        }
                        return v.isBlank() || "null".equals(v) ? null : v;
                    }
                }
            }
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("[perf] 从设备读版本号失败 {}: {}", packageName, e.getMessage());
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
        return null;
    }

    private PerformanceTestExecutionResult toExecutionResult(PerformanceTestResultDto result,
                                                             List<ExecutionLogEntryDto> executionLog) {
        try {
            return new PerformanceTestExecutionResult(null, executionLog, JSON.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.warn("序列化场景化性能结果失败", e);
            return new PerformanceTestExecutionResult(null, executionLog, buildErrorResultJson("结果序列化失败"));
        }
    }

    private static void joinQuietly(Thread t, long ms) {
        if (t == null) return;
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行性能基础质量测试，返回执行日志与结果 JSON（写入 result_data）。
     * 执行时长可从任务 config.traversalMinutes 读取（创建任务时前端传入），否则使用配置项默认值。
     */
    public PerformanceTestExecutionResult runPerformanceTest(Task task, Device device) {
        int effectiveTraversalMinutes = resolveTraversalMinutes(task);
        String deviceId = device.getId();
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        String packageName = app.getPackageName();
        if (packageName == null || packageName.isBlank()) {
            throw new RuntimeException("应用包名为空，无法执行性能测试");
        }
        if (!Device.isAndroidOrPogAndroid(device.getPlatform())) {
            List<ExecutionLogEntryDto> log = new ArrayList<>();
            log.add(ExecutionLogEntryDto.fail("性能测试",
                    "遍历探索仅支持 Android / Pog-Android；iOS 请用「流程性能测试」+ Tidevice/MetricKit", null));
            String resultJson = buildUnsupportedResultJson("遍历探索仅支持 Android；iOS 请用流程性能测试");
            return new PerformanceTestExecutionResult(null, log, resultJson);
        }

        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        executionLog.add(ExecutionLogEntryDto.success("性能测试", "开始性能基础质量测试，遍历时长约 " + effectiveTraversalMinutes + " 分钟"));
        executionLog.add(ExecutionLogEntryDto.success("环境", "设备:" + deviceId + ", 包名:" + packageName));

        PlatformDriver driver = platformRouter.getDriver(device);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "无法获取平台驱动", null));
            return new PerformanceTestExecutionResult(null, executionLog, buildUnsupportedResultJson("无法获取平台驱动"))
                    .markFailed("无法获取平台驱动");
        }

        // 性能测试每次均从冷启动开始：先强制结束应用，再启动，避免在上次运行基础上继续
        executionLog.add(ExecutionLogEntryDto.success("冷启动", "结束应用进程，准备重新启动"));
        forceStopAndroidApp(deviceId, packageName);
        sleep(800);

        // 若开启按间隔截图：在启动应用前先截一张「桌面/当前屏」，供第一条操作「操作前」列展示
        String screenshotDir = "/tmp/screenshots/perf_" + task.getId();
        List<PerformanceTestResultDto.ScreenshotTimelineEntry> screenshotTimeline = new CopyOnWriteArrayList<>();
        AtomicInteger screenshotIndex = new AtomicInteger(0);
        if (screenshotIntervalSeconds > 0) {
            try {
                Files.createDirectories(Paths.get(screenshotDir));
            } catch (Exception e) {
                log.warn("创建截图目录失败，跳过遍历截图: {}", e.getMessage());
            }
            if (Paths.get(screenshotDir).toFile().exists()) {
                long ts0 = System.currentTimeMillis();
                try {
                    String path0 = screenshotDir + "/img_0.png";
                    if (driver.takeScreenshot(deviceId, path0) != null) {
                        screenshotTimeline.add(new PerformanceTestResultDto.ScreenshotTimelineEntry(0, ts0));
                        screenshotIndex.set(1);
                    }
                } catch (Exception e) {
                    log.trace("启动前截图失败: {}", e.getMessage());
                }
            }
        }

        // 启动应用（任意已安装 Android 包均可；启动后校验前台）
        executionLog.add(ExecutionLogEntryDto.success("启动应用", "正在启动 " + packageName));
        if (!driver.launchApp(deviceId, packageName)) {
            executionLog.add(ExecutionLogEntryDto.fail("启动应用", "启动失败: " + packageName, null));
            return new PerformanceTestExecutionResult(null, executionLog, buildErrorResultJson("启动应用失败"))
                    .markFailed("启动应用失败");
        }
        sleep(1500);
        String fg = performanceCollector.getForegroundPackage(deviceId);
        boolean inFg = performanceCollector.isAppInForeground(deviceId, packageName);
        executionLog.add(ExecutionLogEntryDto.success("启动校验",
                "前台包名=" + (fg != null ? fg : "未知") + (inFg ? "（已在前台）" : "（未在前台，请确认手机屏幕）")));
        if (!inFg && performanceCollector.getPid(deviceId, packageName) == null) {
            executionLog.add(ExecutionLogEntryDto.fail("启动应用", "目标应用未运行: " + packageName, null));
            return new PerformanceTestExecutionResult(null, executionLog, buildErrorResultJson("启动应用失败: " + packageName))
                    .markFailed("启动应用失败: " + packageName);
        }
        sleep(1500);

        int width;
        int height;
        try {
            int[] size = getWindowSize(deviceId);
            width = size[0];
            height = size[1];
        } catch (Exception e) {
            log.warn("获取窗口尺寸失败，使用默认 1080x1920: {}", e.getMessage());
            width = 1080;
            height = 1920;
        }
        executionLog.add(ExecutionLogEntryDto.success("窗口尺寸", width + "x" + height));

        // 资源采样列表（后台线程写入）
        List<PerformanceTestResultDto.ResourceSample> resourceSamples = new CopyOnWriteArrayList<>();
        AtomicBoolean samplingStopped = new AtomicBoolean(false);
        PerformanceCollectorResolver.ResolvedCollector resolved =
                openCollector(task, device, packageName, executionLog);
        // 遍历是随机点几十分钟，最该抓的就是这一路点出来的崩溃。
        // 先清 logcat，免得把上一轮的账算进来
        if (crashMonitor != null && isAndroid(task)) {
            crashMonitor.start(deviceId);
        }
        Thread samplerThread = startSampler(deviceId, packageName, resourceSamples, samplingStopped, resolved.collector());

        // 可选：按间隔在后台线程截屏，供「遍历统计」「操作详情」展示；与主线程共用 driver，可能有轻微串行
        List<Long> blackWhiteTimestamps = new CopyOnWriteArrayList<>(); // 判定为「黑白块」的截图时间戳，用于按操作类型归因
        AtomicBoolean screenshotStopped = new AtomicBoolean(false);
        Thread screenshotThread = null; // 声明在外，便于 finally 里 join
        if (screenshotIntervalSeconds > 0 && Paths.get(screenshotDir).toFile().exists()) {
                final PlatformDriver screenshotDriver = driver;
                screenshotThread = new Thread(() -> {
                    while (!screenshotStopped.get()) {
                        int idx = screenshotIndex.getAndIncrement();
                        long ts = System.currentTimeMillis();
                        String path = screenshotDir + "/img_" + idx + ".png";
                        try {
                            String saved = screenshotDriver.takeScreenshot(deviceId, path);
                            if (saved != null) {
                                screenshotTimeline.add(new PerformanceTestResultDto.ScreenshotTimelineEntry(idx, ts));
                                if (isMostlyBlackOrWhite(path)) {
                                    blackWhiteTimestamps.add(ts);
                                }
                            }
                        } catch (Exception e) {
                            log.trace("遍历截图失败 idx={}: {}", idx, e.getMessage());
                        }
                        for (int i = 0; i < screenshotIntervalSeconds && !screenshotStopped.get(); i++) {
                            sleep(1000);
                        }
                    }
                }, "perf-screenshot");
                screenshotThread.setDaemon(true);
                screenshotThread.start();
                executionLog.add(ExecutionLogEntryDto.success("截图", "已开启按 " + screenshotIntervalSeconds + " 秒间隔截屏（后台线程）"));
        }

        List<PerformanceTestResultDto.OperationRecord> operations = new ArrayList<>();
        int launchCount = 1;
        int clickCount = 0;
        int swipeCount = 0;
        long launchEnd = System.currentTimeMillis();
        String launchPage = driver.getCurrentActivity(deviceId);
        operations.add(new PerformanceTestResultDto.OperationRecord(
                0, "launch", launchEnd - 3000, launchEnd, 0L, null, null, null,
                launchPage != null ? launchPage : "启动页", "启动应用", null, null, null));

        long traversalEndMs = System.currentTimeMillis() + (long) effectiveTraversalMinutes * 60 * 1000;
        int stepIndex = 1;
        int centerX = width / 2;
        int centerY = height / 2;

        try {
            while (System.currentTimeMillis() < traversalEndMs) {
                long opStart = System.currentTimeMillis();
                String pageName = driver.getCurrentActivity(deviceId);
                if (pageName == null || pageName.isBlank()) pageName = null;
                boolean doSwipe = (stepIndex % 2 == 0);
                String actionLabel;
                if (doSwipe) {
                    int dir = stepIndex % 4;
                    int x1 = centerX, y1 = centerY, x2 = centerX, y2 = centerY;
                    if (dir == 0) { y1 = height * 8 / 10; y2 = height * 2 / 10; actionLabel = "向上滑"; }
                    else if (dir == 1) { y1 = height * 2 / 10; y2 = height * 8 / 10; actionLabel = "向下滑"; }
                    else if (dir == 2) { x1 = width * 8 / 10; x2 = width * 2 / 10; actionLabel = "向左滑"; }
                    else { x1 = width * 2 / 10; x2 = width * 8 / 10; actionLabel = "向右滑"; }
                    driver.swipe(deviceId, x1, y1, x2, y2, 300);
                    swipeCount++;
                } else {
                    String clickText = getElementTextAtPoint(deviceId, driver, centerX, centerY);
                    actionLabel = (clickText != null && !clickText.isBlank()) ? "点击-\"" + clickText + "\"" : "点击控件";
                    driver.click(deviceId, centerX, centerY);
                    clickCount++;
                }
                long opEnd = System.currentTimeMillis();
                long responseDelayMs = opEnd - opStart;

                AndroidPerformanceCollector.Sample sampleAfter = performanceCollector.sample(deviceId, packageName);
                Double cpuAvg = sampleAfter != null ? sampleAfter.getCpuPercent() : null;
                Long memKb = sampleAfter != null ? sampleAfter.getMemoryPssKb() : null;

                operations.add(new PerformanceTestResultDto.OperationRecord(
                        stepIndex, doSwipe ? "swipe" : "click", opStart, opEnd,
                        responseDelayMs, null, cpuAvg, memKb, pageName, actionLabel, null, null, null
                ));

                if (stepIndex % 10 == 0) {
                    executionLog.add(ExecutionLogEntryDto.success("遍历", "已执行 " + (stepIndex + 1) + " 步（点击 " + clickCount + "，滑动 " + swipeCount + "）"));
                }
                stepIndex++;
                sleep(800);
                // 周期性按返回键，避免卡在播放页/详情页，便于继续遍历其他页面
                if (backKeyInterval > 0 && stepIndex % backKeyInterval == 0) {
                    sendBackKey(deviceId);
                    executionLog.add(ExecutionLogEntryDto.success("按返回键", "退出当前页以便继续遍历"));
                    sleep(500);
                }
            }
        } catch (Exception e) {
            log.error("性能遍历异常", e);
            executionLog.add(ExecutionLogEntryDto.fail("遍历", e.getMessage(), null));
        } finally {
            samplingStopped.set(true);
            if (screenshotThread != null) {
                screenshotStopped.set(true);
                try {
                    screenshotThread.join(8000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                samplerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int totalOps = launchCount + clickCount + swipeCount;
        executionLog.add(ExecutionLogEntryDto.success("遍历结束", "总操作 " + totalOps + " 次（启动 " + launchCount + "，点击 " + clickCount + "，滑动 " + swipeCount + "）"));

        // 完成时延：本步结束到下一步开始的间隔，超过阈值视为「完成慢」
        for (int i = 0; i < operations.size() - 1; i++) {
            long gap = operations.get(i + 1).getStartTimeMs() - operations.get(i).getEndTimeMs();
            if (gap > 0) {
                operations.get(i).setCompletionDelayMs(gap);
            }
        }

        // 操作详情：为每条操作关联操作前/操作后截图索引及本步白屏时长
        fillOperationScreenshotsAndWhiteScreen(operations, screenshotTimeline, blackWhiteTimestamps, screenshotIntervalSeconds);

        PerformanceTestResultDto.ResourceSummary resourceSummary = buildResourceSummary(resourceSamples);
        PerformanceTestResultDto.IssueSummary issueSummary = buildIssueSummary(operations, blackWhiteTimestamps, resourceSamples);
        List<CrashLogParser.Incident> incidents = collectCrashes(task, device, packageName, executionLog);
        long anrCount = incidents.stream()
                .filter(i -> "ANR".equals(i.getType()) || "WATCHDOG".equals(i.getType())).count();
        long crashCount = incidents.size() - anrCount;
        // 崩溃写进结论第一句。它比「体验问题 N 次」重要得多，
        // 排在后面或者只放日志里，等于让人自己去翻
        String conclusion = String.format("总共遍历 %d 分钟，执行操作 %d 次，%s发现较好体验问题 %d 次。",
                effectiveTraversalMinutes, totalOps,
                incidents.isEmpty() ? "未发现崩溃 / ANR，"
                        : String.format("【崩溃 %d 次、ANR %d 次】，", crashCount, anrCount),
                issueSummary != null ? issueSummary.getTotalIssueCount() : 0);
        executionLog.add(ExecutionLogEntryDto.success("测试结论", conclusion));

        int count = screenshotTimeline.size();
        PerformanceTestResultDto result = PerformanceTestResultDto.builder()
                .traversalDurationMinutes(effectiveTraversalMinutes)
                .totalOperations(totalOps)
                .launchCount(launchCount)
                .clickCount(clickCount)
                .swipeCount(swipeCount)
                .conclusion(conclusion)
                .collectorEngineUsed(resolved.engineUsed())
                .resourceSummary(resourceSummary)
                .issueSummary(issueSummary)
                .resourceSamples(resourceSamples.isEmpty() ? null : new ArrayList<>(resourceSamples))
                .operations(operations)
                .screenshotCount(count > 0 ? count : null)
                .screenshotTimeline(count > 0 ? new ArrayList<>(screenshotTimeline) : null)
                .build();

        recordBaseline(task, device, result, incidents);

        String resultJson;
        try {
            resultJson = JSON.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.warn("序列化性能结果失败", e);
            resultJson = buildErrorResultJson("结果序列化失败");
        }
        return new PerformanceTestExecutionResult(null, executionLog, resultJson);
    }

    private int[] getWindowSize(String deviceId) throws Exception {
        if (appiumDriverAdapter != null) {
            AppiumDriver d = appiumDriverAdapter.getAppiumDriver(deviceId);
            if (d != null) {
                Dimension dim = d.manage().window().getSize();
                return new int[]{dim.getWidth(), dim.getHeight()};
            }
        }
        Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "wm", "size")
                .redirectErrorStream(true).start();
        String out = readProcessOutput(p, 5000);
        if (out != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*[xX]\\s*(\\d+)").matcher(out);
            if (m.find()) {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            }
        }
        throw new IllegalStateException("无法解析 wm size 输出");
    }

    private static String readProcessOutput(Process p, int timeoutMs) {
        try {
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private PerformanceTestResultDto.ResourceSummary buildResourceSummary(List<PerformanceTestResultDto.ResourceSample> samples) {
        if (samples == null || samples.isEmpty()) return null;
        double cpuMin = Double.MAX_VALUE, cpuMax = Double.MIN_VALUE, cpuSum = 0;
        long memMin = Long.MAX_VALUE, memMax = Long.MIN_VALUE, memSum = 0;
        long netRxMin = Long.MAX_VALUE, netRxMax = Long.MIN_VALUE, netRxSum = 0;
        long netTxMin = Long.MAX_VALUE, netTxMax = Long.MIN_VALUE, netTxSum = 0;
        int tempMin = Integer.MAX_VALUE, tempMax = Integer.MIN_VALUE;
        long tempSum = 0;
        double fpsMin = Double.MAX_VALUE, fpsMax = Double.MIN_VALUE, fpsSum = 0;
        int cpuCnt = 0, memCnt = 0, netRxCnt = 0, netTxCnt = 0, tempCnt = 0, fpsCnt = 0;
        for (PerformanceTestResultDto.ResourceSample s : samples) {
            if (s.getCpuPercent() != null) {
                cpuMin = Math.min(cpuMin, s.getCpuPercent());
                cpuMax = Math.max(cpuMax, s.getCpuPercent());
                cpuSum += s.getCpuPercent();
                cpuCnt++;
            }
            if (s.getMemoryPssKb() != null) {
                memMin = Math.min(memMin, s.getMemoryPssKb());
                memMax = Math.max(memMax, s.getMemoryPssKb());
                memSum += s.getMemoryPssKb();
                memCnt++;
            }
            if (s.getNetworkRxBytes() != null) {
                netRxMin = Math.min(netRxMin, s.getNetworkRxBytes());
                netRxMax = Math.max(netRxMax, s.getNetworkRxBytes());
                netRxSum += s.getNetworkRxBytes();
                netRxCnt++;
            }
            if (s.getNetworkTxBytes() != null) {
                netTxMin = Math.min(netTxMin, s.getNetworkTxBytes());
                netTxMax = Math.max(netTxMax, s.getNetworkTxBytes());
                netTxSum += s.getNetworkTxBytes();
                netTxCnt++;
            }
            if (s.getTemperatureTenthsC() != null) {
                tempMin = Math.min(tempMin, s.getTemperatureTenthsC());
                tempMax = Math.max(tempMax, s.getTemperatureTenthsC());
                tempSum += s.getTemperatureTenthsC();
                tempCnt++;
            }
            if (s.getFps() != null) {
                fpsMin = Math.min(fpsMin, s.getFps());
                fpsMax = Math.max(fpsMax, s.getFps());
                fpsSum += s.getFps();
                fpsCnt++;
            }
        }
        return new PerformanceTestResultDto.ResourceSummary(
                cpuCnt > 0 ? new PerformanceTestResultDto.MetricStat(cpuMax, cpuMin == Double.MAX_VALUE ? 0 : cpuMin, cpuSum / cpuCnt) : null,
                memCnt > 0 ? new PerformanceTestResultDto.MetricStat((double) memMax, (double) memMin, (double) memSum / memCnt) : null,
                netRxCnt > 0 ? new PerformanceTestResultDto.MetricStat((double) netRxMax, (double) (netRxMin == Long.MAX_VALUE ? 0 : netRxMin), (double) netRxSum / netRxCnt) : null,
                netTxCnt > 0 ? new PerformanceTestResultDto.MetricStat((double) netTxMax, (double) (netTxMin == Long.MAX_VALUE ? 0 : netTxMin), (double) netTxSum / netTxCnt) : null,
                tempCnt > 0 ? new PerformanceTestResultDto.MetricStat(tempMax / 10.0, tempMin == Integer.MAX_VALUE ? 0 : tempMin / 10.0, (tempSum / (double) tempCnt) / 10.0) : null,
                fpsCnt > 0 ? new PerformanceTestResultDto.MetricStat(fpsMax, fpsMin == Double.MAX_VALUE ? 0 : fpsMin, fpsSum / fpsCnt) : null
        );
    }

    /** 为每条操作填充操作前/操作后截图索引及本步白屏时长，供「操作详情」展示 */
    private void fillOperationScreenshotsAndWhiteScreen(List<PerformanceTestResultDto.OperationRecord> operations,
                                                        List<PerformanceTestResultDto.ScreenshotTimelineEntry> screenshotTimeline,
                                                        List<Long> blackWhiteTimestamps, int screenshotIntervalSeconds) {
        if (operations == null || screenshotTimeline == null) return;
        int intervalMs = screenshotIntervalSeconds > 0 ? screenshotIntervalSeconds * 1000 : 1000;
        for (PerformanceTestResultDto.OperationRecord op : operations) {
            Long start = op.getStartTimeMs();
            Long end = op.getEndTimeMs();
            if (start == null || end == null) continue;
            int beforeIdx = -1;
            long beforeTs = -1;
            int afterIdx = -1;
            long afterTs = Long.MAX_VALUE;
            for (PerformanceTestResultDto.ScreenshotTimelineEntry e : screenshotTimeline) {
                long ts = e.getTimestampMs() != null ? e.getTimestampMs() : 0;
                if (ts <= start && ts > beforeTs) {
                    beforeTs = ts;
                    beforeIdx = e.getIndex() != null ? e.getIndex() : -1;
                }
                if (ts >= end && ts < afterTs) {
                    afterTs = ts;
                    afterIdx = e.getIndex() != null ? e.getIndex() : -1;
                }
            }
            op.setScreenshotBeforeIndex(beforeIdx >= 0 ? beforeIdx : null);
            op.setScreenshotAfterIndex(afterIdx >= 0 ? afterIdx : null);
            long whiteMs = 0;
            if (blackWhiteTimestamps != null) {
                for (Long ts : blackWhiteTimestamps) {
                    if (ts >= start && ts <= end) whiteMs += intervalMs;
                }
            }
            op.setWhiteScreenMs(whiteMs > 0 ? whiteMs : null);
        }
    }

    private PerformanceTestResultDto.IssueSummary buildIssueSummary(List<PerformanceTestResultDto.OperationRecord> operations,
                                                                   List<Long> blackWhiteTimestamps,
                                                                   List<PerformanceTestResultDto.ResourceSample> samples) {
        int responseDelayCount = 0;
        int completionDelayCount = 0;
        int launchResp = 0, launchComp = 0, clickResp = 0, clickComp = 0, swipeResp = 0, swipeComp = 0;
        for (PerformanceTestResultDto.OperationRecord op : operations) {
            if (op.getResponseDelayMs() != null && op.getResponseDelayMs() >= responseDelayThresholdMs) {
                responseDelayCount++;
                switch (op.getType()) {
                    case "launch" -> launchResp++;
                    case "click" -> clickResp++;
                    case "swipe" -> swipeResp++;
                }
            }
            if (op.getCompletionDelayMs() != null && op.getCompletionDelayMs() >= completionDelayThresholdMs) {
                completionDelayCount++;
                switch (op.getType()) {
                    case "launch" -> launchComp++;
                    case "click" -> clickComp++;
                    case "swipe" -> swipeComp++;
                }
            }
        }
        int stutterCount = 0;
        int launchStutter = 0, swipeStutter = 0, clickStutter = 0;
        if (samples != null && !samples.isEmpty() && operations != null && !operations.isEmpty()) {
            for (PerformanceTestResultDto.ResourceSample s : samples) {
                if (s.getJankPercent() == null || s.getJankPercent() < jankPercentThreshold) continue;
                stutterCount++;
                int idx = findOperationIndexByTime(operations, s.getTimestampMs() != null ? s.getTimestampMs() : 0L);
                if (idx >= 0 && idx < operations.size()) {
                    switch (operations.get(idx).getType()) {
                        case "launch" -> launchStutter++;
                        case "swipe" -> swipeStutter++;
                        case "click" -> clickStutter++;
                        default -> { }
                    }
                }
            }
        }
        int launchBw = 0, swipeBw = 0, clickBw = 0;
        if (blackWhiteTimestamps != null && !blackWhiteTimestamps.isEmpty() && !operations.isEmpty()) {
            for (Long ts : blackWhiteTimestamps) {
                int idx = findOperationIndexByTime(operations, ts);
                if (idx >= 0 && idx < operations.size()) {
                    switch (operations.get(idx).getType()) {
                        case "launch" -> launchBw++;
                        case "swipe" -> swipeBw++;
                        case "click" -> clickBw++;
                        default -> { }
                    }
                }
            }
        }
        int blackWhiteBlockCount = launchBw + swipeBw + clickBw;
        int avCount = 0; // 音视频问题预留
        int total = responseDelayCount + completionDelayCount + stutterCount + blackWhiteBlockCount + avCount;
        Map<String, PerformanceTestResultDto.OperationTypeIssueCounts> byType = new LinkedHashMap<>();
        byType.put("启动操作", new PerformanceTestResultDto.OperationTypeIssueCounts(launchResp, launchComp, launchStutter, 0, launchBw));
        byType.put("滑动操作", new PerformanceTestResultDto.OperationTypeIssueCounts(swipeResp, swipeComp, swipeStutter, 0, swipeBw));
        byType.put("点击操作", new PerformanceTestResultDto.OperationTypeIssueCounts(clickResp, clickComp, clickStutter, 0, clickBw));
        byType.put("观看视频", new PerformanceTestResultDto.OperationTypeIssueCounts(0, 0, 0, 0, 0));
        return new PerformanceTestResultDto.IssueSummary(responseDelayCount, completionDelayCount, stutterCount, blackWhiteBlockCount, avCount, total, byType);
    }

    /** 按时间戳找到对应的操作下标（该时间落在某操作的 [start,end] 内或最近） */
    private int findOperationIndexByTime(List<PerformanceTestResultDto.OperationRecord> operations, long timestampMs) {
        if (operations == null || operations.isEmpty()) return 0;
        for (int i = 0; i < operations.size(); i++) {
            PerformanceTestResultDto.OperationRecord op = operations.get(i);
            if (op.getStartTimeMs() != null && op.getEndTimeMs() != null && timestampMs >= op.getStartTimeMs() && timestampMs <= op.getEndTimeMs()) {
                return i;
            }
        }
        long best = Long.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < operations.size(); i++) {
            PerformanceTestResultDto.OperationRecord op = operations.get(i);
            long mid = op.getStartTimeMs() != null && op.getEndTimeMs() != null ? (op.getStartTimeMs() + op.getEndTimeMs()) / 2 : op.getStartTimeMs();
            long dist = Math.abs(timestampMs - mid);
            if (dist < best) {
                best = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * 从 Android 层级 XML 中解析出位于 (x,y) 处的元素文案（text 或 content-desc），用于操作描述如「点击-"关注"」。
     * 取包含该点且面积最小的节点的 text/content-desc；解析失败或未命中则返回 null。
     */
    private String getElementTextAtPoint(String deviceId, PlatformDriver driver, int x, int y) {
        if (driver == null) return null;
        try {
            String xml = driver.getPageSource(deviceId);
            if (xml == null || xml.length() > 2_000_000) return null;
            java.util.regex.Pattern boundsPat = java.util.regex.Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");
            java.util.regex.Pattern textPat = java.util.regex.Pattern.compile("text=\"([^\"]*)\"");
            java.util.regex.Pattern descPat = java.util.regex.Pattern.compile("content-desc=\"([^\"]*)\"");
            String[] nodes = xml.split("<node ");
            long bestArea = Long.MAX_VALUE;
            String bestText = null;
            for (int i = 1; i < nodes.length; i++) {
                String node = nodes[i];
                java.util.regex.Matcher b = boundsPat.matcher(node);
                if (!b.find()) continue;
                int x1 = Integer.parseInt(b.group(1));
                int y1 = Integer.parseInt(b.group(2));
                int x2 = Integer.parseInt(b.group(3));
                int y2 = Integer.parseInt(b.group(4));
                int left = Math.min(x1, x2);
                int right = Math.max(x1, x2);
                int top = Math.min(y1, y2);
                int bottom = Math.max(y1, y2);
                if (x < left || x > right || y < top || y > bottom) continue;
                long area = (long) (right - left) * (bottom - top);
                if (area >= bestArea) continue;
                String text = null;
                java.util.regex.Matcher tm = textPat.matcher(node);
                if (tm.find() && tm.group(1) != null && !tm.group(1).isBlank()) text = tm.group(1).trim();
                if (text == null) {
                    java.util.regex.Matcher dm = descPat.matcher(node);
                    if (dm.find() && dm.group(1) != null && !dm.group(1).isBlank()) text = dm.group(1).trim();
                }
                if (text != null) {
                    bestArea = area;
                    bestText = text.length() > 50 ? text.substring(0, 50) : text;
                }
            }
            return bestText;
        } catch (Exception e) {
            log.trace("解析点击位置文案失败: {}", e.getMessage());
            return null;
        }
    }

    /** 判断截图是否大部分为黑或白（简单启发式，用于「黑白块」问题统计） */
    private boolean isMostlyBlackOrWhite(String path) {
        try {
            java.io.File file = new java.io.File(path);
            if (!file.exists() || file.length() == 0) return false;
            javax.imageio.ImageIO.setUseCache(false);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(file);
            if (img == null) return false;
            int w = img.getWidth();
            int h = img.getHeight();
            int step = Math.max(1, Math.min(w, h) / 20);
            int dark = 0, light = 0, total = 0;
            for (int x = 0; x < w; x += step) {
                for (int y = 0; y < h; y += step) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xff;
                    int g = (rgb >> 8) & 0xff;
                    int b = rgb & 0xff;
                    if (r < 35 && g < 35 && b < 35) dark++;
                    else if (r > 220 && g > 220 && b > 220) light++;
                    total++;
                }
            }
            return total > 0 && (dark + light) * 100 / total >= 80;
        } catch (Exception e) {
            log.trace("黑白块检测失败: {}", e.getMessage());
            return false;
        }
    }

    private static String buildUnsupportedResultJson(String message) {
        return "{\"conclusion\":\"" + escapeJson(message) + "\",\"totalOperations\":0}";
    }

    private static String buildErrorResultJson(String message) {
        return "{\"conclusion\":\"" + escapeJson(message) + "\",\"totalOperations\":0}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** 发送 Android 返回键，用于退出播放页/详情页等，继续遍历其他页面 */
    private void sendBackKey(String deviceId) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "input", "keyevent", "KEYCODE_BACK")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("发送返回键失败: deviceId={}", deviceId, e);
        }
    }

    /** 流程 steps JSON 是否包含指定 keywordCode（含嵌套 then/else）。 */
    private boolean flowStepsContain(String flowId, String keywordCode) {
        if (flowId == null || keywordCode == null || uiFlowService == null) return false;
        try {
            UiFlow flow = uiFlowService.get(flowId);
            if (flow == null || flow.getSteps() == null || flow.getSteps().isBlank()) return false;
            return flowStepsJsonContains(flow.getSteps(), keywordCode);
        } catch (Exception e) {
            log.debug("解析流程关键字失败 flowId={}: {}", flowId, e.getMessage());
            return false;
        }
    }

    private static boolean flowStepsJsonContains(String stepsJson, String keywordCode) {
        if (stepsJson == null || keywordCode == null) return false;
        // 轻量匹配：足够判断是否含该关键字，避免再引一套嵌套解析
        String needle = "\"keywordCode\":\"" + keywordCode + "\"";
        String needleSpaced = "\"keywordCode\": \"" + keywordCode + "\"";
        return stepsJson.contains(needle) || stepsJson.contains(needleSpaced);
    }

    /** Android：强制结束应用进程，确保性能测试每次从冷启动开始，不依赖上次运行状态 */
    private void forceStopAndroidApp(String deviceId, String packageName) {
        if (packageName == null || packageName.isBlank()) return;
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "am", "force-stop", packageName)
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            log.info("性能测试冷启动：已结束应用进程 deviceId={}, package={}", deviceId, packageName);
        } catch (Exception e) {
            log.warn("adb force-stop 执行失败，继续启动应用: {}", e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class PerformanceTestExecutionResult {
        private String reportPath;
        private List<ExecutionLogEntryDto> executionLog;
        private String resultDataJson;
        /**
         * 本次性能测试是否失败。
         * <p>
         * 缺了这个字段时，性能任务无论流程有没有跑通都记 COMPLETED——
         * 采样数据是残缺的，报告上却显示「完成」，性能数据的可信度直接归零。
         */
        private boolean failed;
        /** 失败原因，写进 execution.error_message */
        private String errorMessage;

        public PerformanceTestExecutionResult(String reportPath, List<ExecutionLogEntryDto> executionLog,
                                              String resultDataJson) {
            this(reportPath, executionLog, resultDataJson, false, null);
        }

        /** 标记为失败并返回自身，便于在 return 处一行写完 */
        public PerformanceTestExecutionResult markFailed(String reason) {
            this.failed = true;
            this.errorMessage = reason;
            return this;
        }
    }
}
