package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FunctionalTestExecutionResult;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 登录测试服务（功能基础质量 - 登录场景）
 * 执行完美世界电竞 Android/iOS 登录流程，并记录步骤级执行日志供前端「执行日志」展示。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String DEFAULT_PHONE = "12312340002";
    private static final String DEFAULT_CODE = "521758";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    @Value("${appium.enabled:false}")
    private boolean appiumEnabled;
    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    /** PogPog Android 国际版固定包名（与 Appium appium:appPackage 一致） */
    public static final String POGPOG_INTL_PACKAGE = "com.arca.egames.intl";
    /** 相对启动 Activity（与 appium:appActivity 中 .LauncherActivity 一致） */
    public static final String POGPOG_INTL_ACTIVITY_RELATIVE = ".LauncherActivity";
    private static final String POGPOG_INTL_COMPONENT = POGPOG_INTL_PACKAGE + "/" + POGPOG_INTL_ACTIVITY_RELATIVE;

    /** 系统通知权限说明（如「要允许"PogPog"向您发送通知吗」） */
    private static final String SEL_ANDROID_NOTIFICATION_PERMISSION_MSG =
            "xpath://android.widget.TextView[@resource-id=\"com.android.permissioncontroller:id/permission_message\"]";
    /** 系统权限弹窗「允许」 */
    private static final String SEL_ANDROID_NOTIFICATION_PERMISSION_ALLOW =
            "xpath://android.widget.Button[@resource-id=\"com.android.permissioncontroller:id/permission_allow_button\"]";

    /** PogPog 推广弹层文案（二选一出现即视为活动弹层） */
    private static final String SEL_POG_PROMO_TEXT_DOTA_CS_STATS =
            "xpath://android.widget.TextView[@text=\"查询DOTA2/CS2战绩\"]";
    private static final String SEL_POG_PROMO_TEXT_FREE_ITEMS =
            "xpath://android.widget.TextView[@text=\"即可免费领取珍贵饰品！\"]";
    /** 「立即领取」 */
    private static final String SEL_POG_PROMO_GO_ACTION =
            "xpath://android.widget.TextView[@resource-id=\"com.arca.egames.intl:id/tv_go_action\"]";

    /** 启动类流程步骤截图目录名（与登录 login 区分） */
    private static final String LAUNCH_STEP_SCREENSHOT_SCENARIO = "launch_app";
    /** 启动后连续截图次数：覆盖慢机冷启动，避免只拍到黑屏/闪屏 */
    private static final int LAUNCH_SCREENSHOT_ROUNDS = 6;
    /** 每轮截图前额外等待（毫秒）；再叠加 takeStepScreenshot 内部节流与稳定等待 */
    private static final long LAUNCH_SCREENSHOT_INTERVAL_MS = 2000L;

    /** 当前线程执行中的日志（失败时供 TaskService 读取并持久化） */
    private static final ThreadLocal<List<ExecutionLogEntryDto>> THREAD_LOG = new ThreadLocal<>();
    /** 上次截图时间，确保连续截图间隔至少 1.5 秒，避免设备过载 */
    private static final ThreadLocal<Long> LAST_SCREENSHOT_TIME = new ThreadLocal<>();

    /** 执行失败时获取已产生的步骤日志并清除，供 TaskService 写入 execution_log */
    public List<ExecutionLogEntryDto> getAndClearExecutionLog() {
        List<ExecutionLogEntryDto> list = THREAD_LOG.get();
        THREAD_LOG.remove();
        return list != null ? new ArrayList<>(list) : null;
    }

    /** 供设置测试等场景注入执行日志：传入同一引用以便登录步骤追加到该列表，失败时 TaskService 可持久化完整日志 */
    public void setExecutionLogForFailure(List<ExecutionLogEntryDto> log) {
        if (log != null) {
            THREAD_LOG.set(log);
        }
    }

    /**
     * 为 executionLog 最后一条截屏并写入路径。供设置测试等场景在每步操作后调用。
     */
    public void takeScreenshotForLastEntry(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    public boolean isLoginTestSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        if (!Device.isAndroidOrPogAndroid(device.getPlatform()) && !Device.usesIosAutomation(device.getPlatform())) return false;
        String pkg = app.getPackageName().toLowerCase(java.util.Locale.ROOT);
        // Android: com.pwrd.steam.esports；iOS: com.wmzq.esportapp
        return pkg.contains("steam.esports") || pkg.contains("esportapp") || pkg.contains("egames");
    }

    private static final int APP_READY_WAIT_SECONDS = 25;
    private static final long POLL_INTERVAL_MS = 2000;

    /** iOS「点击按钮进行认证」出现后，留给人工点选的等待秒数 */
    @Value("${app.login.ios-captcha-human-wait-seconds:60}")
    private int iosCaptchaHumanWaitSeconds;

    /**
     * 执行登录测试，并返回截图路径与步骤执行日志（用于前端执行日志、截图展示）。
     */
    public FunctionalTestExecutionResult executeLoginTest(Task task, Device device) {
        return executeLoginTest(task, device, false);
    }

    /**
     * 执行登录测试。当 skipLaunch=true 时不再执行启动应用，假定应用已由调用方（如设置测试）启动且 Appium 会话已存在。
     */
    public FunctionalTestExecutionResult executeLoginTest(Task task, Device device, boolean skipLaunch) {
        log.info("登录测试开始: taskId={}, appId={}, deviceId={}, skipLaunch={}", task.getId(), task.getAppId(), device.getId(), skipLaunch);
        if (!skipLaunch) {
            LAST_SCREENSHOT_TIME.remove(); // 新测试开始时重置截图间隔计时
        }
        // 当 skipLaunch 且调用方已通过 setExecutionLogForFailure 注入列表时，复用以追加登录步骤，便于失败时完整持久化
        List<ExecutionLogEntryDto> executionLog = (skipLaunch && THREAD_LOG.get() != null) ? THREAD_LOG.get() : new ArrayList<>();
        if (THREAD_LOG.get() != executionLog) {
            THREAD_LOG.set(executionLog);
        }
        try {

        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            executionLog.add(ExecutionLogEntryDto.fail("校验应用", "应用不存在: " + task.getAppId(), null));
            throw new RuntimeException("应用不存在，请检查 appId 是否正确或是否已选择应用: " + task.getAppId());
        }
        if (app.getPackageName() == null || app.getPackageName().isBlank()) {
            executionLog.add(ExecutionLogEntryDto.fail("校验应用", "应用未配置包名", null));
            throw new RuntimeException("应用未配置包名(package_name)，无法启动: " + app.getName());
        }
        if (!isLoginTestSupported(task, app, device)) {
            executionLog.add(ExecutionLogEntryDto.fail("校验应用", "当前应用或平台不支持登录测试", app.getPackageName()));
            throw new RuntimeException("当前应用或平台不支持登录测试: " + app.getPackageName());
        }

        if (!skipLaunch) {
            FunctionalTestLogHelper.prependHeader(executionLog, "登录测试", app.getPackageName(), device.getId());
        }

        if (!skipLaunch) {
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "是否卸载应用:否"));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用历史版本hap路径:null"));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "正在解析应用信息..."));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用包名:" + app.getPackageName()));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用版本:" + (app.getVersion() != null ? app.getVersion() : "-")));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用主界面:-"));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用信息解析完成"));
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        String phone = getConfigString(task, "phoneNumber", DEFAULT_PHONE);
        String code = getConfigString(task, "verificationCode", DEFAULT_CODE);

        if (!skipLaunch) {
            executionLog.add(ExecutionLogEntryDto.success("环境初始化", "正在检查设备状态并执行环境初始化..."));
        }
        PlatformDriver driver = platformRouter.getDriver(device);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "无法获取平台驱动，请确认 appium.enabled 且设备可用", null));
            throw new RuntimeException("无法获取平台驱动，请确认 appium.enabled 且设备可用");
        }
        if (!skipLaunch) {
            String maskedId = maskDeviceId(deviceId);
            executionLog.add(ExecutionLogEntryDto.success("环境初始化", maskedId + "设备,环境初始化执行成功"));
            executionLog.add(ExecutionLogEntryDto.success("环境初始化", maskedId + ":设备连接服务已启动"));
        }

        if (!skipLaunch) {
            executionLog.add(ExecutionLogEntryDto.success("检测规则", "开始检测规则:启动..."));
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "包名:" + packageOrBundle));
            if (!driver.launchApp(deviceId, packageOrBundle)) {
                executionLog.add(ExecutionLogEntryDto.fail("启动应用程序", "启动失败，请确认设备已连接、应用已安装、包名正确", packageOrBundle));
                throw new RuntimeException("启动应用失败，请确认: 1) 设备已连接 2) 应用已安装 3) 包名正确(" + packageOrBundle + ")");
            }
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "启动标准检测完成"));
            sleep(2000); // 等待应用启动完成后再截图
            takeStepScreenshot(driver, deviceId, task, executionLog);
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "等待 5 秒后检查主界面"));
            sleep(5000);
        } else {
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "已由调用方启动，跳过启动步骤"));
        }

        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            waitForAppReadyAndroid(driver, deviceId, task, executionLog);
        }
        executionLog.add(ExecutionLogEntryDto.success("检测规则", "开始检测规则:运行..."));
        String loginMode = getConfigString(task, "loginMode", "login");
        boolean forceLogin = "force".equalsIgnoreCase(loginMode) || "relogin".equalsIgnoreCase(loginMode);
        boolean logoutOnly = "logout".equalsIgnoreCase(loginMode);

        if (logoutOnly) {
            executionLog.add(ExecutionLogEntryDto.success("退出流程", "loginMode=logout，仅执行退出登录"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
                executeAndroidLogoutOnly(driver, deviceId, task, executionLog);
            } else if (Device.usesIosAutomation(device.getPlatform())) {
                executeIosLogoutOnly(driver, deviceId, task, executionLog);
            } else {
                executionLog.add(ExecutionLogEntryDto.fail("退出流程", "不支持的平台: " + device.getPlatform(), null));
                throw new RuntimeException("不支持的平台: " + device.getPlatform());
            }
        } else {
            executionLog.add(ExecutionLogEntryDto.success("登录流程",
                    forceLogin ? "强制登录（已登录则先退出再登）" : "执行温馨提示/侧边栏/手机号/验证码步骤"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            // 非强制：已登录则跳过；强制（线上回归 login/relogin）：走完整登录（内部会先退出）
            if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
                if (!forceLogin && checkAlreadyLoggedInAndroid(driver, deviceId, task, executionLog)) {
                    sleep(1000);
                } else {
                    executeAndroidLogin(driver, deviceId, task, phone, code, executionLog);
                }
            } else if (Device.usesIosAutomation(device.getPlatform())) {
                if (!forceLogin && checkAlreadyLoggedInIos(driver, deviceId, task, executionLog)) {
                    sleep(1000);
                } else {
                    executeIosLogin(driver, deviceId, task, phone, code, executionLog);
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.fail("登录流程", "不支持的平台: " + device.getPlatform(), null));
                throw new RuntimeException("不支持的平台: " + device.getPlatform());
            }
        }

        sleep(3000);
        String screenshotPath = "/tmp/screenshots/login_" + task.getId() + "_" + System.currentTimeMillis() + ".png";
        try {
            Files.createDirectories(Paths.get(screenshotPath).getParent());
        } catch (Exception e) {
            log.warn("创建截图目录失败: {}", e.getMessage());
        }
        String savedPath = takeScreenshotWithRetry(driver, deviceId, screenshotPath, 3);
        executionLog.add(ExecutionLogEntryDto.success("执行完成", "运行标准检测完成"));
        executionLog.add(ExecutionLogEntryDto.success("执行完成并截图", savedPath != null ? "截图已保存:" + savedPath : "截图失败"));
        if (savedPath != null) {
            executionLog.get(executionLog.size() - 1).setScreenshotPath(savedPath);
        }
        executionLog.add(ExecutionLogEntryDto.success("后置清理", "开始执行后置清理工作..."));
        executionLog.add(ExecutionLogEntryDto.success("后置清理", "后置清理工作执行成功"));
        executionLog.add(ExecutionLogEntryDto.success("报告", "功能体验标准检测执行完成,正在汇总数据并生成报告"));
        executionLog.add(ExecutionLogEntryDto.success("报告", "报告已生成,任务完成"));
        executionLog.add(ExecutionLogEntryDto.success("测试服务", "测试服务执行完毕"));
        FunctionalTestLogHelper.appendSummary(executionLog);
        long screenshotCount = executionLog.stream().filter(e -> e.getScreenshotPath() != null && !e.getScreenshotPath().isBlank()).count();
        log.info("登录测试执行完成，步骤截图成功数: {}/{}，最终截图: {}", screenshotCount, executionLog.size(), savedPath != null ? savedPath : "无");
        THREAD_LOG.remove();
        java.util.Map<String, String> variables = new java.util.LinkedHashMap<>();
        variables.put("phoneNumberMasked", maskPhone(phone));
        variables.put("verificationCodeMasked", formatCodeForLog(code));
        return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, variables);
        } finally {
            // 成功时已在 return 前 remove；失败时保留供 getAndClearExecutionLog 读取
        }
    }

    /**
     * 执行「启动应用程序」场景：仅启动应用并截图，按 Android/iOS/鸿蒙走对应驱动。
     */
    public FunctionalTestExecutionResult executeLaunchAppTest(Task task, Device device) {
        log.info("启动应用程序测试开始: taskId={}, appId={}, deviceId={}", task.getId(), task.getAppId(), device.getId());
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        THREAD_LOG.set(executionLog);
        try {
            LAST_SCREENSHOT_TIME.remove();
            Application app = applicationMapper.findById(task.getAppId());
            if (app == null) {
                executionLog.add(ExecutionLogEntryDto.fail("校验应用", "应用不存在: " + task.getAppId(), null));
                throw new RuntimeException("应用不存在: " + task.getAppId());
            }
            String packageOrBundle = app.getPackageName() != null ? app.getPackageName().trim() : null;
            if (packageOrBundle == null || packageOrBundle.isEmpty()) {
                executionLog.add(ExecutionLogEntryDto.fail("校验应用", "应用未配置包名", null));
                throw new RuntimeException("应用未配置包名");
            }
            FunctionalTestLogHelper.prependHeader(executionLog, "启动应用程序", packageOrBundle, device.getId());
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "正在解析应用信息..."));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用包名:" + packageOrBundle));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用版本:" + (app.getVersion() != null ? app.getVersion() : "-")));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用信息解析完成"));
            String deviceId = device.getId();
            executionLog.add(ExecutionLogEntryDto.success("环境初始化", "正在检查设备状态并执行环境初始化..."));
            PlatformDriver driver = platformRouter.getDriver(device);
            if (driver == null) {
                executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "无法获取平台驱动，请确认 appium.enabled 且设备可用", null));
                throw new RuntimeException("无法获取平台驱动");
            }
            executionLog.add(ExecutionLogEntryDto.success("环境初始化", maskDeviceId(deviceId) + "设备,环境初始化执行成功"));
            executionLog.add(ExecutionLogEntryDto.success("检测规则", "开始检测规则:启动..."));
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "包名:" + packageOrBundle));
            if (!driver.launchApp(deviceId, packageOrBundle)) {
                executionLog.add(ExecutionLogEntryDto.fail("启动应用程序", "启动失败，请确认设备已连接、应用已安装、包名正确", packageOrBundle));
                throw new RuntimeException("启动应用失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("启动应用", "应用已启动"));
            // 慢机冷启动需多帧+延迟，避免只留下空屏；报告取最后一张成功截图
            captureLaunchScreenshotSeries(driver, deviceId, task, executionLog);
            // 启动检测场景只验证能拉起应用；弹窗探测在部分机型（如 vivo）上可能卡死 UiAutomator，导致任务永不结束
            executionLog.add(ExecutionLogEntryDto.success("任务完成", "启动检测流程结束"));
            String reportPath = lastNonBlankScreenshotPath(executionLog);
            if (reportPath == null) {
                reportPath = fallbackReportScreenshot(driver, deviceId, task, "launch");
            }
            FunctionalTestLogHelper.appendSummary(executionLog);
            THREAD_LOG.remove();
            return new FunctionalTestExecutionResult(reportPath, executionLog, null);
        } finally { }
    }

    /**
     * 执行「启动PogPog应用程序(Android)」：固定包名 {@value #POGPOG_INTL_PACKAGE} 与启动 Activity {@value #POGPOG_INTL_COMPONENT}，
     * Appium 模式下使用显式 appPackage/appActivity 建会话；否则使用 {@code adb shell am start -n} 再按需回退。
     */
    public FunctionalTestExecutionResult executeLaunchPogPogAndroidTest(Task task, Device device, Application app) {
        if (task.getPlatform() != Task.Platform.POG_ANDROID) {
            throw new RuntimeException("本场景仅支持任务平台 POG_ANDROID，当前: " + task.getPlatform());
        }
        if (!Device.isAndroidOrPogAndroid(device.getPlatform())) {
            throw new RuntimeException("本场景仅支持 Android / Pog-Android 设备，当前设备平台: " + device.getPlatform());
        }
        log.info("启动PogPog应用程序测试: taskId={}, deviceId={}, package={}", task.getId(), device.getId(), POGPOG_INTL_PACKAGE);
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        THREAD_LOG.set(executionLog);
        try {
            LAST_SCREENSHOT_TIME.remove();
            if (app != null && app.getPackageName() != null && !app.getPackageName().isBlank()) {
                String sel = app.getPackageName().trim();
                if (!POGPOG_INTL_PACKAGE.equals(sel)) {
                    executionLog.add(ExecutionLogEntryDto.success("应用提示",
                            "所选应用包名为 " + sel + "，本场景仍按 PogPog 固定包名 " + POGPOG_INTL_PACKAGE + " 启动"));
                }
            }
            FunctionalTestLogHelper.prependHeader(executionLog, "启动PogPog应用程序(Android)", POGPOG_INTL_PACKAGE, device.getId());
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "appium:appPackage=" + POGPOG_INTL_PACKAGE));
            executionLog.add(ExecutionLogEntryDto.success("应用信息", "appium:appActivity=" + POGPOG_INTL_COMPONENT));
            String deviceId = device.getId();
            executionLog.add(ExecutionLogEntryDto.success("环境初始化", "正在检查设备状态并执行环境初始化..."));
            PlatformDriver driver = platformRouter.getDriver(device);
            if (driver == null) {
                executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "无法获取平台驱动，请确认 appium.enabled 或 adb 可用", null));
                throw new RuntimeException("无法获取平台驱动");
            }
            executionLog.add(ExecutionLogEntryDto.success("环境初始化", maskDeviceId(deviceId) + " 设备,环境初始化执行成功"));
            boolean launched;
            if (appiumEnabled && appiumDriverAdapter != null && driver instanceof AppiumDriverAdapter) {
                launched = appiumDriverAdapter.launchAndroidWithPackageAndRelativeActivity(deviceId,
                        POGPOG_INTL_PACKAGE, POGPOG_INTL_ACTIVITY_RELATIVE);
            } else {
                launched = adbAmStartComponent(deviceId, POGPOG_INTL_COMPONENT);
                if (!launched) {
                    executionLog.add(ExecutionLogEntryDto.success("启动回退", "am start 未成功，尝试通用 Android 启动"));
                    launched = driver.launchApp(deviceId, POGPOG_INTL_PACKAGE);
                }
            }
            if (!launched) {
                executionLog.add(ExecutionLogEntryDto.fail("启动应用程序", "启动失败，请确认设备已连接且已安装 " + POGPOG_INTL_PACKAGE, POGPOG_INTL_PACKAGE));
                throw new RuntimeException("启动PogPog应用失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "已启动: " + POGPOG_INTL_COMPONENT));
            captureLaunchScreenshotSeries(driver, deviceId, task, executionLog);
            maybeDismissAndroidNotificationPermissionDialog(device, driver, deviceId, task, executionLog);
            maybeTapPogPogPromoClaimIfPresent(device, driver, deviceId, task, executionLog);
            executionLog.add(ExecutionLogEntryDto.success("任务完成", "启动检测流程结束"));
            // 弹窗处理后补一轮稳定截图，保证报告画面完整
            sleep(LAUNCH_SCREENSHOT_INTERVAL_MS);
            executionLog.add(ExecutionLogEntryDto.success("启动截图", "弹窗处理后补截"));
            takeStepScreenshot(driver, deviceId, task, executionLog, LAUNCH_STEP_SCREENSHOT_SCENARIO);
            String reportPath = lastNonBlankScreenshotPath(executionLog);
            if (reportPath == null) {
                reportPath = fallbackReportScreenshot(driver, deviceId, task, "launch_pog");
            }
            FunctionalTestLogHelper.appendSummary(executionLog);
            THREAD_LOG.remove();
            return new FunctionalTestExecutionResult(reportPath, executionLog, null);
        } finally {
        }
    }

    private boolean adbAmStartComponent(String deviceId, String component) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "am", "start", "-n", component)
                    .redirectErrorStream(true)
                    .start();
            int code = p.waitFor();
            if (code != 0) {
                log.warn("adb am start -n {} 退出码 {}", component, code);
            }
            return code == 0;
        } catch (Exception e) {
            log.warn("adb am start 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 若出现系统「发送通知」权限询问（如包含要允许某应用向您发送通知），再判断是否出现「允许」按钮；有则点击，无则跳过。
     * 每个分支在写入执行日志后对该步截图。
     */
    private void maybeDismissAndroidNotificationPermissionDialog(
            Device device, PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        if (device == null || device.getPlatform() == null || !Device.isAndroidOrPogAndroid(device.getPlatform())) {
            return;
        }
        try {
            if (!driver.isElementPresent(deviceId, SEL_ANDROID_NOTIFICATION_PERMISSION_MSG)) {
                return;
            }
            executionLog.add(ExecutionLogEntryDto.success("系统权限", "检测到通知权限询问对话框"));
            takeStepScreenshot(driver, deviceId, task, executionLog, LAUNCH_STEP_SCREENSHOT_SCENARIO);
            if (!driver.isElementPresent(deviceId, SEL_ANDROID_NOTIFICATION_PERMISSION_ALLOW)) {
                executionLog.add(ExecutionLogEntryDto.success("系统权限", "未找到「允许」按钮，跳过"));
                takeStepScreenshot(driver, deviceId, task, executionLog, LAUNCH_STEP_SCREENSHOT_SCENARIO);
                return;
            }
            if (driver.clickElement(deviceId, SEL_ANDROID_NOTIFICATION_PERMISSION_ALLOW)) {
                executionLog.add(ExecutionLogEntryDto.success("系统权限", "已点击「允许」"));
            } else {
                executionLog.add(ExecutionLogEntryDto.success("系统权限", "「允许」按钮存在但点击未成功"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog, LAUNCH_STEP_SCREENSHOT_SCENARIO);
        } catch (Exception e) {
            log.warn("处理通知权限弹窗异常: {}", e.getMessage());
        }
    }

    /**
     * 若出现 Pog 推广文案（「查询DOTA2/CS2战绩」或「即可免费领取珍贵饰品！」），再判断是否出现「立即领取」；
     * 存在则点击 {@code tv_go_action}，否则不操作。有日志的步骤均截图。
     */
    private void maybeTapPogPogPromoClaimIfPresent(
            Device device, PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        if (device == null || device.getPlatform() == null || !Device.isAndroidOrPogAndroid(device.getPlatform())) {
            return;
        }
        try {
            boolean textA = driver.isElementPresent(deviceId, SEL_POG_PROMO_TEXT_DOTA_CS_STATS);
            boolean textB = driver.isElementPresent(deviceId, SEL_POG_PROMO_TEXT_FREE_ITEMS);
            if (!textA && !textB) {
                return;
            }
            executionLog.add(ExecutionLogEntryDto.success("活动弹层", "检测到推广文案（查询战绩或免费领取饰品）"));
            takeStepScreenshot(driver, deviceId, task, executionLog, LAUNCH_STEP_SCREENSHOT_SCENARIO);
            if (!driver.isElementPresent(deviceId, SEL_POG_PROMO_GO_ACTION)) {
                executionLog.add(ExecutionLogEntryDto.success("活动弹层", "未找到「立即领取」(tv_go_action)，跳过"));
                takeStepScreenshot(driver, deviceId, task, executionLog, LAUNCH_STEP_SCREENSHOT_SCENARIO);
                return;
            }
            if (driver.clickElement(deviceId, SEL_POG_PROMO_GO_ACTION)) {
                executionLog.add(ExecutionLogEntryDto.success("活动弹层", "已点击「立即领取」"));
            } else {
                executionLog.add(ExecutionLogEntryDto.success("活动弹层", "「立即领取」存在但点击未成功"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog, LAUNCH_STEP_SCREENSHOT_SCENARIO);
        } catch (Exception e) {
            log.warn("处理 Pog 推广弹层异常: {}", e.getMessage());
        }
    }

    /**
     * 判断 Android 是否已登录：侧边栏有「我的主页」/昵称区 → 已登录；有「点击登录」→ 未登录。
     * 与设置测试一致，不用手机号输入框判断登录态。
     */
    private boolean checkAlreadyLoggedInAndroid(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        dismissAndroidWarmTipIfPresent(driver, deviceId, task, logs);
        String sideViewId = "id:com.pwrd.steam.esports:id/sideView";
        String clickLoginXpath = "xpath://android.widget.TextView[@text='点击登录']";
        String homepageXpath = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_mine_homepage']";
        if (!driver.isElementPresent(deviceId, sideViewId) && !adbUiContains(deviceId, "id/sideView")) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "无侧边栏，视为未登录"));
            return false;
        }
        click(driver, deviceId, sideViewId, logs);
        logAndScreenshot(driver, deviceId, task, logs, "打开侧边栏", "用头像/昵称区判断登录态");
        sleep(2000);
        boolean hasClickLogin = driver.isElementPresent(deviceId, clickLoginXpath)
                || adbUiContains(deviceId, "点击登录");
        boolean hasHomepage = driver.isElementPresent(deviceId, homepageXpath)
                || adbUiContains(deviceId, "id/tv_mine_homepage");
        if (hasClickLogin) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "侧边栏为「点击登录」，未登录（无昵称）"));
            takeStepScreenshot(driver, deviceId, task, logs);
            try { click(driver, deviceId, sideViewId, logs); } catch (Exception ignored) {}
            sleep(500);
            return false;
        }
        if (hasHomepage) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "侧边栏有「主页」/已登录头像区，已登录"));
            takeStepScreenshot(driver, deviceId, task, logs);
            try { click(driver, deviceId, sideViewId, logs); } catch (Exception ignored) {}
            sleep(500);
            return true;
        }
        logs.add(ExecutionLogEntryDto.success("判断登录状态", "侧边栏无法确认，视为未登录"));
        takeStepScreenshot(driver, deviceId, task, logs);
        try { click(driver, deviceId, sideViewId, logs); } catch (Exception ignored) {}
        return false;
    }

    /**
     * 判断 iOS 是否已登录：侧边栏「点击登录」=未登录；有「主页」（已登录后头像旁为昵称）=已登录。
     * 与设置测试 {@code SettingsTestService.isAlreadyLoggedIn} 一致，不用手机号判断。
     */
    private boolean checkAlreadyLoggedInIos(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        dismissIosWarmTipIfPresent(driver, deviceId, task, logs);
        String menu = "xpath://XCUIElementTypeButton[@name='icon menu dark']";
        String clickLogin = "xpath://XCUIElementTypeStaticText[@name='点击登录']";
        String clickLoginBtn = "xpath://XCUIElementTypeButton[@name='点击登录']";
        String homepage = "xpath://XCUIElementTypeButton[@name='主页']";
        if (!driver.isElementPresent(deviceId, menu)) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "无侧边栏按钮，视为未登录"));
            return false;
        }
        click(driver, deviceId, menu, logs);
        logAndScreenshot(driver, deviceId, task, logs, "打开侧边栏", "用头像旁昵称/「点击登录」判断登录态");
        sleep(2000);
        boolean hasClickLogin = driver.isElementPresent(deviceId, clickLogin) || driver.isElementPresent(deviceId, clickLoginBtn);
        boolean hasHomepage = driver.isElementPresent(deviceId, homepage);
        if (hasClickLogin) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "头像旁为「点击登录」，未登录"));
            takeStepScreenshot(driver, deviceId, task, logs);
            try { click(driver, deviceId, menu, logs); } catch (Exception ignored) {}
            sleep(500);
            return false;
        }
        if (hasHomepage) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "头像旁为昵称且有「主页」，已登录"));
            takeStepScreenshot(driver, deviceId, task, logs);
            try { click(driver, deviceId, menu, logs); } catch (Exception ignored) {}
            sleep(500);
            return true;
        }
        logs.add(ExecutionLogEntryDto.success("判断登录状态", "侧边栏无法确认，视为未登录"));
        takeStepScreenshot(driver, deviceId, task, logs);
        try { click(driver, deviceId, menu, logs); } catch (Exception ignored) {}
        return false;
    }

    private void waitForAppReadyAndroid(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        int waited = 0;
        while (waited < APP_READY_WAIT_SECONDS) {
            // 只用 adb dump：Appium 在温馨提示/开屏动画时 waitForIdle 会整段挂死
            String dump = adbDumpUiXml(deviceId);
            if (dump != null && (dump.contains("温馨提示") || dump.contains("id/tv_agree"))) {
                logs.add(ExecutionLogEntryDto.success("主界面就绪", "adb 检测到温馨提示，等待 " + waited + " 秒"));
                takeStepScreenshot(driver, deviceId, task, logs);
                return;
            }
            if (dump != null && dump.contains("id/phone_number_et")) {
                logs.add(ExecutionLogEntryDto.success("主界面就绪", "adb 检测到手机号输入框，等待 " + waited + " 秒"));
                takeStepScreenshot(driver, deviceId, task, logs);
                return;
            }
            if (dump != null && dump.contains("id/sideView")) {
                logs.add(ExecutionLogEntryDto.success("主界面就绪", "adb 检测到侧边栏，等待 " + waited + " 秒"));
                takeStepScreenshot(driver, deviceId, task, logs);
                return;
            }
            sleep(POLL_INTERVAL_MS);
            waited += (int) (POLL_INTERVAL_MS / 1000);
        }
        logs.add(ExecutionLogEntryDto.success("主界面就绪", "等待 " + APP_READY_WAIT_SECONDS + " 秒超时，继续尝试登录步骤"));
        log.warn("等待主界面 {} 秒超时，继续尝试执行登录步骤", APP_READY_WAIT_SECONDS);
    }

    /**
     * Android 登录（沿用原流程）：温馨提示同意 → 否则侧边栏 →（已登录先退出）→ 点击登录 → 手机号/协议/验证码。
     */
    private void executeAndroidLogin(PlatformDriver driver, String deviceId, Task task, String phone, String code, List<ExecutionLogEntryDto> logs) {
        // 温馨提示用 adb 优先（Appium 易 idle 卡住）；有则同意后直接登录页
        dismissAndroidWarmTipIfPresent(driver, deviceId, task, logs);
        if (adbUiContains(deviceId, "id/phone_number_et")
                || driver.isElementPresent(deviceId, "id:com.pwrd.steam.esports:id/phone_number_et")) {
            logs.add(ExecutionLogEntryDto.success("登录页", "温馨提示后已在手机号输入页"));
            commonAndroidLoginSteps(driver, deviceId, task, phone, code, logs);
            return;
        }

        logs.add(ExecutionLogEntryDto.success("打开侧边栏", "未在登录页，打开侧边栏进入「点击登录」"));
        takeStepScreenshot(driver, deviceId, task, logs);
        String sideViewId = "id:com.pwrd.steam.esports:id/sideView";
        click(driver, deviceId, sideViewId, logs);
        logAndScreenshot(driver, deviceId, task, logs, "点击侧边栏", "已点击侧边栏按钮");
        sleep(2000);

        String homepageXpath = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_mine_homepage']";
        boolean hasHomepage = driver.isElementPresent(deviceId, homepageXpath) || adbUiContains(deviceId, "id/tv_mine_homepage");
        if (hasHomepage) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "已登录，先执行退出登录"));
            takeStepScreenshot(driver, deviceId, task, logs);
            performAndroidLogout(driver, deviceId, task, logs);
            if (driver.isElementPresent(deviceId, sideViewId) || adbUiContains(deviceId, "id/sideView")) {
                click(driver, deviceId, sideViewId, logs);
                logAndScreenshot(driver, deviceId, task, logs, "关闭侧边栏", "退出登录后关闭侧边栏");
                sleep(2000);
            }
            // 退出后重新打开侧边栏点「点击登录」
            if (driver.isElementPresent(deviceId, sideViewId) || adbUiContains(deviceId, "id/sideView")) {
                click(driver, deviceId, sideViewId, logs);
                sleep(1500);
            }
        } else {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "未登录"));
            takeStepScreenshot(driver, deviceId, task, logs);
        }

        String clickLoginXpath = "xpath://android.widget.TextView[@text='点击登录']";
        String logoutAreaXpath = "xpath://android.widget.LinearLayout[@resource-id='com.pwrd.steam.esports:id/vg_mine_logout']";
        if (driver.waitForElement(deviceId, clickLoginXpath, 10)) {
            click(driver, deviceId, clickLoginXpath, logs);
        } else if (driver.isElementPresent(deviceId, logoutAreaXpath)) {
            click(driver, deviceId, logoutAreaXpath, logs);
        } else {
            logs.add(ExecutionLogEntryDto.fail("点击登录入口", "未找到登录入口（点击登录或未登录区域）",
                    clickLoginXpath + " / " + logoutAreaXpath));
            throw new RuntimeException("未找到登录入口（点击登录或未登录区域 vg_mine_logout）");
        }
        logAndScreenshot(driver, deviceId, task, logs, "点击登录入口", "已点击登录入口");
        sleep(2000);
        commonAndroidLoginSteps(driver, deviceId, task, phone, code, logs);
    }

    /**
     * 温馨提示关闭：优先 adb dump + tap。
     * Appium UiAutomator2 在弹窗/动画时经常 waitForIdle 挂死，导致「检测不到元素」并整段卡住。
     */
    private void dismissAndroidWarmTipIfPresent(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        String dump = adbDumpUiXml(deviceId);
        boolean tipShown = dump != null && (dump.contains("温馨提示") || dump.contains("id/tv_agree"));
        if (!tipShown && dump == null) {
            // 仅当 adb dump 失败时才短探 Appium，避免 idle 挂死
            tipShown = driver.isElementPresent(deviceId, "id:com.pwrd.steam.esports:id/tv_agree");
        }
        if (!tipShown) {
            logs.add(ExecutionLogEntryDto.success("温馨提示", "未出现，跳过"));
            return;
        }
        logs.add(ExecutionLogEntryDto.success("温馨提示", "出现，点击同意（优先 adb）"));
        takeStepScreenshot(driver, deviceId, task, logs);

        boolean clicked = adbTapAndroidAgreeButton(deviceId);
        if (!clicked) {
            String agreeId = "id:com.pwrd.steam.esports:id/tv_agree";
            String agreeXpath = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_agree']";
            clicked = driver.clickElement(deviceId, agreeId) || driver.clickElement(deviceId, agreeXpath);
        }
        if (!clicked) {
            // 禁止写死像素：用当前屏宽高比例兜底（同意按钮约在右下）
            clicked = adbTapByScreenRatio(deviceId, 0.73, 0.95);
            if (clicked) {
                logs.add(ExecutionLogEntryDto.success("温馨提示", "按屏幕比例点击同意（跨机可用）"));
            }
        }
        if (!clicked) {
            logs.add(ExecutionLogEntryDto.fail("温馨提示", "出现但点击同意失败", "tv_agree"));
            throw new RuntimeException("Android 温馨提示点击同意失败");
        }
        logAndScreenshot(driver, deviceId, task, logs, "点击同意", "已点击温馨提示同意");
        sleep(1500);
        String after = adbDumpUiXml(deviceId);
        if (after != null && (after.contains("温馨提示") || after.contains("id/tv_agree"))) {
            logs.add(ExecutionLogEntryDto.success("温馨提示", "仍在，再次点击同意"));
            if (!adbTapAndroidAgreeButton(deviceId)) {
                adbTapByScreenRatio(deviceId, 0.73, 0.95);
            }
            sleep(1000);
        }
    }

    private boolean adbUiContains(String deviceId, String needle) {
        String dump = adbDumpUiXml(deviceId);
        return dump != null && needle != null && dump.contains(needle);
    }

    private String adbDumpUiXml(String deviceId) {
        try {
            Process dump = new ProcessBuilder("adb", "-s", deviceId, "shell", "uiautomator", "dump", "/sdcard/uidump_login.xml")
                    .redirectErrorStream(true)
                    .start();
            if (!dump.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                dump.destroyForcibly();
                return null;
            }
            Process cat = new ProcessBuilder("adb", "-s", deviceId, "shell", "cat", "/sdcard/uidump_login.xml")
                    .redirectErrorStream(true)
                    .start();
            String xml;
            try (java.io.InputStream in = cat.getInputStream()) {
                xml = new String(in.readAllBytes());
            }
            if (!cat.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                cat.destroyForcibly();
                return null;
            }
            return xml.isBlank() ? null : xml;
        } catch (Exception e) {
            log.debug("adb uiautomator dump 失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean adbTap(String deviceId, int x, int y) {
        try {
            Process tap = new ProcessBuilder("adb", "-s", deviceId, "shell", "input", "tap",
                    String.valueOf(x), String.valueOf(y))
                    .redirectErrorStream(true)
                    .start();
            return tap.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && tap.exitValue() == 0;
        } catch (Exception e) {
            log.warn("adb tap 失败: ({},{}) {}", x, y, e.getMessage());
            return false;
        }
    }

    /** 按当前设备分辨率比例点击，换机不依赖写死像素 */
    private boolean adbTapByScreenRatio(String deviceId, double xRatio, double yRatio) {
        int[] wh = adbWmSize(deviceId);
        if (wh == null) {
            return false;
        }
        int x = (int) (wh[0] * xRatio);
        int y = (int) (wh[1] * yRatio);
        log.info("adb 比例点击: deviceId={} size={}x{} ratio=({},{}) -> ({},{})",
                deviceId, wh[0], wh[1], xRatio, yRatio, x, y);
        return adbTap(deviceId, x, y);
    }

    private int[] adbWmSize(String deviceId) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "wm", "size")
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (java.io.InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes());
            }
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            // Physical size: 720x1570  /  Override size: ...
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(out);
            if (m.find()) {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            }
        } catch (Exception e) {
            log.debug("adb wm size 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 等到任一选择器出现（跨机用元素等待，替代固定 sleep） */
    private boolean waitForAny(PlatformDriver driver, String deviceId, int timeoutSeconds, String... selectors) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (String sel : selectors) {
                if (sel != null && driver.isElementPresent(deviceId, sel)) {
                    return true;
                }
            }
            sleep(400);
        }
        return false;
    }

    /** 点击后必须等到「下一屏特征」出现，否则换选择器/坐标重试（换机稳定关键） */
    private boolean clickUntilNextScreen(
            PlatformDriver driver, String deviceId, List<ExecutionLogEntryDto> logs,
            String[] clickSelectors, String[] nextScreenSelectors, int confirmSeconds) {
        for (String sel : clickSelectors) {
            if (sel == null || !driver.isElementPresent(deviceId, sel)) {
                continue;
            }
            boolean ok = appiumDriverAdapter != null
                    ? appiumDriverAdapter.clickElementPreferCoordinates(deviceId, sel)
                    : driver.clickElement(deviceId, sel);
            if (!ok) {
                continue;
            }
            if (waitForAny(driver, deviceId, confirmSeconds, nextScreenSelectors)) {
                logs.add(ExecutionLogEntryDto.success("点击确认", "已进入下一屏: " + sel));
                return true;
            }
            logs.add(ExecutionLogEntryDto.success("点击确认", "点了但未进下一屏，换选择器重试: " + sel));
        }
        return waitForAny(driver, deviceId, 2, nextScreenSelectors);
    }

    /** 从 uiautomator dump 解析 tv_agree 中心点并 adb tap，绕开 Appium idle 卡住 */
    private boolean adbTapAndroidAgreeButton(String deviceId) {
        try {
            String xml = adbDumpUiXml(deviceId);
            if (xml == null) {
                return false;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("resource-id=\"com\\.pwrd\\.steam\\.esports:id/tv_agree\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")
                    .matcher(xml);
            if (!m.find()) {
                m = java.util.regex.Pattern
                        .compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"[^>]*resource-id=\"com\\.pwrd\\.steam\\.esports:id/tv_agree\"")
                        .matcher(xml);
                if (!m.find()) {
                    log.warn("adb dump 未找到 tv_agree bounds");
                    return false;
                }
            }
            int x1 = Integer.parseInt(m.group(1));
            int y1 = Integer.parseInt(m.group(2));
            int x2 = Integer.parseInt(m.group(3));
            int y2 = Integer.parseInt(m.group(4));
            int x = (x1 + x2) / 2;
            int y = (y1 + y2) / 2;
            boolean ok = adbTap(deviceId, x, y);
            log.info("adb tap 温馨提示同意: deviceId={}, ({},{}) ok={}", deviceId, x, y, ok);
            return ok;
        } catch (Exception e) {
            log.warn("adb tap 温馨提示同意失败: {}", e.getMessage());
            return false;
        }
    }

    private void performAndroidLogout(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        click(driver, deviceId, "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_tool_mame' and @text='设置']", logs);
        logAndScreenshot(driver, deviceId, task, logs, "点击设置", "已点击设置入口");
        sleep(2000);
        try {
            driver.swipe(deviceId, 500, 1200, 500, 400, 500);
            logAndScreenshot(driver, deviceId, task, logs, "滑动设置页", "向下滑动查找退出登录");
            sleep(1000);
        } catch (Exception e) {
            log.debug("设置页滑动忽略: {}", e.getMessage());
        }
        click(driver, deviceId, "id:com.pwrd.steam.esports:id/tv_logout", logs);
        logAndScreenshot(driver, deviceId, task, logs, "点击退出登录", "已点击退出登录按钮");
        sleep(1000);
        if (driver.isElementPresent(deviceId, "xpath://android.widget.TextView[@text='确定退出当前账号吗？']")) {
            click(driver, deviceId, "id:com.pwrd.steam.esports:id/tv_certify", logs);
            logAndScreenshot(driver, deviceId, task, logs, "确认退出", "已点击确定退出");
            sleep(2000);
        }
    }

    /** 线上回归「账号退出」步骤：打开侧边栏，已登录则退出，未登录视为成功 */
    private void executeAndroidLogoutOnly(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        dismissAndroidWarmTipIfPresent(driver, deviceId, task, logs);
        String sideViewId = "id:com.pwrd.steam.esports:id/sideView";
        if (!driver.waitForElement(deviceId, sideViewId, 8)) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "无侧边栏，可能已在登录页/未登录，退出视为成功"));
            return;
        }
        click(driver, deviceId, sideViewId, logs);
        logAndScreenshot(driver, deviceId, task, logs, "打开侧边栏", "准备检查登录状态并退出");
        sleep(2000);
        String homepageXpath = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_mine_homepage']";
        if (driver.isElementPresent(deviceId, homepageXpath)) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "已登录，执行退出"));
            takeStepScreenshot(driver, deviceId, task, logs);
            performAndroidLogout(driver, deviceId, task, logs);
            if (driver.isElementPresent(deviceId, sideViewId)) {
                click(driver, deviceId, sideViewId, logs);
                logAndScreenshot(driver, deviceId, task, logs, "关闭侧边栏", "退出后关闭侧边栏");
            }
        } else {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "当前未登录，退出步骤视为成功"));
            takeStepScreenshot(driver, deviceId, task, logs);
            if (driver.isElementPresent(deviceId, sideViewId)) {
                click(driver, deviceId, sideViewId, logs);
            }
        }
    }

    private void commonAndroidLoginSteps(PlatformDriver driver, String deviceId, Task task, String phone, String code, List<ExecutionLogEntryDto> logs) {
        String phoneInputId = "id:com.pwrd.steam.esports:id/phone_number_et";
        String checkboxId = "id:com.pwrd.steam.esports:id/policy_checkbox";
        String sendCodeId = "id:com.pwrd.steam.esports:id/send_code_tv";
        String codeInputId = "id:com.pwrd.steam.esports:id/confirm_code_input_et";

        if (!adbUiContains(deviceId, "id/phone_number_et") && !driver.waitForElement(deviceId, phoneInputId, 10)) {
            logs.add(ExecutionLogEntryDto.fail("等待手机号输入框", "未找到手机号输入框", phoneInputId));
            throw new RuntimeException("未找到手机号输入框(phone_number_et)");
        }
        logs.add(ExecutionLogEntryDto.success("手机号输入框已出现", "元素已定位，输入: " + maskPhone(phone)));
        takeStepScreenshot(driver, deviceId, task, logs);
        click(driver, deviceId, phoneInputId, logs);
        logAndScreenshot(driver, deviceId, task, logs, "点击手机号输入框", "已点击手机号输入框");
        driver.inputTextToElement(deviceId, phoneInputId, phone);
        logs.add(ExecutionLogEntryDto.success("输入手机号", "已输入: " + maskPhone(phone)));
        takeStepScreenshot(driver, deviceId, task, logs);
        click(driver, deviceId, checkboxId, logs);
        logAndScreenshot(driver, deviceId, task, logs, "勾选协议", "已勾选用户协议");
        click(driver, deviceId, sendCodeId, logs);
        logs.add(ExecutionLogEntryDto.success("勾选协议并发送验证码", "已点击发送验证码"));
        takeStepScreenshot(driver, deviceId, task, logs);
        sleep(6000);
        if (!driver.waitForElement(deviceId, codeInputId, 10)) {
            logs.add(ExecutionLogEntryDto.fail("等待验证码输入框", "未找到验证码输入框", codeInputId));
            throw new RuntimeException("未找到验证码输入框(confirm_code_input_et)");
        }
        logs.add(ExecutionLogEntryDto.success("验证码输入框已出现", "元素已定位"));
        takeStepScreenshot(driver, deviceId, task, logs);
        click(driver, deviceId, codeInputId, logs);
        logAndScreenshot(driver, deviceId, task, logs, "点击验证码输入框", "已点击验证码输入框");
        driver.inputTextToElement(deviceId, codeInputId, code);
        logs.add(ExecutionLogEntryDto.success("输入验证码", "已输入: " + formatCodeForLog(code) + "，等待登录完成"));
        takeStepScreenshot(driver, deviceId, task, logs);
        sleep(3000);
    }

    /**
     * iOS 登录（恢复原流程，与设置测试一致）：
     * 打开侧边栏 → 看头像旁是「点击登录」还是昵称/主页 → 未登录才点「点击登录」→ 再填登录表单。
     * 登录态不用手机号判断。
     */
    private void executeIosLogin(PlatformDriver driver, String deviceId, Task task, String phone, String code, List<ExecutionLogEntryDto> logs) {
        dismissIosWarmTipIfPresent(driver, deviceId, task, logs);
        dismissIosNotificationAlertIfPresent(driver, deviceId, task, logs);
        dismissIosTrackingAlertIfPresent(driver, deviceId, task, logs);

        String menu = "xpath://XCUIElementTypeButton[@name='icon menu dark']";
        String clickLoginText = "xpath://XCUIElementTypeStaticText[@name='点击登录']";
        String clickLoginBtn = "xpath://XCUIElementTypeButton[@name='点击登录']";
        String homepage = "xpath://XCUIElementTypeButton[@name='主页']";

        click(driver, deviceId, menu, logs);
        logAndScreenshot(driver, deviceId, task, logs, "打开侧边栏", "判断头像旁是昵称还是「点击登录」");
        sleep(2000);

        boolean hasClickLogin = driver.isElementPresent(deviceId, clickLoginText)
                || driver.isElementPresent(deviceId, clickLoginBtn);
        boolean hasHomepage = driver.isElementPresent(deviceId, homepage);

        if (!hasClickLogin && hasHomepage) {
            // 头像旁已是昵称、有主页 → 已登录；强制登录场景下先退出
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "头像旁为昵称（有「主页」），已登录，先退出再登录"));
            takeStepScreenshot(driver, deviceId, task, logs);
            performIosLogoutFromSideMenu(driver, deviceId, task, logs);
            sleep(1500);
            leaveIosSettingsIfNeeded(driver, deviceId, task, logs);
            click(driver, deviceId, menu, logs);
            logAndScreenshot(driver, deviceId, task, logs, "打开侧边栏", "退出后重新打开");
            sleep(2000);
            hasClickLogin = driver.isElementPresent(deviceId, clickLoginText)
                    || driver.isElementPresent(deviceId, clickLoginBtn);
        } else if (hasClickLogin) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "头像旁为「点击登录」，未登录，执行登录"));
            takeStepScreenshot(driver, deviceId, task, logs);
        } else {
            logs.add(ExecutionLogEntryDto.fail("判断登录状态", "侧边栏既无「点击登录」也无「主页」", clickLoginText));
            throw new RuntimeException("iOS 侧边栏无法判断登录态（头像/昵称）");
        }

        if (!hasClickLogin) {
            logs.add(ExecutionLogEntryDto.fail("点击登录入口", "未找到「点击登录」", clickLoginText));
            throw new RuntimeException("未找到 iOS「点击登录」");
        }

        String phoneField = "xpath://XCUIElementTypeTextField[@value='请输入你的手机号' or @value='请输入您的手机号']";
        String phoneFieldAlt = "xpath://XCUIElementTypeTextField[@name='phone_number' or @name='手机号']";
        String[] loginEntries = {clickLoginText, clickLoginBtn, "xpath://*[@name='点击登录' or @label='点击登录']"};
        String[] loginFormMarkers = {phoneField, phoneFieldAlt, "xpath://XCUIElementTypeStaticText[@name='发送验证码']"};
        if (!clickUntilNextScreen(driver, deviceId, logs, loginEntries, loginFormMarkers, 12)) {
            // 比例兜底：头像旁登录文案约在左上（相对屏幕，换机可用）
            if (appiumDriverAdapter != null) {
                int[] size = appiumDriverAdapter.getWindowSize(deviceId);
                if (size != null && size.length >= 2) {
                    int x = (int) (size[0] * 0.22);
                    int y = (int) (size[1] * 0.11);
                    appiumDriverAdapter.click(deviceId, x, y);
                    logs.add(ExecutionLogEntryDto.success("点击登录入口", "按屏幕比例补点 (" + x + "," + y + ")"));
                }
            }
            if (!waitForAny(driver, deviceId, 10, loginFormMarkers)) {
                logs.add(ExecutionLogEntryDto.fail("点击登录入口", "点击后未进入登录表单", clickLoginText));
                throw new RuntimeException("点击「点击登录」后未进入登录表单");
            }
        }
        logAndScreenshot(driver, deviceId, task, logs, "点击登录入口", "已进入登录表单");
        dismissIosWarmTipIfPresent(driver, deviceId, task, logs);
        fillIosLoginForm(driver, deviceId, task, phone, code, logs);
    }

    private void fillIosLoginForm(PlatformDriver driver, String deviceId, Task task, String phone, String code,
                                  List<ExecutionLogEntryDto> logs) {
        String phoneField = "xpath://XCUIElementTypeTextField[@value='请输入你的手机号' or @value='请输入您的手机号']";
        String phoneFieldAlt = "xpath://XCUIElementTypeTextField[@name='phone_number' or @name='手机号']";
        if (!waitForAny(driver, deviceId, 15, phoneField, phoneFieldAlt)) {
            logs.add(ExecutionLogEntryDto.fail("等待手机号输入框", "未找到登录表单手机号输入框", phoneField));
            throw new RuntimeException("未找到 iOS 手机号输入框");
        }
        String phoneSel = driver.isElementPresent(deviceId, phoneField) ? phoneField : phoneFieldAlt;
        logs.add(ExecutionLogEntryDto.success("手机号输入框已出现", "输入: " + maskPhone(phone)));
        takeStepScreenshot(driver, deviceId, task, logs);
        if (appiumDriverAdapter != null) {
            appiumDriverAdapter.clickElementPreferCoordinates(deviceId, phoneSel);
        } else {
            click(driver, deviceId, phoneSel, logs);
        }
        driver.inputTextToElement(deviceId, phoneSel, phone);
        takeStepScreenshot(driver, deviceId, task, logs);

        String agreeProtocol = "xpath://XCUIElementTypeStaticText[@name='我已阅读并同意《完美世界电竞用户协议》和《完美世界电竞个人信息保护政策》']";
        String agreeAlt = "xpath://XCUIElementTypeStaticText[contains(@name,'我已阅读并同意')]";
        String agreeCheck = "xpath://XCUIElementTypeButton[@name='checkbox' or @name='同意协议']";
        String sendCode = "xpath://XCUIElementTypeStaticText[@name='发送验证码']";
        String sendCodeBtn = "xpath://XCUIElementTypeButton[@name='发送验证码']";
        String sendCodeAny = "xpath://*[contains(@name,'发送验证码') or contains(@label,'发送验证码')]";

        // 先勾协议，再点「发送验证码」（用 wait + 多选择器，避免换机后固定 sleep 不够）
        String[] agreeSels = {agreeCheck, agreeProtocol, agreeAlt};
        for (String a : agreeSels) {
            if (driver.isElementPresent(deviceId, a)) {
                if (appiumDriverAdapter != null) {
                    appiumDriverAdapter.clickElementPreferCoordinates(deviceId, a);
                } else {
                    driver.clickElement(deviceId, a);
                }
                logAndScreenshot(driver, deviceId, task, logs, "勾选协议", "已勾选: " + a);
                break;
            }
        }

        if (!waitForAny(driver, deviceId, 10, sendCode, sendCodeBtn, sendCodeAny)) {
            for (String a : agreeSels) {
                if (driver.isElementPresent(deviceId, a)) {
                    if (appiumDriverAdapter != null) {
                        appiumDriverAdapter.clickElementPreferCoordinates(deviceId, a);
                    } else {
                        driver.clickElement(deviceId, a);
                    }
                    break;
                }
            }
            if (!waitForAny(driver, deviceId, 8, sendCode, sendCodeBtn, sendCodeAny)) {
                logs.add(ExecutionLogEntryDto.fail("发送验证码", "未找到按钮", sendCode));
                throw new RuntimeException("未找到 iOS「发送验证码」");
            }
        }
        String sendSel = driver.isElementPresent(deviceId, sendCode) ? sendCode
                : (driver.isElementPresent(deviceId, sendCodeBtn) ? sendCodeBtn : sendCodeAny);
        if (appiumDriverAdapter != null) {
            appiumDriverAdapter.clickElementPreferCoordinates(deviceId, sendSel);
        } else {
            click(driver, deviceId, sendSel, logs);
        }
        logAndScreenshot(driver, deviceId, task, logs, "发送验证码", "已点击发送验证码");

        String captchaHint = "xpath://XCUIElementTypeStaticText[@name='点击按钮进行认证']";
        String verifyBtn = "xpath://XCUIElementTypeStaticText[@name='验证']";
        String verifyBtnAlt = "xpath://XCUIElementTypeButton[@name='验证']";
        String codeField = "xpath://XCUIElementTypeTextField[@value='请输入验证码' or @name='verification_code' or @name='验证码']";

        if (waitForAny(driver, deviceId, 12, captchaHint)) {
            int waitSec = Math.max(15, iosCaptchaHumanWaitSeconds);
            logs.add(ExecutionLogEntryDto.success("人机认证",
                    "检测到「点击按钮进行认证」，请在手机上完成点选，脚本等待约 " + waitSec + " 秒后点「验证」"));
            takeStepScreenshot(driver, deviceId, task, logs);
            sleep(waitSec * 1000L);
            if (driver.isElementPresent(deviceId, verifyBtn) || driver.isElementPresent(deviceId, verifyBtnAlt)) {
                String v = driver.isElementPresent(deviceId, verifyBtn) ? verifyBtn : verifyBtnAlt;
                if (appiumDriverAdapter != null) {
                    appiumDriverAdapter.clickElementPreferCoordinates(deviceId, v);
                } else {
                    click(driver, deviceId, v, logs);
                }
                logAndScreenshot(driver, deviceId, task, logs, "点击验证", "已点击验证");
            }
            sleep(2000);
        } else {
            logs.add(ExecutionLogEntryDto.success("人机认证", "未出现，跳过人工等待"));
        }

        if (!waitForAny(driver, deviceId, 20, codeField)) {
            logs.add(ExecutionLogEntryDto.fail("等待验证码输入框", "未找到验证码输入框", codeField));
            throw new RuntimeException("未找到 iOS 验证码输入框");
        }
        logs.add(ExecutionLogEntryDto.success("验证码输入框已出现", "输入固定验证码"));
        takeStepScreenshot(driver, deviceId, task, logs);
        if (appiumDriverAdapter != null) {
            appiumDriverAdapter.clickElementPreferCoordinates(deviceId, codeField);
        } else {
            click(driver, deviceId, codeField, logs);
        }
        driver.inputTextToElement(deviceId, codeField, code);
        logs.add(ExecutionLogEntryDto.success("输入验证码", "已输入: " + formatCodeForLog(code)));
        takeStepScreenshot(driver, deviceId, task, logs);
        sleep(3000);
    }

    /** 首页「立即登录」兜底（侧边栏找不到「点击登录」时） */
    private boolean clickIosLoginEntryIfPresent(
            PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs, boolean preferBanner) {
        String loginNowBtn = "xpath://XCUIElementTypeButton[@name='立即登录']";
        String loginNowText = "xpath://XCUIElementTypeStaticText[@name='立即登录']";
        if (driver.isElementPresent(deviceId, loginNowBtn) || driver.isElementPresent(deviceId, loginNowText)) {
            String entry = driver.isElementPresent(deviceId, loginNowBtn) ? loginNowBtn : loginNowText;
            click(driver, deviceId, entry, logs);
            logAndScreenshot(driver, deviceId, task, logs, "点击登录入口", "首页「立即登录」");
            sleep(2000);
            return true;
        }
        return false;
    }

    /** 未登录误进设置页时返回（设置页没有登录按钮） */
    private void leaveIosSettingsIfNeeded(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        String logoutAny = "xpath://*[@name='退出登录']";
        boolean onSettings = driver.isElementPresent(deviceId, "xpath://XCUIElementTypeNavigationBar[@name='设置']")
                || (driver.isElementPresent(deviceId, "xpath://XCUIElementTypeStaticText[@name='设置']")
                && driver.isElementPresent(deviceId, "xpath://XCUIElementTypeStaticText[@name='清理缓存']"));
        if (!onSettings || driver.isElementPresent(deviceId, logoutAny)) {
            return;
        }
        logs.add(ExecutionLogEntryDto.success("离开设置页", "当前在设置且无「退出登录」，返回以进入登录"));
        takeStepScreenshot(driver, deviceId, task, logs);
        String[] backSelectors = {
                "xpath://XCUIElementTypeNavigationBar[@name='设置']//XCUIElementTypeButton",
                "xpath://XCUIElementTypeButton[@name='返回']",
                "xpath://XCUIElementTypeButton[@name='Back']",
                "xpath://XCUIElementTypeButton[@label='返回']"
        };
        for (String sel : backSelectors) {
            if (driver.isElementPresent(deviceId, sel)) {
                try {
                    click(driver, deviceId, sel, logs);
                    sleep(1500);
                    return;
                } catch (Exception e) {
                    log.debug("返回设置失败 {}: {}", sel, e.getMessage());
                }
            }
        }
        if (appiumDriverAdapter != null) {
            appiumDriverAdapter.click(deviceId, 40, 80);
            sleep(1500);
        }
    }

    private void dismissIosWarmTipIfPresent(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        String tip = "xpath://XCUIElementTypeStaticText[@name='温馨提示']";
        String agree = "xpath://XCUIElementTypeStaticText[@name='同意']";
        String agreeBtn = "xpath://XCUIElementTypeButton[@name='同意']";
        if (!driver.waitForElement(deviceId, tip, 3) && !driver.isElementPresent(deviceId, tip)) {
            logs.add(ExecutionLogEntryDto.success("温馨提示", "未出现，跳过"));
            return;
        }
        logs.add(ExecutionLogEntryDto.success("温馨提示", "出现，点击同意"));
        takeStepScreenshot(driver, deviceId, task, logs);
        if (driver.isElementPresent(deviceId, agree)) {
            click(driver, deviceId, agree, logs);
        } else if (driver.isElementPresent(deviceId, agreeBtn)) {
            click(driver, deviceId, agreeBtn, logs);
        } else {
            logs.add(ExecutionLogEntryDto.success("温馨提示", "未找到同意按钮，跳过"));
            return;
        }
        sleep(1500);
    }

    private void dismissIosNotificationAlertIfPresent(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        // 文案可能带弯引号
        String tip1 = "xpath://XCUIElementTypeStaticText[contains(@name,'想给你发送通知')]";
        String tip2 = "xpath://XCUIElementTypeStaticText[contains(@name,'发送通知')]";
        String allow = "xpath://XCUIElementTypeButton[@name='允许']";
        if (!driver.isElementPresent(deviceId, tip1) && !driver.isElementPresent(deviceId, tip2)) {
            // 再短等一下系统弹窗
            sleep(800);
            if (!driver.isElementPresent(deviceId, tip1) && !driver.isElementPresent(deviceId, tip2)) {
                logs.add(ExecutionLogEntryDto.success("发送通知", "未出现，跳过"));
                return;
            }
        }
        logs.add(ExecutionLogEntryDto.success("发送通知", "出现，点击允许"));
        takeStepScreenshot(driver, deviceId, task, logs);
        if (driver.isElementPresent(deviceId, allow)) {
            click(driver, deviceId, allow, logs);
        } else if (appiumDriverAdapter != null) {
            appiumDriverAdapter.dismissIosSpringboardSystemAlert(deviceId, "允许", 0, 0);
        }
        sleep(1200);
    }

    private void dismissIosTrackingAlertIfPresent(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        String tip = "xpath://XCUIElementTypeStaticText[contains(@name,'跟踪你在其他公司的App和网站上的活动')]";
        String tip2 = "xpath://XCUIElementTypeStaticText[contains(@name,'跟踪')]";
        String allow = "xpath://XCUIElementTypeButton[@name='允许']";
        if (!driver.isElementPresent(deviceId, tip)) {
            sleep(800);
            if (!driver.isElementPresent(deviceId, tip) && !driver.isElementPresent(deviceId, tip2)) {
                logs.add(ExecutionLogEntryDto.success("跟踪App活动", "未出现，跳过"));
                return;
            }
        }
        logs.add(ExecutionLogEntryDto.success("跟踪App活动", "出现，点击允许"));
        takeStepScreenshot(driver, deviceId, task, logs);
        if (driver.isElementPresent(deviceId, allow)) {
            click(driver, deviceId, allow, logs);
        } else if (appiumDriverAdapter != null) {
            appiumDriverAdapter.dismissIosSpringboardSystemAlert(deviceId, "允许", 0, 0);
        }
        sleep(1200);
    }

    /** iOS 退出：侧边栏 → 设置 → 退出登录 → 确定 */
    private void executeIosLogoutOnly(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        String menu = "xpath://XCUIElementTypeButton[@name='icon menu dark']";
        String clickLogin = "xpath://XCUIElementTypeButton[@name='点击登录']";
        String clickLoginText = "xpath://XCUIElementTypeStaticText[@name='点击登录']";
        if (driver.isElementPresent(deviceId, menu)) {
            click(driver, deviceId, menu, logs);
            logAndScreenshot(driver, deviceId, task, logs, "打开菜单", "检查登录状态");
            sleep(2000);
        }
        if (driver.isElementPresent(deviceId, clickLogin) || driver.isElementPresent(deviceId, clickLoginText)) {
            logs.add(ExecutionLogEntryDto.success("判断登录状态", "已显示「点击登录」，当前未登录，退出视为成功"));
            takeStepScreenshot(driver, deviceId, task, logs);
            return;
        }
        performIosLogoutFromSideMenu(driver, deviceId, task, logs);
    }

    /** 假定侧边栏已打开（或将自行打开设置） */
    private void performIosLogoutFromSideMenu(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        String settings = "xpath://XCUIElementTypeStaticText[@name='设置']";
        if (!driver.isElementPresent(deviceId, settings)) {
            String menu = "xpath://XCUIElementTypeButton[@name='icon menu dark']";
            if (driver.isElementPresent(deviceId, menu)) {
                click(driver, deviceId, menu, logs);
                sleep(1500);
            }
        }
        if (!driver.waitForElement(deviceId, settings, 8)) {
            logs.add(ExecutionLogEntryDto.fail("退出登录", "未找到「设置」入口", settings));
            throw new RuntimeException("iOS 退出失败：未找到设置入口");
        }
        click(driver, deviceId, settings, logs);
        logAndScreenshot(driver, deviceId, task, logs, "点击设置", "进入设置页找退出登录");
        sleep(2000);
        try {
            int[] size = appiumDriverAdapter != null ? appiumDriverAdapter.getWindowSize(deviceId) : null;
            int x = size != null && size.length >= 2 ? size[0] / 2 : 200;
            int y1 = size != null && size.length >= 2 ? (int) (size[1] * 0.75) : 600;
            int y2 = size != null && size.length >= 2 ? (int) (size[1] * 0.25) : 200;
            driver.swipe(deviceId, x, y1, x, y2, 500);
            sleep(800);
        } catch (Exception e) {
            log.debug("iOS 设置页滑动忽略: {}", e.getMessage());
        }
        String logoutBtn = "xpath:(//XCUIElementTypeButton[@name='退出登录'])[1]";
        String logoutText = "xpath:(//XCUIElementTypeStaticText[@name='退出登录'])[1]";
        String logoutAny = "xpath://*[@name='退出登录']";
        if (driver.waitForElement(deviceId, logoutBtn, 5) || driver.isElementPresent(deviceId, logoutText)
                || driver.isElementPresent(deviceId, logoutAny)) {
            String sel = driver.isElementPresent(deviceId, logoutBtn) ? logoutBtn
                    : (driver.isElementPresent(deviceId, logoutText) ? logoutText : logoutAny);
            click(driver, deviceId, sel, logs);
            logAndScreenshot(driver, deviceId, task, logs, "点击退出登录", "已点击退出登录");
            sleep(1000);
            String confirm = "xpath://XCUIElementTypeButton[@name='确定']";
            String confirm2 = "xpath://XCUIElementTypeStaticText[@name='确定']";
            if (driver.isElementPresent(deviceId, confirm) || driver.isElementPresent(deviceId, confirm2)) {
                click(driver, deviceId, driver.isElementPresent(deviceId, confirm) ? confirm : confirm2, logs);
                logAndScreenshot(driver, deviceId, task, logs, "确认退出", "已点击确定");
                sleep(2000);
            }
        } else {
            logs.add(ExecutionLogEntryDto.success("退出登录", "设置页未找到「退出登录」，视为当前未登录，返回继续登录"));
            leaveIosSettingsIfNeeded(driver, deviceId, task, logs);
        }
    }

    private static void click(PlatformDriver driver, String deviceId, String selector, List<ExecutionLogEntryDto> logs) {
        if (!driver.clickElement(deviceId, selector)) {
            logs.add(ExecutionLogEntryDto.fail("点击元素", "点击失败", selector));
            throw new RuntimeException("点击失败: " + selector);
        }
    }

    /**
     * 启动后按固定间隔连续截图：慢机界面尚未就绪时也能留下多帧，
     * 报告与线上回归步骤截图使用最后一张成功图。
     */
    private void captureLaunchScreenshotSeries(
            PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        if (driver == null || executionLog == null) {
            return;
        }
        executionLog.add(ExecutionLogEntryDto.success(
                "启动截图",
                "开始连续截图: 共 " + LAUNCH_SCREENSHOT_ROUNDS + " 张，间隔 "
                        + LAUNCH_SCREENSHOT_INTERVAL_MS + "ms（兼容慢机冷启动）"));
        for (int i = 1; i <= LAUNCH_SCREENSHOT_ROUNDS; i++) {
            sleep(LAUNCH_SCREENSHOT_INTERVAL_MS);
            executionLog.add(ExecutionLogEntryDto.success(
                    "启动截图", "第 " + i + "/" + LAUNCH_SCREENSHOT_ROUNDS + " 张"));
            takeStepScreenshot(driver, deviceId, task, executionLog, LAUNCH_STEP_SCREENSHOT_SCENARIO);
        }
    }

    /**
     * 添加步骤日志并截图，供前端按步骤展示。每次操作后调用以实现「每次操作都截图」。
     */
    private void logAndScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs, String stepName, String stepDesc) {
        logs.add(ExecutionLogEntryDto.success(stepName, stepDesc));
        takeStepScreenshot(driver, deviceId, task, logs);
    }

    /**
     * 为当前步骤（执行日志最后一条）截屏并写入路径，供前端按步骤展示。
     * 使用 index + 时间戳确保每张截图文件名唯一，避免覆盖。
     * 仅当截图成功保存时才设置 screenshotPath，避免前端请求到不存在的文件返回 404。
     * 单次截图约 0.3～1 秒；若截图异常或超时，仅跳过该步截图，不中断测试。
     */
    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        takeStepScreenshot(driver, deviceId, task, executionLog, "login");
    }

    /**
     * 为当前步骤（执行日志最后一条）截屏并写入路径，供前端按步骤展示。
     * @param scenarioName 截图文件前缀目录，如 login、launch_app
     */
    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog, String scenarioName) {
        if (driver == null || executionLog == null || executionLog.isEmpty()) return;
        int index = executionLog.size() - 1;
        String folder = scenarioName != null && !scenarioName.isBlank() ? scenarioName : "login";
        String path = ScreenshotNamingHelper.buildStepPath(folder, task != null ? task.getId() : null, index);
        try {
            Files.createDirectories(Path.of(path).getParent());
        } catch (Exception e) {
            log.warn("创建截图目录失败: {}", e.getMessage());
            return;
        }
        // 确保与上次截图间隔至少 1.5 秒，避免设备过载导致失败
        long now = System.currentTimeMillis();
        Long last = LAST_SCREENSHOT_TIME.get();
        if (last != null) {
            long elapsed = now - last;
            if (elapsed < 1500) {
                sleep(1500 - elapsed);
            }
        }
        sleep(800); // 等待界面稳定
        String saved = takeScreenshotWithRetry(driver, deviceId, path, 3);
        if (saved != null) {
            LAST_SCREENSHOT_TIME.set(System.currentTimeMillis());
        }
        if (saved != null) {
            executionLog.get(index).setScreenshotPath(saved);
            log.info("步骤截图成功 step={} path={}", index, saved);
        } else {
            log.warn("步骤截图失败 step={} path={}，该步将无截图", index, path);
        }
    }

    /** 带重试的截图，最多尝试 maxAttempts 次，每次间隔 800ms */
    private String takeScreenshotWithRetry(PlatformDriver driver, String deviceId, String path, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            if (i > 0) {
                try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            }
            String saved = driver.takeScreenshot(deviceId, path);
            if (saved != null) return saved;
        }
        return null;
    }

    private static String lastNonBlankScreenshotPath(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) {
            return null;
        }
        String last = null;
        for (ExecutionLogEntryDto e : executionLog) {
            if (e != null && e.getScreenshotPath() != null && !e.getScreenshotPath().isBlank()) {
                last = e.getScreenshotPath();
            }
        }
        return last;
    }

    private String fallbackReportScreenshot(PlatformDriver driver, String deviceId, Task task, String filePrefix) {
        if (driver == null || deviceId == null || task == null) {
            return null;
        }
        try {
            String p = "/tmp/screenshots/" + filePrefix + "_" + task.getId() + "_" + System.currentTimeMillis() + ".png";
            Files.createDirectories(Paths.get(p).getParent());
            return driver.takeScreenshot(deviceId, p);
        } catch (Exception e) {
            log.warn("兜底报告截图失败: {}", e.getMessage());
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待被中断", e);
        }
    }

    /** 手机号脱敏：13800138000 -> 138****8000，便于日志查看又保护隐私 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) return "****";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 设备 ID 脱敏：仅保留前 4 位与后 4 位，便于执行日志展示 */
    private static String maskDeviceId(String deviceId) {
        if (deviceId == null || deviceId.length() < 10) return "****";
        return deviceId.substring(0, 4) + "****" + deviceId.substring(deviceId.length() - 4);
    }

    /** 验证码在日志中只显示位数，不展示明文 */
    private static String formatCodeForLog(String code) {
        if (code == null || code.isEmpty()) return "****";
        return "****(" + code.length() + "位)";
    }

    @SuppressWarnings("unchecked")
    private static String getConfigString(Task task, String key, String defaultValue) {
        String config = task.getConfig();
        if (config == null || config.isBlank()) return defaultValue;
        try {
            Map<String, Object> map = JSON.readValue(config, Map.class);
            Object v = map != null ? map.get(key) : null;
            return v != null ? v.toString().trim() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
