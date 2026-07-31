package com.mobile.standalone.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.PerformanceTestResultDto;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import io.appium.java_client.AppiumDriver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.Dimension;
import org.springframework.beans.factory.annotation.Value;
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
 * 性能基础质量测试：自动遍历（启动/点击/滑动）+ 资源采样（CPU、内存），产出结果 JSON 与执行日志。
 * 当前仅支持 Android；需 adb 可用，建议 appium.enabled=true 以便使用 Appium 做滑动/点击。
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

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final AndroidPerformanceCollector performanceCollector;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

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
            log.add(ExecutionLogEntryDto.fail("性能测试", "当前仅支持 Android / Pog-Android 设备", null));
            String resultJson = buildUnsupportedResultJson("仅支持 Android / Pog-Android 设备");
            return new PerformanceTestExecutionResult(null, log, resultJson);
        }

        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        executionLog.add(ExecutionLogEntryDto.success("性能测试", "开始性能基础质量测试，遍历时长约 " + effectiveTraversalMinutes + " 分钟"));
        executionLog.add(ExecutionLogEntryDto.success("环境", "设备:" + deviceId + ", 包名:" + packageName));

        PlatformDriver driver = platformRouter.getDriver(device);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "无法获取平台驱动", null));
            return new PerformanceTestExecutionResult(null, executionLog, buildUnsupportedResultJson("无法获取平台驱动"));
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

        // 启动应用
        executionLog.add(ExecutionLogEntryDto.success("启动应用", "正在启动 " + packageName));
        if (!driver.launchApp(deviceId, packageName)) {
            executionLog.add(ExecutionLogEntryDto.fail("启动应用", "启动失败", null));
            return new PerformanceTestExecutionResult(null, executionLog, buildErrorResultJson("启动应用失败"));
        }
        sleep(3000);

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
        Thread samplerThread = new Thread(() -> {
            while (!samplingStopped.get()) {
                AndroidPerformanceCollector.Sample s = performanceCollector.sample(deviceId, packageName);
                if (s.getCpuPercent() != null || s.getMemoryPssKb() != null
                        || s.getNetworkRxBytes() != null || s.getNetworkTxBytes() != null || s.getTemperatureTenthsC() != null) {
                    resourceSamples.add(new PerformanceTestResultDto.ResourceSample(
                            System.currentTimeMillis(),
                            s.getCpuPercent(),
                            s.getMemoryPssKb(),
                            s.getNetworkRxBytes(),
                            s.getNetworkTxBytes(),
                            s.getTemperatureTenthsC()
                    ));
                }
                for (int i = 0; i < sampleIntervalSeconds && !samplingStopped.get(); i++) {
                    sleep(1000);
                }
            }
        }, "perf-sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();

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
        PerformanceTestResultDto.IssueSummary issueSummary = buildIssueSummary(operations, blackWhiteTimestamps);
        String conclusion = String.format("总共遍历 %d 分钟，执行操作 %d 次，发现较好体验问题 %d 次。",
                effectiveTraversalMinutes, totalOps, issueSummary != null ? issueSummary.getTotalIssueCount() : 0);
        executionLog.add(ExecutionLogEntryDto.success("测试结论", conclusion));

        int count = screenshotTimeline.size();
        PerformanceTestResultDto result = PerformanceTestResultDto.builder()
                .traversalDurationMinutes(effectiveTraversalMinutes)
                .totalOperations(totalOps)
                .launchCount(launchCount)
                .clickCount(clickCount)
                .swipeCount(swipeCount)
                .conclusion(conclusion)
                .resourceSummary(resourceSummary)
                .issueSummary(issueSummary)
                .resourceSamples(resourceSamples.isEmpty() ? null : new ArrayList<>(resourceSamples))
                .operations(operations)
                .screenshotCount(count > 0 ? count : null)
                .screenshotTimeline(count > 0 ? new ArrayList<>(screenshotTimeline) : null)
                .build();

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
        int cpuCnt = 0, memCnt = 0, netRxCnt = 0, netTxCnt = 0, tempCnt = 0;
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
        }
        return new PerformanceTestResultDto.ResourceSummary(
                cpuCnt > 0 ? new PerformanceTestResultDto.MetricStat(cpuMax, cpuMin == Double.MAX_VALUE ? 0 : cpuMin, cpuSum / cpuCnt) : null,
                memCnt > 0 ? new PerformanceTestResultDto.MetricStat((double) memMax, (double) memMin, (double) memSum / memCnt) : null,
                netRxCnt > 0 ? new PerformanceTestResultDto.MetricStat((double) netRxMax, (double) (netRxMin == Long.MAX_VALUE ? 0 : netRxMin), (double) netRxSum / netRxCnt) : null,
                netTxCnt > 0 ? new PerformanceTestResultDto.MetricStat((double) netTxMax, (double) (netTxMin == Long.MAX_VALUE ? 0 : netTxMin), (double) netTxSum / netTxCnt) : null,
                tempCnt > 0 ? new PerformanceTestResultDto.MetricStat(tempMax / 10.0, tempMin == Integer.MAX_VALUE ? 0 : tempMin / 10.0, (tempSum / (double) tempCnt) / 10.0) : null
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

    private PerformanceTestResultDto.IssueSummary buildIssueSummary(List<PerformanceTestResultDto.OperationRecord> operations, List<Long> blackWhiteTimestamps) {
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
        int stutterCount = 0; // 卡顿需帧率/掉帧数据，当前未实现
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
        byType.put("启动操作", new PerformanceTestResultDto.OperationTypeIssueCounts(launchResp, launchComp, 0, 0, launchBw));
        byType.put("滑动操作", new PerformanceTestResultDto.OperationTypeIssueCounts(swipeResp, swipeComp, 0, 0, swipeBw));
        byType.put("点击操作", new PerformanceTestResultDto.OperationTypeIssueCounts(clickResp, clickComp, 0, 0, clickBw));
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
    }
}
