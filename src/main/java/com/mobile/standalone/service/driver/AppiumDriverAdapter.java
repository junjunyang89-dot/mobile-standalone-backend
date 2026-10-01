package com.mobile.standalone.service.driver;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.AutomationEngine;
import com.mobile.standalone.service.DeviceManager;
import com.mobile.standalone.service.h5.H5CdpLink;
import com.mobile.standalone.service.h5.H5ContextPages;
import com.mobile.standalone.service.h5.H5DomClick;
import com.mobile.standalone.service.h5.H5WebViewJs;
import com.mobile.standalone.util.LocatorTemplate;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.android.options.UiAutomator2Options;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.ios.options.XCUITestOptions;
import io.appium.java_client.remote.SupportsContextSwitching;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Pause;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Appium 的驱动适配器，支持 Android 与 iOS，与 ADB/idevice 驱动二选一（通过 appium.enabled 切换）。
 * 需先启动 Appium Server，并保证设备已连接。
 */
@Component
@ConditionalOnProperty(name = "appium.enabled", havingValue = "true")
@Slf4j
public class AppiumDriverAdapter implements PlatformDriver {

    private final DeviceMapper deviceMapper;
    private final AutomationEngine automationEngine;
    private final DeviceManager deviceManager;
    private final String serverUrl;
    private final int newCommandTimeout;
    private final int implicitWaitSeconds;
    private final Map<String, AppiumDriver> sessions = new ConcurrentHashMap<>();
    /** 多 Android 并行时每台设备固定一条 UiAutomator2 systemPort，避免默认 8200 冲突 */
    private final ConcurrentHashMap<String, Integer> androidSystemPorts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> androidMjpegPorts = new ConcurrentHashMap<>();
    /** 多 iOS 并行时每台设备固定一条 WDA 本地端口 */
    private final ConcurrentHashMap<String, Integer> iosWdaLocalPorts = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger nextAndroidSystemPort =
            new java.util.concurrent.atomic.AtomicInteger(8201);
    private final java.util.concurrent.atomic.AtomicInteger nextAndroidMjpegPort =
            new java.util.concurrent.atomic.AtomicInteger(9101);
    private final java.util.concurrent.atomic.AtomicInteger nextIosWdaPort =
            new java.util.concurrent.atomic.AtomicInteger(8101);

    public AppiumDriverAdapter(
            DeviceMapper deviceMapper,
            AutomationEngine automationEngine,
            @Lazy DeviceManager deviceManager,
            @Value("${appium.server-url:http://127.0.0.1:4723}") String serverUrl,
            @Value("${appium.new-command-timeout:300}") int newCommandTimeout,
            @Value("${appium.implicit-wait:10}") int implicitWaitSeconds
    ) {
        this.deviceMapper = deviceMapper;
        this.automationEngine = automationEngine;
        this.deviceManager = deviceManager;
        this.serverUrl = serverUrl;
        this.newCommandTimeout = newCommandTimeout;
        this.implicitWaitSeconds = implicitWaitSeconds;
    }

    /** 对外暴露本设备固定的 WDA 本地端口（默认从 8101 起） */
    public int resolveWdaLocalPort(String deviceId) {
        return wdaLocalPortFor(deviceId);
    }

    private int systemPortFor(String deviceId) {
        return androidSystemPorts.computeIfAbsent(deviceId, id -> nextAndroidSystemPort.getAndIncrement());
    }

    private int mjpegPortFor(String deviceId) {
        return androidMjpegPorts.computeIfAbsent(deviceId, id -> nextAndroidMjpegPort.getAndIncrement());
    }

    private int wdaLocalPortFor(String deviceId) {
        return iosWdaLocalPorts.computeIfAbsent(deviceId, id -> nextIosWdaPort.getAndIncrement());
    }

    /** 给 Android 会话打上 per-device 端口，保证一台 Appium 上可并行多台真机 */
    private void applyAndroidParallelPorts(UiAutomator2Options opts, String deviceId) {
        int systemPort = systemPortFor(deviceId);
        int mjpegPort = mjpegPortFor(deviceId);
        // 上一会话异常退出时，本机 systemPort/mjpeg 可能仍被占用
        freeLocalTcpPortIfBusy(systemPort, "systemPort");
        freeLocalTcpPortIfBusy(mjpegPort, "mjpegServerPort");
        opts.setSystemPort(systemPort);
        try {
            opts.setMjpegServerPort(mjpegPort);
        } catch (Throwable ignored) {
            // 旧客户端无此 API 时忽略
        }
        log.info("Appium Android 并行端口: udid={}, systemPort={}, mjpegServerPort={}", deviceId, systemPort, mjpegPort);
    }

    private void freeLocalTcpPortIfBusy(int port, String label) {
        if (port <= 0) {
            return;
        }
        try {
            Process p = new ProcessBuilder("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t")
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (java.io.InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes()).trim();
            }
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return;
            }
            if (out.isBlank()) {
                return;
            }
            for (String pidStr : out.split("\\s+")) {
                if (pidStr.isBlank()) {
                    continue;
                }
                try {
                    long pid = Long.parseLong(pidStr);
                    log.warn("释放被占用的 Appium {} 端口 {}: pid={}", label, port, pid);
                    new ProcessBuilder("kill", String.valueOf(pid))
                            .start()
                            .waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (NumberFormatException ignored) {
                }
            }
            Thread.sleep(200);
        } catch (Exception e) {
            log.debug("检查/释放端口 {} 失败: {}", port, e.getMessage());
        }
    }

    private void applyIosParallelPorts(XCUITestOptions opts, String deviceId) {
        int wda = wdaLocalPortFor(deviceId);
        opts.setWdaLocalPort(wda);
        log.info("Appium iOS 并行端口: udid={}, wdaLocalPort={}", deviceId, wda);
    }

    @Override
    public boolean launchApp(String deviceId, String packageName) {
        try {
            closeSession(deviceId);
            Device device = deviceMapper.findById(deviceId);
            if (device == null || device.getPlatform() == null) {
                log.warn("设备不存在或平台未知: deviceId={}", deviceId);
                return false;
            }
            // 登录自动化等场景需要每次冷启动：Android 先 force-stop 再启动，避免残留界面导致找不到元素
            if (Device.isAndroidOrPogAndroid(device.getPlatform()) && packageName != null && !packageName.isBlank()) {
                forceStopAndroidApp(deviceId, packageName);
            }
            URL url = new URL(serverUrl);
            AppiumDriver driver;
            Device.Platform dp = device.getPlatform();
            if (Device.usesAndroidAutomation(dp) && dp != Device.Platform.HARMONYOS) {
                UiAutomator2Options opts = buildAndroidOptions(deviceId, packageName);
                driver = new AndroidDriver(url, opts);
                applyAndroidUiStabilitySettings(driver);
            } else if (Device.usesIosAutomation(dp)) {
                XCUITestOptions opts = buildIosOptions(deviceId, packageName);
                if (deviceManager.isIosSimulator(deviceId)) {
                    // Simulator 上 Appium 自己编/拉 WDA，不走真机那套 iproxy + 签名安装
                    opts.setUsePrebuiltWda(false);
                    opts.setForceAppLaunch(true);
                    log.info("iOS launchApp 走 Simulator: udid={} bundle={}", deviceId, packageName);
                } else {
                    // 本机已有 WDA+iproxy 占着 wdaLocalPort 时，不能再让 Appium 去绑同一端口，否则报
                    // "The port #8101 is occupied" → 表现为「无法启动应用程序」
                    int wdaPort = wdaLocalPortFor(deviceId);
                    String wdaUrl = "http://127.0.0.1:" + wdaPort;
                    if (!deviceManager.ensureIosWdaRunning(deviceId, wdaPort)) {
                        log.error("无法拉起本机 WDA（test-without-building+iproxy）: deviceId={}, port={}", deviceId, wdaPort);
                        return false;
                    }
                    opts.setWebDriverAgentUrl(wdaUrl);
                    opts.setForceAppLaunch(true);
                    log.info("iOS launchApp 挂接已运行的 WDA: {} bundle={}", wdaUrl, packageName);
                }
                driver = new IOSDriver(url, opts);
            } else {
                log.warn("Appium 驱动暂不支持平台: {}", device.getPlatform());
                return false;
            }
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWaitSeconds));
            sessions.put(deviceId, driver);
            try {
                if (driver instanceof AndroidDriver androidDriver) {
                    androidDriver.activateApp(packageName);
                } else if (driver instanceof IOSDriver iosDriver) {
                    iosDriver.activateApp(packageName);
                }
                log.info("Appium 会话已创建并激活应用: deviceId={}, package={}", deviceId, packageName);
            } catch (Exception e) {
                log.warn("activateApp 失败，会话已创建: {}", e.getMessage());
            }
            // 任意 Android 包：activateApp 后校验前台；未进前台则 monkey / resolve-activity / 已知组件补拉起
            if (Device.usesAndroidAutomation(dp) && dp != Device.Platform.HARMONYOS
                    && packageName != null && !packageName.isBlank()) {
                bringAndroidAppToForeground(deviceId, packageName);
            }
            return true;
        } catch (MalformedURLException e) {
            log.error("Appium Server URL 无效: {}", serverUrl, e);
            return false;
        } catch (Exception e) {
            log.error("创建 Appium 会话失败: deviceId={}, package={}", deviceId, packageName, e);
            return false;
        }
    }

    /**
     * 确保目标包在前台。部分机型 activateApp 只建会话不切前台；电竞包另有已知 LauncherActivity。
     */
    private void bringAndroidAppToForeground(String deviceId, String packageName) {
        if (isPackageInForeground(deviceId, packageName)) {
            log.info("应用已在前台: {}", packageName);
            return;
        }
        log.warn("activateApp 后应用未在前台，尝试补拉起: {}", packageName);

        if ("com.pwrd.steam.esports".equals(packageName)) {
            adbAmStartKnownComponent(deviceId,
                    "com.pwrd.steam.esports/.ui.activity.launcher.LauncherActivity");
            sleepQuiet(800);
            if (isPackageInForeground(deviceId, packageName)) return;
        }

        // monkey LAUNCHER
        try {
            Process monkey = new ProcessBuilder("adb", "-s", deviceId, "shell", "monkey",
                    "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
                    .redirectErrorStream(true)
                    .start();
            monkey.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            sleepQuiet(800);
            if (isPackageInForeground(deviceId, packageName)) {
                log.info("monkey 补拉起成功: {}", packageName);
                return;
            }
        } catch (Exception e) {
            log.debug("monkey 补拉起失败: {}", e.getMessage());
        }

        // resolve-activity → am start
        String component = resolveLauncherComponent(deviceId, packageName);
        if (component != null) {
            adbAmStartKnownComponent(deviceId, component);
            sleepQuiet(800);
            if (isPackageInForeground(deviceId, packageName)) {
                log.info("am start 补拉起成功: {}", component);
                return;
            }
        }
        log.warn("补拉起后仍可能未在前台，请核对包名是否已安装: deviceId={}, package={}, foreground={}",
                deviceId, packageName, getForegroundPackage(deviceId));
    }

    private boolean isPackageInForeground(String deviceId, String packageName) {
        String fg = getForegroundPackage(deviceId);
        return fg != null && packageName.equals(fg);
    }

    private String getForegroundPackage(String deviceId) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "window", "windows")
                    .redirectErrorStream(true)
                    .start();
            String out = readProcessOutput(p, 8000);
            if (out != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("mCurrentFocus=.*?\\s([a-zA-Z0-9_.]+)/").matcher(out);
                if (m.find()) return m.group(1);
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("mFocusedApp=.*?\\s([a-zA-Z0-9_.]+)/").matcher(out);
                if (m2.find()) return m2.group(1);
            }
        } catch (Exception e) {
            log.debug("读取前台包名失败: {}", e.getMessage());
        }
        return null;
    }

    private String resolveLauncherComponent(String deviceId, String packageName) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "cmd", "package", "resolve-activity",
                    "--brief", "-c", "android.intent.category.LAUNCHER", packageName)
                    .redirectErrorStream(true)
                    .start();
            String out = readProcessOutput(p, 5000);
            if (out != null && !out.isBlank()) {
                for (String raw : out.trim().split("\n")) {
                    String line = raw.trim();
                    if (line.contains("/") && !line.contains("null")) {
                        String component = line.contains(":") ? line.substring(line.indexOf(':') + 1).trim() : line;
                        if (component.matches("^[a-zA-Z0-9_.]+/[.a-zA-Z0-9_]+$")) {
                            return component;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("resolve-activity 失败: {}", e.getMessage());
        }
        return null;
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

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 轻量 am start，不做 resolve-activity / dumpsys，避免拖慢启动 */
    private boolean adbAmStartKnownComponent(String deviceId, String component) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "am", "start", "-n", component)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("adb am start 超时: {}", component);
                return false;
            }
            int code = p.exitValue();
            if (code != 0) {
                log.warn("adb am start -n {} 退出码 {}", component, code);
                return false;
            }
            log.info("adb am start 已执行: {}", component);
            return true;
        } catch (Exception e) {
            log.warn("adb am start 失败: {}", e.getMessage());
            return false;
        }
    }

    /** Android：先强制结束应用再启动，保证冷启动，避免上次残留界面导致 NoSuchElement。 */
    private void forceStopAndroidApp(String deviceId, String packageName) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "am", "force-stop", packageName)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("adb force-stop 超时，继续启动: package={}", packageName);
                return;
            }
            int code = p.exitValue();
            if (code == 0) {
                log.info("Android 应用已强制结束，即将冷启动: deviceId={}, package={}", deviceId, packageName);
            } else {
                log.debug("adb force-stop 退出码 {}（应用可能未在运行）: package={}", code, packageName);
            }
        } catch (Exception e) {
            log.warn("adb force-stop 执行失败，继续启动: {}", e.getMessage());
        }
    }

    /** 供流程关键字「冷启动」显式调用，语义等同 {@link #launchApp}（Android 会 force-stop；iOS forceAppLaunch）。 */
    public boolean coldStartApp(String deviceId, String packageName) {
        return launchApp(deviceId, packageName);
    }

    /**
     * iOS 冷启动：terminateApp（若有会话）后再 {@link #launchApp}（forceAppLaunch=true）。
     */
    public boolean coldStartIosApp(String deviceId, String bundleId) {
        if (deviceId == null || deviceId.isBlank() || bundleId == null || bundleId.isBlank()) {
            return false;
        }
        Device device = deviceMapper.findById(deviceId);
        if (device == null || !Device.usesIosAutomation(device.getPlatform())) {
            log.warn("coldStartIosApp 仅支持 iOS/Pog-iOS: deviceId={}", deviceId);
            return false;
        }
        try {
            AppiumDriver existing = sessions.get(deviceId);
            if (existing instanceof IOSDriver iosDriver) {
                try {
                    iosDriver.terminateApp(bundleId);
                    log.info("iOS terminateApp 完成: bundle={}", bundleId);
                } catch (Exception e) {
                    log.debug("iOS terminateApp 忽略: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("iOS 冷启 terminate 阶段忽略: {}", e.getMessage());
        }
        sleepQuiet(500);
        return launchApp(deviceId, bundleId);
    }

    /**
     * 热启动：不杀进程。若进程不在则先冷启一次作为准备；再按 Home 进后台，停留后 activate 回前台。
     * Android 与 iOS 均支持。
     *
     * @param backgroundWaitMs 切后台后停留毫秒，建议 ≥1000
     * @return 热启动阶段耗时毫秒；失败返回 -1
     */
    public long hotStartApp(String deviceId, String packageName, long backgroundWaitMs) {
        if (deviceId == null || deviceId.isBlank() || packageName == null || packageName.isBlank()) {
            return -1;
        }
        Device device = deviceMapper.findById(deviceId);
        if (device == null || device.getPlatform() == null) {
            return -1;
        }
        if (Device.usesIosAutomation(device.getPlatform())) {
            return hotStartIosApp(deviceId, packageName, backgroundWaitMs);
        }
        if (!Device.isAndroidOrPogAndroid(device.getPlatform())
                || device.getPlatform() == Device.Platform.HARMONYOS) {
            log.warn("hotStartApp 当前仅支持 Android/Pog-Android/iOS: deviceId={}", deviceId);
            return -1;
        }
        try {
            if (!isAndroidProcessAlive(deviceId, packageName)) {
                log.info("热启动准备：进程未在运行，先冷启动 {}", packageName);
                if (!launchApp(deviceId, packageName)) {
                    return -1;
                }
                sleepQuiet(1500);
            } else if (sessions.get(deviceId) == null) {
                if (!ensureAndroidSessionWithoutForceStop(deviceId, packageName)) {
                    return -1;
                }
            }

            pressAndroidHome(deviceId);
            long wait = Math.max(500, backgroundWaitMs);
            sleepQuiet(wait);

            long t0 = System.currentTimeMillis();
            AppiumDriver driver = sessions.get(deviceId);
            if (driver instanceof AndroidDriver androidDriver) {
                try {
                    androidDriver.activateApp(packageName);
                } catch (Exception e) {
                    log.warn("activateApp 失败，尝试补拉起: {}", e.getMessage());
                }
            }
            bringAndroidAppToForeground(deviceId, packageName);
            long deadline = t0 + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (isPackageInForeground(deviceId, packageName)) {
                    long cost = System.currentTimeMillis() - t0;
                    log.info("热启动完成: deviceId={}, package={}, costMs={}", deviceId, packageName, cost);
                    return cost;
                }
                sleepQuiet(100);
            }
            log.warn("热启动超时未进前台: package={}, foreground={}", packageName, getForegroundPackage(deviceId));
            return -1;
        } catch (Exception e) {
            log.error("热启动失败: deviceId={}, package={}", deviceId, packageName, e);
            return -1;
        }
    }

    /** iOS 热启动：不 terminate；Home 后台后 activateApp。 */
    public long hotStartIosApp(String deviceId, String bundleId, long backgroundWaitMs) {
        if (deviceId == null || deviceId.isBlank() || bundleId == null || bundleId.isBlank()) {
            return -1;
        }
        try {
            if (!(sessions.get(deviceId) instanceof IOSDriver)) {
                if (!ensureIosSessionWithoutForceLaunch(deviceId, bundleId)) {
                    return -1;
                }
                // 无会话时先激活一次，保证进程在
                try {
                    ((IOSDriver) sessions.get(deviceId)).activateApp(bundleId);
                    sleepQuiet(1200);
                } catch (Exception e) {
                    log.warn("iOS 热启准备 activate 失败，改冷启: {}", e.getMessage());
                    if (!coldStartIosApp(deviceId, bundleId)) {
                        return -1;
                    }
                    sleepQuiet(1200);
                }
            }

            pressIosHome(deviceId);
            long wait = Math.max(500, backgroundWaitMs);
            sleepQuiet(wait);

            long t0 = System.currentTimeMillis();
            AppiumDriver driver = sessions.get(deviceId);
            if (!(driver instanceof IOSDriver iosDriver)) {
                return -1;
            }
            try {
                iosDriver.activateApp(bundleId);
            } catch (Exception e) {
                log.warn("iOS activateApp 失败: {}", e.getMessage());
                return -1;
            }
            // iOS 无可靠前台包名轮询时，activate 成功即计时结束（短暂等待稳定）
            sleepQuiet(300);
            long cost = System.currentTimeMillis() - t0;
            log.info("iOS 热启动完成: deviceId={}, bundle={}, costMs={}", deviceId, bundleId, cost);
            return cost;
        } catch (Exception e) {
            log.error("iOS 热启动失败: deviceId={}, bundle={}", deviceId, bundleId, e);
            return -1;
        }
    }

    private boolean ensureIosSessionWithoutForceLaunch(String deviceId, String bundleId) {
        try {
            closeSession(deviceId);
            int wdaPort = wdaLocalPortFor(deviceId);
            String wdaUrl = "http://127.0.0.1:" + wdaPort;
            if (!deviceManager.ensureIosWdaRunning(deviceId, wdaPort)) {
                log.error("无法拉起 WDA: deviceId={}, port={}", deviceId, wdaPort);
                return false;
            }
            URL url = new URL(serverUrl);
            XCUITestOptions opts = buildIosOptions(deviceId, bundleId);
            opts.setWebDriverAgentUrl(wdaUrl);
            opts.setForceAppLaunch(false);
            IOSDriver driver = new IOSDriver(url, opts);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWaitSeconds));
            sessions.put(deviceId, driver);
            log.info("已挂接 iOS 会话（未 force launch）: deviceId={}, bundle={}", deviceId, bundleId);
            return true;
        } catch (Exception e) {
            log.error("挂接 iOS 会话失败: deviceId={}, bundle={}", deviceId, bundleId, e);
            return false;
        }
    }

    private void pressIosHome(String deviceId) {
        goIosHomeScreen(deviceId);
    }

    /**
     * 回到 iOS 主屏幕。
     * <p>
     * 全面屏机型的正确手势是<strong>屏幕底部中央快速上滑</strong>（Home Indicator），
     * 不是左右滑，也不是页内慢拖。慢拖会被 App 当成内容滚动/翻页。
     * 顺序：pressButton home → 底部短促上滑 → 拉起 SpringBoard。
     */
    public boolean goIosHomeScreen(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        AppiumDriver driver = sessions.get(deviceId);
        if (!(driver instanceof IOSDriver iosDriver)) {
            return false;
        }
        boolean ok = false;

        // 1) 系统 Home（有实体键 / 部分机型有效；全面屏常无效，不作为唯一手段）
        try {
            iosDriver.executeScript("mobile: pressButton", Map.of("name", "home"));
            log.info("iOS mobile:pressButton home: deviceId={}", deviceId);
            ok = true;
            sleepQuiet(350);
        } catch (Exception e) {
            log.debug("iOS pressButton home: {}", e.getMessage());
        }

        // 2) 全面屏：底部中央 → 向上快速轻扫（duration 宜短；长了会进多任务或被当成页内滑）
        if (iosHomeIndicatorFlick(deviceId, iosDriver)) {
            ok = true;
            sleepQuiet(400);
        }

        // 3) 兜底：直接拉起 SpringBoard（桌面）
        try {
            if (deviceManager.launchIosAppCli(deviceId, "com.apple.springboard")) {
                log.info("iOS 已拉起 SpringBoard 回主屏: deviceId={}", deviceId);
                ok = true;
            }
        } catch (Exception e) {
            log.debug("拉起 SpringBoard 失败: {}", e.getMessage());
        }
        return ok;
    }

    /**
     * Face ID / 全面屏回桌面手势：底边中点快速上滑到约 35% 屏高。
     * 必须竖向（x 不变），禁止左右分量，否则看起来像「从左往右滑」。
     */
    private boolean iosHomeIndicatorFlick(String deviceId, IOSDriver driver) {
        int[] size = getWindowSize(deviceId);
        if (size == null || size.length < 2 || size[0] <= 0 || size[1] <= 0) {
            return false;
        }
        int w = size[0];
        int h = size[1];
        // 竖屏为准；若当前是横屏窗口，仍沿「短边」做底部上滑更接近 Home Indicator
        int x = w / 2;
        int fromY = Math.max(h - 3, (int) (h * 0.98));
        int toY = (int) (h * 0.35);

        // 短促 0.12s：回桌面；过长会像拖内容或进 App Switcher
        Map<String, Object> args = new HashMap<>();
        args.put("duration", 0.12);
        args.put("fromX", x);
        args.put("fromY", fromY);
        args.put("toX", x);
        args.put("toY", toY);
        try {
            driver.executeScript("mobile: dragFromToForDuration", args);
            log.info("iOS 底部上滑回主屏: deviceId={} ({},{})->({},{}) dur=0.12s",
                    deviceId, x, fromY, x, toY);
            return true;
        } catch (Exception e) {
            log.warn("iOS dragFromToForDuration 回主屏失败，试 W3C 轻扫: {}", e.getMessage());
        }
        // W3C 快速 flick，同样保持 x 不变
        return swipeViaW3c(driver, x, fromY, x, toY, 120);
    }

    /** 建 Appium 会话但不 force-stop，用于热启动挂接已在跑的应用。 */
    private boolean ensureAndroidSessionWithoutForceStop(String deviceId, String packageName) {
        try {
            closeSession(deviceId);
            URL url = new URL(serverUrl);
            UiAutomator2Options opts = buildAndroidOptions(deviceId, packageName);
            AndroidDriver driver = new AndroidDriver(url, opts);
            applyAndroidUiStabilitySettings(driver);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWaitSeconds));
            sessions.put(deviceId, driver);
            log.info("已挂接 Android 会话（未 force-stop）: deviceId={}, package={}", deviceId, packageName);
            return true;
        } catch (Exception e) {
            log.error("挂接 Android 会话失败: deviceId={}, package={}", deviceId, packageName, e);
            return false;
        }
    }

    private boolean isAndroidProcessAlive(String deviceId, String packageName) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "pidof", packageName)
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (java.io.InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes()).trim();
            }
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && !out.isBlank() && out.chars().anyMatch(Character::isDigit);
        } catch (Exception e) {
            log.debug("pidof 失败: {}", e.getMessage());
            return false;
        }
    }

    private void pressAndroidHome(String deviceId) {
        pressAndroidHomeKey(deviceId);
    }

    /**
     * 创建 Android 会话时不指定 appPackage/appActivity，避免「Activity .MainActivity 不存在」报错。
     * 会话创建后通过 activateApp(packageName) 启动应用，由系统使用默认启动 Activity。
     */
    private UiAutomator2Options buildAndroidOptions(String deviceId, String appPackage) {
        UiAutomator2Options opts = new UiAutomator2Options();
        opts.setUdid(deviceId);
        opts.setNewCommandTimeout(Duration.ofSeconds(newCommandTimeout));
        applyAndroidParallelPorts(opts, deviceId);
        // 指针位置/开屏动画等会让 UiAutomator 一直等 idle，表现为元素「检测不到」且整段卡住
        opts.setCapability("appium:settings[waitForIdleTimeout]", 0);
        opts.setCapability("appium:settings[waitForSelectorTimeout]", 2_000);
        // 与 Appium Inspector 一致：把 PopupWindow/对话框等非焦点窗口并入 page source，否则真机点选只能「穿透」到背后页
        opts.setCapability("appium:settings[enableMultiWindows]", true);
        opts.setCapability("appium:settings[enableTopmostWindowFromActivePackage]", true);
        // 小米/MIUI 等无 WRITE_SECURE_SETTINGS 时，忽略 hidden_api_policy 写入失败，否则会话创建直接 500
        opts.setCapability("appium:ignoreHiddenApiPolicyError", true);
        // 不要 pm clear / 重装：国内应用常无 CLEAR_APP_USER_DATA，否则会话创建失败
        opts.setNoReset(true);
        opts.setFullReset(false);
        // 不设置 appPackage/appActivity，否则 Appium 会尝试在会话创建时启动指定 Activity，
        // 若应用主入口不是 .MainActivity 会报错；创建会话后通过 activateApp(packageName) 启动即可
        return opts;
    }

    /** 会话建立后再写一遍；本机 uia2 不支持 mobile: settings，改用 setSetting */
    private void applyAndroidUiStabilitySettings(AppiumDriver driver) {
        if (!(driver instanceof AndroidDriver androidDriver)) {
            return;
        }
        try {
            androidDriver.setSetting("waitForIdleTimeout", 0);
            androidDriver.setSetting("waitForSelectorTimeout", 2_000);
            applyAndroidMultiWindowSettings(androidDriver);
            log.info("已设置 Android waitForIdleTimeout=0 + multiWindows，避免动画卡住并可见弹窗层");
        } catch (Exception e) {
            log.warn("设置 Android UI settings 失败: {}", e.getMessage());
        }
    }

    /** 对齐 Inspector：可见 PopupWindow / 自定义对话框等非焦点窗口 */
    private void applyAndroidMultiWindowSettings(AndroidDriver androidDriver) {
        try {
            androidDriver.setSetting("enableMultiWindows", true);
        } catch (Exception e) {
            log.warn("enableMultiWindows 设置失败: {}", e.getMessage());
        }
        try {
            androidDriver.setSetting("enableTopmostWindowFromActivePackage", true);
        } catch (Exception e) {
            log.warn("enableTopmostWindowFromActivePackage 设置失败: {}", e.getMessage());
        }
        try {
            // 这一条是关键：UiAutomator2 默认要等界面 idle 才返回控件树，
            // 页面上有常驻动画（温馨提示页的渐变、加载转圈）时永远等不到，表现为「卡死」。
            // 设 0 就是不等——采集控件树本来也不需要界面停稳。
            // 当初正是这个卡死让取源退回了 adb dump，代价是丢掉多窗口里的节点。
            androidDriver.setSetting("waitForIdleTimeout", 0);
        } catch (Exception e) {
            log.warn("waitForIdleTimeout 设置失败: {}", e.getMessage());
        }
        try {
            // 不要压缩层级。压缩会把「对无障碍不重要」的节点整棵剪掉，
            // 而那里面往往就有要定位的自定义控件——这是自研工具比 Appium Inspector
            // 少认出元素的原因之一。
            androidDriver.setSetting("ignoreUnimportantViews", false);
        } catch (Exception e) {
            log.warn("ignoreUnimportantViews 设置失败: {}", e.getMessage());
        }
        // 刻意不开 allowInvisibleElements：这个采集是给「按点击坐标命中控件」用的，
        // 把 displayed=false 的节点放进来会产生命中不到的假目标，反而更难选。
    }

    /**
     * 真机点选：挂接/复用 Android 会话，不 force-stop、不重新启动应用，避免关掉当前弹窗。
     */
    public boolean attachAndroidUiSession(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return false;
        AppiumDriver existing = sessions.get(deviceId);
        // 缓存里有会话 ≠ 那个会话还活着。
        // Appium 的 newCommandTimeout（我们设的 300 秒）一到，服务端会把会话干掉，
        // 而这边的对象还在 map 里躺着——之后每条命令都会撞上
        // 「A session is either terminated or not started」，而且<b>永远不会自愈</b>，
        // 因为没有任何一处会把死掉的会话从 map 里摘出去。
        // 人的体感是「点选用着用着就废了，重启后端才好」。
        if (existing != null && !sessionAlive(existing)) {
            log.info("缓存的 Appium 会话已失效（多半是 newCommandTimeout 到了），重建: deviceId={}", deviceId);
            quitQuietly(sessions.remove(deviceId));
            existing = null;
        }
        if (existing instanceof AndroidDriver androidDriver) {
            applyAndroidMultiWindowSettings(androidDriver);
            return true;
        }
        if (existing != null) {
            return true; // 非 Android 会话已在
        }
        try {
            Device device = deviceMapper.findById(deviceId);
            if (device == null || device.getPlatform() == null
                    || !Device.usesAndroidAutomation(device.getPlatform())
                    || device.getPlatform() == Device.Platform.HARMONYOS) {
                return false;
            }
            URL url = new URL(serverUrl);
            UiAutomator2Options opts = buildAndroidOptions(deviceId, null);
            AndroidDriver driver = new AndroidDriver(url, opts);
            applyAndroidUiStabilitySettings(driver);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWaitSeconds));
            sessions.put(deviceId, driver);
            log.info("已挂接 Android 点选会话(不重启应用): deviceId={}", deviceId);
            return true;
        } catch (Exception e) {
            log.warn("挂接点选会话失败: deviceId={}, err={}", deviceId, e.getMessage());
            return false;
        }
    }

    /**
     * 点选/采集用 page source：确保 multiWindows 后 dump（与 Inspector 同源能力）。
     */
    public String getPageSourceWithMultiWindows(String deviceId) {
        if (!attachAndroidUiSession(deviceId)) {
            return getPageSource(deviceId);
        }
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) return null;
        try {
            if (driver instanceof AndroidDriver androidDriver) {
                applyAndroidMultiWindowSettings(androidDriver);
            }
            return driver.getPageSource();
        } catch (Exception e) {
            log.warn("Appium multiWindow pageSource 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 先建「无 appPackage」的 Appium 会话（避免 MIUI 上 pm clear 权限失败），再 force-stop + 显式启动 Activity。
     */
    public boolean launchAndroidWithPackageAndRelativeActivity(String deviceId, String appPackage, String relativeActivityDotForm) {
        if (deviceId == null || deviceId.isBlank() || appPackage == null || appPackage.isBlank()) {
            return false;
        }
        String rel = relativeActivityDotForm != null && !relativeActivityDotForm.isBlank() ? relativeActivityDotForm.trim() : ".LauncherActivity";
        if (!rel.startsWith(".")) {
            rel = "." + rel;
        }
        try {
            closeSession(deviceId);
            Device device = deviceMapper.findById(deviceId);
            if (device == null || device.getPlatform() == null) {
                log.warn("设备不存在或平台未知: deviceId={}", deviceId);
                return false;
            }
            if (!Device.isAndroidOrPogAndroid(device.getPlatform()) || device.getPlatform() == Device.Platform.HARMONYOS) {
                log.warn("launchAndroidWithPackageAndRelativeActivity 仅支持 Android/Pog-Android: {}", device.getPlatform());
                return false;
            }
            forceStopAndroidApp(deviceId, appPackage);
            URL url = new URL(serverUrl);
            // 会话不带 appPackage/appActivity，避免 Appium 执行 pm clear
            UiAutomator2Options opts = buildAndroidOptions(deviceId, appPackage);
            AndroidDriver driver = new AndroidDriver(url, opts);
            applyAndroidUiStabilitySettings(driver);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWaitSeconds));
            sessions.put(deviceId, driver);

            boolean started = startAndroidActivity(deviceId, driver, appPackage, rel);
            if (!started) {
                closeSession(deviceId);
                return false;
            }
            log.info("Appium 会话已创建并启动: deviceId={}, package={}, activity={}", deviceId, appPackage, rel);
            return true;
        } catch (MalformedURLException e) {
            log.error("Appium Server URL 无效: {}", serverUrl, e);
            return false;
        } catch (Exception e) {
            log.error("创建 Appium 会话失败(显式 Activity): deviceId={}, package={}", deviceId, appPackage, e);
            closeSession(deviceId);
            return false;
        }
    }

    /** 优先 mobile: startActivity，失败则 ADB am start */
    private boolean startAndroidActivity(String deviceId, AndroidDriver driver, String appPackage, String relativeActivity) {
        String component = appPackage + "/" + relativeActivity;
        try {
            driver.executeScript("mobile: startActivity", Map.of(
                    "component", component,
                    "package", appPackage,
                    "activity", relativeActivity
            ));
            log.info("mobile: startActivity 成功: {}", component);
            return true;
        } catch (Exception e) {
            log.warn("mobile: startActivity 失败，改用 ADB: {} — {}", component, e.getMessage());
        }
        try {
            Process p = new ProcessBuilder(
                    "adb", "-s", deviceId, "shell", "am", "start", "-n", component
            ).redirectErrorStream(true).start();
            String out;
            try (java.io.InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes());
            }
            boolean ok = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok) {
                log.info("ADB am start 成功(会话已建立): {}", component);
                return true;
            }
            log.warn("ADB am start 失败: exit={} out={}", p.exitValue(), out.trim());
        } catch (Exception e) {
            log.warn("ADB am start 异常: {}", e.getMessage());
        }
        try {
            driver.activateApp(appPackage);
            log.info("activateApp 兜底成功: {}", appPackage);
            return true;
        } catch (Exception e) {
            log.warn("activateApp 兜底失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * WDA 的签名配置。
     * <p>
     * 免费个人账号签出来的描述文件<b>只有 7 天</b>，而且每接一台新设备都要先把它注册进 team，
     * 否则签名对这台机无效——表现就是「WDA 装完立刻被卸载」。
     * 真要稳定，用付费开发者账号或企业证书，profile 有效期变成一年。
     */
    @org.springframework.beans.factory.annotation.Value("${ios.wda.bundle-id:com.travYang.wda.Runner}")
    private String wdaBundleId;

    @org.springframework.beans.factory.annotation.Value("${ios.wda.xcode-org-id:}")
    private String xcodeOrgId;

    @org.springframework.beans.factory.annotation.Value("${ios.wda.xcode-signing-id:Apple Development}")
    private String xcodeSigningId;

    private XCUITestOptions buildIosOptions(String deviceId, String bundleId) {
        XCUITestOptions opts = new XCUITestOptions();
        opts.setUdid(deviceId);
        opts.setNewCommandTimeout(Duration.ofSeconds(newCommandTimeout));
        applyIosParallelPorts(opts, deviceId);
        opts.setBundleId(bundleId);
        // 签名参数不能写死。原来 xcodeOrgId 硬编码成某个具体的个人 team，
        // 换台执行机、换个开发者账号就全废，而现象是「WDA 装完立刻被卸载」——
        // 从这个现象反查到「签名 team 不对」中间隔了十万八千里
        opts.setUpdatedWdaBundleId(wdaBundleId);
        if (xcodeOrgId != null && !xcodeOrgId.isBlank()) {
            opts.setCapability("appium:xcodeOrgId", xcodeOrgId.trim());
        }
        opts.setCapability("appium:xcodeSigningId", xcodeSigningId);
        opts.setCapability("appium:allowProvisioningUpdates", true);
        // 尽量复用已装好的 WDA，避免每次会话都重新编译/安装到手机上
        opts.setUsePrebuiltWda(true);
        opts.setUseNewWDA(false);
        opts.setSkipLogCapture(true);
        opts.setWdaLaunchTimeout(Duration.ofSeconds(120));
        return opts;
    }

    /**
     * 挂接 iOS UI 会话（用于 App Store 点「获取」）。
     * 先确保本机 WDA+iproxy 就绪并设置 webDriverAgentUrl，避免 Appium 再去重装 WDA。
     */
    public boolean attachIosUiSession(String deviceId, String bundleId) {
        return attachIosUiSession(deviceId, bundleId, false, true);
    }

    /**
     * @param forceNew true 时关闭旧会话再挂（App Store 点安装 / 启动目标 App 后必须重挂到正确 bundle）
     */
    public boolean attachIosUiSession(String deviceId, String bundleId, boolean forceNew) {
        return attachIosUiSession(deviceId, bundleId, forceNew, true);
    }

    /**
     * @param activate false 时不 {@code activateApp}，避免把前台系统「安装」弹窗冲掉（蒲公英确认框场景）
     */
    public boolean attachIosUiSession(String deviceId, String bundleId, boolean forceNew, boolean activate) {
        if (deviceId == null || bundleId == null || bundleId.isBlank()) {
            return false;
        }
        String wantBundle = bundleId.trim();
        AppiumDriver existing = sessions.get(deviceId);
        if (!forceNew && existing instanceof IOSDriver iosExisting) {
            if (isIosSessionReusable(iosExisting, wantBundle)) {
                log.info("复用已有 iOS Appium 会话: deviceId={}, bundle={}", deviceId, wantBundle);
                return true;
            }
            log.warn("已有 iOS 会话不可复用（已失效或 bundle 不匹配 want={}），将强制重挂", wantBundle);
            forceNew = true;
        }
        int wdaPort = wdaLocalPortFor(deviceId);
        String wdaUrl = "http://127.0.0.1:" + wdaPort;
        if (!deviceManager.ensureIosWdaRunning(deviceId, wdaPort)) {
            log.error("无法拉起本机 WDA（test-without-building+iproxy）: deviceId={}, port={}", deviceId, wdaPort);
            return false;
        }
        try {
            closeSession(deviceId);
            XCUITestOptions opts = buildIosOptions(deviceId, wantBundle);
            opts.setForceAppLaunch(false);
            opts.setShouldTerminateApp(false);
            opts.setWebDriverAgentUrl(wdaUrl);
            log.info("挂接已运行的 WDA: {} forceNew={} activate={} bundle={}",
                    wdaUrl, forceNew, activate, wantBundle);
            URL url = new URL(serverUrl);
            IOSDriver driver = new IOSDriver(url, opts);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(Math.min(5, implicitWaitSeconds)));
            if (activate) {
                try {
                    driver.activateApp(wantBundle);
                } catch (Exception e) {
                    log.debug("activateApp({}) 忽略: {}", wantBundle, e.getMessage());
                }
            }
            sessions.put(deviceId, driver);
            log.info("iOS UI 会话已挂接（activate={}）: deviceId={}, bundle={}", activate, deviceId, wantBundle);
            return true;
        } catch (Exception e) {
            log.error("挂接 iOS UI 会话失败: deviceId={}, bundle={}", deviceId, wantBundle, e);
            return false;
        }
    }

    /** 会话仍存活且 capability 中的 bundle 与目标一致才可复用 */
    private static boolean isIosSessionReusable(IOSDriver driver, String wantBundle) {
        try {
            if (driver.getSessionId() == null) {
                return false;
            }
            // 轻量探活（勿用 getPageSource，App Store 树很大且慢）
            driver.manage().window().getSize();
            Object cap = driver.getCapabilities().getCapability("appium:bundleId");
            if (cap == null) {
                cap = driver.getCapabilities().getCapability("bundleId");
            }
            if (cap == null) {
                return false;
            }
            return wantBundle.equalsIgnoreCase(String.valueOf(cap).trim());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isHttpOk(String statusUrl) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(statusUrl).openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** 在当前会话窗口按比率点击（xRatio/yRatio ∈ 0~1） */
    public boolean clickByRatio(String deviceId, double xRatio, double yRatio) {
        int[] size = getWindowSize(deviceId);
        if (size == null || size[0] <= 0 || size[1] <= 0) {
            return false;
        }
        int x = (int) Math.round(size[0] * Math.max(0, Math.min(1, xRatio)));
        int y = (int) Math.round(size[1] * Math.max(0, Math.min(1, yRatio)));
        log.info("按比率点击: deviceId={}, ({},{}) from ratio ({},{}) size={}x{}",
                deviceId, x, y, xRatio, yRatio, size[0], size[1]);
        return click(deviceId, x, y);
    }

    @Override
    public boolean click(String deviceId, int x, int y) {
        return clickWithMethod(deviceId, x, y, "AUTO");
    }

    /**
     * 坐标点击（落点已算好）。
     * ADB=仅 adb；COORDINATE/ELEMENT=仅 Appium W3C；AUTO=Appium 失败回退 ADB。
     */
    public boolean clickWithMethod(String deviceId, int x, int y, String method) {
        String m = normalizeClickMethod(method);
        if ("ADB".equals(m)) {
            return adbInputTap(deviceId, x, y);
        }
        boolean appiumOk = clickViaAppium(deviceId, x, y);
        if (appiumOk) {
            return true;
        }
        if ("COORDINATE".equals(m) || "ELEMENT".equals(m) || "ELEMENT_THEN_COORD".equals(m)) {
            return false;
        }
        log.warn("Appium 坐标点击失败，回退 adb input tap: deviceId={}, ({},{})", deviceId, x, y);
        return adbInputTap(deviceId, x, y);
    }

    private boolean clickViaAppium(String deviceId, int x, int y) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) return false;
        // iOS：先试 mobile: tap（对 App Store 云图标更稳），再 W3C 按压
        if (driver instanceof IOSDriver) {
            try {
                driver.executeScript("mobile: tap", Map.of("x", x, "y", y));
                log.info("iOS mobile:tap 成功: deviceId={}, ({},{})", deviceId, x, y);
                return true;
            } catch (Exception e) {
                log.warn("iOS mobile:tap 失败，回退 W3C: ({},{}) {}", x, y, e.getMessage());
            }
        }
        try {
            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
            Sequence seq = new Sequence(finger, 0)
                    .addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, y))
                    .addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
                    .addAction(new Pause(finger, Duration.ofMillis(120)))
                    .addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
            driver.perform(Collections.singletonList(seq));
            log.info("Appium W3C 坐标点击成功: deviceId={}, ({},{})", deviceId, x, y);
            return true;
        } catch (Exception e) {
            log.warn("Appium 坐标点击失败: ({}, {})", x, y, e);
            return false;
        }
    }

    /** adb shell input tap —— 小米等机型上比 UiAutomator 注入更稳 */
    public boolean adbInputTap(String deviceId, int x, int y) {
        try {
            Process p = new ProcessBuilder(
                    "adb", "-s", deviceId, "shell", "input", "tap",
                    String.valueOf(x), String.valueOf(y)
            ).redirectErrorStream(true).start();
            String out;
            try (java.io.InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes()).trim();
            }
            boolean ok = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok) {
                log.info("adb input tap 成功: deviceId={}, ({},{})", deviceId, x, y);
            } else {
                log.warn("adb input tap 失败: deviceId={}, ({},{}) exit={} out={}",
                        deviceId, x, y, p.exitValue(), out);
            }
            return ok;
        } catch (Exception e) {
            log.warn("adb input tap 异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 编排 params.clickMethod 归一化：
     * AUTO | ELEMENT | COORDINATE | ADB | ELEMENT_THEN_COORD
     * 兼容旧值：APPIUM→ELEMENT，W3C→COORDINATE，BOTH/PREFER_COORD→ELEMENT_THEN_COORD
     */
    static String normalizeClickMethod(String method) {
        if (method == null || method.isBlank()) {
            return "AUTO";
        }
        String m = method.trim().toUpperCase().replace('-', '_');
        if ("ADB".equals(m) || "ADB_TAP".equals(m) || "INPUT_TAP".equals(m)) {
            return "ADB";
        }
        if ("ELEMENT".equals(m) || "APPIUM".equals(m) || "EL_CLICK".equals(m)) {
            return "ELEMENT";
        }
        if ("COORDINATE".equals(m) || "W3C".equals(m) || "CENTER".equals(m) || "COORD".equals(m)) {
            return "COORDINATE";
        }
        if ("ELEMENT_THEN_COORD".equals(m) || "BOTH".equals(m) || "PREFER_COORD".equals(m)
                || "PREFER_COORDINATES".equals(m)) {
            return "ELEMENT_THEN_COORD";
        }
        return "AUTO";
    }

    /** 当前会话窗口尺寸 [width, height]；无会话返回 null */
    public int[] getWindowSize(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) {
            return null;
        }
        try {
            org.openqa.selenium.Dimension d = driver.manage().window().getSize();
            return new int[]{d.getWidth(), d.getHeight()};
        } catch (Exception e) {
            log.warn("读取 Appium 窗口尺寸失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean clickElement(String deviceId, String selector) {
        if (sessions.get(deviceId) == null) {
            log.warn("无 Appium 会话，回退 uiautomator 点击: {}", selector);
            return automationEngine.clickElement(deviceId, resolvePlatform(deviceId), selector);
        }
        WebElement el = findElement(deviceId, selector);
        if (el == null) return false;
        try {
            el.click();
            return true;
        } catch (Exception e) {
            log.warn("Appium 元素点击失败，尝试坐标点击: selector={} err={}", selector, e.getMessage());
            try {
                org.openqa.selenium.Rectangle r = el.getRect();
                int x = r.getX() + Math.max(1, r.getWidth() / 2);
                int y = r.getY() + Math.max(1, r.getHeight() / 2);
                return click(deviceId, x, y);
            } catch (Exception e2) {
                log.warn("坐标点击也失败，尝试 adb: {}", e2.getMessage());
                try {
                    org.openqa.selenium.Rectangle r = el.getRect();
                    int x = r.getX() + Math.max(1, r.getWidth() / 2);
                    int y = r.getY() + Math.max(1, r.getHeight() / 2);
                    return adbInputTap(deviceId, x, y);
                } catch (Exception e3) {
                    return false;
                }
            }
        }
    }

    /**
     * 点击元素；默认 AUTO。
     * 编排可指定 clickMethod，见 {@link #normalizeClickMethod(String)}。
     */
    public boolean clickElementPreferCoordinates(String deviceId, String selector) {
        return clickElementPreferCoordinates(deviceId, selector, "AUTO", null);
    }

    /**
     * @param clickMethod AUTO | ELEMENT | COORDINATE | ADB | ELEMENT_THEN_COORD
     */
    public boolean clickElementPreferCoordinates(String deviceId, String selector, String clickMethod) {
        return clickElementPreferCoordinates(deviceId, selector, clickMethod, null);
    }

    /**
     * @param clickMethod AUTO | ELEMENT | COORDINATE | ADB | ELEMENT_THEN_COORD
     * @param elementKey  元素库 nameEn（如 CHECK_TO_AGREE）；勾选识别必须靠它，不能只看 locator 串
     */
    public boolean clickElementPreferCoordinates(String deviceId, String selector, String clickMethod,
                                                 String elementKey) {
        return clickElementPreferCoordinates(deviceId, selector, clickMethod, elementKey, 50, 50);
    }

    /**
     * @param offsetXPercent 元素宽度百分比，50=中心；H5 合并栏点「最新」大约 90
     * @param offsetYPercent 元素高度百分比，50=中心
     */
    public boolean clickElementPreferCoordinates(String deviceId, String selector, String clickMethod,
                                                 String elementKey, int offsetXPercent, int offsetYPercent) {
        String method = normalizeClickMethod(clickMethod);
        int[] coord = parseCoordinateSelector(selector);
        if (coord != null) {
            boolean ok = switch (method) {
                case "ADB" -> adbInputTap(deviceId, coord[0], coord[1]);
                case "COORDINATE", "ELEMENT", "ELEMENT_THEN_COORD" ->
                        clickWithMethod(deviceId, coord[0], coord[1], "COORDINATE")
                                || adbInputTap(deviceId, coord[0], coord[1]);
                default -> clickWithMethod(deviceId, coord[0], coord[1], "AUTO")
                        || adbInputTap(deviceId, coord[0], coord[1]);
            };
            log.info("clickElementPreferCoordinates: coordinate tap ({},{}) method={} ok={}",
                    coord[0], coord[1], method, ok);
            return ok;
        }
        if (sessions.get(deviceId) == null) {
            log.warn("无 Appium 会话，回退 uiautomator/ADB 点击: method={}, selector={}", method, selector);
            return automationEngine.clickElement(deviceId, resolvePlatform(deviceId), selector);
        }
        WebElement el = findElementWithTimeout(deviceId, selector, 3);
        if (el == null) {
            return false;
        }
        org.openqa.selenium.Rectangle rect;
        int x;
        int y;
        try {
            rect = el.getRect();
            int[] tap = ClickTapPoint.of(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(),
                    offsetXPercent, offsetYPercent);
            x = tap[0];
            y = tap[1];
        } catch (Exception e) {
            log.warn("读取元素坐标失败: {}", e.getMessage());
            if ("ADB".equals(method) || "COORDINATE".equals(method)) {
                return false;
            }
            return tryElementClickOnly(el, selector);
        }

        boolean ios = sessions.get(deviceId) instanceof IOSDriver;
        boolean customOffset = ClickTapPoint.isCustom(offsetXPercent, offsetYPercent);
        boolean toggleLike = !customOffset && looksLikeIosToggleOrCheckbox(el, selector, elementKey);
        // 勾选框若误选「补点」仍会连点两次 → 降级为 AUTO 单次路径
        if (toggleLike && "ELEMENT_THEN_COORD".equals(method)) {
            log.warn("勾选/开关勿用 ELEMENT_THEN_COORD，已降级为单次: elementKey={} selector={}",
                    elementKey, selector);
            method = "AUTO";
        }
        boolean ok;
        if (customOffset) {
            // element.click 永远打中心；偏移必须走坐标/ADB
            ok = switch (method) {
                case "ADB" -> adbInputTap(deviceId, x, y);
                default -> clickWithMethod(deviceId, x, y, "COORDINATE")
                        || adbInputTap(deviceId, x, y);
            };
            log.info("clickElementPreferCoordinates: offset tap ({},{}) x%={} y%={} method={} elementKey={} selector={} ok={}",
                    x, y, offsetXPercent, offsetYPercent, method, elementKey, selector, ok);
            return ok;
        }
        switch (method) {
            case "ADB" -> ok = adbInputTap(deviceId, x, y);
            case "COORDINATE" -> ok = clickWithMethod(deviceId, x, y, "COORDINATE");
            case "ELEMENT" -> ok = tryElementClickOnly(el, selector);
            case "ELEMENT_THEN_COORD" -> {
                // 先点再补坐标：适合 iOS StaticText；Android 抽屉会「开又关」，勿滥用
                try {
                    el.click();
                } catch (Exception ignored) {
                }
                ok = clickWithMethod(deviceId, x, y, "COORDINATE");
            }
            default -> {
                // AUTO：
                // - Android：元素 → 坐标 → ADB，只成功一种
                // - 勾选/开关：只点一次（避免勾上又勾掉）；宽控件点左侧圆点
                // - iOS 普通按钮（如「点击登录」）：元素 + 坐标补点
                if (toggleLike) {
                    ok = clickToggleOnce(deviceId, el, selector, rect, ios);
                    log.info("AUTO 勾选/开关路径（单次） elementKey={} selector={}", elementKey, selector);
                } else if (ios) {
                    try {
                        el.click();
                        log.info("iOS AUTO 元素 click 已执行，继续坐标补点: {}", selector);
                    } catch (Exception e) {
                        log.warn("iOS AUTO 元素 click 异常，仍尝试坐标: {} — {}", selector, e.getMessage());
                    }
                    ok = clickWithMethod(deviceId, x, y, "COORDINATE");
                    if (!ok) {
                        ok = tryElementClickOnly(el, selector);
                    }
                } else if (tryElementClickOnly(el, selector)) {
                    ok = true;
                } else if (clickWithMethod(deviceId, x, y, "COORDINATE")) {
                    ok = true;
                } else {
                    ok = adbInputTap(deviceId, x, y);
                }
            }
        }
        log.info("clickElementPreferCoordinates: method={} ios={} toggleLike={} elementKey={} selector={} center=({},{}) ok={}",
                method, ios, toggleLike, elementKey, selector, x, y, ok);
        return ok;
    }

    /** 勾选/开关只点一次：优先点矩形左侧（圆点），避免点到协议链接文字。 */
    private boolean clickToggleOnce(String deviceId, WebElement el, String selector,
                                    org.openqa.selenium.Rectangle rect, boolean ios) {
        if (rect != null && rect.getWidth() > 0) {
            int tapX = rect.getX() + Math.max(8, Math.min(24, Math.max(1, rect.getWidth() / 8)));
            int tapY = rect.getY() + Math.max(1, rect.getHeight() / 2);
            if (clickWithMethod(deviceId, tapX, tapY, "COORDINATE")) {
                log.info("勾选单次坐标点击 (left) ({},{}) selector={}", tapX, tapY, selector);
                return true;
            }
            if (!ios && adbInputTap(deviceId, tapX, tapY)) {
                return true;
            }
        }
        return tryElementClickOnly(el, selector);
    }

    private boolean tryElementClickOnly(WebElement el, String selector) {
        try {
            el.click();
            log.info("element.click 成功: {}", selector);
            return true;
        } catch (Exception e) {
            log.warn("element.click 失败: {} — {}", selector, e.getMessage());
            return false;
        }
    }

    /**
     * 协议勾选/开关：AUTO 只能点一次。
     * 必须同时看 elementKey（nameEn），因为传入的 selector 往往是 xpath/id，不含 CHECK_TO_AGREE。
     */
    private static boolean looksLikeIosToggleOrCheckbox(WebElement el, String selector, String elementKey) {
        StringBuilder sb = new StringBuilder();
        if (elementKey != null) {
            sb.append(elementKey).append(' ');
        }
        if (selector != null) {
            sb.append(selector).append(' ');
        }
        try {
            for (String attr : List.of("type", "name", "label", "value")) {
                String v = el.getAttribute(attr);
                if (v != null && !v.isBlank()) {
                    sb.append(v).append(' ');
                }
            }
            try {
                String tag = el.getTagName();
                if (tag != null) {
                    sb.append(tag);
                }
            } catch (Exception ignored) {
                // ignore
            }
        } catch (Exception ignored) {
            // ignore
        }
        String s = sb.toString();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("switch") || lower.contains("checkbox") || lower.contains("toggle")
                || lower.contains("check_to_agree") || lower.contains("policy_checkbox")
                || lower.contains("login_policy") || lower.contains("protocol_selected")
                || (lower.contains("privacy") && (lower.contains("check") || lower.contains("agree")))) {
            return true;
        }
        // nameEn：CHECK_TO_AGREE / agree_checkbox / policy_check —— 勿用过宽的 agree/policy 误伤链接/按钮
        if (elementKey != null) {
            String ek = elementKey.toLowerCase(Locale.ROOT).replace('-', '_');
            if (ek.contains("check_to_agree") || ek.contains("checkbox") || ek.contains("toggle")
                    || ek.contains("switch") || ek.contains("login_policy")
                    || ek.contains("protocol_selected") || ek.endsWith("_check") || ek.startsWith("check_")
                    || (ek.contains("agree") && (ek.contains("check") || ek.contains("box")))
                    || (ek.contains("policy") && ek.contains("check"))) {
                return true;
            }
        }
        return s.contains("协议") || s.contains("勾选") || s.contains("已同意")
                || s.contains("我已阅读") || s.contains("同意并继续") || s.contains("同意协议");
    }

    /** iOS/Android deep link（用于打开 App Store / market URI） */
    public boolean openDeepLink(String deviceId, String url, String bundleOrPackage) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null || url == null || url.isBlank()) {
            return false;
        }
        try {
            if (driver instanceof IOSDriver) {
                driver.executeScript("mobile: deepLink", Map.of(
                        "url", url,
                        "bundleId", bundleOrPackage != null ? bundleOrPackage : "com.apple.AppStore"
                ));
            } else {
                driver.executeScript("mobile: deepLink", Map.of(
                        "url", url,
                        "package", bundleOrPackage != null ? bundleOrPackage : "com.android.vending"
                ));
            }
            log.info("deepLink 已打开: deviceId={}, url={}", deviceId, url);
            return true;
        } catch (Exception e) {
            log.warn("deepLink 失败: {}", e.getMessage());
            return false;
        }
    }

    /** 用 Safari 打开 URL（App Store 搜索页兜底） */
    public boolean openUrlInSafari(String deviceId, String url) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null || url == null || url.isBlank()) {
            return false;
        }
        try {
            if (driver instanceof IOSDriver iosDriver) {
                try {
                    iosDriver.activateApp("com.apple.mobilesafari");
                } catch (Exception ignored) {
                    // ignore
                }
                iosDriver.get(url);
                return true;
            }
            driver.get(url);
            return true;
        } catch (Exception e) {
            log.warn("Safari/浏览器打开 URL 失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean swipe(String deviceId, int x1, int y1, int x2, int y2, int duration) {
        AppiumDriver driver = sessions.get(deviceId);
        int durMs = Math.max(100, duration);
        int travel = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));

        if (driver instanceof IOSDriver) {
            try {
                double durSec = Math.max(0.15, Math.min(3.0, durMs / 1000.0));
                driver.executeScript("mobile: dragFromToForDuration", Map.of(
                        "duration", durSec,
                        "fromX", x1,
                        "fromY", y1,
                        "toX", x2,
                        "toY", y2
                ));
                log.info("iOS dragFromToForDuration 成功: ({},{})->({},{}) dur={}s",
                        x1, y1, x2, y2, durSec);
                return true;
            } catch (Exception e) {
                log.warn("iOS dragFromToForDuration 失败，回退 W3C: {}", e.getMessage());
            }
            return swipeViaW3c(driver, x1, y1, x2, y2, durMs);
        }

        if (driver == null) {
            log.warn("无 Appium 会话，小幅滑动走 ADB: ({},{})->({},{})", x1, y1, x2, y2);
            return swipeViaAdb(deviceId, x1, y1, x2, y2, shortTravelDur(travel, durMs));
        }

        // 短距离：swipeGesture 常空滑成功；W3C 在会话抖时会抛，必须再回退 ADB，
        // 否则关键字只报「滑动失败: up #1」，页面上也看不见原因。
        if (travel < 180) {
            int flickMs = shortTravelDur(travel, durMs);
            if (swipeViaW3c(driver, x1, y1, x2, y2, flickMs)) {
                return true;
            }
            log.warn("W3C 短滑失败，回退 ADB: ({},{})->({},{})", x1, y1, x2, y2);
            return swipeViaAdb(deviceId, x1, y1, x2, y2, flickMs);
        }

        try {
            int left, top, width, height;
            String direction;
            if (x1 == x2) {
                direction = y2 < y1 ? "up" : "down";
                int minY = Math.min(y1, y2);
                int maxY = Math.max(y1, y2);
                left = Math.max(0, x1 - 50);
                top = minY;
                width = 100;
                height = Math.max(50, maxY - minY);
            } else if (y1 == y2) {
                direction = x2 > x1 ? "right" : "left";
                int minX = Math.min(x1, x2);
                int maxX = Math.max(x1, x2);
                left = minX;
                top = Math.max(0, y1 - 50);
                width = Math.max(50, maxX - minX);
                height = 100;
            } else if (Math.abs(y2 - y1) >= Math.abs(x2 - x1)) {
                direction = y2 < y1 ? "up" : "down";
                left = Math.max(0, Math.min(x1, x2) - 50);
                top = Math.min(y1, y2);
                width = 100;
                height = Math.max(50, Math.abs(y2 - y1));
            } else {
                direction = x2 > x1 ? "right" : "left";
                left = Math.min(x1, x2);
                top = Math.max(0, Math.min(y1, y2) - 50);
                width = Math.max(50, Math.abs(x2 - x1));
                height = 100;
            }
            driver.executeScript("mobile: swipeGesture", Map.of(
                    "left", left,
                    "top", top,
                    "width", width,
                    "height", height,
                    "direction", direction,
                    "percent", 1.0
            ));
            log.info("Android swipeGesture 成功: {} ({},{})->({},{})", direction, x1, y1, x2, y2);
            return true;
        } catch (Exception e) {
            log.warn("Android swipeGesture 失败，回退 W3C: {}", e.getMessage());
            if (swipeViaW3c(driver, x1, y1, x2, y2, durMs)) {
                return true;
            }
            return swipeViaAdb(deviceId, x1, y1, x2, y2, durMs);
        }
    }

    private static int shortTravelDur(int travel, int durationMs) {
        return travel < 180 ? Math.min(Math.max(80, durationMs), 220) : durationMs;
    }

    /** 不依赖 Appium 会话，设备还在就能滑 */
    private boolean swipeViaAdb(String deviceId, int x1, int y1, int x2, int y2, int durationMs) {
        try {
            Process p = new ProcessBuilder(
                    "adb", "-s", deviceId, "shell", "input", "swipe",
                    String.valueOf(x1), String.valueOf(y1),
                    String.valueOf(x2), String.valueOf(y2),
                    String.valueOf(Math.max(80, durationMs)))
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("ADB input swipe 超时: ({},{})->({},{})", x1, y1, x2, y2);
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("ADB input swipe 退出码 {}: ({},{})->({},{})", p.exitValue(), x1, y1, x2, y2);
                return false;
            }
            log.info("ADB input swipe 成功: ({},{})->({},{})", x1, y1, x2, y2);
            return true;
        } catch (Exception e) {
            log.warn("ADB input swipe 失败 ({},{})->({},{}): {}", x1, y1, x2, y2, e.getMessage());
            return false;
        }
    }

    /** 双端通用：W3C 手指拖动 */
    private boolean swipeViaW3c(AppiumDriver driver, int x1, int y1, int x2, int y2, int durationMs) {
        try {
            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
            Sequence seq = new Sequence(finger, 0)
                    .addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x1, y1))
                    .addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
                    .addAction(new Pause(finger, Duration.ofMillis(
                            Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) < 180 ? 20 : 80)))
                    .addAction(finger.createPointerMove(
                            Duration.ofMillis(Math.max(80, durationMs)),
                            PointerInput.Origin.viewport(), x2, y2))
                    .addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
            driver.perform(Collections.singletonList(seq));
            log.info("W3C 滑动成功: ({},{})->({},{})", x1, y1, x2, y2);
            return true;
        } catch (Exception e) {
            log.warn("W3C 滑动失败 ({},{})->({},{}): {}", x1, y1, x2, y2, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean inputText(String deviceId, String text) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) return false;
        try {
            driver.executeScript("mobile: type", Map.of("text", text));
            return true;
        } catch (Exception e) {
            log.warn("Appium 输入文本失败", e);
            return false;
        }
    }

    @Override
    public boolean inputTextToElement(String deviceId, String selector, String text) {
        WebElement el = findElement(deviceId, selector);
        if (el == null) return false;
        String raw = text != null ? text : "";
        try {
            try {
                el.click();
            } catch (Exception ignored) {
            }
            // 评论入口经常是 TextView 占位（「友好评论，友善社区」）。
            // sendKeys 打在 TextView 上不抛异常、也不进输入框，流程会绿、字却没打上去。
            WebElement target = isEditable(el) ? el : findFocusedEditText(deviceId);
            if (target == null) {
                log.warn("输入目标不可编辑且找不到 EditText: selector={}", selector);
                return typeViaMobileType(deviceId, raw);
            }
            if (target != el) {
                try {
                    target.click();
                } catch (Exception ignored) {
                }
            }
            try {
                target.clear();
            } catch (Exception ignored) {
            }
            target.sendKeys(raw);
            if (!raw.isEmpty() && !textLanded(target, raw)) {
                log.warn("sendKeys 后输入框仍空，改走 mobile: type: selector={}", selector);
                return typeViaMobileType(deviceId, raw);
            }
            return true;
        } catch (Exception e) {
            log.warn("Appium 元素输入失败，尝试焦点 EditText: selector={} {}", selector, e.getMessage());
            return typeIntoFocusedEditText(deviceId, raw);
        }
    }

    /** TextView / StaticText 占位不算可输入，真正能打字的是 EditText / TextField */
    static boolean isEditable(WebElement el) {
        if (el == null) {
            return false;
        }
        String cls = firstAttr(el, "class", "className");
        if (cls.isEmpty()) {
            return false;
        }
        String lower = cls.toLowerCase(Locale.ROOT);
        return lower.contains("edittext")
                || lower.contains("textfield")
                || lower.contains("xcuielementtypetextview");
    }

    private WebElement findFocusedEditText(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) {
            return null;
        }
        try {
            Thread.sleep(400);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        try {
            return driver.findElement(AppiumBy.xpath("//android.widget.EditText[@focused='true']"));
        } catch (Exception ignored) {
        }
        try {
            return driver.findElement(AppiumBy.className("android.widget.EditText"));
        } catch (Exception ignored) {
        }
        try {
            return driver.findElement(AppiumBy.className("XCUIElementTypeTextField"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean typeIntoFocusedEditText(String deviceId, String text) {
        try {
            WebElement focused = findFocusedEditText(deviceId);
            if (focused == null) {
                return typeViaMobileType(deviceId, text);
            }
            focused.click();
            try {
                focused.clear();
            } catch (Exception ignored) {
            }
            focused.sendKeys(text);
            if (!text.isEmpty() && !textLanded(focused, text)) {
                return typeViaMobileType(deviceId, text);
            }
            return true;
        } catch (Exception e2) {
            log.warn("Appium 焦点 EditText 输入仍失败: {}", e2.getMessage());
            return typeViaMobileType(deviceId, text);
        }
    }

    private boolean typeViaMobileType(String deviceId, String text) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) {
            return false;
        }
        try {
            driver.executeScript("mobile: type", Map.of("text", text != null ? text : ""));
            return true;
        } catch (Exception e) {
            log.warn("mobile: type 输入失败: {}", e.getMessage());
            return false;
        }
    }

    static boolean textLanded(WebElement el, String expected) {
        if (el == null || expected == null || expected.isEmpty()) {
            return true;
        }
        String got = firstAttr(el, "text", "value");
        if (got.isEmpty()) {
            try {
                got = el.getText() != null ? el.getText() : "";
            } catch (Exception ignored) {
                return false;
            }
        }
        return got.contains(expected);
    }

    private static String firstAttr(WebElement el, String... names) {
        for (String name : names) {
            try {
                String v = el.getAttribute(name);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    @Override
    public boolean inputTextToFocusedEditText(String deviceId, String selector, String text) {
        String value = text != null ? text : "";
        if (selector != null && !selector.isBlank()) {
            // 点不开也继续：半屏可能已经起来，占位节点已从树上消失
            clickElement(deviceId, selector);
            sleepQuiet(450);
        }
        WebElement field = findFocusedEditField(deviceId);
        if (field != null) {
            try {
                field.click();
            } catch (Exception ignored) {
            }
            sleepQuiet(180);
        }
        // 先走 IME。hint 还在 text 上时 clear()+sendKeys 会把框搞挂
        if (inputText(deviceId, value)) {
            return true;
        }
        if (field != null) {
            try {
                field.sendKeys(value);
                return true;
            } catch (Exception e) {
                log.warn("焦点输入框 sendKeys 仍失败: {}", e.getMessage());
            }
        }
        return typeAsciiViaAdb(deviceId, value);
    }

    /** 当前焦点的原生输入框；找不到再退到页面上第一个 EditText / TextField */
    private WebElement findFocusedEditField(String deviceId) {
        Device device = deviceMapper.findById(deviceId);
        boolean ios = device != null && Device.usesIosAutomation(device.getPlatform());
        List<String> xpaths = ios
                ? List.of(
                "//XCUIElementTypeTextView[@focused='true' or @focused='1']",
                "//XCUIElementTypeTextField[@focused='true' or @focused='1']",
                "//XCUIElementTypeSearchField[@focused='true' or @focused='1']",
                "//XCUIElementTypeTextView",
                "//XCUIElementTypeTextField")
                : List.of(
                "//android.widget.EditText[@focused='true']",
                "//android.widget.MultiAutoCompleteTextView[@focused='true']",
                "//android.widget.EditText");
        for (String xp : xpaths) {
            WebElement el = findElementWithTimeout(deviceId, "xpath:" + xp, 2);
            if (el != null) {
                return el;
            }
        }
        return null;
    }

    /** adb input text 只吃 ASCII；中文评论走这条等于白打 */
    private boolean typeAsciiViaAdb(String deviceId, String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        if (!text.chars().allMatch(c -> c < 127)) {
            return false;
        }
        Device device = deviceMapper.findById(deviceId);
        if (device == null || !Device.isAndroidOrPogAndroid(device.getPlatform())) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "input", "text", text)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("adb input text 失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String takeScreenshot(String deviceId, String savePath) {
        Device device = deviceMapper.findById(deviceId);
        // Android 统一走 ADB 截图，更稳定、适合连续多张
        if (device != null && Device.isAndroidOrPogAndroid(device.getPlatform())) {
            try {
                return automationEngine.takeScreenshot(deviceId, device.getPlatform(), savePath);
            } catch (Exception e) {
                log.warn("ADB 截图失败(Android): deviceId={}", deviceId, e);
                return null;
            }
        }
        AppiumDriver driver = sessions.get(deviceId);
        if (driver != null) {
            try {
                byte[] bytes = driver.getScreenshotAs(OutputType.BYTES);
                Path path = Path.of(savePath);
                Files.createDirectories(path.getParent());
                Files.write(path, bytes);
                return savePath;
            } catch (Exception e) {
                log.warn("Appium 截图失败(iOS)，尝试 WDA HTTP: path={} {}", savePath, e.getMessage());
            }
        }
        // CLI 启动后常无 Appium 会话；idevicescreenshot 在未挂 Developer Disk 时也常失败 → 直接打 WDA /screenshot
        String viaWda = takeScreenshotViaWdaHttp(deviceId, savePath);
        if (viaWda != null) {
            return viaWda;
        }
        try {
            return automationEngine.takeScreenshot(deviceId,
                    device != null ? device.getPlatform() : Device.Platform.IOS, savePath);
        } catch (Exception e) {
            log.warn("iOS 截图全部失败: deviceId={} {}", deviceId, e.getMessage());
            return null;
        }
    }

    /**
     * 不经 Appium 会话：确保 WDA+iproxy 后 GET /screenshot，解码 PNG。
     * 线上回归 CLI 启动后截图依赖此路径（idevicescreenshot 常不可用）。
     */
    public String takeScreenshotViaWdaHttp(String deviceId, String savePath) {
        if (deviceId == null || savePath == null || savePath.isBlank()) {
            return null;
        }
        int wdaPort = wdaLocalPortFor(deviceId);
        if (!deviceManager.ensureIosWdaRunning(deviceId, wdaPort)) {
            log.warn("WDA HTTP 截图失败：WDA 未就绪 deviceId={} port={}", deviceId, wdaPort);
            return null;
        }
        String url = "http://127.0.0.1:" + wdaPort + "/screenshot";
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(20000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                log.warn("WDA /screenshot HTTP {}: deviceId={} port={}", code, deviceId, wdaPort);
                c.disconnect();
                return null;
            }
            String body;
            try (var in = c.getInputStream()) {
                body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            c.disconnect();
            String b64 = extractWdaScreenshotBase64(body);
            if (b64 == null || b64.isBlank()) {
                log.warn("WDA /screenshot 无 base64 value: deviceId={}", deviceId);
                return null;
            }
            byte[] png = java.util.Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
            if (png.length < 100) {
                log.warn("WDA 截图过短: {} bytes", png.length);
                return null;
            }
            Path path = Path.of(savePath);
            Files.createDirectories(path.getParent());
            Files.write(path, png);
            log.info("WDA HTTP 截图成功: deviceId={} port={} bytes={} path={}",
                    deviceId, wdaPort, png.length, savePath);
            return savePath;
        } catch (Exception e) {
            log.warn("WDA HTTP 截图异常: deviceId={} {}", deviceId, e.getMessage());
            return null;
        }
    }

    private static String extractWdaScreenshotBase64(String json) {
        if (json == null) return null;
        // {"value":"<base64>"} 或带空格的 pretty JSON
        int key = json.indexOf("\"value\"");
        if (key < 0) return null;
        int colon = json.indexOf(':', key);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        // base64 很长且不含引号；若中间被截断则从 q1+1 扫到最后一个合适的结束
        if (q2 < 0) return null;
        // 正常情况 value 是一整段 base64，结束引号在字符串末尾附近
        // 若 JSON 还有其它字段，找 value 字符串的结束：从 q1+1 起找未转义的 "
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') {
                // 结束
                break;
            }
            if (ch == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(++i));
                continue;
            }
            sb.append(ch);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 这台设备上是否有活跃的 Appium 会话。
     * <p>
     * 关键用途：Appium 持有 UiAutomator instrumentation 期间，
     * {@code adb shell uiautomator dump} 会被顶住——实测卡 127 秒后返回空控件树。
     * 有会话时控件树必须走会话，不能走 adb dump。
     */
    public boolean hasSession(String deviceId) {
        return deviceId != null && sessions.get(deviceId) != null;
    }

    /**
     * 最近一次取控件树失败的原因。
     * <p>
     * 原来只有一句 {@code log.warn} 然后返回 null，上层看到的是「驱动返回空控件树」——
     * 而「取不到」和「屏幕上真没有」表现完全一样，弹窗探测因此静默失效。实测被坑过一次：
     * 温馨提示明明在屏幕上，日志却说「未发现任何已知弹窗」。
     */
    private final java.util.concurrent.atomic.AtomicReference<String> lastPageSourceFailure =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 供上层写进执行日志：null 表示最近一次是好的 */
    public String lastPageSourceFailure() {
        return lastPageSourceFailure.get();
    }

    /**
     * 最近一次失败是否像「机上 UiAutomator2/WDA 已挂」——截图还能拍，但控件树/找元素全废。
     * 实测 Vivo OriginOS 等机型在隐私弹窗页会反复复现，应提示换机而不是改流程定位。
     */
    public boolean isUiAutomationLikelyDead() {
        return looksLikeUiAutomationDead(lastPageSourceFailure.get());
    }

    /**
     * 给人看的换机提示（可拼进任务执行日志）。
     * 技术原因可空；有则附在后面方便排障。
     */
    public String deviceMismatchHint(String deviceId, String technicalReason) {
        String deviceLabel = describeDevice(deviceId);
        String base = "⚠ 设备与自动化引擎不匹配：" + deviceLabel
                + " 上 UiAutomator2/控件树不可用（截图能看见界面，但找不到/点不到元素）。"
                + " 请换用兼容机（如小米等）重跑，勿在本机反复重试同一流程。";
        if (technicalReason == null || technicalReason.isBlank()) {
            return base;
        }
        return base + " | 技术原因: " + technicalReason.trim();
    }

    public static boolean looksLikeUiAutomationDead(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String r = reason.toLowerCase();
        return r.contains("instrumentation")
                || r.contains("socket hang up")
                || r.contains("空控件树")
                || (r.contains("timeout of") && r.contains("exceeded"))
                || r.contains("cannot be proxied")
                || r.contains("uiautomator2 server")
                || r.contains("not running");
    }

    private String describeDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return "未知设备";
        }
        Device d = deviceMapper.findById(deviceId);
        if (d == null) {
            return deviceId;
        }
        String name = d.getName() != null && !d.getName().isBlank() ? d.getName().trim() : deviceId;
        String ver = d.getVersion() != null && !d.getVersion().isBlank() ? d.getVersion().trim() : "";
        String plat = d.getPlatform() != null ? d.getPlatform().name() : "";
        String extra = (plat + (ver.isEmpty() ? "" : " " + ver)).trim();
        return extra.isEmpty() ? name + "(" + deviceId + ")" : name + "/" + extra + "(" + deviceId + ")";
    }

    private void recordPageSourceFailure(String reason) {
        String enriched = looksLikeUiAutomationDead(reason)
                ? reason + "（疑似机型与 UiAutomator2 不兼容，建议换机）"
                : reason;
        lastPageSourceFailure.set(enriched);
    }

    @Override
    public String getPageSource(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver != null) {
            long startedAt = System.currentTimeMillis();
            try {
                String src = driver.getPageSource();
                if (src != null && !src.isBlank()) {
                    lastPageSourceFailure.set(null);
                    return src;
                }
                recordPageSourceFailure("Appium 会话返回空控件树（耗时 "
                        + (System.currentTimeMillis() - startedAt) + "ms）");
                return null;
            } catch (Exception e) {
                String reason = "Appium getPageSource " + e.getClass().getSimpleName() + ": "
                        + firstLineOf(e.getMessage())
                        + "（耗时 " + (System.currentTimeMillis() - startedAt) + "ms）";
                recordPageSourceFailure(reason);
                log.warn("Appium 获取页面源失败: {}", reason);
            }
        }
        // 真机点选常无 Appium 会话：直接走 WDA /source（与截图同源）
        Device device = deviceMapper.findById(deviceId);
        if (device != null && Device.usesIosAutomation(device.getPlatform())) {
            return getPageSourceViaWdaHttp(deviceId);
        }
        return null;
    }

    /**
     * 不经 Appium 会话：确保 WDA+iproxy 后 GET /source，解析 XML。
     * 真机点选 iOS 依赖此路径。
     */
    public String getPageSourceViaWdaHttp(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        int wdaPort = wdaLocalPortFor(deviceId);
        if (!deviceManager.ensureIosWdaRunning(deviceId, wdaPort)) {
            log.warn("WDA HTTP pageSource 失败：WDA 未就绪 deviceId={} port={}", deviceId, wdaPort);
            return null;
        }
        String url = "http://127.0.0.1:" + wdaPort + "/source";
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(30000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                log.warn("WDA /source HTTP {}: deviceId={} port={}", code, deviceId, wdaPort);
                c.disconnect();
                return null;
            }
            String body;
            try (var in = c.getInputStream()) {
                body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            c.disconnect();
            String xml = extractWdaSourceXml(body);
            if (xml == null || !xml.contains("<")) {
                log.warn("WDA /source 无 XML value: deviceId={}", deviceId);
                return null;
            }
            log.info("WDA HTTP pageSource 成功: deviceId={} port={} chars={}", deviceId, wdaPort, xml.length());
            return xml;
        } catch (Exception e) {
            log.warn("WDA HTTP pageSource 异常: deviceId={} {}", deviceId, e.getMessage());
            return null;
        }
    }

    /** 从 WDA /source JSON 取出 XML 字符串（{@code {"value":"<?xml ...>"}}）。 */
    private static String extractWdaSourceXml(String json) {
        if (json == null) return null;
        int key = json.indexOf("\"value\"");
        if (key < 0) return null;
        int colon = json.indexOf(':', key);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char ch = json.charAt(i++);
            if (ch == '\\' && i < json.length()) {
                char n = json.charAt(i++);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        if (i + 4 <= json.length()) {
                            try {
                                int cp = Integer.parseInt(json.substring(i, i + 4), 16);
                                sb.append((char) cp);
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('u').append(n);
                            }
                        } else {
                            sb.append('u');
                        }
                    }
                    default -> sb.append(n);
                }
                continue;
            }
            if (ch == '"') break;
            sb.append(ch);
        }
        String xml = sb.toString().trim();
        return xml.contains("<") ? xml : null;
    }

    @Override
    public boolean waitForElement(String deviceId, String selector, int timeoutSeconds) {
        if (sessions.get(deviceId) == null) {
            log.warn("无 Appium 会话，回退 uiautomator 等待元素: deviceId={}, selector={}", deviceId, selector);
            return automationEngine.waitForElement(deviceId, resolvePlatform(deviceId), selector, timeoutSeconds);
        }
        WebElement el = findElementWithTimeout(deviceId, selector, timeoutSeconds);
        return el != null;
    }

    @Override
    public boolean isElementPresent(String deviceId, String selector) {
        if (sessions.get(deviceId) == null) {
            return automationEngine.isElementPresent(deviceId, resolvePlatform(deviceId), selector);
        }
        // 存在性探测必须用短超时：启动/登录等流程会对多个弹窗做“有则点、无则跳过”，
        // 若沿用 implicitWait(默认 10s) 会对每个“不存在”的选择器傻等，前端体感像“半天无响应”。
        return findElementWithTimeout(deviceId, selector, 1) != null;
    }

    /** Appium 的异常动辄几十行堆栈，只留第一行 */
    private static String firstLineOf(String msg) {
        if (msg == null) {
            return "无异常信息";
        }
        int nl = msg.indexOf('\n');
        String s = nl > 0 ? msg.substring(0, nl) : msg;
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private Device.Platform resolvePlatform(String deviceId) {
        Device d = deviceMapper.findById(deviceId);
        return d != null && d.getPlatform() != null ? d.getPlatform() : Device.Platform.ANDROID;
    }

    @Override
    public String getElementText(String deviceId, String selector) {
        WebElement el = findElement(deviceId, selector);
        if (el == null) return null;
        try {
            String t = el.getText();
            if (t == null || t.isBlank()) t = el.getAttribute("content-desc");
            if (t == null || t.isBlank()) t = el.getAttribute("text");
            if (t == null || t.isBlank()) t = el.getAttribute("name");
            return (t != null && !t.isBlank()) ? t.trim() : null;
        } catch (Exception e) {
            log.debug("getElementText 失败: {}", e.getMessage());
            return null;
        }
    }


    @Override
    public String getElementAttribute(String deviceId, String selector, String attribute) {
        WebElement el = findElement(deviceId, selector);
        return el != null ? el.getAttribute(attribute) : null;
    }

    /**
     * 读勾选/开关状态。WebDriver 与 page source 交叉验证，避免 ImageView 误报 false 导致误点取消勾选。
     */
    public Boolean readToggleCheckedState(String deviceId, String selector, String elementKey) {
        WebElement el = findElementWithTimeout(deviceId, selector, 2);
        Boolean fromElement = readToggleFromElement(el);
        Boolean fromXml = null;
        try {
            String pageSource = getPageSource(deviceId);
            fromXml = ToggleStateReader.readCheckedFromPageSource(pageSource, selector);
            if (fromXml != null) {
                log.debug("勾选态 page source: elementKey={} selector={} checked={}",
                        elementKey, selector, fromXml);
            }
        } catch (Exception e) {
            log.debug("page source 读勾选态失败: {}", e.getMessage());
        }
        Boolean merged = ToggleStateReader.mergeElementAndPageSource(fromElement, fromXml);
        if (merged == null && Boolean.FALSE.equals(fromElement)) {
            log.info("勾选态仅 WebDriver 报 false、page source 无佐证，视为读不准（避免误点）: elementKey={}",
                    elementKey);
        }
        return merged;
    }

    private static Boolean readToggleFromElement(WebElement el) {
        if (el == null) {
            return null;
        }
        for (String attr : List.of("checked", "selected", "value")) {
            try {
                Boolean parsed = ToggleStateReader.parseToggleValue(el.getAttribute(attr));
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // 换下一个属性
            }
        }
        return null;
    }

    /**
     * 协议勾选框：从当前屏幕截图识别蓝勾（空圈 vs 蓝勾号）。
     */
    public Boolean readCheckboxVisual(String deviceId, String selector) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) {
            return null;
        }
        WebElement el = findElementWithTimeout(deviceId, selector, 2);
        if (el == null) {
            return null;
        }
        try {
            org.openqa.selenium.Rectangle rect = el.getRect();
            byte[] png = driver.getScreenshotAs(OutputType.BYTES);
            Boolean visual = CheckboxVisualReader.inferCheckedFromPng(
                    png, rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
            if (visual != null) {
                log.debug("勾选态视觉识别: selector={} checked={}", selector, visual);
            }
            return visual;
        } catch (Exception e) {
            log.debug("勾选态视觉识别失败: {}", e.getMessage());
            return null;
        }
    }

    private WebElement findElement(String deviceId, String selector) {
        return findElementWithTimeout(deviceId, selector, implicitWaitSeconds);
    }

    private WebElement findElementWithTimeout(String deviceId, String selector, int timeoutSeconds) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) return null;
        String resolved = LocatorTemplate.expandRandomIndexes(selector);
        if (!resolved.equals(selector)) {
            log.info("定位随机下标: {} → {}", selector, resolved);
        }
        By by = parseSelector(resolved);
        if (by == null) return null;
        try {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(timeoutSeconds));
            return driver.findElement(by);
        } catch (Exception e) {
            String msg = firstLineOf(e.getMessage());
            // 会话被回收掉的话，这里每次都会失败，而且表现成「元素找不到」——
            // 方向全错。摘掉死会话，下一次调用会重建，自己就好了
            dropSessionIfDead(deviceId, e);
            if (looksLikeUiAutomationDead(msg)) {
                recordPageSourceFailure("findElement: " + msg);
                log.warn("Appium 找元素失败且疑似 UiAutomator2 已挂: {}", msg);
            }
            return null;
        } finally {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWaitSeconds));
        }
    }

    /**
     * 解析 coordinate:x,y / tap:x,y / 纯 "x,y"（配合 locatorType=coordinate）。
     */
    static int[] parseCoordinateSelector(String selector) {
        if (selector == null || selector.isBlank()) return null;
        String s = selector.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("coordinate:") || lower.startsWith("coord:") || lower.startsWith("tap:")
                || lower.startsWith("point:")) {
            s = s.substring(s.indexOf(':') + 1).trim();
        } else if (!s.matches("^-?\\d+\\s*,\\s*-?\\d+$")) {
            return null;
        }
        String[] parts = s.split(",");
        if (parts.length != 2) return null;
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            if (x < 0 || y < 0) return null;
            return new int[]{x, y};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析选择器字符串为 Appium By。
     * 格式：selectorType:value。支持 id, accessibilityId, xpath, name, className。
     */
    private By parseSelector(String selector) {
        if (selector == null || !selector.contains(":")) {
            return By.id(selector != null ? selector : "");
        }
        int colon = selector.indexOf(':');
        String type = selector.substring(0, colon).trim().toLowerCase();
        if ("coordinate".equals(type) || "coord".equals(type) || "tap".equals(type) || "point".equals(type)) {
            return null;
        }
        String value = selector.substring(colon + 1).trim();
        return switch (type) {
            case "id" -> AppiumBy.id(value);
            case "accessibilityid", "accessibility_id", "accessibility id" -> AppiumBy.accessibilityId(value);
            case "xpath" -> By.xpath(value);
            case "name" -> AppiumBy.name(value);
            case "classname", "class" -> By.className(value);
            case "ios predicate", "iospredicate", "-ios predicate string" -> AppiumBy.iOSNsPredicateString(value);
            case "ios class chain", "iosclasschain", "-ios class chain" -> AppiumBy.iOSClassChain(value);
            default -> AppiumBy.id(value);
        };
    }

    /**
     * 点击系统弹窗按钮。注意：{@code mobile: alert} 有时返回成功但实际未点掉，
     * 必须以元素点击 / 坐标点击为准，并做消失校验。
     */
    public boolean dismissIosSystemAlertButton(String deviceId, String buttonLabel) {
        if (deviceId == null || buttonLabel == null || buttonLabel.isBlank()) {
            return false;
        }
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) {
            return false;
        }
        String label = buttonLabel.trim();
        try {
            driver.executeScript("mobile: alert", Map.of("action", "accept", "buttonLabel", label));
            log.info("iOS mobile:alert 已调用「{}」（需再校验是否消失）", label);
        } catch (Exception e) {
            log.debug("mobile:alert 未命中「{}」: {}", label, e.getMessage());
        }
        List<String> selectors = List.of(
                "ios predicate:type == 'XCUIElementTypeButton' AND (label == '" + label + "' OR name == '" + label + "')",
                "ios predicate:label == '" + label + "' OR name == '" + label + "'",
                "xpath://XCUIElementTypeButton[@label='" + label + "' or @name='" + label + "']",
                "xpath://*[contains(@label,'" + label + "') or contains(@name,'" + label + "')]"
        );
        for (String sel : selectors) {
            WebElement el = findElementWithTimeout(deviceId, sel, 1);
            if (el == null) {
                continue;
            }
            try {
                el.click();
                log.info("iOS 系统弹窗元素点击「{}」: {}", label, sel);
                return true;
            } catch (Exception e) {
                try {
                    // 部分系统按钮 click 无效，改坐标点中心
                    org.openqa.selenium.Rectangle r = el.getRect();
                    int x = r.getX() + r.getWidth() / 2;
                    int y = r.getY() + r.getHeight() / 2;
                    if (click(deviceId, x, y)) {
                        log.info("iOS 系统弹窗中心坐标点击「{}」: ({},{})", label, x, y);
                        return true;
                    }
                } catch (Exception e2) {
                    log.debug("弹窗坐标点击失败 {}: {}", sel, e2.getMessage());
                }
            }
        }
        return false;
    }

    /**
     * 启动后点掉「允许使用无线数据」→「无线局域网与蜂窝网络」。
     * <p>
     * 关键：App 会话 pageSource 经常读不到该系统弹窗，不能用“文案未出现”当跳过条件。
     * 安装后首次启动几乎必现，因此每轮：元素点击 + 坐标点顶部按钮，直到弹窗按钮消失或轮次用尽。
     */
    public boolean waitAndClickIosWirelessDataAlert(String deviceId, long timeoutMs) {
        if (sessions.get(deviceId) == null) {
            return false;
        }
        final String primary = "无线局域网与蜂窝网络";
        long deadline = System.currentTimeMillis() + Math.max(5000L, timeoutMs);
        int round = 0;
        boolean everSeen = false;
        while (System.currentTimeMillis() < deadline) {
            round++;
            boolean present = isIosWirelessDataAlertPresent(deviceId);
            if (present) {
                everSeen = true;
                log.info("检测到无线数据弹窗控件(round={})", round);
            }
            dismissIosSystemAlertButton(deviceId, primary);
            // 安装后首次启动几乎必现：每轮都坐标点顶部按钮（不依赖 pageSource）
            tapIosWirelessDataAlertTopButton(deviceId);
            log.info("无线数据弹窗处理轮次 {}: 元素尝试+坐标点击完成", round);
            try {
                Thread.sleep(700L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (everSeen && !isIosWirelessDataAlertPresent(deviceId)) {
                log.info("无线数据弹窗已消失(round={})", round);
                return true;
            }
            // 控件树始终看不到时：连点够 3 轮也返回（坐标应已点到）
            if (!everSeen && round >= 3) {
                log.info("未在控件树看到弹窗，已坐标连点 {} 轮（按安装后必现处理）", round);
                return true;
            }
        }
        if (everSeen && isIosWirelessDataAlertPresent(deviceId)) {
            log.warn("无线数据弹窗仍在，最后再强点一轮");
            tapIosWirelessDataAlertTopButton(deviceId);
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return !isIosWirelessDataAlertPresent(deviceId);
        }
        log.info("无线数据弹窗处理结束(rounds={})", round);
        return round >= 3;
    }

    /** 是否仍能看到无线数据弹窗相关按钮（短超时；找不到不代表一定没有，需配合坐标点击） */
    public boolean isIosWirelessDataAlertPresent(String deviceId) {
        String[] sels = {
                "ios predicate:type == 'XCUIElementTypeButton' AND (label == '无线局域网与蜂窝网络' OR name == '无线局域网与蜂窝网络')",
                "ios predicate:type == 'XCUIElementTypeButton' AND (label == '不允许' OR name == '不允许')",
                "ios predicate:type == 'XCUIElementTypeButton' AND (label == '仅限无线局域网' OR name == '仅限无线局域网')",
                "xpath://XCUIElementTypeAlert",
                "xpath://XCUIElementTypeSheet"
        };
        for (String sel : sels) {
            if (findElementWithTimeout(deviceId, sel, 0) != null) {
                return true;
            }
        }
        try {
            AppiumDriver driver = sessions.get(deviceId);
            if (driver != null) {
                String src = driver.getPageSource();
                if (src != null && (src.contains("使用无线数据") || src.contains("无线局域网与蜂窝网络")
                        || src.contains("仅限无线局域网"))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return false;
    }

    /**
     * 三按钮系统弹窗最上方「无线局域网与蜂窝网络」：按屏占比连点几个 Y，兼容不同机型。
     */
    public boolean tapIosWirelessDataAlertTopButton(String deviceId) {
        // 竖屏居中 Alert：首个按钮约在 0.44~0.52 高度
        double[] ys = {0.46, 0.48, 0.50, 0.52, 0.44};
        boolean any = false;
        for (double y : ys) {
            if (clickByRatio(deviceId, 0.50, y)) {
                any = true;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return any;
                }
            }
        }
        return any;
    }

    /**
     * 处理 SpringBoard <b>系统级</b>弹窗（如「允许使用无线数据」）。
     * <p>
     * 关键：该弹窗属于 {@code com.apple.springboard}，不是 App 内控件。
     * 挂在目标 App 会话上直接坐标点击经常无效。应按 XCUITest 官方方式：
     * {@code respectSystemAlerts=true} + 切到 SpringBoard + {@code mobile: alert}/{@code acceptAlertButtonSelector}。
     *
     * @param buttonLabel 例如「无线局域网与蜂窝网络」
     * @param fallbackX   系统 API 失败时的兜底坐标 x（建议屏幕中心）
     * @param fallbackY   系统 API 失败时的兜底坐标 y
     */
    public boolean dismissIosSpringboardSystemAlert(String deviceId, String buttonLabel, int fallbackX, int fallbackY) {
        AppiumDriver raw = sessions.get(deviceId);
        if (!(raw instanceof IOSDriver driver)) {
            log.warn("dismissIosSpringboardSystemAlert: 无 iOS 会话 deviceId={}", deviceId);
            return false;
        }
        String label = buttonLabel != null ? buttonLabel.trim() : "";
        if (label.isBlank()) {
            label = "无线局域网与蜂窝网络";
        }
        String classChain = "**/XCUIElementTypeButton[`label == '" + label + "'`]";
        try {
            // 让驱动识别 SpringBoard 系统弹窗（XCUITest driver 官方推荐）
            driver.setSetting("respectSystemAlerts", true);
            driver.setSetting("acceptAlertButtonSelector", classChain);
            driver.setSetting("defaultActiveApplication", "com.apple.springboard");
            log.info("已切换系统弹窗上下文: respectSystemAlerts=true, activeApp=springboard, button={}", label);
        } catch (Exception e) {
            log.warn("设置系统弹窗 settings 失败: {}", e.getMessage());
        }

        boolean ok = false;
        // 1) 列出按钮，确认是系统 Alert
        try {
            Object buttons = driver.executeScript("mobile: alert", Map.of("action", "getButtons"));
            log.info("系统弹窗 getButtons: {}", buttons);
        } catch (Exception e) {
            log.debug("getButtons: {}", e.getMessage());
        }
        // 2) mobile: alert 按文案 accept
        try {
            driver.executeScript("mobile: alert", Map.of("action", "accept", "buttonLabel", label));
            log.info("mobile: alert accept buttonLabel={}", label);
            ok = true;
        } catch (Exception e) {
            log.warn("mobile: alert 失败: {}", e.getMessage());
        }
        // 3) WebDriver Alert.accept + acceptAlertButtonSelector
        if (!ok) {
            try {
                driver.switchTo().alert().accept();
                log.info("switchTo().alert().accept() 已调用");
                ok = true;
            } catch (Exception e) {
                log.debug("switchTo().alert 失败: {}", e.getMessage());
            }
        }
        // 4) 在 SpringBoard 上下文按 class chain / predicate 点按钮
        if (!ok) {
            List<String> sels = List.of(
                    "ios class chain:" + classChain,
                    "ios predicate:type == 'XCUIElementTypeButton' AND label == '" + label + "'",
                    "xpath://XCUIElementTypeAlert//XCUIElementTypeButton[@label='" + label + "']",
                    "xpath://XCUIElementTypeSheet//XCUIElementTypeButton[@label='" + label + "']"
            );
            for (String sel : sels) {
                WebElement el = findElementWithTimeout(deviceId, sel, 2);
                if (el == null) {
                    continue;
                }
                try {
                    el.click();
                    log.info("SpringBoard 上下文元素点击成功: {}", sel);
                    ok = true;
                    break;
                } catch (Exception e) {
                    log.debug("元素点击失败 {}: {}", sel, e.getMessage());
                }
            }
        }
        // 5) 最后才坐标兜底（必须是按钮中心，不是左缘）
        if (!ok && fallbackX > 0 && fallbackY > 0) {
            try {
                driver.executeScript("mobile: tap", Map.of("x", fallbackX, "y", fallbackY));
                log.info("系统弹窗坐标兜底 mobile:tap ({},{})", fallbackX, fallbackY);
                ok = true;
            } catch (Exception e) {
                log.warn("坐标兜底失败: {}", e.getMessage());
            }
        }

        // 恢复自动识别前台 App，避免后续操作一直停在 SpringBoard
        try {
            driver.setSetting("defaultActiveApplication", "auto");
        } catch (Exception e) {
            log.debug("恢复 defaultActiveApplication 失败: {}", e.getMessage());
        }
        return ok;
    }

    /** @deprecated 使用 {@link #dismissIosSpringboardSystemAlert(String, String, int, int)} */
    public boolean tapIosSystemAlertAt(String deviceId, int x, int y) {
        return dismissIosSpringboardSystemAlert(deviceId, "无线局域网与蜂窝网络", x, y);
    }

    public boolean dismissIosWirelessDataAlert(String deviceId) {
        return waitAndClickIosWirelessDataAlert(deviceId, 8000L);
    }

    @Override
    public String getCurrentActivity(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver instanceof AndroidDriver androidDriver) {
            try {
                return androidDriver.currentActivity();
            } catch (Exception e) {
                log.trace("获取当前 Activity 失败: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * 获取当前设备已创建的 Appium 会话（需在 launchApp 之后调用）。
     * 供设置测试等需要直接操作 Appium 元素的场景使用。
     */
    public io.appium.java_client.AppiumDriver getAppiumDriver(String deviceId) {
        return sessions.get(deviceId);
    }

    /**
     * 读当前 WebView 的完整地址（尽量含 hash）。
     * <p>
     * 优先切到 {@code WEBVIEW_*} / {@code CHROMIUM} 后执行 {@code location.href}。
     * 切失败（常见：缺 Chromedriver）时，回退 {@code mobile: getContexts} 读 pages[].url——
     * 完美电竞 X5/{@code pwX5WebView} 在调试通道开着时，这条往往仍能拿到带 hash 的完整地址。
     * 两边都没有时 {@code available=false}，文案说明原因，不假装成功。
     */
    /**
     * 这条错误说明服务端的会话已经没了，本地这个对象是个空壳。
     * <p>字符串匹配不优雅，但 Appium 把这几种都归在同一类异常里，
     * 按类型分不出来；而分得出来与否，决定了我们是「重建一次就好」还是「一直报错」。
     */
    static boolean isDeadSessionError(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("a session is either terminated or not started")
                || m.contains("invalid session id")
                || m.contains("session id is null")
                || m.contains("nosuchsessionexception")
                || m.contains("session not started");
    }

    /** 打一条最便宜的往返命令探活。拿不到就当它死了——宁可多重建一次 */
    private boolean sessionAlive(AppiumDriver driver) {
        if (driver == null) return false;
        try {
            if (driver.getSessionId() == null) return false;
            driver.manage().timeouts().getImplicitWaitTimeout();
            return true;
        } catch (Exception e) {
            log.debug("会话探活失败（视为已失效）: {}", firstLineOf(e.getMessage()));
            return false;
        }
    }

    private void quitQuietly(AppiumDriver driver) {
        if (driver == null) return;
        try {
            driver.quit();
        } catch (Exception ignored) {
            // 会话本来就没了，quit 报错很正常
        }
    }

    /**
     * 命令失败时，如果是会话已死，就把它从缓存里摘掉，让下一次调用自己重建。
     * <p>不在这里重试：各处的重试语义不一样（有的要重新截图、有的要重新找元素），
     * 统一在这儿硬重试反而会掩盖真正的失败。摘掉就够了——下一次自然是新会话。
     */
    private void dropSessionIfDead(String deviceId, Exception e) {
        if (deviceId == null || !isDeadSessionError(e == null ? null : e.getMessage())) return;
        AppiumDriver dead = sessions.remove(deviceId);
        if (dead != null) {
            log.info("会话已失效，已从缓存摘除，下次调用会重建: deviceId={}", deviceId);
            quitQuietly(dead);
        }
    }

    /**
     * 探一下 WebView 里到底渲染出东西没有。
     *
     * <p>和 {@link #readWebViewUrl} 走同一条路（切 WEBVIEW_* context），
     * 因为那条路上 X5 的各种坑已经趟过一遍了，没必要再趟一次。
     *
     * <p>返回 {@code available=false} 时调用方<b>绝不能当成通过</b>——
     * 那只是「没验成」。release 包没开调试通道时就是这个结果。
     */
    public Map<String, Object> probeWebViewRender(String deviceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("message", "");

        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) {
            out.put("message", "没有 Appium 会话");
            return out;
        }
        if (!(driver instanceof SupportsContextSwitching ctxDriver)) {
            out.put("message", "当前驱动不支持 WebView context 切换");
            return out;
        }

        String originalContext = null;
        try {
            originalContext = ctxDriver.getContext();
            Set<String> contexts = ctxDriver.getContextHandles();
            List<String> all = contexts == null ? List.of() : new ArrayList<>(contexts);
            out.put("contexts", all);
            List<String> webViews = all.stream()
                    .filter(c -> c != null && (c.startsWith("WEBVIEW_") || c.startsWith("CHROMIUM")))
                    .toList();
            if (webViews.isEmpty()) {
                out.put("message", all.isEmpty()
                        ? "getContextHandles 为空，WebView 没开调试通道"
                        : "只有原生 context（" + String.join(", ", all)
                                + "），读不到页面内容。X5 WebView 未开调试通道时就是这样");
                return out;
            }
            String lastErr = null;
            for (String ctx : webViews) {
                try {
                    ctxDriver.context(ctx);
                    Object raw = driver.executeScript(RENDER_PROBE_JS);
                    if (raw instanceof Map<?, ?> m) {
                        out.put("available", true);
                        out.put("context", ctx);
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            out.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        return out;
                    }
                    lastErr = "探针脚本没有返回对象";
                } catch (Exception e) {
                    lastErr = e.getMessage();
                    log.warn("[h5-probe] context={} 执行探针失败: {}", ctx, e.getMessage());
                }
            }
            out.put("message", "切进了 WebView 但探针没跑成：" + lastErr);
            return out;
        } catch (Exception e) {
            out.put("message", "读 WebView context 失败：" + e.getMessage());
            return out;
        } finally {
            if (originalContext != null) {
                try {
                    ctxDriver.context(originalContext);
                } catch (Exception e) {
                    log.debug("切回原 context 失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 渲染探针。
     *
     * <p>两个刻意的选择：
     * <ul>
     *   <li>用 {@code innerText} <b>不是</b> {@code textContent}——前者只算渲染出来的文字。
     *       用 textContent 的话，一个 DOM 齐全但整体没显示的白屏页会被判成正常</li>
     *   <li>图只数 {@code naturalWidth > 0} 的——一页全是加载失败的图，看上去就是白屏，
     *       而 {@code querySelectorAll('img').length} 会把它们全算成「有内容」</li>
     * </ul>
     */
    private static final String RENDER_PROBE_JS = """
            try {
              var b = document.body;
              if (!b) {
                return {bodyPresent:false, readyState:document.readyState,
                        textLength:0, mediaCount:0, elementCount:0, url:location.href};
              }
              var t = (b.innerText || '').replace(/\\s+/g, ' ').trim();
              var media = 0;
              var imgs = document.getElementsByTagName('img');
              for (var i = 0; i < imgs.length; i++) { if (imgs[i].naturalWidth > 0) media++; }
              media += document.getElementsByTagName('canvas').length;
              media += document.getElementsByTagName('video').length;
              return {bodyPresent:true, readyState:document.readyState,
                      textLength:t.length, mediaCount:media,
                      elementCount:document.getElementsByTagName('*').length,
                      url:location.href};
            } catch (e) {
              return {error:String(e)};
            }
            """;

    public Map<String, Object> readWebViewUrl(String deviceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("url", "");
        out.put("context", "");
        out.put("contexts", List.of());
        out.put("message", "");

        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) {
            out.put("message", "没有 Appium 会话，请先在真机点选里点一次「刷新截图」");
            return out;
        }
        if (!(driver instanceof SupportsContextSwitching ctxDriver)) {
            // 仍可试 mobile:getContexts（部分驱动无 context API 但仍暴露该扩展）
            Map<String, String> viaMobile = readUrlViaMobileGetContexts(driver);
            if (viaMobile != null) {
                out.put("available", true);
                out.put("url", viaMobile.get("url"));
                out.put("context", viaMobile.getOrDefault("context", "mobile:getContexts"));
                return out;
            }
            out.put("message", "当前驱动不支持 WebView context 切换");
            return out;
        }

        String originalContext = null;
        List<String> webViews = List.of();
        String switchError = null;
        try {
            originalContext = ctxDriver.getContext();
            Set<String> contexts = ctxDriver.getContextHandles();
            List<String> all = contexts == null ? List.of() : new ArrayList<>(contexts);
            out.put("contexts", all);

            webViews = all.stream()
                    .filter(c -> c != null && (c.startsWith("WEBVIEW_") || c.startsWith("CHROMIUM")))
                    .toList();

            for (String ctx : webViews) {
                try {
                    ctxDriver.context(ctx);
                    String href = null;
                    try {
                        Object raw = driver.executeScript("return window.location.href;");
                        if (raw != null) {
                            href = String.valueOf(raw).trim();
                        }
                    } catch (Exception e) {
                        log.debug("location.href 失败 context={}: {}", ctx, e.getMessage());
                    }
                    if (href == null || href.isBlank() || "undefined".equalsIgnoreCase(href)
                            || "null".equalsIgnoreCase(href)) {
                        try {
                            href = driver.getCurrentUrl();
                        } catch (Exception e) {
                            log.debug("getCurrentUrl 失败 context={}: {}", ctx, e.getMessage());
                        }
                    }
                    if (isUsableWebUrl(href)) {
                        out.put("available", true);
                        out.put("url", href);
                        out.put("context", ctx);
                        out.put("message", "");
                        return out;
                    }
                } catch (Exception e) {
                    switchError = e.getMessage();
                    log.warn("切换 WebView context={} 失败: {}", ctx, e.getMessage());
                }
            }

            Map<String, String> viaMobile = readUrlViaMobileGetContexts(driver);
            if (viaMobile != null) {
                out.put("available", true);
                out.put("url", viaMobile.get("url"));
                out.put("context", viaMobile.getOrDefault("context", "mobile:getContexts"));
                out.put("message", "");
                return out;
            }

            if (all.isEmpty()) {
                out.put("message", "getContextHandles 为空，WebView 可能未开启调试");
            } else if (webViews.isEmpty()) {
                out.put("message", "只有原生 context（" + String.join(", ", all)
                        + "），读不到 H5 地址。常见于 X5 WebView（pwX5WebView）且未暴露调试通道；"
                        + "系统 Chrome WebView / 已开调试的包才容易成功");
            } else if (switchError != null && switchError.toLowerCase(Locale.ROOT).contains("chromedriver")) {
                out.put("message", "已找到 WebView context（" + String.join(", ", webViews)
                        + "），但本机 Chromedriver 无法驱动该 Chrome 版本，且 mobile:getContexts 也没读到页面 URL。"
                        + "详情: " + switchError);
            } else {
                out.put("message", "已找到 WebView context，但读不到有效 URL（"
                        + String.join(", ", webViews) + "）");
            }
            return out;
        } catch (Exception e) {
            Map<String, String> viaMobile = readUrlViaMobileGetContexts(driver);
            if (viaMobile != null) {
                out.put("available", true);
                out.put("url", viaMobile.get("url"));
                out.put("context", viaMobile.getOrDefault("context", "mobile:getContexts"));
                out.put("message", "");
                return out;
            }
            if (isDeadSessionError(e.getMessage())) {
                dropSessionIfDead(deviceId, e);
                out.put("message", "Appium 会话已经失效了（闲置超过 "
                        + "newCommandTimeout 会被服务端回收）。已经把它清掉，再点一次「读当前 H5 URL」就会重建会话。"
                        + "如果连着两次都这样，说明设备和 Appium 之间断了，先点「刷新截图」确认还连得上");
            } else {
                out.put("message", "读 H5 URL 失败: " + e.getMessage());
            }
            return out;
        } finally {
            if (originalContext != null) {
                try {
                    ctxDriver.context(originalContext);
                } catch (Exception e) {
                    log.debug("切回原 context 失败: {}", e.getMessage());
                }
            }
        }
    }

    /** 判断是否为可展示的 H5 地址。 */
    private static boolean isUsableWebUrl(String href) {
        if (href == null) {
            return false;
        }
        String t = href.trim();
        if (t.isEmpty()) {
            return false;
        }
        if ("undefined".equalsIgnoreCase(t) || "null".equalsIgnoreCase(t)
                || "about:blank".equalsIgnoreCase(t)) {
            return false;
        }
        return t.startsWith("http://") || t.startsWith("https://") || t.contains("://");
    }

    /**
     * Android：{@code mobile: getContexts} 可在不切 Chromedriver 的情况下列出各页 url（含 hash）。
     * 返回 {@code url}/{@code context}，读不到则 null。
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> readUrlViaMobileGetContexts(AppiumDriver driver) {
        try {
            Object raw = driver.executeScript("mobile: getContexts");
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                return null;
            }
            String bestVisible = null;
            String bestHash = null;
            String bestAny = null;
            String bestCtx = "mobile:getContexts";
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Object webview = m.get("webview");
                Object pagesObj = m.get("pages");
                if (!(pagesObj instanceof List<?> pages)) {
                    continue;
                }
                for (Object p : pages) {
                    if (!(p instanceof Map<?, ?> page)) {
                        continue;
                    }
                    Object urlObj = page.get("url");
                    if (urlObj == null) {
                        continue;
                    }
                    String url = String.valueOf(urlObj).trim();
                    if (!isUsableWebUrl(url)) {
                        continue;
                    }
                    if (bestAny == null) {
                        bestAny = url;
                        if (webview != null) {
                            bestCtx = String.valueOf(webview);
                        }
                    }
                    if (url.contains("#") && bestHash == null) {
                        bestHash = url;
                        if (webview != null) {
                            bestCtx = String.valueOf(webview);
                        }
                    }
                    Object desc = page.get("description");
                    if (desc != null && String.valueOf(desc).contains("\"visible\":true") && bestVisible == null) {
                        bestVisible = url;
                        if (webview != null) {
                            bestCtx = String.valueOf(webview);
                        }
                    }
                }
            }
            String pick = bestVisible != null ? bestVisible : (bestHash != null ? bestHash : bestAny);
            if (pick == null) {
                return null;
            }
            Map<String, String> hit = new LinkedHashMap<>();
            hit.put("url", pick);
            hit.put("context", bestCtx);
            return hit;
        } catch (Exception e) {
            log.debug("mobile: getContexts 读 URL 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 通用 H5 点/输入。页与页只换文案和 URL，这里按通道自动试：
     * Appium CDP → adb 转发 devtools → 能切则 WebView JS。
     */
    public Map<String, Object> clickH5(String deviceId, String text, String css) {
        return clickH5(deviceId, text, css, null);
    }

    /** {@code typeValue} 非空时，点中后再往该节点写入。 */
    public Map<String, Object> clickH5(String deviceId, String text, String css, String typeValue) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("url", "");
        out.put("x", 0);
        out.put("y", 0);
        out.put("tag", "");
        out.put("message", "");

        String t = text == null ? "" : text.trim();
        String s = css == null ? "" : css.trim();
        if (t.isEmpty() && s.isEmpty()) {
            out.put("message", "请填写文案或 CSS 选择器");
            return out;
        }
        if (!attachAndroidUiSession(deviceId)) {
            out.put("message", "没有 Appium 会话。请先启动应用并打开 H5，或在真机点选里点一次「刷新截图」");
            return out;
        }
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) {
            out.put("message", "挂接会话后仍没有 Appium driver");
            return out;
        }
        List<String> tried = new ArrayList<>();
        String typed = typeValue == null ? "" : typeValue;
        H5ContextPages.Target page = null;
        try {
            Object raw = driver.executeScript("mobile: getContexts");
            page = H5ContextPages.pick(H5ContextPages.parse(raw));
        } catch (Exception e) {
            tried.add("getContexts: " + e.getMessage());
        }
        if (page != null) {
            out.put("url", page.url());
        }
        try {
            try (H5CdpLink link = H5CdpLink.open(deviceId, page)) {
                return finishH5Click(out, deviceId, page, link.wsUrl(), t, s, typed, "CDP");
            }
        } catch (Exception e) {
            tried.add("CDP: " + e.getMessage());
            log.info("H5 CDP 不可用，改试 WebView JS: {}", e.getMessage());
        }
        H5DomClick.Hit js = H5WebViewJs.run(driver, t, s, typed);
        if (js.ok()) {
            return finishH5Hit(out, deviceId, page, js, typed, "WebViewJS");
        }
        tried.add("WebViewJS: " + js.message());
        dropSessionIfDead(deviceId, new IllegalStateException(String.join(" | ", tried)));
        out.put("message", "当前页没有可用的 H5 通道，已自动试过 CDP / adb 调试口 / WebView JS。"
                + "换页只改步骤里的地址和文案，不必加新关键字。详情: " + String.join("；", tried));
        return out;
    }

    private Map<String, Object> finishH5Click(Map<String, Object> out, String deviceId,
                                              H5ContextPages.Target page, String wsUrl,
                                              String text, String css, String typed, String via)
            throws Exception {
        H5DomClick.Hit hit = H5DomClick.run(wsUrl, text, css, typed, Duration.ofSeconds(8));
        if (!hit.ok()) {
            out.put("message", "页上找不到：" + hit.message()
                    + "（页面上的字=" + text + (css.isEmpty() ? "" : " css=" + css) + "）via " + via);
            return out;
        }
        Map<String, Object> done = finishH5Hit(out, deviceId, page, hit, typed, via);
        if (!typed.isEmpty() && Boolean.TRUE.equals(done.get("ok"))) {
            H5DomClick.Hit again = H5DomClick.run(wsUrl, text, css, typed, Duration.ofSeconds(8));
            if (!again.ok()) {
                done.put("ok", false);
                done.put("message", "已点到 " + hit.tag() + "，但写入失败：" + again.message());
            }
        }
        return done;
    }

    private Map<String, Object> finishH5Hit(Map<String, Object> out, String deviceId,
                                            H5ContextPages.Target page, H5DomClick.Hit hit,
                                            String typed, String via) {
        int x = H5ContextPages.tapX(page, hit.x(), hit.viewW());
        int y = H5ContextPages.tapY(page, hit.y(), hit.viewH());
        out.put("x", x);
        out.put("y", y);
        out.put("tag", hit.tag());
        boolean tapped = click(deviceId, x, y);
        out.put("ok", true);
        String write = typed.isEmpty() ? "" : (" 已输入「" + typed + "」");
        out.put("message", (tapped
                ? ("H5 " + hit.tag() + " + 坐标(" + x + "," + y + ")" + write)
                : ("H5 已操作 " + hit.tag() + write + "，坐标补点失败 (" + x + "," + y + ")"))
                + " via " + via);
        log.info("H5 点选: deviceId={} {} ({},{}) tap={} typed={} via={}",
                deviceId, hit.tag(), x, y, tapped, !typed.isEmpty(), via);
        return out;
    }

    /**
     * 调试用：将当前设备所有 context（原生 + WebView）的页面源保存到 logs 目录。
     * 用于排查 WebView 内战队名无法提取的问题。返回保存的文件路径列表。
     */
    public List<String> dumpAllContextsForDebug(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null || !(driver instanceof AndroidDriver androidDriver)) {
            log.warn("dumpAllContextsForDebug: 无 Appium 会话或非 Android，deviceId={}", deviceId);
            return List.of();
        }
        SupportsContextSwitching ctxDriver = (SupportsContextSwitching) androidDriver;
        List<String> savedPaths = new ArrayList<>();
        String originalContext = null;
        try {
            originalContext = ctxDriver.getContext();
            Set<String> contexts = ctxDriver.getContextHandles();
            if (contexts == null || contexts.isEmpty()) {
                log.warn("dumpAllContextsForDebug: getContextHandles 返回空，可能 WebView 未启用调试");
                return List.of();
            }
            log.info("dumpAllContextsForDebug: 共 {} 个 context: {}", contexts.size(), contexts);
            Path dir = Path.of("logs");
            if (!Files.exists(dir)) Files.createDirectories(dir);
            String prefix = "cs_debug_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            for (String ctx : contexts) {
                try {
                    ctxDriver.context(ctx);
                    String content = driver.getPageSource();
                    String safeName = ctx.replaceAll("[^a-zA-Z0-9_]", "_");
                    Path file = dir.resolve(prefix + "_" + safeName + ".xml");
                    if (content != null && content.length() > 0) {
                        Files.writeString(file, content);
                        savedPaths.add(file.toAbsolutePath().toString());
                        log.info("dumpAllContextsForDebug: 已保存 context={} 长度={} -> {}", ctx, content.length(), file.toAbsolutePath());
                    }
                } catch (Exception e) {
                    log.warn("dumpAllContextsForDebug: 保存 context={} 失败: {}", ctx, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("dumpAllContextsForDebug 异常: {}", e.getMessage());
        } finally {
            if (originalContext != null) {
                try {
                    ctxDriver.context(originalContext);
                } catch (Exception e) {
                    log.debug("切回原 context 失败: {}", e.getMessage());
                }
            }
        }
        return savedPaths;
    }

    /**
     * 尝试切换到 WebView 上下文，从 HTML 中提取战队名，再切回 NATIVE_APP。
     * getContext/getContextHandles/context 在 SupportsContextSwitching 接口中，AndroidDriver 已实现。
     */
    public List<String> extractTeamsFromWebViewContext(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null || !(driver instanceof AndroidDriver androidDriver)) return List.of();
        SupportsContextSwitching ctxDriver = (SupportsContextSwitching) androidDriver;
        String originalContext = null;
        try {
            originalContext = ctxDriver.getContext();
            Set<String> contexts = ctxDriver.getContextHandles();
            if (contexts == null) return List.of();
            if (log.isInfoEnabled()) {
                log.info("extractTeamsFromWebViewContext: 共 {} 个 context: {}", contexts.size(), contexts);
            }
            for (String ctx : contexts) {
                if (ctx == null || (!ctx.startsWith("WEBVIEW_") && !ctx.startsWith("CHROMIUM"))) continue;
                try {
                    ctxDriver.context(ctx);
                    String html = driver.getPageSource();
                    if (html != null && html.length() > 100) {
                        List<String> texts = new ArrayList<>();
                        java.util.regex.Pattern p = java.util.regex.Pattern.compile(">([A-Za-z0-9\\s]{2,30})<");
                        java.util.regex.Matcher m = p.matcher(html);
                        while (m.find()) {
                            String s = m.group(1).trim();
                            if (s.length() >= 2 && s.length() <= 60 && !s.matches("^\\d+$")) {
                                texts.add(s);
                            }
                        }
                        if (!texts.isEmpty()) {
                            log.info("WebView 上下文 {} 解析到 {} 个文本片段", ctx, texts.size());
                            return texts;
                        }
                    }
                } catch (Exception e) {
                    log.debug("切换 WebView 上下文 {} 失败: {}", ctx, e.getMessage());
                }
            }
            long webViewCount = contexts.stream().filter(c -> c != null && (c.startsWith("WEBVIEW_") || c.startsWith("CHROMIUM"))).count();
            if (webViewCount == 0) {
                log.info("extractTeamsFromWebViewContext: 无 WEBVIEW_/CHROMIUM context，战队名可能在 WebView 内但无法切换");
            }
        } catch (Exception e) {
            log.debug("WebView 上下文提取异常: {}", e.getMessage());
        } finally {
            if (originalContext != null) {
                try {
                    ctxDriver.context(originalContext);
                } catch (Exception e) {
                    log.debug("切回原上下文失败: {}", e.getMessage());
                }
            }
        }
        return List.of();
    }

    /**
     * 尝试切换到 WebView 上下文，从 HTML 中提取战队成员名（类似 extractTeamsFromWebViewContext），再切回 NATIVE_APP。
     */
    public List<String> extractMembersFromWebViewContext(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null || !(driver instanceof AndroidDriver androidDriver)) return List.of();
        SupportsContextSwitching ctxDriver = (SupportsContextSwitching) androidDriver;
        String originalContext = null;
        try {
            originalContext = ctxDriver.getContext();
            Set<String> contexts = ctxDriver.getContextHandles();
            if (contexts == null) return List.of();
            for (String ctx : contexts) {
                if (ctx == null || (!ctx.startsWith("WEBVIEW_") && !ctx.startsWith("CHROMIUM"))) continue;
                try {
                    ctxDriver.context(ctx);
                    String html = driver.getPageSource();
                    if (html == null || html.length() < 100) continue;
                    List<String> texts = new ArrayList<>();
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(">([A-Za-z0-9_\\-]{2,20})<");
                    java.util.regex.Matcher m = p.matcher(html);
                    while (m.find()) {
                        String s = m.group(1).trim();
                        if (s.length() >= 2 && s.length() <= 20 && !s.matches("^\\d+$")
                                && !s.matches("^[A-Fa-f0-9-]{15,}$")) {
                            texts.add(s);
                        }
                    }
                    if (!texts.isEmpty()) {
                        log.info("WebView 上下文 {} 解析到 {} 个疑似队员文本", ctx, texts.size());
                        return texts;
                    }
                } catch (Exception e) {
                    log.debug("切换 WebView 上下文 {} 失败: {}", ctx, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("extractMembersFromWebViewContext 异常: {}", e.getMessage());
        } finally {
            if (originalContext != null) {
                try {
                    ctxDriver.context(originalContext);
                } catch (Exception e) {
                    log.debug("切回原上下文失败: {}", e.getMessage());
                }
            }
        }
        return List.of();
    }

    /**
     * 从当前 Appium 会话获取真实设备/会话数据，用于兼容性测试结果。
     */
    @Override
    public Map<String, Object> getSessionInfo(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) return null;
        try {
            org.openqa.selenium.Capabilities caps = driver.getCapabilities();
            Map<String, Object> info = new HashMap<>();
            putCap(info, caps, "deviceName");
            putCap(info, caps, "platformName");
            putCap(info, caps, "platformVersion");
            putCap(info, caps, "udid");
            putCap(info, caps, "deviceManufacturer");
            putCap(info, caps, "deviceModel");
            putCap(info, caps, "appPackage");
            putCap(info, caps, "bundleId");
            return info;
        } catch (Exception e) {
            log.warn("获取 Appium 会话信息失败: deviceId={}", deviceId, e);
            return null;
        }
    }

    private static void putCap(Map<String, Object> out, org.openqa.selenium.Capabilities caps, String key) {
        try {
            Object v = caps.getCapability(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                out.put(key, v);
            }
        } catch (Exception ignored) { }
    }

    @Override
    public int[] getElementCenter(String deviceId, String selector) {
        WebElement el = findElement(deviceId, selector);
        if (el == null) return null;
        try {
            org.openqa.selenium.Point loc = el.getLocation();
            org.openqa.selenium.Dimension size = el.getSize();
            int centerX = loc.getX() + size.getWidth() / 2;
            int centerY = loc.getY() + size.getHeight() / 2;
            return new int[]{centerX, centerY};
        } catch (Exception e) {
            log.warn("获取元素中心失败: selector={}", selector, e);
            return null;
        }
    }

    @Override
    public int[] getElementBounds(String deviceId, String selector) {
        WebElement el = findElement(deviceId, selector);
        if (el == null) return null;
        try {
            org.openqa.selenium.Point loc = el.getLocation();
            org.openqa.selenium.Dimension size = el.getSize();
            return new int[]{
                    loc.getX(),
                    loc.getY(),
                    loc.getX() + size.getWidth(),
                    loc.getY() + size.getHeight()};
        } catch (Exception e) {
            log.warn("获取元素范围失败: selector={}", selector, e);
            return null;
        }
    }

    @Override
    public List<String> getElementsTextList(String deviceId, String selector) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) return List.of();
        By by = parseSelector(selector);
        if (by == null) return List.of();
        try {
            List<WebElement> elements = driver.findElements(by);
            List<String> texts = new ArrayList<>();
            for (WebElement el : elements) {
                try {
                    String t = el.getText();
                    if (t == null || t.isBlank()) {
                        t = el.getAttribute("content-desc");
                    }
                    if (t == null || t.isBlank()) {
                        t = el.getAttribute("text");
                    }
                    if (t == null || t.isBlank()) {
                        t = el.getAttribute("name");
                    }
                    if (t != null && !t.isBlank()) {
                        texts.add(t.trim());
                    }
                } catch (Exception ignored) { }
            }
            return texts;
        } catch (Exception e) {
            log.debug("获取多元素文本失败: selector={}", selector, e);
            return List.of();
        }
    }

    @Override
    public List<String> getChildTextsFromParents(String deviceId, String parentSelector, String childRelativeXPath) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) return List.of();
        By parentBy = parseSelector(parentSelector);
        if (parentBy == null) return List.of();
        try {
            List<WebElement> parents = driver.findElements(parentBy);
            if (parents.isEmpty()) {
                log.info("getChildTextsFromParents: 未找到父元素, 将尝试其他选择器");
            } else {
                log.info("getChildTextsFromParents: 找到 {} 个父元素", parents.size());
            }
            List<String> texts = new ArrayList<>();
            for (WebElement parent : parents) {
                try {
                    List<WebElement> children = parent.findElements(By.xpath(childRelativeXPath));
                    for (WebElement child : children) {
                        try {
                            String t = child.getText();
                            if (t == null || t.isBlank()) t = child.getAttribute("content-desc");
                            if (t == null || t.isBlank()) t = child.getAttribute("text");
                            if (t == null || t.isBlank()) t = child.getAttribute("name");
                            if (t != null && !t.isBlank()) {
                                texts.add(t.trim());
                                break;
                            }
                        } catch (Exception ignored) { }
                    }
                } catch (Exception ignored) { }
            }
            return texts;
        } catch (Exception e) {
            log.debug("getChildTextsFromParents 失败: parent={}", parentSelector, e);
            return List.of();
        }
    }

    @Override
    public boolean pressBack(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver instanceof AndroidDriver androidDriver) {
            try {
                androidDriver.pressKey(new KeyEvent(AndroidKey.BACK));
                log.info("已发送 Android 返回键: deviceId={}", deviceId);
                return true;
            } catch (Exception e) {
                log.warn("发送 Android 返回键失败: deviceId={}", deviceId, e);
                return false;
            }
        }
        return false;
    }

    /**
     * 回到主屏幕：iOS 底部上滑 + Home 键；Android 优先 Appium HOME，失败再 adb KEYCODE_HOME。
     */
    @Override
    public boolean pressHome(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        AppiumDriver driver = sessions.get(deviceId);
        if (driver instanceof IOSDriver) {
            return goIosHomeScreen(deviceId);
        }
        if (driver instanceof AndroidDriver androidDriver) {
            try {
                androidDriver.pressKey(new KeyEvent(AndroidKey.HOME));
                log.info("已发送 Android Home 键: deviceId={}", deviceId);
                return true;
            } catch (Exception e) {
                log.warn("Appium 发 Home 失败，改用 adb: deviceId={} err={}", deviceId, e.getMessage());
            }
        }
        return pressAndroidHomeKey(deviceId);
    }

    private boolean pressAndroidHomeKey(String deviceId) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "input", "keyevent", "KEYCODE_HOME")
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            log.info("已按 Home 切入后台: deviceId={}", deviceId);
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("按 Home 失败: {}", e.getMessage());
            return false;
        }
    }

    private void closeSession(String deviceId) {
        AppiumDriver driver = sessions.remove(deviceId);
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception e) {
                log.debug("关闭 Appium 会话异常: {}", e.getMessage());
            }
        }
    }
}
