package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.OnlineRegressionAccount;
import com.mobile.standalone.entity.OnlineRegressionJob;
import com.mobile.standalone.entity.OnlineRegressionRun;
import com.mobile.standalone.entity.OnlineRegressionStep;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
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
 * APP 线上回归编排：复用功能测试场景（启动/登录/CS 浏览等）+ 独立账号池与批次表。
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
    private static final String PKG_POG_ANDROID = LoginTestService.POGPOG_INTL_PACKAGE;
    /** iOS PogPog Bundle ID（真机已安装） */
    private static final String PKG_POG_IOS = "com.arca.egamesintl";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TASK_WAIT_MS = 20 * 60 * 1000L;
    private static final long POLL_MS = 2000L;

    /** 功能勾选 → 原子步骤（登录=登→退→再登；安装=卸→装→启动，权重排末尾） */
    private static final Map<String, List<String>> FEATURE_TO_STEPS = Map.ofEntries(
            Map.entry("install", List.of("uninstall_pkg", "install_pkg", "launch")),
            Map.entry("login", List.of("login", "logout", "relogin")),
            Map.entry("comment", List.of("comment")),
            Map.entry("match", List.of("match")),
            Map.entry("activity", List.of("activity")),
            Map.entry("square", List.of("square")),
            Map.entry("news", List.of("news"))
    );

    /** 越小越先执行；安装/卸载/启动固定在浏览类步骤之后 */
    private static final Map<String, Integer> STEP_WEIGHT = Map.ofEntries(
            Map.entry("login", 30),
            Map.entry("logout", 32),
            Map.entry("relogin", 34),
            Map.entry("comment", 40),
            Map.entry("match", 41),
            Map.entry("activity", 42),
            Map.entry("square", 43),
            Map.entry("news", 44),
            Map.entry("uninstall_pkg", 90),
            Map.entry("install_pkg", 91),
            Map.entry("launch", 92)
    );

    private static final Map<String, String> STEP_LABEL = Map.ofEntries(
            Map.entry("install_pkg", "包体安装"),
            Map.entry("launch", "应用启动"),
            Map.entry("login", "账号登录"),
            Map.entry("comment", "互动发评论"),
            Map.entry("match", "赛事浏览（CS / DOTA2 当天）"),
            Map.entry("activity", "活动登录与查看"),
            Map.entry("square", "广场信息流浏览"),
            Map.entry("news", "发现-资讯页浏览"),
            Map.entry("logout", "账号退出"),
            Map.entry("relogin", "账号再登录"),
            Map.entry("uninstall_pkg", "卸载包体")
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
    private final AppPackageInstallService appPackageInstallService;

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

    public List<OnlineRegressionAccount> listAccounts(String appId) {
        return accountMapper.findEnabled(appId);
    }

    public List<OrderedStepDto> previewOrderedSteps(List<String> featureIds) {
        return buildOrderedSteps(featureIds);
    }

    @Transactional
    public OnlineRegressionRunDetail startRun(StartRunRequest req) {
        if (req.getAppIds() == null || req.getAppIds().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个目标 APP");
        }
        if (req.getFeatureIds() == null || req.getFeatureIds().isEmpty()) {
            throw new IllegalArgumentException("请至少勾选一个回归功能");
        }
        if (req.getDeviceIds() == null || req.getDeviceIds().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一台执行设备");
        }

        List<OrderedStepDto> ordered = buildOrderedSteps(req.getFeatureIds());
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("勾选功能未展开出可执行步骤");
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
        run.setSelectedFeatures(toJson(req.getFeatureIds()));
        run.setOrderedSteps(toJson(ordered.stream().map(OrderedStepDto::getId).toList()));
        run.setEnableOverseasProxy(Boolean.TRUE.equals(req.getEnableOverseasProxy()));
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);

        boolean needsDistinctAccounts = req.getFeatureIds() != null && req.getFeatureIds().contains("login");

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
                        "APP「" + appName + "」勾选「账号登录与退出」时需为每台设备分配独立账号：当前设备 "
                                + appDevices.size() + " 台，可用账号 " + accounts.size()
                                + " 个。两机请在前置配置选择两个不同手机号");
            }
            // 随机打乱后按设备一一分配，避免两机落到同一账号
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

                int ord = 0;
                for (OrderedStepDto s : ordered) {
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
        // 必须在事务提交后再异步执行：否则 executeRun 在未提交数据上 findById 得到 null，批次会永远停在「排队中」
        scheduleExecuteRunAfterCommit(runId, req);
        return getRunDetail(runId);
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
                for (OnlineRegressionJob job : deviceJobs) {
                    executeJob(runId, job, req);
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
                if (OnlineRegressionStep.Status.SUCCESS.name().equals(st.getStatus())
                        || OnlineRegressionStep.Status.FAILED.name().equals(st.getStatus())) {
                    done++;
                    if (OnlineRegressionStep.Status.SUCCESS.name().equals(st.getStatus())) ok++;
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
    }

    private void executeJob(String runId, OnlineRegressionJob job, StartRunRequest req) {
        job.setStatus(OnlineRegressionJob.Status.RUNNING.name());
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.update(job);

        OnlineRegressionAccount account = resolveAccountForJob(job, req);
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
                StepExecResult result = executeStep(job, step, account);
                step.setTaskId(result.taskId);
                step.setScreenshotPath(result.screenshotPath);
                if (result.skipped) {
                    step.setStatus(OnlineRegressionStep.Status.SKIPPED.name());
                    step.setErrorMessage(result.message);
                } else if (result.success) {
                    step.setStatus(OnlineRegressionStep.Status.SUCCESS.name());
                } else {
                    step.setStatus(OnlineRegressionStep.Status.FAILED.name());
                    step.setErrorMessage(result.message);
                }
            } catch (Exception e) {
                log.error("线上回归步骤失败: job={}, step={}", job.getJobKey(), step.getStepId(), e);
                step.setStatus(OnlineRegressionStep.Status.FAILED.name());
                step.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.toString());
            }
            step.setFinishedAt(LocalDateTime.now());
            step.setUpdatedAt(LocalDateTime.now());
            stepMapper.update(step);

            if (OnlineRegressionStep.Status.FAILED.name().equals(step.getStatus())) {
                job.setStatus(OnlineRegressionJob.Status.FAILED.name());
                job.setErrorMessage(step.getErrorMessage());
                job.setProgress(Math.round((i + 1) * 100f / steps.size()));
                job.setUpdatedAt(LocalDateTime.now());
                jobMapper.update(job);
                for (int j = i + 1; j < steps.size(); j++) {
                    OnlineRegressionStep skip = steps.get(j);
                    skip.setStatus(OnlineRegressionStep.Status.SKIPPED.name());
                    skip.setFinishedAt(LocalDateTime.now());
                    skip.setUpdatedAt(LocalDateTime.now());
                    stepMapper.update(skip);
                }
                return;
            }
        }

        job.setProgress(100);
        job.setStatus(OnlineRegressionJob.Status.SUCCESS.name());
        job.setCurrentStepLabel("全部完成");
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.update(job);
    }

    private StepExecResult executeStep(OnlineRegressionJob job, OnlineRegressionStep step, OnlineRegressionAccount account)
            throws Exception {
        String sid = step.getStepId();
        // 暂未单独落地自动化的步骤：明确 SKIPPED，避免假成功
        if ("comment".equals(sid) || "square".equals(sid)) {
            return StepExecResult.skipped("步骤「" + step.getStepLabel() + "」后端脚本适配中，已跳过");
        }

        Device device = deviceMapper.findById(job.getDeviceId());
        if (device == null) {
            return StepExecResult.fail("设备不存在: " + job.getDeviceId());
        }

        // 线上回归安装/卸载/启动：商店拉起后用 CLI 启动，避免建 Task 触库锁、避免 iOS 重装 WDA
        if ("uninstall_pkg".equals(sid)) {
            AppPackageInstallService.OpResult r = appPackageInstallService.uninstallForOnlineRegression(job, device);
            return r.success ? StepExecResult.ok(null, null) : StepExecResult.fail(r.message);
        }
        if ("install_pkg".equals(sid)) {
            AppPackageInstallService.OpResult r = appPackageInstallService.installFromStoreForOnlineRegression(job, device);
            return r.success ? StepExecResult.ok(null, null) : StepExecResult.fail(r.message);
        }
        if ("launch".equals(sid)) {
            AppPackageInstallService.OpResult r = appPackageInstallService.launchForOnlineRegression(job, device);
            return r.success
                    ? StepExecResult.ok(null, r.screenshotPath)
                    : StepExecResult.fail(r.message);
        }

        Task.Platform taskPlatform = resolveTaskPlatform(job.getAppId(), device.getPlatform());
        Application app = resolveOrCreateApplication(device, job.getAppId(), taskPlatform);

        String scenarioKey = mapScenarioKey(sid, job.getAppId(), taskPlatform);
        if (scenarioKey == null) {
            return StepExecResult.skipped("步骤「" + step.getStepLabel() + "」暂无对应场景键");
        }

        Task task = new Task();
        task.setName("线上回归-" + job.getAppName() + "-" + step.getStepLabel());
        task.setDescription("online-regression job=" + job.getJobKey() + " step=" + sid);
        task.setPlatform(taskPlatform);
        task.setDeviceId(device.getId());
        task.setAppId(app.getId());
        task.setTaskType(Task.TaskType.FUNCTIONAL_QUALITY_TEST);
        task.setScenarioKey(scenarioKey);
        task.setStatus(Task.TaskStatus.PENDING);
        if (account != null && (isLoginFlowStep(sid) || needsLoginConfig(sid))) {
            task.setConfig(loginConfigJson(account, loginModeForStep(sid)));
        }
        Task created = taskService.createTask(task);
        boolean started = taskService.runTask(created.getId());
        if (!started) {
            return StepExecResult.fail("任务启动失败: " + created.getId(), created.getId(), null);
        }

        Task finished = waitTask(created.getId());
        String shot = latestScreenshotFromExecutions(created.getId());
        if (finished == null) {
            return StepExecResult.fail("等待任务结果超时", created.getId(), shot);
        }
        if (finished.getStatus() == Task.TaskStatus.COMPLETED) {
            return StepExecResult.ok(created.getId(), shot);
        }
        String err = "任务状态: " + finished.getStatus();
        List<TaskExecution> execs = taskExecutionMapper.findByTaskIdOrderByStartTimeDesc(created.getId());
        if (execs != null && !execs.isEmpty() && execs.get(0).getErrorMessage() != null) {
            err = execs.get(0).getErrorMessage();
        }
        return StepExecResult.fail(err, created.getId(), shot);
    }

    private boolean needsLoginConfig(String stepId) {
        return "activity".equals(stepId) || "comment".equals(stepId);
    }

    private static boolean isLoginFlowStep(String stepId) {
        return "login".equals(stepId) || "logout".equals(stepId) || "relogin".equals(stepId);
    }

    /** login/relogin 强制走登录；logout 仅退出 */
    private static String loginModeForStep(String stepId) {
        if ("logout".equals(stepId)) return "logout";
        if ("login".equals(stepId) || "relogin".equals(stepId)) return "force";
        return "login";
    }

    private String mapScenarioKey(String stepId, String appId, Task.Platform platform) {
        return switch (stepId) {
            case "launch" -> {
                if (APP_POG.equals(appId) && (platform == Task.Platform.POG_ANDROID || platform == Task.Platform.ANDROID)) {
                    yield FunctionalTestScenarioService.SCENARIO_LAUNCH_POGPOG_ANDROID;
                }
                yield FunctionalTestScenarioService.SCENARIO_LAUNCH_APP;
            }
            case "login", "logout", "relogin" -> FunctionalTestScenarioService.SCENARIO_LOGIN;
            case "match" -> FunctionalTestScenarioService.SCENARIO_CS_EVENT_SCHEDULE_BROWSE;
            case "activity" -> FunctionalTestScenarioService.SCENARIO_CS_ACTIVITY_BROWSE;
            case "news" -> FunctionalTestScenarioService.SCENARIO_CS_NEWS_BROWSING;
            default -> null;
        };
    }

    private Task.Platform resolveTaskPlatform(String appId, Device.Platform devicePlatform) {
        boolean android = Device.isAndroidOrPogAndroid(devicePlatform);
        boolean ios = Device.usesIosAutomation(devicePlatform);
        if (APP_POG.equals(appId)) {
            if (android) return Task.Platform.POG_ANDROID;
            if (ios) return Task.Platform.POG_IOS;
        } else {
            if (android) return Task.Platform.ANDROID;
            if (ios) return Task.Platform.IOS;
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
            List<OnlineRegressionAccount> pool = accountMapper.findEnabled(appId);
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
                List<OnlineRegressionAccount> pool = accountMapper.findEnabled(appId);
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

        List<OnlineRegressionAccount> pool = accountMapper.findEnabled(appId);
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
            List<OnlineRegressionAccount> pool = accountMapper.findEnabled(job.getAppId());
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
                return t;
            }
            Thread.sleep(POLL_MS);
        }
        return taskMapper.findById(taskId);
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

    private String loginConfigJson(OnlineRegressionAccount account, String loginMode) {
        try {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("phoneNumber", account.getPhoneNumber());
            m.put("verificationCode", account.getVerificationCode() != null ? account.getVerificationCode() : "");
            if (loginMode != null && !loginMode.isBlank()) {
                m.put("loginMode", loginMode);
            }
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"phoneNumber\":\"" + account.getPhoneNumber()
                    + "\",\"verificationCode\":\"" + (account.getVerificationCode() != null ? account.getVerificationCode() : "")
                    + "\",\"loginMode\":\"" + (loginMode != null ? loginMode : "login") + "\"}";
        }
    }

    public List<OrderedStepDto> buildOrderedSteps(List<String> featureIds) {
        Set<String> seen = new HashSet<>();
        List<OrderedStepDto> steps = new ArrayList<>();
        for (String fid : featureIds) {
            List<String> expanded = FEATURE_TO_STEPS.getOrDefault(fid, Collections.emptyList());
            for (String sid : expanded) {
                if (!seen.add(sid)) continue;
                OrderedStepDto dto = new OrderedStepDto();
                dto.setId(sid);
                dto.setLabel(STEP_LABEL.getOrDefault(sid, sid));
                dto.setWeight(STEP_WEIGHT.getOrDefault(sid, 50));
                dto.setFromFeature(fid);
                steps.add(dto);
            }
        }
        steps.sort(Comparator.comparingInt(OrderedStepDto::getWeight).thenComparing(OrderedStepDto::getId));
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
            StepExecResult r = new StepExecResult();
            r.skipped = true;
            r.message = msg;
            return r;
        }
    }
}
