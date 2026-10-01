package com.mobile.standalone.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FunctionalTestExecutionResult;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.UiApp;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.service.keyword.handlers.DontKeepActivitiesHandler;
import com.mobile.standalone.util.TestEnvironment;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import com.mobile.standalone.service.keyword.FlowOutcome;
import com.mobile.standalone.service.keyword.FlowRuntime;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.KeywordParamValidator;
import com.mobile.standalone.service.keyword.KeywordRegistry;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import com.mobile.standalone.util.LocatorTemplate;
import com.mobile.standalone.util.PlatformNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 按 ui_flows.steps（keywordCode + elementKey）执行流程。元素只认 ui_elements 中 status=ACTIVE 的记录。
 * <p>
 * 本类只负责「怎么跑一条流程」：步骤循环、取消检查、截图、元素解析、变量与包名解析。
 * 「每个关键字做什么」由 {@link KeywordHandler} 各自实现，通过 {@link KeywordRegistry} 分发——
 * 关键字的定义、参数契约、截图策略、执行逻辑收在同一个类里，不再散落多处各写一份。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UiFlowExecutor implements FlowRuntime {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 显式配置 screenshotRounds 时的连拍参数（兼容「就是要看动画过程」的老流程）。
     * 默认不再连拍，改为等页面稳定后拍一张。
     */
    private static final int STEP_SCREENSHOT_ROUNDS = 3;
    private static final long STEP_SCREENSHOT_INTERVAL_MS = 1000L;

    /** 页面稳定判定：连续两次控件树一致即认为稳定 */
    private static final long SETTLE_TIMEOUT_MS = 6000L;
    private static final long SETTLE_POLL_MS = 400L;
    /** 拿不到控件树时（驱动不支持/会话异常）的兜底等待 */
    private static final long SETTLE_FALLBACK_MS = 1200L;

    /** 取消检查的最小粒度：长等待按此切片，保证「停止」最多 500ms 内生效 */
    private static final long CANCEL_CHECK_SLICE_MS = 500L;

    /** 可重试关键字失败后的默认重试次数；步骤可用 retryTimes 覆盖，0 为关闭 */
    private static final int DEFAULT_RETRY_TIMES = 1;
    /** 重试前的等待：失败往往是页面还没稳，立刻重试多半还是失败 */
    private static final long DEFAULT_RETRY_DELAY_MS = 800L;

    private final UiFlowService uiFlowService;
    private final UiElementService uiElementService;
    private final UiAppService uiAppService;
    private final PlatformRouter platformRouter;
    private final AppiumServerManager appiumServerManager;
    private final AppPackageInstallService appPackageInstallService;
    private final ExecutionCancelRegistry cancelRegistry;
    private final KeywordRegistry keywordRegistry;
    private final EnvVariableService envVariableService;
    private final PgyerAppService pgyerAppService;
    private final UiElementHealthService elementHealthService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    @Value("${app.screenshot-dir:${user.home}/.mobile-standalone/screenshots}")
    private String screenshotDir;

    /**
     * 当前线程的进度回写。试跑弹窗每 2 秒拉一次 execution-log，
     * 日志若只在整条流程结束时入库，界面会一直停在「第 0 步」。
     */
    private static final ThreadLocal<Consumer<List<ExecutionLogEntryDto>>> PROGRESS = new ThreadLocal<>();

    /** 一条流程执行期间不变的环境（包级可见：重试等分支需要在单测里直接驱动） */
    record FlowEnv(
            PlatformDriver driver,
            Device device,
            String deviceId,
            String platform,
            String uiAppId,
            Map<String, String> execParams,
            List<ExecutionLogEntryDto> logs,
            Task task,
            KeywordContext.RunState state,
            boolean performanceMode
    ) {
    }

    public FunctionalTestExecutionResult execute(Task task, Device device) throws Exception {
        return execute(task, device, null);
    }

    public FunctionalTestExecutionResult execute(Task task, Device device,
                                                 Consumer<List<ExecutionLogEntryDto>> progress) throws Exception {
        if (progress != null) {
            PROGRESS.set(progress);
        }
        try {
            return executeInner(task, device);
        } finally {
            PROGRESS.remove();
        }
    }

    private FunctionalTestExecutionResult executeInner(Task task, Device device) throws Exception {
        List<ExecutionLogEntryDto> logs = new ArrayList<>();
        Map<String, String> vars = new HashMap<>();

        JsonNode config = parseConfig(task.getConfig());
        String flowId = text(config, "flowId");
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalArgumentException("UI_FLOW_TEST 任务 config 须含 flowId");
        }
        // 环境变量垫底，任务 config 里显式传的覆盖它。
        //
        // 这是「用例两边一样、数据两边不一样」的解法：流程里写 ${phone}，
        // 跑测试包时取测试环境那份手机号，跑线上包时取线上那份，流程本身一个字不用改。
        // 顺序不能反——单次执行想临时换个账号时，传进来的必须能压过配置里的
        // 环境优先级：任务显式指定 > 设备自己绑的 > 默认。
        //
        // 四台机器同时跑（两台线上、两台测试）时，靠设备上绑的环境自动区分，
        // 不用每次执行都去勾一遍——需要人每次重复配的东西迟早会配错一次，
        // 而配错的现象是「登录失败」，指向的方向完全不对
        String rawEnv = text(config, "environment");
        if ((rawEnv == null || rawEnv.isBlank()) && device != null) {
            rawEnv = device.getEnvironment();
        }
        String envKey = TestEnvironment.normalize(rawEnv);
        Map<String, String> envVars = new HashMap<>();
        try {
            envVars.putAll(envVariableService.resolveValues(envKey));
        } catch (Exception e) {
            // 环境变量拿不到不该挡住执行：没引用变量的流程照样能跑，
            // 引用了的会在替换时留下 ${xxx} 原样，日志里看得见
            log.warn("[ui-flow] 读环境变量失败 env={}: {}", envKey, e.getMessage());
        }
        Map<String, String> params = new HashMap<>(envVars);
        params.putAll(readParams(config));
        vars.putAll(params);
        vars.put("environment", envKey);

        UiFlow flow = uiFlowService.get(flowId);
        if (flow == null) {
            throw new IllegalStateException("流程不存在: " + flowId);
        }
        if (Boolean.FALSE.equals(flow.getEnabled())) {
            throw new IllegalStateException("流程已禁用: " + flow.getName());
        }

        String uiAppId = text(config, "uiAppId");
        if (uiAppId == null || uiAppId.isBlank()) {
            uiAppId = flow.getAppId();
        }
        String configPackage = text(config, "packageName");
        if (configPackage != null && !configPackage.isBlank()) {
            params.putIfAbsent("packageName", configPackage.trim());
        }
        String configActivity = text(config, "activity");
        if (configActivity != null && !configActivity.isBlank()) {
            params.putIfAbsent("activity", configActivity.trim());
        }
        if ((uiAppId == null || uiAppId.isBlank())
                && (params.get("packageName") == null || params.get("packageName").isBlank())) {
            throw new IllegalArgumentException("无法确定应用：请配置 uiAppId 或 packageName");
        }
        if (uiAppId != null && !uiAppId.isBlank()) {
            vars.put("uiAppId", uiAppId);
        }
        if (params.get("packageName") != null) {
            vars.put("packageName", params.get("packageName"));
        }
        vars.put("flowId", flowId);
        vars.put("flowName", flow.getName() != null ? flow.getName() : flowId);

        String platform = task.getPlatform() != null ? task.getPlatform().name() : device.getPlatform().name();
        PlatformDriver driver = platformRouter.getDriver(device);

        // 性能任务：同一流程只负责驱动路径 + 少干扰；功能断言/逐步截图留给 UI_FLOW_TEST
        boolean performanceMode = task.getTaskType() == Task.TaskType.SCENARIO_PERFORMANCE_TEST;
        logs.add(ExecutionLogEntryDto.success("开始执行流程", flow.getName() + " (" + flowId + ")"));
        logs.add(ExecutionLogEntryDto.success("执行模式",
                performanceMode
                        ? "性能采样模式：跳过逐步截图；断言失败不中断（与功能测试互斥，勿并行同设备）"
                        : "功能验证模式：完整断言与步骤截图"));
        publishProgress(logs);

        KeywordContext.RunState state = new KeywordContext.RunState(false, null);
        FlowEnv env = new FlowEnv(driver, device, device.getId(), platform, uiAppId,
                params, logs, task, state, performanceMode);

        try {
            runSteps(parseSteps(flow.getSteps()), env, 0, "");
        } catch (UiFlowExecutionException e) {
            throw e;
        } catch (Exception e) {
            logs.add(ExecutionLogEntryDto.fail("流程中断", e.getMessage(), null));
            publishProgress(logs);
            throw new UiFlowExecutionException(e.getMessage(), logs);
        } finally {
            // 「不保留活动」这类设备级开关必须还原，而且不能只靠流程末尾那一步——
            // 流程中途挂了就走不到那一步，开关会一直留在这台手机上。
            // 后果很隐蔽：之后这台机跑什么都带着它，冒出一堆跟本次改动无关的失败，
            // 没人会想到是几天前某条流程留下的
            restoreDeviceToggles(device, logs);
            // 元素健康记账。放在 finally 里是因为失败的那次执行信息量最大——
            // 恰恰是挂掉的流程才知道哪个元素定位不到了
            try {
                elementHealthService.flush(state.elementUsages);
            } catch (Exception e) {
                log.warn("[ui-flow] 元素健康记账失败（不影响本次执行结果）: {}", e.getMessage());
            }
        }

        FunctionalTestExecutionResult result =
                new FunctionalTestExecutionResult(lastScreenshotPath(logs), logs, vars);
        if (state.earlyReturn) {
            String message = state.returnMessage != null ? state.returnMessage : "";
            FlowOutcome outcome = state.returnOutcome != null ? state.returnOutcome : FlowOutcome.FAIL;
            // 提前返回不是「跑完了」：把判定带出去，由上游决定任务算成功、失败还是跳过
            logs.add(ExecutionLogEntryDto.success("流程返回",
                    message + "（未跑完剩余步骤，本次" + outcome.getDisplayName() + "）"));
            result.setEarlyReturnOutcome(outcome.name());
            result.setEarlyReturnMessage(message);
        } else {
            logs.add(ExecutionLogEntryDto.success("流程结束", "全部步骤执行完成"));
        }
        return result;
    }

    /**
     * 步骤循环：解析关键字 → 校验参数 → 前置截图 → 执行 → 后置截图。
     * 关键字自身逻辑一律不在这里，加关键字不需要动本方法。
     */
    /**
     * 把加压类开关还原成正常状态。
     * <p>
     * 不能只靠流程末尾那一步：流程中途挂了就走不到那里，开关会一直留在这台手机上。
     * 后果很隐蔽——之后这台机跑什么都带着它，冒出一堆跟本次改动无关的失败，
     * 没人会想到是几天前某条流程留下的。
     * <p>
     * 只在确实开着时才动，避免每条流程都白跑一次 adb。
     */
    private void restoreDeviceToggles(Device device, List<ExecutionLogEntryDto> logs) {
        if (device == null || device.getId() == null
                || !Device.isAndroidOrPogAndroid(device.getPlatform())) {
            return;
        }
        try {
            if (DontKeepActivitiesHandler.isOn(device.getId())) {
                DontKeepActivitiesHandler.apply(device.getId(), false);
                logs.add(ExecutionLogEntryDto.success("收尾", "已关闭「不保留活动」，设备恢复正常"));
            }
        } catch (Exception e) {
            log.warn("[ui-flow] 还原设备开关失败: {}", e.getMessage());
        }
    }

    private void runSteps(List<Map<String, Object>> steps, FlowEnv env, int depth, String pathPrefix)
            throws Exception {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            if (env.state().earlyReturn) {
                return;
            }
            ensureNotCancelled(env.task(), env.logs());

            String pathNo = outlineNo(pathPrefix, i + 1);
            String rawCode = firstNonBlank(str(step.get("keywordCode")), str(step.get("action")));
            if (rawCode == null || rawCode.isBlank()) {
                env.logs().add(ExecutionLogEntryDto.fail(sn(pathNo, "未知步骤"), "缺少 keywordCode", null));
                throw new UiFlowExecutionException("步骤缺少 keywordCode", env.logs());
            }
            rawCode = rawCode.trim().toUpperCase(Locale.ROOT);

            KeywordHandler handler = keywordRegistry.find(rawCode);
            if (handler == null) {
                env.logs().add(ExecutionLogEntryDto.fail(sn(pathNo, "不支持的关键字"), rawCode, null));
                throw new UiFlowExecutionException("不支持的关键字: " + rawCode, env.logs());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> stepParams = step.get("params") instanceof Map
                    ? (Map<String, Object>) step.get("params")
                    : Map.of();

            KeywordContext ctx = KeywordContext.builder()
                    .rawCode(rawCode)
                    .elementKey(firstNonBlank(str(step.get("elementKey")), str(step.get("element"))))
                    .stepParams(stepParams)
                    .driver(env.driver())
                    .device(env.device())
                    .deviceId(env.deviceId())
                    .platform(env.platform())
                    .uiAppId(env.uiAppId())
                    .execParams(env.execParams())
                    .logs(env.logs())
                    .task(env.task())
                    .depth(depth)
                    .pathNo(pathNo)
                    .performanceMode(env.performanceMode())
                    .state(env.state())
                    .runtime(this)
                    .build();

            KeywordParamValidator.validate(handler, ctx);

            // 性能模式少干扰采样，跳过逐步截图
            ScreenshotPolicy policy = env.performanceMode() ? ScreenshotPolicy.NONE : handler.screenshotPolicy();
            String numberedLabel = sn(pathNo, handler.shortLabel());

            if (policy.capturesBefore()) {
                captureScreenshot(env.driver(), env.deviceId(), env.task(), env.logs(), "开始 · " + numberedLabel);
                publishProgress(env.logs());
            }
            String usedElement = ctx.getElementKey();
            try {
                executeWithRetry(handler, ctx, env, stepParams);
                recordElementUsage(env, usedElement, true, null);
            } catch (Exception e) {
                // 断言类关键字失败不算元素的账：元素多半是找到了，只是内容不符。
                // 把它记成「元素失效」会让健康报表里全是冤案，而报表一旦不可信就没人看了
                if (!"ASSERT".equalsIgnoreCase(handler.category())) {
                    recordElementUsage(env, usedElement, false, numberedLabel + " · " + shortMessage(e));
                }
                publishProgress(env.logs());
                throw e;
            }
            // 已请求停止：不再为这一步补拍收尾图。留一张「动作做了一半」的证据只会误导复核，
            // 而且等页面稳定还要多花几秒，白白拖长停止的响应时间
            boolean cancelledDuringStep = env.task() != null && env.task().getId() != null
                    && cancelRegistry.isCancelled(env.task().getId());
            if (policy.capturesAfter() && !env.state().earlyReturn && !cancelledDuringStep) {
                if (policy.burstsAfter()) {
                    captureSettledScreenshot(env.driver(), env.deviceId(), env.task(), env.logs(),
                            stepParams, numberedLabel);
                } else {
                    captureScreenshot(env.driver(), env.deviceId(), env.task(), env.logs(),
                            "结束 · " + numberedLabel);
                }
            }
            publishProgress(env.logs());
        }
    }

    private static void publishProgress(List<ExecutionLogEntryDto> logs) {
        Consumer<List<ExecutionLogEntryDto>> cb = PROGRESS.get();
        if (cb == null || logs == null) {
            return;
        }
        try {
            cb.accept(logs);
        } catch (Exception e) {
            // 中途落库失败不能打断真机步骤，下一轮轮询再试
        }
    }

    /**
     * 执行一个步骤，可重试的关键字失败后自动再试。
     * <p>
     * UI 自动化的失败有相当比例是偶发的（元素刚渲染完还没可点、点击落空、会话抖动），
     * 重试一次就能过。不重试的话这些会变成「偶现红」，而偶现红最终会让人不再相信红色——
     * 每次先怀疑是自动化抽风，复核成本超过收益，然后整套东西就没人看了。
     * <p>
     * 带嵌套步骤或有副作用的关键字（IF / 竞速等待 / 组件调用 / 安装卸载）不重试，
     * 否则会把分支里已经跑过的步骤再执行一遍。
     */
    void executeWithRetry(KeywordHandler handler, KeywordContext ctx, FlowEnv env,
                          Map<String, Object> stepParams) throws Exception {
        int retries = resolveRetryTimes(handler, stepParams);
        long delayMs = Math.max(0, longParam(stepParams, "retryDelayMs", DEFAULT_RETRY_DELAY_MS));
        int maxAttempts = retries + 1;

        int logMark = env.logs().size();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                handler.execute(ctx);
                if (attempt > 1) {
                    // 这一步最终是过的：把前几次尝试留下的红降级成告警，
                    // 否则一个已经通过的步骤仍然挂着「不通过」，红色就不再只代表真问题了
                    downgradeFailedAttempts(env.logs(), logMark);
                    env.logs().add(ExecutionLogEntryDto.neutral(
                            ctx.sn("重试后通过"),
                            handler.shortLabel() + " 第 " + attempt + "/" + maxAttempts
                                    + " 次尝试才通过——本步不稳定，建议检查元素定位或补等待"));
                }
                return;
            } catch (UiFlowCancelledException e) {
                throw e;
            } catch (UiFlowExecutionException e) {
                if (attempt >= maxAttempts) {
                    dumpPageSourceOnFailure(env, ctx, handler);
                    throw e;
                }
                env.logs().add(ExecutionLogEntryDto.neutral(
                        ctx.sn("准备重试"),
                        handler.shortLabel() + " 第 " + attempt + "/" + maxAttempts + " 次失败："
                                + e.getMessage() + "，" + delayMs + "ms 后重试"));
                if (delayMs > 0) {
                    sleepCancellable(delayMs, env.task(), env.logs());
                }
            }
        }
    }

    /** 把 fromIndex 之后的失败日志降级为中立态：这一步最终通过了，那些失败只是过程 */
    private static void downgradeFailedAttempts(List<ExecutionLogEntryDto> logs, int fromIndex) {
        for (int i = fromIndex; i < logs.size(); i++) {
            ExecutionLogEntryDto entry = logs.get(i);
            if (entry != null && "fail".equalsIgnoreCase(entry.getStatus())) {
                entry.setStatus("neutral");
                entry.setLevel("Warn");
                entry.setMessage("[重试后已通过] " + (entry.getMessage() != null ? entry.getMessage() : ""));
            }
        }
    }

    private static int resolveRetryTimes(KeywordHandler handler, Map<String, Object> stepParams) {
        if (isAssertStateFixByClick(handler, stepParams)) {
            return 0;
        }
        Object raw = stepParams == null ? null : stepParams.get("retryTimes");
        if (raw != null) {
            // 步骤显式配了就听它的，可以给不可重试的关键字也开（用户自己知道在做什么）
            return (int) Math.min(5, Math.max(0, KeywordContext.asLong(raw, 0)));
        }
        return handler.retryable() ? DEFAULT_RETRY_TIMES : 0;
    }

    /**
     * 「断言元素状态 + 不符时点一下」每重试一次就会再点一次勾选框，必须禁止重试。
     */
    private static boolean isAssertStateFixByClick(KeywordHandler handler, Map<String, Object> stepParams) {
        if (handler == null || stepParams == null
                || !"ASSERT_ELEMENT_STATE".equalsIgnoreCase(handler.code())) {
            return false;
        }
        Object fix = stepParams.get("fixByClick");
        if (fix instanceof Boolean b) {
            return b;
        }
        return fix != null && Boolean.parseBoolean(String.valueOf(fix));
    }

    /**
     * 失败瞬间保存控件树。
     * <p>
     * 只有截图的话，很多失败分不清是「元素压根没渲染」还是「渲染了但被弹窗盖住/在屏幕外」——
     * 这两种的修法完全不同。控件树能直接回答，而事后再去复现往往已经没有现场了。
     */
    private void dumpPageSourceOnFailure(FlowEnv env, KeywordContext ctx, KeywordHandler handler) {
        // 失败会从最内层的步骤逐层抛到顶层，每层都存一份就会留下好几个一模一样的控件树
        if (env.state().failureDumped) {
            return;
        }
        env.state().failureDumped = true;
        try {
            String source = env.driver().getPageSource(env.deviceId());
            if (source == null || source.isBlank()) {
                return;
            }
            Path dir = Path.of(screenshotDir, env.task().getId() != null ? env.task().getId() : "flow");
            Files.createDirectories(dir);
            Path file = dir.resolve("pagesource-" + System.currentTimeMillis() + ".xml");
            Files.writeString(file, source);
            env.logs().add(ExecutionLogEntryDto.success(
                    ctx.sn("失败现场"),
                    "已保存失败时的控件树，可据此判断元素是没渲染还是被遮挡：" + file));
        } catch (Exception e) {
            log.warn("保存失败现场控件树失败: {}", e.getMessage());
        }
    }

    // ==================== FlowRuntime 实现 ====================

    @Override
    public void runNestedSteps(List<Map<String, Object>> steps, KeywordContext ctx, int depth) throws Exception {
        FlowEnv env = new FlowEnv(ctx.getDriver(), ctx.getDevice(), ctx.getDeviceId(), ctx.getPlatform(),
                ctx.getUiAppId(), ctx.getExecParams(), ctx.getLogs(), ctx.getTask(), ctx.getState(),
                ctx.isPerformanceMode());
        runSteps(steps, env, depth, ctx.getPathNo());
    }

    @Override
    public AppiumDriverAdapter appiumDriverAdapter() {
        return appiumDriverAdapter;
    }

    @Override
    public AppiumServerManager appiumServerManager() {
        return appiumServerManager;
    }

    @Override
    public AppPackageInstallService appPackageInstallService() {
        return appPackageInstallService;
    }

    @Override
    public PgyerAppService pgyerAppService() {
        return pgyerAppService;
    }

    /**
     * 用户点了「停止」就在此中断，交由上游把任务标为 STOPPED。
     * 每个步骤前调用；滑动、滚动查找等自身耗时较长的关键字在内部循环里也要调。
     */
    @Override
    public void ensureNotCancelled(Task task, List<ExecutionLogEntryDto> logs) throws UiFlowCancelledException {
        if (task == null || task.getId() == null || !cancelRegistry.isCancelled(task.getId())) {
            return;
        }
        // 人为中断不是用例失败：记中立态，报告里归告警，不拉低达标率
        logs.add(ExecutionLogEntryDto.neutral("已停止", "用户请求停止，流程在当前步骤前中断"));
        throw new UiFlowCancelledException("流程已被用户停止", logs);
    }

    /** 可被「停止」打断的等待：按 500ms 切片，避免一条长延迟把停止拖到几十秒后才生效 */
    @Override
    public void sleepCancellable(long totalMs, Task task, List<ExecutionLogEntryDto> logs)
            throws InterruptedException, UiFlowCancelledException {
        long remaining = Math.max(0, totalMs);
        while (remaining > 0) {
            ensureNotCancelled(task, logs);
            long slice = Math.min(CANCEL_CHECK_SLICE_MS, remaining);
            Thread.sleep(slice);
            remaining -= slice;
        }
        ensureNotCancelled(task, logs);
    }

    /** 主定位 + 备用定位（locator_fallbacks）按顺序尝试 */
    @Override
    public List<String> requireSelectors(String platform, String uiAppId, String elementKey,
                                         Map<String, String> execParams,
                                         List<ExecutionLogEntryDto> logs, String stepName)
            throws UiFlowExecutionException {
        return resolveSelectors(platform, uiAppId, elementKey, execParams, logs, stepName, true);
    }

    @Override
    public List<String> requireSelectorTemplates(String platform, String uiAppId, String elementKey,
                                                 Map<String, String> execParams,
                                                 List<ExecutionLogEntryDto> logs, String stepName)
            throws UiFlowExecutionException {
        return resolveSelectors(platform, uiAppId, elementKey, execParams, logs, stepName, false);
    }

    private List<String> resolveSelectors(String platform, String uiAppId, String elementKey,
                                          Map<String, String> execParams,
                                          List<ExecutionLogEntryDto> logs, String stepName,
                                          boolean expandRandom)
            throws UiFlowExecutionException {
        if (elementKey == null || elementKey.isBlank()) {
            logs.add(ExecutionLogEntryDto.fail(stepName, "缺少 elementKey", null));
            throw new UiFlowExecutionException(stepName + " 缺少 elementKey", logs);
        }
        if (uiAppId == null || uiAppId.isBlank()) {
            String msg = "未选择应用字典，无法解析元素「" + elementKey + "」（启动可用自定义包名；点击/输入等步骤需选择应用字典）";
            logs.add(ExecutionLogEntryDto.fail(stepName, msg, elementKey, elementKey));
            throw new UiFlowExecutionException(msg, logs);
        }
        UiElement el = findElementAcrossLegacyPlatforms(uiAppId, platform, elementKey.trim());
        if (el == null) {
            String msg = "无 ACTIVE 元素: appId=" + uiAppId + ", platform=" + platform + ", nameEn=" + elementKey
                    + "（请在元素管理中「选用」）";
            logs.add(ExecutionLogEntryDto.fail(stepName, msg, elementKey, elementKey));
            throw new UiFlowExecutionException(msg, logs);
        }
        List<String> selectors = new ArrayList<>();
        String primary = toDriverSelector(platform, el.getLocatorType(), el.getLocatorValue());
        if (primary != null && !primary.isBlank()) {
            selectors.add(primary);
        }
        for (String fb : parseFallbackSelectors(platform, el.getLocatorFallbacks())) {
            if (!selectors.contains(fb)) {
                selectors.add(fb);
            }
        }
        if (selectors.isEmpty()) {
            String msg = "元素无有效定位: " + elementKey;
            logs.add(ExecutionLogEntryDto.fail(stepName, msg, elementKey, elementKey));
            throw new UiFlowExecutionException(msg, logs);
        }
        // ${变量} → 按执行参数替换。把会变的内容（相对时间、当期赛事名）从定位里挪出去，
        // 同一条定位才能在测试环境和生产环境通用
        List<String> resolved = new ArrayList<>(selectors.size());
        for (String sel : selectors) {
            try {
                resolved.add(LocatorTemplate.expandVariables(sel, execParams));
            } catch (LocatorTemplate.MissingVariableException e) {
                String msg = "元素「" + elementKey + "」的定位需要填写变量: "
                        + String.join("、", e.getNames()) + "（在执行流程时填入）";
                logs.add(ExecutionLogEntryDto.fail(stepName, msg, sel, elementKey));
                throw new UiFlowExecutionException(msg, logs);
            }
        }

        if (!expandRandom) {
            return resolved;
        }
        // xpath 下标 [X] / [X:1-5] → 本次执行随机成具体数字（列表里随机点一场比赛等）
        List<String> expanded = new ArrayList<>(resolved.size());
        for (String sel : resolved) {
            String next = LocatorTemplate.expandRandomIndexes(sel);
            if (!next.equals(sel)) {
                log.info("定位随机下标 [{}]: {} → {}", elementKey, sel, next);
            }
            expanded.add(next);
        }
        return expanded;
    }

    /**
     * 记一笔「这个元素这次用没用成」。只攒在内存里，执行结束才落库。
     * <p>
     * 记的是 (appId, platform, nameEn) 而不是元素 id：这样不需要在执行链路上多查一次库，
     * 落库时按这三个字段直接 UPDATE 就行。
     */
    private void recordElementUsage(FlowEnv env, String elementKey, boolean ok, String note) {
        if (elementKey == null || elementKey.isBlank() || env.uiAppId() == null) {
            return;
        }
        try {
            env.state().elementUsages.add(new UiElementHealthService.ElementUsage(
                    env.uiAppId(), PlatformNames.normalize(env.platform()), elementKey.trim(), ok, note));
        } catch (Exception e) {
            log.debug("[ui-flow] 记元素使用失败: {}", e.getMessage());
        }
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return m.length() <= 200 ? m : m.substring(0, 200);
    }

    /**
     * 按归一后的平台查元素，查不到再按历史别名找一次。
     * <p>
     * 存量元素还存在 POG_ANDROID / POG_IOS 下，代码改成按 ANDROID / IOS 查之后就找不到了。
     * 有了这层回退，数据迁移前后都能正常执行，不必卡着停机窗口做迁移。
     */
    private UiElement findElementAcrossLegacyPlatforms(String uiAppId, String platform, String elementKey) {
        String normalized = PlatformNames.normalize(platform);
        UiElement el = uiElementService.findActiveByKey(uiAppId, normalized, elementKey);
        if (el != null) {
            return el;
        }
        for (String legacy : PlatformNames.legacyAliasesOf(normalized)) {
            el = uiElementService.findActiveByKey(uiAppId, legacy, elementKey);
            if (el != null) {
                log.info("元素仍存在历史平台名下，建议执行 migration_drop_pog_platform.sql 迁移: "
                        + "appId={}, nameEn={}, platform={} → {}", uiAppId, elementKey, legacy, normalized);
                return el;
            }
        }
        return null;
    }

    @Override
    public void captureScreenshot(PlatformDriver driver, String deviceId, Task task,
                                  List<ExecutionLogEntryDto> logs, String label) {
        try {
            Path dir = Path.of(screenshotDir, task.getId() != null ? task.getId() : "flow");
            Files.createDirectories(dir);
            String file = "step-" + System.currentTimeMillis() + ".png";
            String saved = driver.takeScreenshot(deviceId, dir.resolve(file).toString());
            // step 用可读标签，避免概览里一堆都叫「截图」分不清
            String stepName = (label != null && !label.isBlank()) ? label : "截图";
            ExecutionLogEntryDto entry = ExecutionLogEntryDto.success(stepName, saved != null ? saved : file);
            entry.setScreenshotPath(saved != null ? saved : dir.resolve(file).toString());
            logs.add(entry);
        } catch (Exception e) {
            log.warn("流程截图失败: {}", e.getMessage());
            logs.add(ExecutionLogEntryDto.fail("截图", "截图失败: " + e.getMessage(), null));
        }
    }

    /**
     * 等页面稳定后拍一张最终态。
     * <p>
     * 原先是「动作后每秒 1 张、连拍 3 张」——固定时间窗，与页面实际快慢无关：
     * 页面 4 秒才加载完就 3 张全是 loading，0.3 秒就好了则 3 张一模一样还白等 3 秒。
     * 人眼扫一遍能跳过噪声图，交给 AI 做结果比对时这些噪声就是误判来源。
     * <p>
     * 改成以控件树是否还在变化来判断稳定：快页面立刻拍完就走（省掉大部分等待），
     * 慢页面等到真稳了再拍。显式配了 screenshotRounds 的步骤仍走连拍，不影响存量流程。
     */
    @Override
    public void captureSettledScreenshot(PlatformDriver driver, String deviceId, Task task,
                                         List<ExecutionLogEntryDto> logs, Map<String, Object> stepParams,
                                         String stepLabel) {
        if (explicitRounds(stepParams) != null) {
            captureBurst(driver, deviceId, task, logs, stepParams, stepLabel);
            return;
        }
        String label = stepLabel != null && !stepLabel.isBlank() ? stepLabel : "步骤";
        SettleResult settle = awaitSettled(driver, deviceId, task, stepParams);

        int before = logs.size();
        captureScreenshot(driver, deviceId, task, logs, label + " 结果");
        for (int i = before; i < logs.size(); i++) {
            ExecutionLogEntryDto entry = logs.get(i);
            if (entry.getScreenshotPath() != null) {
                entry.setScreenshotSettled(settle.settled());
                entry.setMessage(entry.getMessage() + settle.describe());
            }
        }
    }

    /** 轮询控件树，连续两次一致即认为页面稳定 */
    private SettleResult awaitSettled(PlatformDriver driver, String deviceId, Task task,
                                      Map<String, Object> stepParams) {
        long timeoutMs = longParam(stepParams, "settleTimeoutMs", SETTLE_TIMEOUT_MS);
        long pollMs = Math.max(100L, longParam(stepParams, "settlePollMs", SETTLE_POLL_MS));
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + Math.max(pollMs, timeoutMs);

        String lastHash = null;
        while (System.currentTimeMillis() < deadline) {
            if (task != null && task.getId() != null && cancelRegistry.isCancelled(task.getId())) {
                return new SettleResult(false, System.currentTimeMillis() - startedAt, "已请求停止");
            }
            String hash = pageSourceHash(driver, deviceId);
            if (hash == null) {
                // 驱动不支持控件树：退化成一次短等待，至少避开动作刚发出的瞬间
                sleepQuietly(SETTLE_FALLBACK_MS);
                String why = lastPageSourceError.get();
                return new SettleResult(false, System.currentTimeMillis() - startedAt,
                        "无控件树，按固定等待" + (why == null ? "" : "；原因：" + why));
            }
            if (hash.equals(lastHash)) {
                return new SettleResult(true, System.currentTimeMillis() - startedAt, null);
            }
            lastHash = hash;
            sleepQuietly(pollMs);
        }
        return new SettleResult(false, System.currentTimeMillis() - startedAt, "超时仍在变化");
    }

    /** 最近一次控件树失败的原因。debug 级别等于没有，出事时人只看到「无控件树」四个字 */
    private final java.util.concurrent.atomic.AtomicReference<String> lastPageSourceError =
            new java.util.concurrent.atomic.AtomicReference<>();

    private String pageSourceHash(PlatformDriver driver, String deviceId) {
        long startedAt = System.currentTimeMillis();
        try {
            String src = driver.getPageSource(deviceId);
            if (src == null || src.isBlank()) {
                // 适配器内部已经把真实原因记下了（异常被它 catch 掉，上层只看得到 null）
                String fromAdapter = appiumDriverAdapter == null
                        ? null : appiumDriverAdapter.lastPageSourceFailure();
                lastPageSourceError.set(fromAdapter != null ? fromAdapter
                        : "驱动返回空控件树（耗时 " + (System.currentTimeMillis() - startedAt) + "ms）");
                return null;
            }
            lastPageSourceError.set(null);
            return Integer.toHexString(src.hashCode()) + ":" + src.length();
        } catch (Exception e) {
            // 原来是 log.debug，等于把原因丢掉：实测遇到过一次 getPageSource 卡了 244 秒才失败，
            // 而人只看到「无控件树，按固定等待」，完全查不下去
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + firstLine(e.getMessage()))
                    + "（耗时 " + (System.currentTimeMillis() - startedAt) + "ms）";
            lastPageSourceError.set(reason);
            log.warn("获取控件树失败，跳过稳定判定: {}", reason);
            return null;
        }
    }

    /** Appium 的异常动辄几十行堆栈，日志里只留第一行 */
    private static String firstLine(String msg) {
        int nl = msg.indexOf('\n');
        String s = nl > 0 ? msg.substring(0, nl) : msg;
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Long explicitRounds(Map<String, Object> stepParams) {
        if (stepParams == null) return null;
        Object r = stepParams.get("screenshotRounds");
        if (r == null) return null;
        long parsed = KeywordContext.asLong(r, -1);
        return parsed > 0 ? parsed : null;
    }

    private static long longParam(Map<String, Object> stepParams, String key, long defaultVal) {
        if (stepParams == null) return defaultVal;
        Object raw = stepParams.get(key);
        return raw == null ? defaultVal : KeywordContext.asLong(raw, defaultVal);
    }

    /** 页面是否已稳定 + 用时 + 未稳定的原因 */
    private record SettleResult(boolean settled, long elapsedMs, String reason) {
        String describe() {
            return settled
                    ? "（页面 " + elapsedMs + "ms 后稳定）"
                    : "（" + (reason != null ? reason : "未稳定") + "，已等 " + elapsedMs + "ms）";
        }
    }

    /** 显式配置 screenshotRounds 时的连拍：适合确实要看动画过程的步骤 */
    private void captureBurst(PlatformDriver driver, String deviceId, Task task,
                              List<ExecutionLogEntryDto> logs, Map<String, Object> stepParams,
                              String stepLabel) {
        int rounds = STEP_SCREENSHOT_ROUNDS;
        long intervalMs = STEP_SCREENSHOT_INTERVAL_MS;
        if (stepParams != null) {
            Object r = stepParams.get("screenshotRounds");
            if (r instanceof Number n && n.intValue() > 0) {
                rounds = Math.min(20, n.intValue());
            } else if (r != null) {
                try {
                    int parsed = Integer.parseInt(String.valueOf(r).trim());
                    if (parsed > 0) rounds = Math.min(20, parsed);
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
            Object iv = stepParams.get("screenshotIntervalMs");
            if (iv instanceof Number n && n.longValue() >= 200) {
                intervalMs = Math.min(10_000L, n.longValue());
            } else if (iv != null) {
                try {
                    long parsed = Long.parseLong(String.valueOf(iv).trim());
                    if (parsed >= 200) intervalMs = Math.min(10_000L, parsed);
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
        }
        String title = (stepLabel != null && !stepLabel.isBlank() ? stepLabel : "步骤") + " 连续截图";
        logs.add(ExecutionLogEntryDto.success(title,
                "动作后连拍: 共 " + rounds + " 张，间隔 " + intervalMs + "ms（约 "
                        + (rounds * intervalMs / 1000.0) + " 秒窗口）"));
        for (int i = 1; i <= rounds; i++) {
            // 已请求停止时不再补拍证据图，让流程尽快收尾（真正的中断交给下一次 ensureNotCancelled）
            if (task != null && task.getId() != null && cancelRegistry.isCancelled(task.getId())) {
                logs.add(ExecutionLogEntryDto.success(title, "已请求停止，剩余 " + (rounds - i + 1) + " 张连拍跳过"));
                return;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logs.add(ExecutionLogEntryDto.fail(title, "截图等待被中断", null));
                return;
            }
            String prefix = stepLabel != null && !stepLabel.isBlank() ? stepLabel : "步骤";
            captureScreenshot(driver, deviceId, task, logs, prefix + " 后 " + i + "/" + rounds);
        }
    }

    /**
     * 包名解析优先级：步骤 params.packageName → 执行参数/任务 config.packageName → 应用字典。
     * 不写死任何业务包名。
     */
    @Override
    public String resolvePackage(String uiAppId, String platform, Map<String, Object> stepParams,
                                 Map<String, String> execParams) {
        String fromStep = str(stepParams.get("packageName"));
        if (fromStep != null && !fromStep.isBlank()) {
            return fromStep.trim();
        }
        if (execParams != null) {
            String fromExec = execParams.get("packageName");
            if (fromExec != null && !fromExec.isBlank()) {
                return fromExec.trim();
            }
        }
        if (uiAppId == null || uiAppId.isBlank()) {
            throw new IllegalStateException("未指定包名：请在执行时填写包名，或在启动步骤填写 packageName，或绑定应用字典");
        }
        UiApp app = uiAppService.get(uiAppId);
        if (app == null) {
            throw new IllegalStateException("应用字典不存在: " + uiAppId);
        }
        boolean ios = platform != null && platform.toUpperCase(Locale.ROOT).contains("IOS");
        String pkg = ios ? app.getIosBundleId() : app.getAndroidPackage();
        if (pkg == null || pkg.isBlank()) {
            throw new IllegalStateException("应用字典未配置包名: " + uiAppId + " / " + platform
                    + "，请填写 packageName 或完善应用字典");
        }
        return pkg.trim();
    }

    /**
     * 元素类步骤失败时，回答一个最常被忽略的问题：应用真的在前台吗。
     * <p>
     * 「点击失败」和「定位写错了」在报错上长得一样，但方向完全相反：
     * 应用没打开得补「启动应用」，定位错了得改元素。不把这句话打出来，
     * 人只能盯着模拟器才发现自己在看桌面。
     */
    @Override
    public String foregroundMismatchHint(PlatformDriver driver, String deviceId, String platform,
                                         String uiAppId, Map<String, Object> stepParams,
                                         Map<String, String> execParams) {
        if (!(driver instanceof com.mobile.standalone.service.driver.AndroidDriver android)) {
            return null;
        }
        String expected;
        try {
            expected = resolvePackage(uiAppId, platform, stepParams, execParams);
        } catch (RuntimeException e) {
            return null;
        }
        if (expected == null || expected.isBlank()) return null;
        String actual = android.getForegroundPackage(deviceId);
        if (actual == null || actual.isBlank()) return null;
        if (actual.equals(expected)) return null;
        return "当前前台是 " + actual + describeKnownPackage(actual)
                + "，不是被测应用 " + expected
                + "——应用没在前台，元素当然找不到。"
                + "多半是这条流程缺了「启动应用」这一步（录制时应用已经开着，那一步没被记进来）";
    }

    private static String describeKnownPackage(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("launcher") || p.contains("home")) return "（桌面）";
        if (p.startsWith("com.android.systemui")) return "（系统界面 / 弹窗）";
        if (p.contains("packageinstaller")) return "（安装器 / 权限弹窗）";
        return "";
    }

    @Override
    public boolean directionalSwipe(PlatformDriver driver, String deviceId, String direction, int durationMs) {
        return directionalSwipe(driver, deviceId, direction, durationMs, 0);
    }

    @Override
    public boolean directionalSwipe(PlatformDriver driver, String deviceId, String direction,
                                    int durationMs, int distancePercent) {
        return directionalSwipe(driver, deviceId, direction, durationMs, (double) distancePercent);
    }

    @Override
    public boolean directionalSwipe(PlatformDriver driver, String deviceId, String direction,
                                    int durationMs, double distancePercent) {
        int[] size = appiumDriverAdapter != null ? appiumDriverAdapter.getWindowSize(deviceId) : null;
        int w = size != null && size.length >= 2 ? size[0] : 1080;
        int h = size != null && size.length >= 2 ? size[1] : 1920;
        int[] p = swipeEndpoints(w, h, direction, distancePercent);
        return driver.swipe(deviceId, p[0], p[1], p[2], p[3], Math.max(100, durationMs));
    }

    /** Appium 不可用时的 Android 回退：优先 am start 指定组件，否则 monkey 拉起 launcher */
    @Override
    public boolean adbLaunchAndroid(String deviceId, String pkg, String activity) {
        try {
            if (activity != null && !activity.isBlank()) {
                String act = activity.trim();
                String component = act.contains("/") ? act : pkg + "/" + act;
                Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "am", "start", "-n", component)
                        .redirectErrorStream(true)
                        .start();
                boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                if (finished && p.exitValue() == 0) {
                    log.info("ADB am start 成功: deviceId={}, component={}", deviceId, component);
                    return true;
                }
                log.warn("ADB am start 未成功: component={}, exit={}", component, finished ? p.exitValue() : "timeout");
            }
            Process monkey = new ProcessBuilder(
                    "adb", "-s", deviceId, "shell", "monkey",
                    "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
                    .redirectErrorStream(true)
                    .start();
            boolean ok = monkey.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && monkey.exitValue() == 0;
            log.info("ADB monkey 启动: deviceId={}, pkg={}, ok={}", deviceId, pkg, ok);
            return ok;
        } catch (Exception e) {
            log.warn("ADB 启动失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> parseSteps(String stepsJson) throws Exception {
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        return JSON.readValue(stepsJson, new TypeReference<>() {});
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> asStepList(Object raw) throws Exception {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        if (raw instanceof String s && !s.isBlank()) {
            return parseSteps(s);
        }
        return List.of();
    }

    // ==================== 内部工具 ====================

    /** 取执行日志中最后一张截图路径，供任务 reportPath / 详情页单图回退 */
    private static String lastScreenshotPath(List<ExecutionLogEntryDto> logs) {
        if (logs == null || logs.isEmpty()) {
            return null;
        }
        for (int i = logs.size() - 1; i >= 0; i--) {
            ExecutionLogEntryDto e = logs.get(i);
            if (e != null && e.getScreenshotPath() != null && !e.getScreenshotPath().isBlank()) {
                return e.getScreenshotPath();
            }
        }
        return null;
    }

    private static String outlineNo(String pathPrefix, int index1Based) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return String.valueOf(index1Based);
        }
        return pathPrefix + "." + index1Based;
    }

    private static String sn(String pathNo, String title) {
        if (pathNo == null || pathNo.isBlank()) {
            return title != null ? title : "";
        }
        if (title == null || title.isBlank()) {
            return pathNo;
        }
        return pathNo + " · " + title;
    }

    private List<String> parseFallbackSelectors(String platform, String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode arr = JSON.readTree(json);
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String type = n.path("locatorType").asText(null);
                String value = n.path("locatorValue").asText(null);
                if (value == null || value.isBlank()) continue;
                String sel = toDriverSelector(platform, type, value);
                if (sel != null && !sel.isBlank()) out.add(sel);
            }
            return out;
        } catch (Exception e) {
            log.warn("解析 locatorFallbacks 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 元素库的 locatorType/locatorValue → 驱动认识的 selector 串（默认 Android/iOS 规则） */
    static String toDriverSelector(String locatorType, String locatorValue) {
        return toDriverSelector(null, locatorType, locatorValue);
    }

    /**
     * @param platform 任务/设备平台；鸿蒙走 text:/id:/desc:，避免被转成 Android xpath
     */
    static String toDriverSelector(String platform, String locatorType, String locatorValue) {
        String raw = locatorValue != null ? locatorValue.trim() : "";
        if (raw.isEmpty()) {
            return raw;
        }
        if (raw.startsWith("id:") || raw.startsWith("xpath:") || raw.startsWith("accessibility id:")
                || raw.startsWith("name:") || raw.startsWith("class name:") || raw.startsWith("css selector:")
                || raw.startsWith("ios predicate:") || raw.startsWith("ios class chain:")
                || raw.startsWith("-ios predicate string:") || raw.startsWith("-ios class chain:")
                || raw.startsWith("text:") || raw.startsWith("desc:") || raw.startsWith("description:")
                || raw.startsWith("key:") || raw.startsWith("type:") || raw.startsWith("resource-id:")
                || raw.startsWith("coordinate:") || raw.startsWith("tap:")) {
            return raw;
        }
        boolean harmony = platform != null && platform.toUpperCase(Locale.ROOT).contains("HARMONY");
        String type = locatorType != null ? locatorType.trim().toLowerCase(Locale.ROOT) : "";
        if (type.isEmpty()) {
            // 鸿蒙默认按文案；其它端保持历史行为按 xpath 猜
            type = harmony ? "text" : "xpath";
        }
        if (harmony) {
            return switch (type) {
                case "id", "resource-id", "resourceid" ->
                        raw.startsWith("id:") ? raw : "id:" + raw;
                case "key" ->
                        raw.startsWith("key:") ? raw : "key:" + raw;
                case "accessibilityid", "accessibility-id", "accessibility id", "desc", "description" ->
                        raw.startsWith("desc:") || raw.startsWith("description:") ? raw : "desc:" + raw;
                case "name", "text" -> raw.startsWith("//") || raw.startsWith("(")
                        ? (raw.startsWith("xpath:") ? raw : "xpath:" + raw)
                        : "text:" + raw;
                case "classname", "class", "type" ->
                        raw.startsWith("type:") ? raw : "type:" + raw;
                case "xpath" -> raw.startsWith("xpath:") ? raw : "xpath:" + raw;
                case "coordinate", "coord", "tap", "point" -> {
                    if (raw.startsWith("coordinate:") || raw.startsWith("tap:")) yield raw;
                    yield "coordinate:" + raw;
                }
                default -> raw.contains("/") ? (raw.startsWith("xpath:") ? raw : "xpath:" + raw) : "text:" + raw;
            };
        }
        return switch (type) {
            case "id", "resource-id", "resourceid" -> "id:" + raw;
            case "accessibilityid", "accessibility-id", "accessibility id" -> "accessibility id:" + raw;
            case "iospredicate", "ios-predicate", "ios predicate", "-ios predicate string" ->
                    "ios predicate:" + raw;
            case "iosclasschain", "ios-class-chain", "ios class chain", "-ios class chain" ->
                    "ios class chain:" + raw;
            case "name", "text" -> raw.startsWith("//") || raw.startsWith("(")
                    ? "xpath:" + raw
                    : "xpath://*[@text='" + raw + "' or @name='" + raw + "']";
            case "classname", "class" -> "class name:" + raw;
            case "css", "cssselector", "css-selector", "css selector" ->
                    raw.startsWith("css selector:") ? raw : "css selector:" + raw;
            case "xpath" -> raw.startsWith("xpath:") ? raw : "xpath:" + raw;
            case "coordinate", "coord", "tap", "point" -> {
                if (raw.startsWith("coordinate:") || raw.startsWith("tap:")) yield raw;
                yield "coordinate:" + raw;
            }
            default -> raw.contains("/") ? (raw.startsWith("xpath:") ? raw : "xpath:" + raw) : raw;
        };
    }

    /**
     * 整屏滑动的起止点。{@code distancePercent <= 0} 保持原来的大半屏；
     * 小幅滑动只挪一小截，避免「滑动直到元素」一把把目标划出屏幕。
     */
    public static int[] swipeEndpoints(int w, int h, String direction, double distancePercent) {
        int x1 = w / 2, y1 = h / 2, x2 = w / 2, y2 = h / 2;
        String dir = normalizeSwipeDirection(direction);
        if (distancePercent <= 0) {
            switch (dir) {
                case "up" -> { y1 = (int) (h * 0.72); y2 = (int) (h * 0.28); }
                case "down" -> { y1 = (int) (h * 0.28); y2 = (int) (h * 0.72); }
                case "left" -> { x1 = (int) (w * 0.82); x2 = (int) (w * 0.18); }
                case "right" -> { x1 = (int) (w * 0.18); x2 = (int) (w * 0.82); }
                default -> { y1 = (int) (h * 0.72); y2 = (int) (h * 0.28); }
            }
            return new int[]{x1, y1, x2, y2};
        }
        double pct = Math.min(70, Math.max(1, distancePercent));
        int dy = Math.max(8, (int) Math.round(h * pct / 100.0));
        int dx = Math.max(8, (int) Math.round(w * pct / 100.0));
        // 不对称绕中心：资讯页正中间经常是大卡片/视频，4% 来回等于按在卡片上。
        // 从内容区下半段起手，往上顶一小截，列表才吃得到。
        switch (dir) {
            case "up" -> { y1 = (int) (h * 0.62); y2 = Math.max(8, y1 - dy); }
            case "down" -> { y1 = (int) (h * 0.38); y2 = Math.min(h - 8, y1 + dy); }
            case "left" -> { x1 = (int) (w * 0.62); x2 = Math.max(8, x1 - dx); }
            case "right" -> { x1 = (int) (w * 0.38); x2 = Math.min(w - 8, x1 + dx); }
            default -> { y1 = (int) (h * 0.62); y2 = Math.max(8, y1 - dy); }
        }
        return new int[]{x1, y1, x2, y2};
    }

    /** up/down/left/right；兼容「up（上滑）」等展示文案 */
    static String normalizeSwipeDirection(String direction) {
        if (direction == null || direction.isBlank()) return "up";
        String s = direction.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("up") || s.contains("上滑") || s.contains("向上")) return "up";
        if (s.startsWith("down") || s.contains("下滑") || s.contains("向下")) return "down";
        if (s.startsWith("left") || s.contains("左滑") || s.contains("向左")) return "left";
        if (s.startsWith("right") || s.contains("右滑") || s.contains("向右")) return "right";
        return "up";
    }

    private JsonNode parseConfig(String config) throws Exception {
        if (config == null || config.isBlank()) {
            return JSON.createObjectNode();
        }
        return JSON.readTree(config);
    }

    private Map<String, String> readParams(JsonNode config) {
        Map<String, String> map = new HashMap<>();
        JsonNode p = config.get("params");
        if (p != null && p.isObject()) {
            Iterator<String> names = p.fieldNames();
            while (names.hasNext()) {
                String k = names.next();
                JsonNode v = p.get(k);
                if (v != null && !v.isNull()) {
                    map.put(k, v.asText());
                }
            }
        }
        // 顶层常用字段也并入，方便直接传 phone/code
        for (String k : List.of("phone", "phoneNumber", "code", "verificationCode")) {
            String v = text(config, k);
            if (v != null && !v.isBlank()) {
                map.put(k, v);
                if ("phoneNumber".equals(k)) map.putIfAbsent("phone", v);
                if ("verificationCode".equals(k)) map.putIfAbsent("code", v);
            }
        }
        return map;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        return n.get(field).asText();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
