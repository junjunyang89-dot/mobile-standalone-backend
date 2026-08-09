package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.constant.AppPackageConstants;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.OnlineRegressionAccount;
import com.mobile.standalone.entity.OnlineRegressionJob;
import com.mobile.standalone.entity.OnlineRegressionRun;
import com.mobile.standalone.entity.OnlineRegressionStep;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.util.TestEnvironment;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.mapper.OnlineRegressionAccountMapper;
import com.mobile.standalone.mapper.OnlineRegressionJobMapper;
import com.mobile.standalone.mapper.OnlineRegressionRunMapper;
import com.mobile.standalone.mapper.OnlineRegressionStepMapper;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.mapper.TaskMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * APP 线上回归编排：勾选 ui_flows（ONLINE/SHARED）后按序创建 UI_FLOW_TEST 任务执行。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnlineRegressionOrchestrator {

    public static final String APP_DOMESTIC = "DOMESTIC_ESPORTS";
    public static final String APP_POG = "OVERSEAS_POGPOG";

    /** Android 国内电竞包名片段（匹配用）/ 完整包名 */
    private static final String PKG_ESPORTS_ANDROID = "com.pwrd.steam.esports";
    /** iOS 国内电竞 Bundle ID（真机已安装：完美世界电竞） */
    private static final String PKG_ESPORTS_IOS = "com.wmzq.esportapp";
    /** Android PogPog */
    private static final String PKG_POG_ANDROID = AppPackageConstants.POGPOG_INTL_PACKAGE;
    /** iOS PogPog Bundle ID（真机已安装） */
    private static final String PKG_POG_IOS = "com.arca.egamesintl";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TASK_WAIT_MS = 20 * 60 * 1000L;
    private static final long POLL_MS = 2000L;
    private static final DateTimeFormatter ALERT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 兼容旧前端 featureIds → 种子流程 id（一两个版本后可删）。
     */
    private static final Map<String, String> LEGACY_FEATURE_TO_FLOW = Map.ofEntries(
            Map.entry("login", "flow-login-logout-android"),
            Map.entry("install", "flow-install-verify-android"),
            Map.entry("match", "flow-browse-match-android"),
            Map.entry("activity", "flow-browse-activity-android"),
            Map.entry("news", "flow-browse-news-android"),
            Map.entry("comment", "flow-browse-comment-android"),
            Map.entry("square", "flow-browse-square-android")
    );

    private final OnlineRegressionAccountMapper accountMapper;
    private final OnlineRegressionRunMapper runMapper;
    private final OnlineRegressionJobMapper jobMapper;
    private final OnlineRegressionStepMapper stepMapper;
    private final DeviceMapper deviceMapper;
    private final ApplicationMapper applicationMapper;
    private final TaskMapper taskMapper;
    private final TaskExecutionMapper taskExecutionMapper;
    private final TaskService taskService;
    private final UiFlowService uiFlowService;
    private final DeviceLockService deviceLockService;
    private final ExecutionCancelRegistry cancelRegistry;
    private final PushPlusNotifyService pushPlusNotifyService;

    @Value("${app.online-regression.alert.detail-url-template:}")
    private String alertDetailUrlTemplate;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "online-regression");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    @PostConstruct
    public void ensureDefaultAccounts() {
        try {
            seedIfMissing("or-acc-domestic-01", APP_DOMESTIC, "线上回归账号1", "18851663603", "487721", 1);
            seedIfMissing("or-acc-domestic-02", APP_DOMESTIC, "线上回归账号2", "12312340001", "660032", 2);
            seedIfMissing("or-acc-pog-01", APP_POG, "PogPog线上账号1", "18851663603", "487721", 1);
            seedIfMissing("or-acc-pog-02", APP_POG, "PogPog线上账号2", "12312340001", "660032", 2);
        } catch (Exception e) {
            log.warn("初始化线上回归账号池失败（请先执行 schema_online_regression.sql）: {}", e.getMessage());
        }
    }

    private void seedIfMissing(String id, String appId, String label, String phone, String code, int order) {
        if (accountMapper.findById(id) != null) return;
        OnlineRegressionAccount a = new OnlineRegressionAccount();
        a.setId(id);
        a.setAppId(appId);
        a.setLabel(label);
        a.setPhoneNumber(phone);
        a.setVerificationCode(code);
        a.setEnabled(true);
        a.setSortOrder(order);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        accountMapper.insert(a);
        log.info("已种子线上回归账号: {} {} {}", id, appId, phone);
    }

    /**
     * 界面上那个账号下拉。
     * <p>
     * 按环境筛：账号只区分测试和线上（数据不同），之前靠名字里带「线上」来认。
     * 命名约定唯一的失效方式是「有人建账号时没按约定命名」——那一定会发生，
     * 而结果是测试环境回归选到线上账号、登不上，你得从登录失败一路查回来。
     * <p>
     * 没标环境的账号两边都列：存量账号都没这个字段，不能因为加了列就一个都选不出来。
     */
    public List<OnlineRegressionAccount> listAccounts(String appId, String environment) {
        return accountMapper.findEnabled(appId, environment);
    }

    /** 清空全部历史批次（保留账号池与模块本身） */
    @Transactional
    public int clearAllHistory() {
        return runMapper.deleteAll();
    }

    public List<OrderedStepDto> previewOrderedSteps(List<String> flowIds) {
        return buildOrderedSteps(resolveFlowIds(flowIds, null));
    }

    /**
     * 执行顺序预览。自动化轨道会把没勾的前置补进来并按依赖排序——
     * 补了什么必须在开跑前让人看见，否则「我只勾了 3 条，怎么跑了 5 条」。
     */
    public OrderPreview previewOrder(List<String> flowIds, String track, String resetFlowId) {
        List<String> ids = new ArrayList<>(resolveFlowIds(flowIds, null));
        String reset = blankToNull(resetFlowId);
        if (reset != null) {
            ids.remove(reset);
        }
        List<String> autoAdded = List.of();
        if (RunTrack.isAutomation(track)) {
            FlowDependencyResolver.Result r = FlowDependencyResolver.resolve(ids, uiFlowService::get);
            ids = new ArrayList<>(r.orderedIds());
            autoAdded = r.autoAdded();
        }
        List<OrderedStepDto> steps = buildOrderedSteps(ids);
        if (reset != null) {
            UiFlow rf = uiFlowService.get(reset);
            if (rf != null) {
                steps = withResetBetween(steps, toOrderedStep(rf, "复位·" + rf.getName()));
            }
        }
        OrderPreview out = new OrderPreview();
        out.setSteps(steps);
        out.setAutoAddedIds(autoAdded);
        out.setResetFlowId(reset);
        return out;
    }

    @Transactional
    public OnlineRegressionRunDetail startRun(StartRunRequest req) {
        if (req.getAppIds() == null || req.getAppIds().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个目标 APP");
        }
        List<String> flowIds = resolveFlowIds(req.getFlowIds(), req.getFeatureIds());
        if (flowIds.isEmpty()) {
            throw new IllegalArgumentException("请至少勾选一个回归用例（流程）");
        }
        if (req.getDeviceIds() == null || req.getDeviceIds().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一台执行设备");
        }

        String environment = TestEnvironment.normalize(req.getEnvironment());
        String track = RunTrack.normalize(req.getTrack());
        req.setEnvironment(environment);
        req.setTrack(track);

        // 自动化轨道：先把没勾的前置补进来、按依赖排好，再校验——
        // 否则自动补入的前置会绕过校验，跑到一半才发现它压根不能进这条轨道
        List<String> autoAdded = List.of();
        if (RunTrack.isAutomation(track)) {
            FlowDependencyResolver.Result resolved =
                    FlowDependencyResolver.resolve(flowIds, uiFlowService::get);
            flowIds = resolved.orderedIds();
            autoAdded = resolved.autoAdded();
            if (!autoAdded.isEmpty()) {
                log.info("自动化批量执行：按前置依赖补入 {} 条流程 {}", autoAdded.size(), autoAdded);
            }
        }

        String resetFlowId = blankToNull(req.getResetFlowId());
        OrderedStepDto resetStep = null;
        if (resetFlowId != null) {
            UiFlow reset = uiFlowService.get(resetFlowId);
            if (reset == null) {
                throw new IllegalArgumentException("复位流程不存在: " + resetFlowId);
            }
            if (!uiFlowService.isBatchEligible(reset, environment, track)) {
                throw new IllegalArgumentException("复位流程「" + reset.getName() + "」不能用于"
                        + TestEnvironment.label(environment) + RunTrack.label(track)
                        + "（用途、环境、启用、非组件四条要同时满足）");
            }
            resetStep = toOrderedStep(reset, "复位·" + reset.getName());
            // 它已经在每条用例前跑了，再作为一条用例出现只会连着跑两遍
            flowIds = new ArrayList<>(flowIds);
            flowIds.remove(resetFlowId);
            if (flowIds.isEmpty()) {
                throw new IllegalArgumentException("除复位流程外没有可执行的用例");
            }
        }
        req.setResetFlowId(resetFlowId);

        List<UiFlow> validated = validateBatchFlows(flowIds, environment, track);
        List<OrderedStepDto> ordered = buildOrderedSteps(validated.stream().map(UiFlow::getId).toList());
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("勾选流程无可执行步骤");
        }

        List<Device> devices = new ArrayList<>();
        for (String deviceId : req.getDeviceIds()) {
            Device d = deviceMapper.findById(deviceId);
            if (d == null) throw new IllegalArgumentException("设备不存在: " + deviceId);
            devices.add(d);
        }

        String runId = "or-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        OnlineRegressionRun run = new OnlineRegressionRun();
        run.setId(runId);
        run.setStatus(OnlineRegressionRun.Status.PENDING.name());
        run.setSelectedApps(toJson(req.getAppIds()));
        run.setSelectedFeatures(toJson(flowIds));
        run.setOrderedSteps(toJson(ordered.stream().map(OrderedStepDto::getId).toList()));
        run.setEnableOverseasProxy(Boolean.TRUE.equals(req.getEnableOverseasProxy()));
        run.setEnvironment(environment);
        run.setTrack(track);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);

        boolean needsDistinctAccounts = flowsNeedAccount(validated);

        List<OnlineRegressionJob> jobs = new ArrayList<>();
        for (String appId : req.getAppIds()) {
            String appName = APP_POG.equals(appId) ? "海外 PogPog" : "国内电竞";
            List<Device> appDevices = new ArrayList<>();
            for (Device device : devices) {
                String endpoint = endpointOf(device.getPlatform());
                if ("other".equals(endpoint)) continue;
                String jobKey = appId + "__" + device.getId();
                if (req.getExcludeJobKeys() != null && req.getExcludeJobKeys().contains(jobKey)) continue;
                appDevices.add(device);
            }
            if (appDevices.isEmpty()) continue;

            List<OnlineRegressionAccount> accounts = resolveAccountsForApp(appId, req);
            if (needsDistinctAccounts && accounts.size() < appDevices.size()) {
                throw new IllegalArgumentException(
                        "APP「" + appName + "」勾选了需登录的流程，需为每台设备分配独立账号：当前设备 "
                                + appDevices.size() + " 台，可用账号 " + accounts.size()
                                + " 个。两机请在前置配置选择两个不同手机号");
            }
            Collections.shuffle(accounts);

            for (int di = 0; di < appDevices.size(); di++) {
                Device device = appDevices.get(di);
                String endpoint = endpointOf(device.getPlatform());
                String jobKey = appId + "__" + device.getId();
                OnlineRegressionAccount account = accounts.isEmpty() ? null : accounts.get(di % accounts.size());

                OnlineRegressionJob job = new OnlineRegressionJob();
                job.setId("orj-" + UUID.randomUUID());
                job.setRunId(runId);
                job.setJobKey(jobKey);
                job.setAppId(appId);
                job.setAppName(appName);
                job.setDeviceId(device.getId());
                job.setDeviceName(device.getName());
                job.setDevicePlatform(device.getPlatform() != null ? device.getPlatform().name() : "");
                job.setEndpoint(endpoint);
                job.setStatus(OnlineRegressionJob.Status.PENDING.name());
                job.setProgress(0);
                job.setCurrentStepLabel("排队中");
                job.setAccountPhone(account != null ? account.getPhoneNumber() : null);
                job.setCreatedAt(now);
                job.setUpdatedAt(now);
                jobMapper.insert(job);
                jobs.add(job);
                if (account != null) {
                    log.info("线上回归账号分配: jobKey={}, phone={}", jobKey, maskPhone(account.getPhoneNumber()));
                }

                List<OrderedStepDto> jobSteps = withResetBetween(
                        filterFlowsForJob(validated, appId, device), resetStep);
                if (jobSteps.isEmpty()) {
                    throw new IllegalArgumentException(
                            "APP「" + appName + "」× 设备「" + device.getName()
                                    + "」没有匹配的流程（请检查流程的 app_id / platform）");
                }
                int ord = 0;
                for (OrderedStepDto s : jobSteps) {
                    OnlineRegressionStep step = new OnlineRegressionStep();
                    step.setId("ors-" + UUID.randomUUID());
                    step.setJobId(job.getId());
                    step.setStepId(s.getId());
                    step.setStepLabel(s.getLabel());
                    step.setSortOrder(ord++);
                    step.setStatus(OnlineRegressionStep.Status.PENDING.name());
                    step.setCreatedAt(now);
                    step.setUpdatedAt(now);
                    stepMapper.insert(step);
                }
            }
        }
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("APP×设备矩阵为空（请选择 Android/iOS 族设备）");
        }

        stopFlags.put(runId, new AtomicBoolean(false));
        scheduleExecuteRunAfterCommit(runId, req);
        return getRunDetail(runId);
    }

    private static String resetFlowIdOf(StartRunRequest req) {
        return req == null ? null : blankToNull(req.getResetFlowId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void scheduleExecuteRunAfterCommit(String runId, StartRunRequest req) {
        Runnable start = () -> executor.submit(() -> executeRunSafely(runId, req));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    start.run();
                }
            });
        } else {
            start.run();
        }
    }

    private void executeRunSafely(String runId, StartRunRequest req) {
        try {
            executeRun(runId, req);
        } catch (Exception e) {
            log.error("线上回归批次执行异常: runId={}", runId, e);
            try {
                OnlineRegressionRun run = runMapper.findById(runId);
                if (run != null
                        && (OnlineRegressionRun.Status.PENDING.name().equals(run.getStatus())
                        || OnlineRegressionRun.Status.RUNNING.name().equals(run.getStatus()))) {
                    run.setStatus(OnlineRegressionRun.Status.FAILED.name());
                    run.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.toString());
                    run.setFinishedAt(LocalDateTime.now());
                    run.setUpdatedAt(LocalDateTime.now());
                    runMapper.update(run);
                    notifyOnlineRegressionFailure(run, jobMapper.findByRunId(runId));
                }
            } catch (Exception updateEx) {
                log.warn("回写线上回归失败状态异常: {}", updateEx.getMessage());
            } finally {
                stopFlags.remove(runId);
            }
        }
    }

    public void stopRun(String runId) {
        AtomicBoolean flag = stopFlags.computeIfAbsent(runId, k -> new AtomicBoolean(false));
        flag.set(true);
        OnlineRegressionRun run = runMapper.findById(runId);
        if (run != null && OnlineRegressionRun.Status.RUNNING.name().equals(run.getStatus())) {
            run.setStatus(OnlineRegressionRun.Status.STOPPED.name());
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.update(run);
        }
        for (OnlineRegressionJob job : jobMapper.findByRunId(runId)) {
            List<OnlineRegressionStep> steps = stepMapper.findByJobId(job.getId());
            for (OnlineRegressionStep st : steps) {
                if (OnlineRegressionStep.Status.RUNNING.name().equals(st.getStatus()) && st.getTaskId() != null) {
                    try {
                        taskService.stopTask(st.getTaskId());
                    } catch (Exception e) {
                        log.warn("停止关联任务失败: {}", e.getMessage());
                    }
                }
            }
        }
    }

    public OnlineRegressionRunDetail getRunDetail(String runId) {
        OnlineRegressionRun run = runMapper.findById(runId);
        if (run == null) return null;
        OnlineRegressionRunDetail detail = new OnlineRegressionRunDetail();
        detail.setRun(run);
        List<JobDetail> jobDetails = new ArrayList<>();
        for (OnlineRegressionJob job : jobMapper.findByRunId(runId)) {
            JobDetail jd = new JobDetail();
            jd.setJob(job);
            List<OnlineRegressionStep> steps = stepMapper.findByJobId(job.getId());
            for (OnlineRegressionStep s : steps) {
                if (s.getTaskId() != null && s.getScreenshotPath() != null && !s.getScreenshotPath().isBlank()) {
                    s.setScreenshotPath(s.getScreenshotPath()); // keep path; API adds URL
                }
            }
            jd.setSteps(steps);
            jobDetails.add(jd);
        }
        detail.setJobs(jobDetails);
        return detail;
    }

    /** 历史批次摘要（不含步骤明细；详情用 {@link #getRunDetail}） */
    public List<RunSummary> listRecentRunSummaries(int limit) {
        int n = Math.min(Math.max(limit, 1), 100);
        List<OnlineRegressionRun> runs = runMapper.findRecent(n);
        List<RunSummary> out = new ArrayList<>();
        if (runs == null) {
            return out;
        }
        for (OnlineRegressionRun run : runs) {
            RunSummary s = new RunSummary();
            s.setRun(run);
            List<OnlineRegressionJob> jobs = jobMapper.findByRunId(run.getId());
            s.setJobCount(jobs != null ? jobs.size() : 0);
            out.add(s);
        }
        return out;
    }

    private void executeRun(String runId, StartRunRequest req) {
        OnlineRegressionRun run = runMapper.findById(runId);
        if (run == null) {
            log.error("线上回归批次不存在或尚未可见，跳过执行: runId={}", runId);
            return;
        }
        List<OnlineRegressionJob> jobs = jobMapper.findByRunId(runId);
        log.info("线上回归批次开始执行: runId={}, jobs={}", runId, jobs.size());
        run.setStatus(OnlineRegressionRun.Status.RUNNING.name());
        run.setStartedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.update(run);
        // 不同 deviceId 可并行（共用一台 Appium Server，但会话按 udid + 独立端口隔离）；
        // 同一物理设备上的多个 APP 作业必须串行，否则会互相 closeSession / 抢前台。
        Map<String, List<OnlineRegressionJob>> byDevice = new LinkedHashMap<>();
        for (OnlineRegressionJob job : jobs) {
            byDevice.computeIfAbsent(job.getDeviceId() != null ? job.getDeviceId() : "", k -> new ArrayList<>()).add(job);
        }
        List<Thread> workers = new ArrayList<>();
        for (Map.Entry<String, List<OnlineRegressionJob>> e : byDevice.entrySet()) {
            String deviceId = e.getKey();
            List<OnlineRegressionJob> deviceJobs = e.getValue();
            Thread t = new Thread(() -> {
                // 整个 run 期间独占这台机：子任务以 runId 为父链重入，外部手动任务则被挡住，
                // 不会在两个回归步骤之间插进来抢设备
                DeviceLockService.Holder blocker = deviceId.isBlank() ? null
                        : deviceLockService.tryAcquire(deviceId, DeviceLockService.Kind.REGRESSION,
                        runId, "线上回归 " + runId, null);
                if (blocker != null) {
                    log.warn("线上回归无法占用设备，跳过该机作业: deviceId={}, 持有者={}", deviceId, blocker.describe());
                    failJobsForBusyDevice(deviceJobs, blocker);
                    return;
                }
                try {
                    for (OnlineRegressionJob job : deviceJobs) {
                        executeJob(runId, job, req);
                    }
                } finally {
                    if (!deviceId.isBlank()) {
                        deviceLockService.release(deviceId, runId);
                    }
                }
            }, "or-device-" + (deviceId.length() > 12 ? deviceId.substring(0, 12) : deviceId));
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 汇总
        int done = 0, ok = 0;
        for (OnlineRegressionJob job : jobMapper.findByRunId(runId)) {
            for (OnlineRegressionStep st : stepMapper.findByJobId(job.getId())) {
                // 复位是基础设施不是用例，算进通过率只会把指标冲淡
                if (resetFlowIdOf(req) != null && resetFlowIdOf(req).equals(st.getStepId())) {
                    continue;
                }
                boolean passed = OnlineRegressionStep.Status.SUCCESS.name().equals(st.getStatus())
                        || OnlineRegressionStep.Status.UNSTABLE.name().equals(st.getStatus());
                if (passed || OnlineRegressionStep.Status.FAILED.name().equals(st.getStatus())) {
                    done++;
                    // 不稳定的算通过：功能确实是好的。但状态保留 UNSTABLE，报告里能单独看到该治理哪些
                    if (passed) ok++;
                }
            }
        }
        OnlineRegressionRun finished = runMapper.findById(runId);
        if (finished == null) return;
        AtomicBoolean stopped = stopFlags.getOrDefault(runId, new AtomicBoolean(false));
        if (stopped.get() || OnlineRegressionRun.Status.STOPPED.name().equals(finished.getStatus())) {
            finished.setStatus(OnlineRegressionRun.Status.STOPPED.name());
        } else {
            finished.setStatus(OnlineRegressionRun.Status.COMPLETED.name());
        }
        finished.setPassRate(done == 0 ? 0 : Math.round(ok * 100f / done));
        finished.setFinishedAt(LocalDateTime.now());
        finished.setUpdatedAt(LocalDateTime.now());
        runMapper.update(finished);
        stopFlags.remove(runId);

        List<OnlineRegressionJob> finishedJobs = jobMapper.findByRunId(runId);
        // 人工停止不告警；有失败步骤/失败作业才推微信（含设备被占用导致全跳过）
        if (!OnlineRegressionRun.Status.STOPPED.name().equals(finished.getStatus())
                && shouldAlertOnlineRegression(finished, finishedJobs, done, ok)) {
            notifyOnlineRegressionFailure(finished, finishedJobs);
        }
    }

    private boolean shouldAlertOnlineRegression(OnlineRegressionRun run, List<OnlineRegressionJob> jobs,
                                                int done, int ok) {
        if (OnlineRegressionRun.Status.FAILED.name().equals(run.getStatus())) {
            return true;
        }
        if (done > 0 && ok < done) {
            return true;
        }
        if (jobs == null) {
            return false;
        }
        for (OnlineRegressionJob job : jobs) {
            if (OnlineRegressionJob.Status.FAILED.name().equals(job.getStatus())) {
                return true;
            }
            for (OnlineRegressionStep st : stepMapper.findByJobId(job.getId())) {
                if (OnlineRegressionStep.Status.FAILED.name().equals(st.getStatus())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 线上回归整场失败推个人微信（PushPlus）；同一 run 调一次即可。
     * 推送失败不影响回归落库。
     */
    private void notifyOnlineRegressionFailure(OnlineRegressionRun run, List<OnlineRegressionJob> jobs) {
        if (run == null || !pushPlusNotifyService.isReady()) {
            return;
        }
        try {
            List<String> failLines = new ArrayList<>();
            Set<String> devices = new LinkedHashSet<>();
            Set<String> apps = new LinkedHashSet<>();
            if (jobs != null) {
                for (OnlineRegressionJob job : jobs) {
                    if (job.getDeviceName() != null && !job.getDeviceName().isBlank()) {
                        devices.add(job.getDeviceName());
                    } else if (job.getDeviceId() != null) {
                        devices.add(job.getDeviceId());
                    }
                    if (job.getAppName() != null && !job.getAppName().isBlank()) {
                        apps.add(job.getAppName());
                    } else if (job.getAppId() != null) {
                        apps.add(job.getAppId());
                    }
                    if (OnlineRegressionJob.Status.FAILED.name().equals(job.getStatus())
                            && job.getErrorMessage() != null && !job.getErrorMessage().isBlank()) {
                        failLines.add("· [" + nullToDash(job.getAppName() != null ? job.getAppName() : job.getAppId())
                                + "] 作业失败 — " + truncateAlert(job.getErrorMessage(), 80));
                    }
                    for (OnlineRegressionStep st : stepMapper.findByJobId(job.getId())) {
                        if (!OnlineRegressionStep.Status.FAILED.name().equals(st.getStatus())) {
                            continue;
                        }
                        String label = st.getStepLabel() != null && !st.getStepLabel().isBlank()
                                ? st.getStepLabel() : st.getStepId();
                        String err = st.getErrorMessage() != null ? truncateAlert(st.getErrorMessage().trim(), 80) : "";
                        failLines.add("· [" + nullToDash(job.getAppName() != null ? job.getAppName() : job.getAppId())
                                + "] " + nullToDash(label)
                                + (err.isEmpty() ? "" : " — " + err));
                    }
                }
            }
            if (failLines.isEmpty() && run.getErrorMessage() != null && !run.getErrorMessage().isBlank()) {
                failLines.add("· " + run.getErrorMessage().trim());
            }

            int passRate = run.getPassRate() != null ? run.getPassRate() : 0;
            String title = "线上回归失败 · 通过率 " + passRate + "%";
            StringBuilder body = new StringBuilder();
            body.append("批次：").append(run.getId()).append('\n');
            body.append("状态：").append(run.getStatus()).append('\n');
            body.append("通过率：").append(passRate).append("%\n");
            body.append("环境：").append(run.getEnvironment() != null ? run.getEnvironment() : "PROD").append('\n');
            if (!apps.isEmpty()) {
                body.append("应用：").append(String.join("、", apps)).append('\n');
            }
            if (!devices.isEmpty()) {
                body.append("设备：").append(String.join("、", devices)).append('\n');
            }
            if (run.getFinishedAt() != null) {
                body.append("结束：").append(run.getFinishedAt().format(ALERT_TIME)).append('\n');
            }
            body.append('\n').append("失败明细（最多 8 条）：\n");
            int n = 0;
            for (String line : failLines) {
                if (n++ >= 8) {
                    body.append("· …还有 ").append(failLines.size() - 8).append(" 条\n");
                    break;
                }
                body.append(line).append('\n');
            }
            if (failLines.isEmpty()) {
                body.append("· （无逐步失败明细，请到平台查看）\n");
            }
            String detailTpl = alertDetailUrlTemplate != null ? alertDetailUrlTemplate.trim() : "";
            if (!detailTpl.isEmpty()) {
                body.append('\n').append("详情：").append(detailTpl.replace("{runId}", run.getId()));
            }

            pushPlusNotifyService.sendText(title, body.toString().trim());
        } catch (Exception e) {
            log.warn("线上回归失败告警组装异常: runId={}, err={}", run.getId(), e.getMessage());
        }
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String truncateAlert(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 设备被别的执行链占着时，本机作业整体判失败并说明原因，而不是静默不跑 */
    private void failJobsForBusyDevice(List<OnlineRegressionJob> deviceJobs, DeviceLockService.Holder blocker) {
        String reason = "设备被占用：" + blocker.describe() + "，本次回归未在该机执行";
        for (OnlineRegressionJob job : deviceJobs) {
            job.setStatus(OnlineRegressionJob.Status.FAILED.name());
            job.setErrorMessage(reason);
            job.setCurrentStepLabel("设备被占用");
            job.setUpdatedAt(LocalDateTime.now());
            jobMapper.update(job);
            for (OnlineRegressionStep st : stepMapper.findByJobId(job.getId())) {
                if (OnlineRegressionStep.Status.PENDING.name().equals(st.getStatus())) {
                    st.setStatus(OnlineRegressionStep.Status.SKIPPED.name());
                    st.setErrorMessage(reason);
                    st.setFinishedAt(LocalDateTime.now());
                    st.setUpdatedAt(LocalDateTime.now());
                    stepMapper.update(st);
                }
            }
        }
    }

    private void executeJob(String runId, OnlineRegressionJob job, StartRunRequest req) {
        job.setStatus(OnlineRegressionJob.Status.RUNNING.name());
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.update(job);

        OnlineRegressionAccount account = resolveAccountForJob(job, req);
        String environment = TestEnvironment.normalize(req.getEnvironment());
        String track = RunTrack.normalize(req.getTrack());
        String resetFlowId = resetFlowIdOf(req);
        // 自动化轨道失败不中断整批，得记着最后把作业判成失败
        boolean anyFailed = false;
        List<OnlineRegressionStep> steps = stepMapper.findByJobId(job.getId());
        steps.sort(Comparator.comparingInt(s -> s.getSortOrder() != null ? s.getSortOrder() : 0));

        for (int i = 0; i < steps.size(); i++) {
            if (stopFlags.getOrDefault(runId, new AtomicBoolean(false)).get()) {
                job.setStatus(OnlineRegressionJob.Status.STOPPED.name());
                job.setCurrentStepLabel("已停止");
                job.setUpdatedAt(LocalDateTime.now());
                jobMapper.update(job);
                for (int j = i; j < steps.size(); j++) {
                    OnlineRegressionStep st = steps.get(j);
                    if (OnlineRegressionStep.Status.PENDING.name().equals(st.getStatus())) {
                        st.setStatus(OnlineRegressionStep.Status.SKIPPED.name());
                        st.setUpdatedAt(LocalDateTime.now());
                        st.setFinishedAt(LocalDateTime.now());
                        stepMapper.update(st);
                    }
                }
                return;
            }

            OnlineRegressionStep step = steps.get(i);
            step.setStatus(OnlineRegressionStep.Status.RUNNING.name());
            step.setStartedAt(LocalDateTime.now());
            step.setUpdatedAt(LocalDateTime.now());
            stepMapper.update(step);
            job.setCurrentStepLabel(step.getStepLabel());
            job.setProgress(Math.round(i * 100f / steps.size()));
            job.setUpdatedAt(LocalDateTime.now());
            jobMapper.update(job);

            try {
                StepExecResult result = executeStep(job, step, account, environment, track);

                // 首次失败就整步重跑一次：真坏的东西重跑还是坏，偶发的重跑就过了。
                // 这样「红色」才能稳定地代表真问题——否则偶现红会让人先怀疑自动化，
                // 复核成本超过收益之后，整套东西就没人看了。
                boolean retried = false;
                if (!result.success && !result.skipped
                        && !stopFlags.getOrDefault(runId, new AtomicBoolean(false)).get()) {
                    log.info("回归步骤首次失败，重跑一次: job={}, step={}, err={}",
                            job.getJobKey(), step.getStepId(), result.message);
                    String firstError = result.message;
                    StepExecResult second = executeStep(job, step, account, environment, track);
                    retried = true;
                    if (second.success) {
                        // 重跑通过：功能是好的，但这条用例不稳定
                        second.message = "首次失败后重跑通过（不稳定）。首次错误：" + firstError;
                    }
                    result = second;
                }

                if (result.taskId != null) {
                    step.setTaskId(result.taskId);
                }
                step.setScreenshotPath(result.screenshotPath);
                if (result.skipped) {
                    step.setStatus(OnlineRegressionStep.Status.SKIPPED.name());
                    step.setErrorMessage(result.message);
                } else if (result.success) {
                    step.setStatus(retried
                            ? OnlineRegressionStep.Status.UNSTABLE.name()
                            : OnlineRegressionStep.Status.SUCCESS.name());
                    step.setErrorMessage(retried ? result.message : null);
                } else {
                    step.setStatus(OnlineRegressionStep.Status.FAILED.name());
                    step.setErrorMessage(retried ? "重跑仍失败：" + result.message : result.message);
                }
            } catch (Exception e) {
                log.error("线上回归步骤失败: job={}, step={}", job.getJobKey(), step.getStepId(), e);
                step.setStatus(OnlineRegressionStep.Status.FAILED.name());
                step.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.toString());
            }
            step.setFinishedAt(LocalDateTime.now());
            step.setUpdatedAt(LocalDateTime.now());
            stepMapper.update(step);

            // 停止请求可能在本步执行期间下发：此时不该把作业记成失败
            if (stopFlags.getOrDefault(runId, new AtomicBoolean(false)).get()) {
                job.setStatus(OnlineRegressionJob.Status.STOPPED.name());
                job.setCurrentStepLabel("已停止");
                job.setUpdatedAt(LocalDateTime.now());
                jobMapper.update(job);
                skipRest(steps, i + 1, null);
                return;
            }

            if (OnlineRegressionStep.Status.FAILED.name().equals(step.getStatus())) {
                job.setErrorMessage(step.getErrorMessage());
                job.setProgress(Math.round((i + 1) * 100f / steps.size()));
                job.setUpdatedAt(LocalDateTime.now());

                if (!RunTrack.isAutomation(track)) {
                    // 回归是一条线性的串：前一步没过，后面的前提就不成立了，整批到此为止
                    job.setStatus(OnlineRegressionJob.Status.FAILED.name());
                    jobMapper.update(job);
                    skipRest(steps, i + 1, null);
                    return;
                }

                jobMapper.update(job);
                if (resetFlowId != null && resetFlowId.equals(step.getStepId())) {
                    // 复位失败：只有紧跟其后的那条用例没了起点前提。再往后每条都有自己的复位，
                    // 而复位本身就是冷启动——下一次多半能把设备拉回来，没必要判死整批
                    if (i + 1 < steps.size()) {
                        OnlineRegressionStep next = steps.get(i + 1);
                        log.info("复位失败，跳过紧随其后的「{}」", next.getStepLabel());
                        skipOne(next, "复位失败，本条没有已知起点");
                    }
                } else {
                    // 自动化是依赖图：只有（传递）依赖这一条的才没了前提，不相干的用例继续跑。
                    // 一次前置失败就把整批判死，报告里会全是查不出所以然的红
                    List<String> remaining = steps.subList(i + 1, steps.size()).stream()
                            .map(OnlineRegressionStep::getStepId).toList();
                    Set<String> blocked = FlowDependencyResolver.dependentsOf(
                            step.getStepId(), remaining, uiFlowService::get);
                    if (!blocked.isEmpty()) {
                        log.info("自动化批量执行：{} 失败，跳过依赖它的 {} 条", step.getStepLabel(), blocked.size());
                        skipRest(steps, i + 1, blocked);
                    }
                }
                anyFailed = true;
            }
        }

        job.setProgress(100);
        job.setStatus(anyFailed
                ? OnlineRegressionJob.Status.FAILED.name()
                : OnlineRegressionJob.Status.SUCCESS.name());
        job.setCurrentStepLabel(anyFailed ? "已跑完，有失败" : "全部完成");
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.update(job);
    }

    /**
     * 把 {@code from} 之后的待跑步骤标成跳过。
     *
     * @param onlyStepIds 只跳这些 stepId；传 null 表示后面全跳
     */
    /** 只跳这一条（复位失败时用：受影响的仅有紧随其后的那条用例） */
    private void skipOne(OnlineRegressionStep step, String reason) {
        if (!OnlineRegressionStep.Status.PENDING.name().equals(step.getStatus())) {
            return;
        }
        step.setStatus(OnlineRegressionStep.Status.SKIPPED.name());
        step.setErrorMessage(reason);
        step.setFinishedAt(LocalDateTime.now());
        step.setUpdatedAt(LocalDateTime.now());
        stepMapper.update(step);
    }

    private void skipRest(List<OnlineRegressionStep> steps, int from, Set<String> onlyStepIds) {
        for (int j = from; j < steps.size(); j++) {
            OnlineRegressionStep skip = steps.get(j);
            if (onlyStepIds != null && !onlyStepIds.contains(skip.getStepId())) {
                continue;
            }
            if (!OnlineRegressionStep.Status.PENDING.name().equals(skip.getStatus())) {
                continue;
            }
            skip.setStatus(OnlineRegressionStep.Status.SKIPPED.name());
            if (onlyStepIds != null) {
                skip.setErrorMessage("前置流程失败，本条没有执行前提");
            }
            skip.setFinishedAt(LocalDateTime.now());
            skip.setUpdatedAt(LocalDateTime.now());
            stepMapper.update(skip);
        }
    }

    private StepExecResult executeStep(OnlineRegressionJob job, OnlineRegressionStep step,
                                       OnlineRegressionAccount account, String environment, String track)
            throws Exception {
        String flowId = step.getStepId();
        Device device = deviceMapper.findById(job.getDeviceId());
        if (device == null) {
            return StepExecResult.fail("设备不存在: " + job.getDeviceId());
        }
        UiFlow flow = uiFlowService.get(flowId);
        if (flow == null) {
            return StepExecResult.fail("流程不存在: " + flowId);
        }
        if (!uiFlowService.isBatchEligible(flow, environment, track)) {
            return StepExecResult.fail("流程不可用于" + TestEnvironment.label(environment)
                    + RunTrack.label(track) + "（用途、环境、启用、非组件四条要同时满足）: " + flow.getName());
        }
        Task.Platform taskPlatform = resolveTaskPlatform(job.getAppId(), device.getPlatform());
        return executeStepViaUiFlow(job, step, account, device, taskPlatform, flowId, environment);
    }

    /**
     * 回归步骤走 ui_flows：创建 UI_FLOW_TEST 任务并等待结束（taskId 写入步骤，可在测试任务中查看）。
     */
    private StepExecResult executeStepViaUiFlow(OnlineRegressionJob job, OnlineRegressionStep step,
                                                OnlineRegressionAccount account, Device device,
                                                Task.Platform taskPlatform, String flowId,
                                                String environment) throws Exception {
        Application app = resolveOrCreateApplication(device, job.getAppId(), taskPlatform);

        ObjectNode config = JSON.createObjectNode();
        config.put("flowId", flowId);
        config.put("runTrack", RunTrack.REGRESSION);
        config.put("uiAppId", job.getAppId());
        // 归因签名与任务总览都按这个字段区分环境，别让两个环境的失败互相「复用」结论
        config.put("environment", environment);
        if (account != null) {
            ObjectNode params = config.putObject("params");
            if (account.getPhoneNumber() != null) {
                params.put("phone", account.getPhoneNumber());
                params.put("phoneNumber", account.getPhoneNumber());
            }
            if (account.getVerificationCode() != null) {
                params.put("code", account.getVerificationCode());
                params.put("verificationCode", account.getVerificationCode());
            }
        }

        Task task = new Task();
        // 生产的任务名保持原样，别让存量报告集体变名字；只有测试环境换前缀
        task.setName((TestEnvironment.isTest(environment) ? "测试环境回归-" : "线上回归-")
                + job.getAppName() + "-" + step.getStepLabel());
        task.setDescription("online-regression job=" + job.getJobKey() + " step=" + step.getStepId()
                + " flowId=" + flowId);
        task.setPlatform(taskPlatform);
        task.setDeviceId(device.getId());
        task.setAppId(app.getId());
        task.setTaskType(Task.TaskType.UI_FLOW_TEST);
        task.setScenarioKey("ui_flow");
        task.setConfig(JSON.writeValueAsString(config));
        task.setStatus(Task.TaskStatus.PENDING);

        Task created = taskService.createTask(task);
        // taskId 必须在等待之前就落库：停止批次时要靠 step.task_id 找到在跑的任务下发取消，
        // 等步骤跑完再写就永远来不及——那样「停止」只能卡在步骤之间，单步骤作业根本停不掉
        step.setTaskId(created.getId());
        step.setUpdatedAt(LocalDateTime.now());
        stepMapper.update(step);
        log.info("回归步骤走流程: step={}, flowId={}, taskId={}", step.getStepId(), flowId, created.getId());
        // 以 runId 为父链：设备已被本次回归持有，子任务重入而不是被自己挡住
        boolean started = taskService.runTask(created.getId(), job.getRunId());
        if (!started) {
            return StepExecResult.fail("流程任务启动失败: " + created.getId(), created.getId(), null);
        }

        Task finished = waitTask(created.getId());
        String shot = latestScreenshotFromExecutions(created.getId());
        if (finished == null) {
            return StepExecResult.fail("等待流程任务超时", created.getId(), shot);
        }
        if (finished.getStatus() == Task.TaskStatus.COMPLETED) {
            return StepExecResult.ok(created.getId(), shot);
        }
        String err = "流程任务状态: " + finished.getStatus();
        List<TaskExecution> execs = taskExecutionMapper.findByTaskIdOrderByStartTimeDesc(created.getId());
        if (execs != null && !execs.isEmpty() && execs.get(0).getErrorMessage() != null) {
            err = execs.get(0).getErrorMessage();
        }
        // 流程声明「本次不适用」而提前结束：记跳过，不计入通过率，也不算失败
        if (finished.getStatus() == Task.TaskStatus.SKIPPED) {
            return StepExecResult.skipped(err, created.getId(), shot);
        }
        // 用户点了停止：不是用例失败，记跳过，避免把人为中断算进失败率
        if (finished.getStatus() == Task.TaskStatus.STOPPED) {
            return StepExecResult.skipped("已停止：" + step.getStepLabel(), created.getId(), shot);
        }
        return StepExecResult.fail(err, created.getId(), shot);
    }

    /**
     * 任务平台只表达操作系统。
     * <p>
     * 以前会按 appId 把海外应用映射成 POG_ANDROID / POG_IOS，导致同一个元素要按平台各存一份；
     * 而 appId 本身已经区分了国内/海外，平台再分一次是冗余。
     */
    private Task.Platform resolveTaskPlatform(String appId, Device.Platform devicePlatform) {
        if (Device.isAndroidOrPogAndroid(devicePlatform)) {
            return Task.Platform.ANDROID;
        }
        if (Device.usesIosAutomation(devicePlatform)) {
            return Task.Platform.IOS;
        }
        if (devicePlatform == Device.Platform.HARMONYOS) {
            return Task.Platform.HARMONYOS;
        }
        throw new IllegalArgumentException("设备平台不支持该 APP: " + devicePlatform);
    }


    private Application resolveOrCreateApplication(Device device, String appId, Task.Platform taskPlatform) {
        boolean ios = taskPlatform == Task.Platform.IOS || taskPlatform == Task.Platform.POG_IOS;
        String exactPkg = resolveTargetPackage(appId, ios);
        String matchHint = APP_POG.equals(appId)
                ? (ios ? "egamesintl" : "egames.intl")
                : (ios ? "esportapp" : "steam.esports");
        List<Application> list = applicationMapper.findByDeviceId(device.getId());
        if (list != null) {
            for (Application a : list) {
                String pn = a.getPackageName();
                if (pn == null) continue;
                String lower = pn.toLowerCase(Locale.ROOT);
                if (exactPkg.equalsIgnoreCase(pn) || lower.contains(matchHint.toLowerCase(Locale.ROOT))) {
                    return a;
                }
            }
        }
        // 设备应用列表未必已扫描到包名：创建占位记录供任务外键，启动场景仍可按固定包名运行
        Application stub = new Application();
        stub.setId("or-app-" + UUID.randomUUID());
        stub.setName(APP_POG.equals(appId) ? "PogPog" : "完美世界电竞");
        stub.setPackageName(exactPkg);
        stub.setDeviceId(device.getId());
        stub.setPlatform(Application.Platform.valueOf(taskPlatform.name()));
        stub.setInstalledAt(LocalDateTime.now());
        applicationMapper.insert(stub);
        log.info("线上回归为设备 {} 创建应用占位: {} ({})", device.getId(), stub.getName(), stub.getPackageName());
        return stub;
    }

    private String resolveTargetPackage(String appId, boolean ios) {
        if (APP_POG.equals(appId)) {
            return ios ? PKG_POG_IOS : PKG_POG_ANDROID;
        }
        return ios ? PKG_ESPORTS_IOS : PKG_ESPORTS_ANDROID;
    }

    /**
     * 解析本 APP 可用账号列表（多账号优先；兼容单账号/自定义手机号）。
     * 调用方负责 shuffle 后按设备一一分配。
     */
    private List<OnlineRegressionAccount> resolveAccountsForApp(String appId, StartRunRequest req) {
        List<OnlineRegressionAccount> out = new ArrayList<>();
        Set<String> seenPhones = new LinkedHashSet<>();

        if (req.getCustomPhones() != null && !req.getCustomPhones().isEmpty()) {
            List<String> codes = req.getCustomCodes() != null ? req.getCustomCodes() : List.of();
            List<OnlineRegressionAccount> pool = accountMapper.findEnabled(appId, null);
            for (int i = 0; i < req.getCustomPhones().size(); i++) {
                String phone = req.getCustomPhones().get(i);
                if (phone == null || phone.isBlank()) continue;
                phone = phone.trim();
                if (!seenPhones.add(phone)) continue;
                String code = i < codes.size() && codes.get(i) != null ? codes.get(i).trim() : "";
                // 未传验证码时，按手机号从账号池匹配（各渠道号码一一对应）
                if (code.isEmpty() && pool != null) {
                    for (OnlineRegressionAccount a : pool) {
                        if (phone.equals(a.getPhoneNumber() != null ? a.getPhoneNumber().trim() : null)
                                && a.getVerificationCode() != null && !a.getVerificationCode().isBlank()) {
                            code = a.getVerificationCode().trim();
                            break;
                        }
                    }
                }
                OnlineRegressionAccount custom = new OnlineRegressionAccount();
                custom.setAppId(appId);
                custom.setPhoneNumber(phone);
                custom.setVerificationCode(code);
                custom.setLabel("自定义账号" + (i + 1));
                out.add(custom);
            }
            if (!out.isEmpty()) return out;
        }

        if (req.getCustomPhone() != null && !req.getCustomPhone().isBlank()) {
            String phone = req.getCustomPhone().trim();
            String code = req.getCustomCode() != null ? req.getCustomCode().trim() : "";
            if (code.isEmpty()) {
                List<OnlineRegressionAccount> pool = accountMapper.findEnabled(appId, null);
                if (pool != null) {
                    for (OnlineRegressionAccount a : pool) {
                        if (phone.equals(a.getPhoneNumber() != null ? a.getPhoneNumber().trim() : null)
                                && a.getVerificationCode() != null && !a.getVerificationCode().isBlank()) {
                            code = a.getVerificationCode().trim();
                            break;
                        }
                    }
                }
            }
            OnlineRegressionAccount custom = new OnlineRegressionAccount();
            custom.setAppId(appId);
            custom.setPhoneNumber(phone);
            custom.setVerificationCode(code);
            custom.setLabel("自定义账号");
            out.add(custom);
            return out;
        }

        List<String> ids = new ArrayList<>();
        if (req.getAccountIds() != null) {
            for (String id : req.getAccountIds()) {
                if (id != null && !id.isBlank()) ids.add(id.trim());
            }
        }
        if (ids.isEmpty() && req.getAccountId() != null && !req.getAccountId().isBlank()) {
            ids.add(req.getAccountId().trim());
        }
        for (String id : ids) {
            OnlineRegressionAccount hit = accountMapper.findById(id);
            if (hit == null) continue;
            if (hit.getAppId() != null && !hit.getAppId().equals(appId)) continue;
            String phone = hit.getPhoneNumber();
            if (phone == null || !seenPhones.add(phone.trim())) continue;
            out.add(hit);
        }
        if (!out.isEmpty()) return out;

        List<OnlineRegressionAccount> pool = accountMapper.findEnabled(appId, null);
        return pool != null ? new ArrayList<>(pool) : new ArrayList<>();
    }

    /** 执行时按 job 上已分配的手机号还原账号（含验证码） */
    private OnlineRegressionAccount resolveAccountForJob(OnlineRegressionJob job, StartRunRequest req) {
        String phone = job.getAccountPhone();
        if (phone != null && !phone.isBlank()) {
            phone = phone.trim();
            List<OnlineRegressionAccount> candidates = resolveAccountsForApp(job.getAppId(), req);
            for (OnlineRegressionAccount a : candidates) {
                if (phone.equals(a.getPhoneNumber() != null ? a.getPhoneNumber().trim() : null)) {
                    return a;
                }
            }
            List<OnlineRegressionAccount> pool = accountMapper.findEnabled(job.getAppId(), null);
            if (pool != null) {
                for (OnlineRegressionAccount a : pool) {
                    if (phone.equals(a.getPhoneNumber() != null ? a.getPhoneNumber().trim() : null)) {
                        return a;
                    }
                }
            }
            OnlineRegressionAccount fallback = new OnlineRegressionAccount();
            fallback.setAppId(job.getAppId());
            fallback.setPhoneNumber(phone);
            fallback.setVerificationCode("");
            fallback.setLabel("job分配账号");
            return fallback;
        }
        List<OnlineRegressionAccount> list = resolveAccountsForApp(job.getAppId(), req);
        return list.isEmpty() ? null : list.get(0);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "****";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private Task waitTask(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TASK_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskMapper.findById(taskId);
            if (t == null) return null;
            Task.TaskStatus s = t.getStatus();
            if (s == Task.TaskStatus.COMPLETED || s == Task.TaskStatus.FAILED || s == Task.TaskStatus.STOPPED) {
                // 停止请求会立刻把状态写成 STOPPED，但执行线程可能还在收尾（当前步骤跑完才中断）。
                // 此时设备仍被占用，必须等线程真正退出，否则下一步会和上一步抢同一台机。
                awaitExecutionThreadExit(taskId, deadline);
                return taskMapper.findById(taskId);
            }
            Thread.sleep(POLL_MS);
        }
        return taskMapper.findById(taskId);
    }

    /** 等待任务的执行线程退出，最多等到本次 waitTask 的截止时间 */
    private void awaitExecutionThreadExit(String taskId, long deadline) throws InterruptedException {
        while (cancelRegistry.isActive(taskId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
        }
        if (cancelRegistry.isActive(taskId)) {
            log.warn("等待任务执行线程退出超时，后续步骤可能与其抢占设备: taskId={}", taskId);
        }
    }

    private String latestScreenshotFromExecutions(String taskId) {
        try {
            List<TaskExecution> list = taskExecutionMapper.findByTaskIdOrderByStartTimeDesc(taskId);
            if (list == null || list.isEmpty()) return null;
            TaskExecution ex = list.get(0);
            if (ex.getReportPath() != null && !ex.getReportPath().isBlank()) {
                return ex.getReportPath();
            }
            if (ex.getExecutionLog() == null || ex.getExecutionLog().isBlank()) return null;
            JsonNode arr = JSON.readTree(ex.getExecutionLog());
            String last = null;
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    JsonNode p = n.get("screenshotPath");
                    if (p != null && !p.asText("").isBlank()) last = p.asText();
                }
            }
            return last;
        } catch (Exception e) {
            log.debug("解析截图失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析请求中的流程 id：优先 flowIds；兼容旧 featureIds。
     */
    public List<String> resolveFlowIds(List<String> flowIds, List<String> featureIds) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (flowIds != null) {
            for (String id : flowIds) {
                if (id != null && !id.isBlank()) out.add(id.trim());
            }
        }
        if (out.isEmpty() && featureIds != null) {
            for (String fid : featureIds) {
                if (fid == null || fid.isBlank()) continue;
                String mapped = LEGACY_FEATURE_TO_FLOW.get(fid.trim());
                if (mapped != null) {
                    log.warn("线上回归收到旧 featureIds={}，已映射为 flowId={}（请改前端提交 flowIds）", fid, mapped);
                    out.add(mapped);
                } else if (fid.contains("-") || fid.startsWith("uif-") || fid.startsWith("flow-")) {
                    // 误把 flowId 塞进 featureIds 的兼容
                    out.add(fid.trim());
                } else {
                    log.warn("无法映射旧 featureId={}", fid);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** 按传入顺序校验并返回（顺序已由调用方定：回归=勾选顺序，自动化=依赖拓扑序）。 */
    private List<UiFlow> validateBatchFlows(List<String> flowIds, String environment, String track) {
        List<UiFlow> flows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : flowIds) {
            if (id == null || id.isBlank() || !seen.add(id.trim())) continue;
            UiFlow f = uiFlowService.get(id.trim());
            if (f == null) {
                throw new IllegalArgumentException("流程不存在: " + id);
            }
            if (!uiFlowService.isBatchEligible(f, environment, track)) {
                throw new IllegalArgumentException(
                        "流程「" + f.getName() + "」不可用于" + TestEnvironment.label(environment)
                                + RunTrack.label(track) + "：需用途属于「"
                                + String.join(" / ", RunTrack.catalogs(track))
                                + "」、环境为「" + TestEnvironment.label(environment)
                                + "」、且已启用、非组件（当前用途=" + f.getCatalog()
                                + "，环境=" + TestEnvironment.label(f.getEnvironment()) + "）");
            }
            flows.add(f);
        }
        return flows;
    }

    private static OrderedStepDto toOrderedStep(UiFlow f, String label) {
        OrderedStepDto dto = new OrderedStepDto();
        dto.setId(f.getId());
        dto.setLabel(label != null ? label : (f.getName() != null ? f.getName() : f.getId()));
        dto.setWeight(f.getSortOrder() != null ? f.getSortOrder() : 100);
        dto.setFromFeature(f.getCatalog());
        return dto;
    }

    /**
     * 在每条用例之前插一次复位，<b>第一条除外</b>——那时 APP 可能还没装，冷启动必挂。
     * 复位为 null 时原样返回。
     */
    static List<OrderedStepDto> withResetBetween(List<OrderedStepDto> steps, OrderedStepDto reset) {
        if (reset == null || steps == null || steps.size() <= 1) {
            return steps;
        }
        List<OrderedStepDto> out = new ArrayList<>(steps.size() * 2);
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                out.add(reset);
            }
            out.add(steps.get(i));
        }
        return out;
    }

    private static boolean flowsNeedAccount(List<UiFlow> flows) {
        for (UiFlow f : flows) {
            String blob = ((f.getName() != null ? f.getName() : "")
                    + " " + (f.getDescription() != null ? f.getDescription() : "")
                    + " " + (f.getId() != null ? f.getId() : "")).toLowerCase(Locale.ROOT);
            if (blob.contains("登录") || blob.contains("login") || blob.contains("logout")
                    || blob.contains("退出") || blob.contains("账号")) {
                return true;
            }
        }
        return false;
    }

    private List<OrderedStepDto> filterFlowsForJob(List<UiFlow> flows, String appId, Device device) {
        List<OrderedStepDto> out = new ArrayList<>();
        for (UiFlow f : flows) {
            if (!UiFlowService.matchesRegressionApp(f, appId)) {
                continue;
            }
            if (!platformMatches(f.getPlatform(), device.getPlatform())) {
                continue;
            }
            OrderedStepDto dto = new OrderedStepDto();
            dto.setId(f.getId());
            dto.setLabel(f.getName() != null ? f.getName() : f.getId());
            dto.setWeight(f.getSortOrder() != null ? f.getSortOrder() : 100);
            dto.setFromFeature(f.getCatalog());
            out.add(dto);
        }
        return out;
    }

    private static boolean platformMatches(String flowPlatform, Device.Platform devicePlatform) {
        if (flowPlatform == null || flowPlatform.isBlank()) return true;
        if (devicePlatform == null) return false;
        String fp = flowPlatform.trim().toUpperCase(Locale.ROOT);
        String dp = devicePlatform.name().toUpperCase(Locale.ROOT);
        if (fp.equals(dp)) return true;
        if ("ANDROID".equals(fp) && Device.isAndroidOrPogAndroid(devicePlatform)) return true;
        if ("IOS".equals(fp) && Device.usesIosAutomation(devicePlatform)) return true;
        return false;
    }

    /** 按 flowIds 传入顺序生成步骤列表（勾选顺序 = 执行顺序）。 */
    public List<OrderedStepDto> buildOrderedSteps(List<String> flowIds) {
        if (flowIds == null || flowIds.isEmpty()) return List.of();
        List<OrderedStepDto> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : flowIds) {
            if (id == null || id.isBlank() || !seen.add(id.trim())) continue;
            UiFlow f = uiFlowService.get(id.trim());
            if (f == null) continue;
            OrderedStepDto dto = new OrderedStepDto();
            dto.setId(f.getId());
            dto.setLabel(f.getName() != null ? f.getName() : f.getId());
            dto.setWeight(f.getSortOrder() != null ? f.getSortOrder() : 100);
            dto.setFromFeature(f.getCatalog());
            steps.add(dto);
        }
        return steps;
    }

    private static String endpointOf(Device.Platform p) {
        if (Device.isAndroidOrPogAndroid(p)) return "android";
        if (Device.usesIosAutomation(p)) return "ios";
        return "other";
    }

    private String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    @Data
    public static class StartRunRequest {
        private List<String> appIds;
        private List<String> deviceIds;
        /** 勾选的 ui_flows.id 列表 */
        private List<String> flowIds;
        /** @deprecated 兼容旧前端，映射到种子 flowId */
        private List<String> featureIds;
        private List<String> excludeJobKeys;
        /** 多账号（两机回归传两个不同 id，随机分配） */
        private List<String> accountIds;
        private String accountId;
        private List<String> customPhones;
        private List<String> customCodes;
        private String customPhone;
        private String customCode;
        private Boolean enableOverseasProxy;
        /**
         * 本批次跑哪个环境：PROD（默认）/ TEST。
         * <p>
         * 只决定两件事——候选用例取哪批 catalog、报告与任务名上的标签。
         * 执行逻辑一个字都不变：两个环境包名相同、元素通用、跑的是同一套关键字。
         */
        private String environment;
        /**
         * 轨道：REGRESSION（默认）/ AUTOMATION。
         * <p>
         * 决定候选用例取哪批 catalog，以及<b>顺序怎么来</b>——回归用勾选顺序，
         * 自动化按流程声明的前置依赖拓扑排序。空值按 REGRESSION，线上回归行为不变。
         */
        private String track;
        /**
         * 复位流程：每条用例<b>之前</b>先跑一次（冷启动 + 清弹窗，把设备带回已知起点）。
         * <p>
         * 跟 {@code dependsOn} 的前置是两回事——前置一批里只跑一次，复位每条都跑。
         * 它解决的是「上一条用例停在设置页，下一条以为自己从首页开始」这类状态污染：
         * 依赖图排得再对，页面状态被上一条带歪了照样一片红。
         * <p>
         * 第一条用例前面<b>不</b>插复位：那时 APP 可能还没装，冷启动必挂。
         */
        private String resetFlowId;
    }

    @Data
    public static class OrderPreview {
        private List<OrderedStepDto> steps;
        /** 用户没勾、因被依赖而补进来的流程 ID */
        private List<String> autoAddedIds;
        /** 复位流程 ID；steps 里 id 等于它的都是复位步骤 */
        private String resetFlowId;
    }

    @Data
    public static class OrderedStepDto {
        private String id;
        private String label;
        private int weight;
        private String fromFeature;
    }

    @Data
    public static class OnlineRegressionRunDetail {
        private OnlineRegressionRun run;
        private List<JobDetail> jobs;
    }

    @Data
    public static class RunSummary {
        private OnlineRegressionRun run;
        private int jobCount;
    }

    @Data
    public static class JobDetail {
        private OnlineRegressionJob job;
        private List<OnlineRegressionStep> steps;
    }

    private static class StepExecResult {
        boolean success;
        boolean skipped;
        String message;
        String taskId;
        String screenshotPath;

        static StepExecResult ok(String taskId, String shot) {
            StepExecResult r = new StepExecResult();
            r.success = true;
            r.taskId = taskId;
            r.screenshotPath = shot;
            return r;
        }

        static StepExecResult fail(String msg) {
            return fail(msg, null, null);
        }

        static StepExecResult fail(String msg, String taskId, String shot) {
            StepExecResult r = new StepExecResult();
            r.success = false;
            r.message = msg;
            r.taskId = taskId;
            r.screenshotPath = shot;
            return r;
        }

        static StepExecResult skipped(String msg) {
            return skipped(msg, null, null);
        }

        static StepExecResult skipped(String msg, String taskId, String shot) {
            StepExecResult r = new StepExecResult();
            r.skipped = true;
            r.message = msg;
            r.taskId = taskId;
            r.screenshotPath = shot;
            return r;
        }
    }
}
