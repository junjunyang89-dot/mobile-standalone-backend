package com.mobile.standalone.service.driver;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.PageObject;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.AutomationEngine;
import com.mobile.standalone.service.DeviceManager;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
                // 本机已有 WDA+iproxy 占着 wdaLocalPort 时，不能再让 Appium 去绑同一端口，否则报
                // "The port #8101 is occupied" → 表现为「无法启动应用程序」
                int wdaPort = wdaLocalPortFor(deviceId);
                String wdaUrl = "http://127.0.0.1:" + wdaPort;
                if (!deviceManager.ensureIosWdaRunning(deviceId, wdaPort)) {
                    log.error("无法拉起本机 WDA（test-without-building+iproxy）: deviceId={}, port={}", deviceId, wdaPort);
                    return false;
                }
                XCUITestOptions opts = buildIosOptions(deviceId, packageName);
                opts.setWebDriverAgentUrl(wdaUrl);
                opts.setForceAppLaunch(true);
                log.info("iOS launchApp 挂接已运行的 WDA: {} bundle={}", wdaUrl, packageName);
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
            // 国内电竞：部分机型 activateApp 进不了前台，会话建立后补一次 am start（已知组件，不阻塞解析）
            if (Device.usesAndroidAutomation(dp) && dp != Device.Platform.HARMONYOS
                    && "com.pwrd.steam.esports".equals(packageName)) {
                adbAmStartKnownComponent(deviceId,
                        "com.pwrd.steam.esports/.ui.activity.launcher.LauncherActivity");
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
            log.info("已设置 Android waitForIdleTimeout=0，避免动画导致查找卡住");
        } catch (Exception e) {
            log.warn("设置 waitForIdleTimeout 失败: {}", e.getMessage());
        }
    }

    /**
     * 使用显式 {@code appPackage} + {@code appActivity}（相对 Activity，如 {@code .LauncherActivity}）创建会话并启动应用，
     * 等价于 Appium caps：{@code appium:appPackage}、{@code appium:appActivity}。
     * 用于 PogPog 等需固定启动入口的 Android 应用。
     */
    public boolean launchAndroidWithPackageAndRelativeActivity(String deviceId, String appPackage, String relativeActivityDotForm) {
        if (deviceId == null || deviceId.isBlank() || appPackage == null || appPackage.isBlank()) {
            return false;
        }
        String rel = relativeActivityDotForm != null && !relativeActivityDotForm.isBlank() ? relativeActivityDotForm.trim() : ".LauncherActivity";
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
            UiAutomator2Options opts = new UiAutomator2Options();
            opts.setUdid(deviceId);
            opts.setNewCommandTimeout(Duration.ofSeconds(newCommandTimeout));
            applyAndroidParallelPorts(opts, deviceId);
            opts.setCapability("appium:settings[waitForIdleTimeout]", 0);
            opts.setCapability("appium:settings[waitForSelectorTimeout]", 2_000);
            opts.setAppPackage(appPackage);
            opts.setAppActivity(rel.startsWith(".") ? rel : "." + rel);
            AndroidDriver driver = new AndroidDriver(url, opts);
            applyAndroidUiStabilitySettings(driver);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWaitSeconds));
            sessions.put(deviceId, driver);
            log.info("Appium 会话已创建（显式 appPackage/appActivity）: deviceId={}, package={}, activity={}", deviceId, appPackage, rel);
            return true;
        } catch (MalformedURLException e) {
            log.error("Appium Server URL 无效: {}", serverUrl, e);
            return false;
        } catch (Exception e) {
            log.error("创建 Appium 会话失败(显式 Activity): deviceId={}, package={}", deviceId, appPackage, e);
            return false;
        }
    }

    private XCUITestOptions buildIosOptions(String deviceId, String bundleId) {
        XCUITestOptions opts = new XCUITestOptions();
        opts.setUdid(deviceId);
        opts.setNewCommandTimeout(Duration.ofSeconds(newCommandTimeout));
        applyIosParallelPorts(opts, deviceId);
        opts.setBundleId(bundleId);
        // 个人团队不能使用 com.facebook.WebDriverAgentRunner，需唯一 Bundle ID + Team
        opts.setUpdatedWdaBundleId("com.travYang.wda.Runner");
        opts.setCapability("appium:xcodeOrgId", "37ZX3X6QJY");
        opts.setCapability("appium:xcodeSigningId", "Apple Development");
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
        return attachIosUiSession(deviceId, bundleId, false);
    }

    /**
     * @param forceNew true 时关闭旧会话再挂（App Store 点安装 / 启动目标 App 后必须重挂到正确 bundle）
     */
    public boolean attachIosUiSession(String deviceId, String bundleId, boolean forceNew) {
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
            log.info("挂接已运行的 WDA: {} forceNew={} bundle={}", wdaUrl, forceNew, wantBundle);
            URL url = new URL(serverUrl);
            IOSDriver driver = new IOSDriver(url, opts);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(Math.min(5, implicitWaitSeconds)));
            // App Store / 目标 App：确保自动化上下文切到该 bundle（不杀进程重装）
            try {
                driver.activateApp(wantBundle);
            } catch (Exception e) {
                log.debug("activateApp({}) 忽略: {}", wantBundle, e.getMessage());
            }
            sessions.put(deviceId, driver);
            log.info("iOS UI 会话已挂接（不重启前台 App）: deviceId={}, bundle={}", deviceId, wantBundle);
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
                log.warn("坐标点击也失败: {}", e2.getMessage());
                return false;
            }
        }
    }

    /**
     * 点击元素；若元素 click 后仍在（可选校验），再按中心坐标点一次。
     * iOS 侧边栏「点击登录」常为 StaticText，element.click 假成功时用坐标补点。
     */
    public boolean clickElementPreferCoordinates(String deviceId, String selector) {
        WebElement el = findElementWithTimeout(deviceId, selector, 3);
        if (el == null) {
            return false;
        }
        int x;
        int y;
        try {
            org.openqa.selenium.Rectangle r = el.getRect();
            x = r.getX() + Math.max(1, r.getWidth() / 2);
            y = r.getY() + Math.max(1, r.getHeight() / 2);
        } catch (Exception e) {
            log.warn("读取元素坐标失败: {}", e.getMessage());
            return clickElement(deviceId, selector);
        }
        try {
            el.click();
        } catch (Exception ignored) {
        }
        boolean tapped = click(deviceId, x, y);
        log.info("clickElementPreferCoordinates: selector={} center=({},{}) tapped={}", selector, x, y, tapped);
        return tapped;
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
        if (driver == null) return false;
        try {
            int left, top, width, height;
            String direction;
            if (x1 == x2) {
                // 垂直滑动：向上或向下
                direction = y2 < y1 ? "up" : "down";
                int minY = Math.min(y1, y2);
                int maxY = Math.max(y1, y2);
                left = Math.max(0, x1 - 50);
                top = minY;
                width = 100;
                height = Math.max(50, maxY - minY);
            } else if (y1 == y2) {
                // 水平滑动：向左或向右
                direction = x2 > x1 ? "right" : "left";
                int minX = Math.min(x1, x2);
                int maxX = Math.max(x1, x2);
                left = minX;
                top = Math.max(0, y1 - 50);
                width = Math.max(50, maxX - minX);
                height = 100;
            } else {
                // 斜向滑动：按主要方向处理
                if (Math.abs(y2 - y1) >= Math.abs(x2 - x1)) {
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
            }
            driver.executeScript("mobile: swipeGesture", Map.of(
                    "left", left,
                    "top", top,
                    "width", width,
                    "height", height,
                    "direction", direction,
                    "percent", 1.0
            ));
            return true;
        } catch (Exception e) {
            log.warn("Appium 滑动失败 deviceId={} ({},{})->({},{})", deviceId, x1, y1, x2, y2, e);
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
        try {
            el.sendKeys(text);
            return true;
        } catch (Exception e) {
            log.warn("Appium 元素输入失败: selector={}", selector, e);
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

    @Override
    public String getPageSource(String deviceId) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) return null;
        try {
            return driver.getPageSource();
        } catch (Exception e) {
            log.warn("Appium 获取页面源失败", e);
            return null;
        }
    }

    @Override
    public boolean waitForElement(String deviceId, String selector, int timeoutSeconds) {
        WebElement el = findElementWithTimeout(deviceId, selector, timeoutSeconds);
        return el != null;
    }

    @Override
    public boolean isElementPresent(String deviceId, String selector) {
        // 存在性探测必须用短超时：启动/登录等流程会对多个弹窗做“有则点、无则跳过”，
        // 若沿用 implicitWait(默认 10s) 会对每个“不存在”的选择器傻等，前端体感像“半天无响应”。
        return findElementWithTimeout(deviceId, selector, 1) != null;
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
    public boolean clickByPageObject(String deviceId, PageObject pageObject, String elementName) {
        if (pageObject == null || pageObject.getElements() == null) return false;
        PageObject.PageElement element = pageObject.getElements().get(elementName);
        if (element == null) return false;
        String selector = (element.getSelectorType() != null ? element.getSelectorType() : "id") + ":" + element.getSelector();
        return clickElement(deviceId, selector);
    }

    @Override
    public String getElementAttribute(String deviceId, String selector, String attribute) {
        WebElement el = findElement(deviceId, selector);
        return el != null ? el.getAttribute(attribute) : null;
    }

    private WebElement findElement(String deviceId, String selector) {
        return findElementWithTimeout(deviceId, selector, implicitWaitSeconds);
    }

    private WebElement findElementWithTimeout(String deviceId, String selector, int timeoutSeconds) {
        AppiumDriver driver = sessions.get(deviceId);
        if (driver == null) return null;
        By by = parseSelector(selector);
        if (by == null) return null;
        try {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(timeoutSeconds));
            return driver.findElement(by);
        } catch (Exception e) {
            return null;
        } finally {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(implicitWaitSeconds));
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
        String value = selector.substring(colon + 1).trim();
        return switch (type) {
            case "id" -> AppiumBy.id(value);
            case "accessibilityid", "accessibility_id" -> AppiumBy.accessibilityId(value);
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
