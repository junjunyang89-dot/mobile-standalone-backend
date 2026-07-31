package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FunctionalTestExecutionResult;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.locators.android.settings.SettingsLocators;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.ios.IOSDriver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置自动化测试服务（功能体验基础质量 - 设置场景）。
 * 先执行登录测试确保已登录，再进入设置页并截图；依赖完美世界电竞应用与 Appium。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final int WAIT_SECONDS = 15;
    private static final int SLEEP_AFTER_CLICK_MS = 2000;
    /** 检测是否已登录时等待「主页」出现的超时（秒），避免未登录时等太久 */
    private static final int CHECK_LOGIN_TIMEOUT_SECONDS = 4;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isSettingsTestSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        if (!Device.isAndroidOrPogAndroid(device.getPlatform()) && !Device.usesIosAutomation(device.getPlatform())) return false;
        return app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    /**
     * 执行设置测试：先登录，再进入设置页并截图。
     */
    public FunctionalTestExecutionResult executeSettingsTest(Task task, Device device) {
        log.info("设置测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isSettingsTestSupported(task, app, device)) {
            throw new RuntimeException("设置测试仅支持完美世界电竞应用(包名含 steam.esports)，当前: " + (app.getPackageName() != null ? app.getPackageName() : "无包名"));
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("设置测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "设置测试", packageOrBundle, deviceId);

        // 1) 先启动应用程序并建立 Appium 会话，再执行登录（登录步骤内跳过再次启动）
        PlatformDriver platformDriver = platformRouter.getDriver(device);
        if (platformDriver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "无法获取平台驱动，请确认 appium.enabled=true 且设备可用", null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("无法获取平台驱动，请确认 appium.enabled 且设备可用");
        }
        executionLog.add(ExecutionLogEntryDto.success("环境初始化", "设置测试-准备启动应用"));
        executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "包名:" + packageOrBundle));
        if (!platformDriver.launchApp(deviceId, packageOrBundle)) {
            executionLog.add(ExecutionLogEntryDto.fail("启动应用程序", "启动失败，请确认设备已连接、应用已安装、包名正确且 Appium Server 已启动", packageOrBundle));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("启动应用失败，请确认: 1) 设备已连接 2) 应用已安装 3) 包名正确 4) Appium Server 已启动(" + packageOrBundle + ")");
        }
        executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "启动完成，等待 3 秒后检查登录状态"));
        takeStepScreenshotIfPossible(platformDriver, deviceId, task, executionLog);
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        AppiumDriver driver = appiumDriverAdapter.getAppiumDriver(deviceId);
        if (driver == null) {
            log.error("设置测试: 启动后未获取到 Appium 会话, deviceId={}", deviceId);
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "启动后未获取到 Appium 会话", null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("未获取到 Appium 会话，请确认 appium.enabled 且设备可用");
        }

        executionLog.add(ExecutionLogEntryDto.success("设置测试", "开始检查登录状态并进入设置页"));
        try {
            if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
                openSidebarAndRunSettingsFlow((AndroidDriver) driver, executionLog, task, device, deviceId, false, false, false);
            } else if (Device.usesIosAutomation(device.getPlatform())) {
                openSidebarAndRunSettingsFlow((IOSDriver) driver, executionLog, task, device, deviceId, false, false, false);
            } else {
                executionLog.add(ExecutionLogEntryDto.fail("设置测试", "不支持的平台: " + device.getPlatform(), null));
                loginTestService.setExecutionLogForFailure(executionLog);
                throw new RuntimeException("不支持的平台: " + device.getPlatform());
            }
        } catch (Exception e) {
            log.error("设置测试执行失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("设置测试", e.getMessage(), null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("设置测试执行失败: " + e.getMessage(), e);
        }

        // 截图失败只记入步骤为不通过，不导致整次运行 FAILED
        String screenshotPath = "/tmp/screenshots/settings_" + task.getId() + "_" + System.currentTimeMillis() + ".png";
        String savedPath = null;
        try {
            try {
                Files.createDirectories(Paths.get(screenshotPath).getParent());
            } catch (Exception e) {
                log.warn("创建截图目录失败: {}", e.getMessage());
            }
            savedPath = appiumDriverAdapter.takeScreenshot(deviceId, screenshotPath);
            executionLog.add(ExecutionLogEntryDto.success("设置页面截图", savedPath != null ? "截图已保存:" + savedPath : "路径:" + screenshotPath));
            if (savedPath != null && !executionLog.isEmpty()) {
                executionLog.get(executionLog.size() - 1).setScreenshotPath(savedPath);
            }
        } catch (Exception e) {
            log.warn("设置页面截图失败，仍标记本次运行为成功: {}", e.getMessage());
            executionLog.add(ExecutionLogEntryDto.fail("设置页面截图", e.getMessage() != null ? e.getMessage() : "截图异常", null));
        }
        FunctionalTestLogHelper.appendSummary(executionLog);
        return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
    }

    /**
     * 执行设置-我的账号测试：先登录，进入设置页，再进入「我的账号」页并截图。
     */
    public FunctionalTestExecutionResult executeSettingsAccountTest(Task task, Device device) {
        log.info("设置-我的账号测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isSettingsTestSupported(task, app, device)) {
            throw new RuntimeException("设置-我的账号测试仅支持完美世界电竞应用(包名含 steam.esports)，当前: " + (app.getPackageName() != null ? app.getPackageName() : "无包名"));
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("设置-我的账号测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "设置-我的账号", packageOrBundle, deviceId);

        PlatformDriver platformDriver = platformRouter.getDriver(device);
        if (platformDriver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "无法获取平台驱动，请确认 appium.enabled=true 且设备可用", null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("无法获取平台驱动，请确认 appium.enabled 且设备可用");
        }
        executionLog.add(ExecutionLogEntryDto.success("环境初始化", "设置-我的账号测试-准备启动应用"));
        executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "包名:" + packageOrBundle));
        if (!platformDriver.launchApp(deviceId, packageOrBundle)) {
            executionLog.add(ExecutionLogEntryDto.fail("启动应用程序", "启动失败", packageOrBundle));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("启动应用失败: " + packageOrBundle);
        }
        executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "启动完成，等待 3 秒后检查登录状态"));
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        AppiumDriver driver = appiumDriverAdapter.getAppiumDriver(deviceId);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "启动后未获取到 Appium 会话", null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("未获取到 Appium 会话");
        }

        executionLog.add(ExecutionLogEntryDto.success("设置-我的账号测试", "开始检查登录状态并进入设置页->我的账号"));
        try {
            if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
                openSidebarAndRunSettingsFlow((AndroidDriver) driver, executionLog, task, device, deviceId, true, false, false);
            } else if (Device.usesIosAutomation(device.getPlatform())) {
                openSidebarAndRunSettingsFlow((IOSDriver) driver, executionLog, task, device, deviceId, true, false, false);
            } else {
                executionLog.add(ExecutionLogEntryDto.fail("设置-我的账号测试", "不支持的平台: " + device.getPlatform(), null));
                loginTestService.setExecutionLogForFailure(executionLog);
                throw new RuntimeException("不支持的平台: " + device.getPlatform());
            }
        } catch (Exception e) {
            log.error("设置-我的账号测试执行失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("设置-我的账号测试", e.getMessage(), null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("设置-我的账号测试执行失败: " + e.getMessage(), e);
        }

        // 截图失败只记入步骤为不通过，不导致整次运行 FAILED，报告按各步骤实际结果展示
        String screenshotPath = "/tmp/screenshots/settings_account_" + task.getId() + "_" + System.currentTimeMillis() + ".png";
        String savedPath = null;
        try {
            try {
                Files.createDirectories(Paths.get(screenshotPath).getParent());
            } catch (Exception e) {
                log.warn("创建截图目录失败: {}", e.getMessage());
            }
            savedPath = appiumDriverAdapter.takeScreenshot(deviceId, screenshotPath);
            executionLog.add(ExecutionLogEntryDto.success("我的账号页截图", savedPath != null ? "截图已保存:" + savedPath : "路径:" + screenshotPath));
            if (savedPath != null && !executionLog.isEmpty()) {
                executionLog.get(executionLog.size() - 1).setScreenshotPath(savedPath);
            }
        } catch (Exception e) {
            log.warn("我的账号页截图失败，仍标记本次运行为成功，步骤结果中记录为不通过: {}", e.getMessage());
            executionLog.add(ExecutionLogEntryDto.fail("我的账号页截图", e.getMessage() != null ? e.getMessage() : "截图异常", null));
        }
        FunctionalTestLogHelper.appendSummary(executionLog);
        return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
    }

    /**
     * 执行设置-我的兴趣测试：先登录，进入设置页，再进入「我的兴趣」页并截图。
     */
    public FunctionalTestExecutionResult executeSettingsInterestsTest(Task task, Device device) {
        log.info("设置-我的兴趣测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isSettingsTestSupported(task, app, device)) {
            throw new RuntimeException("设置-我的兴趣测试仅支持完美世界电竞应用(包名含 steam.esports)，当前: " + (app.getPackageName() != null ? app.getPackageName() : "无包名"));
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("设置-我的兴趣测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "设置-我的兴趣", packageOrBundle, deviceId);

        PlatformDriver platformDriver = platformRouter.getDriver(device);
        if (platformDriver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "无法获取平台驱动，请确认 appium.enabled=true 且设备可用", null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("无法获取平台驱动，请确认 appium.enabled 且设备可用");
        }
        executionLog.add(ExecutionLogEntryDto.success("环境初始化", "设置-我的兴趣测试-准备启动应用"));
        executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "包名:" + packageOrBundle));
        if (!platformDriver.launchApp(deviceId, packageOrBundle)) {
            executionLog.add(ExecutionLogEntryDto.fail("启动应用程序", "启动失败", packageOrBundle));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("启动应用失败: " + packageOrBundle);
        }
        executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "启动完成，等待 3 秒后检查登录状态"));
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        AppiumDriver driver = appiumDriverAdapter.getAppiumDriver(deviceId);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "启动后未获取到 Appium 会话", null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("未获取到 Appium 会话");
        }

        executionLog.add(ExecutionLogEntryDto.success("设置-我的兴趣测试", "开始检查登录状态并进入设置页->我的兴趣"));
        try {
            if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
                openSidebarAndRunSettingsFlow((AndroidDriver) driver, executionLog, task, device, deviceId, false, true, false);
            } else if (Device.usesIosAutomation(device.getPlatform())) {
                openSidebarAndRunSettingsFlow((IOSDriver) driver, executionLog, task, device, deviceId, false, true, false);
            } else {
                executionLog.add(ExecutionLogEntryDto.fail("设置-我的兴趣测试", "不支持的平台: " + device.getPlatform(), null));
                loginTestService.setExecutionLogForFailure(executionLog);
                throw new RuntimeException("不支持的平台: " + device.getPlatform());
            }
        } catch (Exception e) {
            log.error("设置-我的兴趣测试执行失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("设置-我的兴趣测试", e.getMessage(), null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("设置-我的兴趣测试执行失败: " + e.getMessage(), e);
        }

        String screenshotPath = "/tmp/screenshots/settings_interests_" + task.getId() + "_" + System.currentTimeMillis() + ".png";
        String savedPath = null;
        try {
            try {
                Files.createDirectories(Paths.get(screenshotPath).getParent());
            } catch (Exception e) {
                log.warn("创建截图目录失败: {}", e.getMessage());
            }
            savedPath = appiumDriverAdapter.takeScreenshot(deviceId, screenshotPath);
            executionLog.add(ExecutionLogEntryDto.success("我的兴趣页截图", savedPath != null ? "截图已保存:" + savedPath : "路径:" + screenshotPath));
            if (savedPath != null && !executionLog.isEmpty()) {
                executionLog.get(executionLog.size() - 1).setScreenshotPath(savedPath);
            }
        } catch (Exception e) {
            log.warn("我的兴趣页截图失败，仍标记本次运行为成功: {}", e.getMessage());
            executionLog.add(ExecutionLogEntryDto.fail("我的兴趣页截图", e.getMessage() != null ? e.getMessage() : "截图异常", null));
        }
        FunctionalTestLogHelper.appendSummary(executionLog);
        return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
    }

    /**
     * 执行设置-高校认证测试：先登录，进入设置页，再进入「高校认证」页并截图。
     */
    public FunctionalTestExecutionResult executeSettingsSchoolCertTest(Task task, Device device) {
        log.info("设置-高校认证测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isSettingsTestSupported(task, app, device)) {
            throw new RuntimeException("设置-高校认证测试仅支持完美世界电竞应用(包名含 steam.esports)，当前: " + (app.getPackageName() != null ? app.getPackageName() : "无包名"));
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("设置-高校认证测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "设置-高校认证", packageOrBundle, deviceId);

        PlatformDriver platformDriver = platformRouter.getDriver(device);
        if (platformDriver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "无法获取平台驱动，请确认 appium.enabled=true 且设备可用", null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("无法获取平台驱动，请确认 appium.enabled 且设备可用");
        }
        executionLog.add(ExecutionLogEntryDto.success("环境初始化", "设置-高校认证测试-准备启动应用"));
        executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "包名:" + packageOrBundle));
        if (!platformDriver.launchApp(deviceId, packageOrBundle)) {
            executionLog.add(ExecutionLogEntryDto.fail("启动应用程序", "启动失败", packageOrBundle));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("启动应用失败: " + packageOrBundle);
        }
        executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "启动完成，等待 3 秒后检查登录状态"));
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        AppiumDriver driver = appiumDriverAdapter.getAppiumDriver(deviceId);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "启动后未获取到 Appium 会话", null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("未获取到 Appium 会话");
        }

        executionLog.add(ExecutionLogEntryDto.success("设置-高校认证测试", "开始检查登录状态并进入设置页->高校认证"));
        try {
            if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
                openSidebarAndRunSettingsFlow((AndroidDriver) driver, executionLog, task, device, deviceId, false, false, true);
            } else if (Device.usesIosAutomation(device.getPlatform())) {
                openSidebarAndRunSettingsFlow((IOSDriver) driver, executionLog, task, device, deviceId, false, false, true);
            } else {
                executionLog.add(ExecutionLogEntryDto.fail("设置-高校认证测试", "不支持的平台: " + device.getPlatform(), null));
                loginTestService.setExecutionLogForFailure(executionLog);
                throw new RuntimeException("不支持的平台: " + device.getPlatform());
            }
        } catch (Exception e) {
            log.error("设置-高校认证测试执行失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("设置-高校认证测试", e.getMessage(), null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("设置-高校认证测试执行失败: " + e.getMessage(), e);
        }

        String screenshotPath = "/tmp/screenshots/settings_school_cert_" + task.getId() + "_" + System.currentTimeMillis() + ".png";
        String savedPath = null;
        try {
            try {
                Files.createDirectories(Paths.get(screenshotPath).getParent());
            } catch (Exception e) {
                log.warn("创建截图目录失败: {}", e.getMessage());
            }
            savedPath = appiumDriverAdapter.takeScreenshot(deviceId, screenshotPath);
            executionLog.add(ExecutionLogEntryDto.success("高校认证页截图", savedPath != null ? "截图已保存:" + savedPath : "路径:" + screenshotPath));
            if (savedPath != null && !executionLog.isEmpty()) {
                executionLog.get(executionLog.size() - 1).setScreenshotPath(savedPath);
            }
        } catch (Exception e) {
            log.warn("高校认证页截图失败，仍标记本次运行为成功: {}", e.getMessage());
            executionLog.add(ExecutionLogEntryDto.fail("高校认证页截图", e.getMessage() != null ? e.getMessage() : "截图异常", null));
        }
        FunctionalTestLogHelper.appendSummary(executionLog);
        return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
    }

    /** 在已进入设置页的前提下：点击「我的账号」并验证进入我的账号页（Android） */
    private void runAndroidSettingsAccountFlow(AndroidDriver driver, List<ExecutionLogEntryDto> log) {
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "点击我的账号", () -> {
            waitForElement(driver, SettingsLocators.SettingsPage.MY_ACCOUNT.getBy());
            driver.findElement(SettingsLocators.SettingsPage.MY_ACCOUNT.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入我的账号页面", () -> {
            waitForElement(driver, SettingsLocators.MyAccountPage.PAGE_TITLE.getBy());
        });
    }

    /** 在已进入设置页的前提下：点击「我的账号」并验证进入我的账号页（iOS） */
    private void runIosSettingsAccountFlow(IOSDriver driver, List<ExecutionLogEntryDto> log) {
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "点击我的账号", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SettingsPage.MY_ACCOUNT.getBy());
            driver.findElement(com.mobile.standalone.locators.ios.settings.SettingsLocators.SettingsPage.MY_ACCOUNT.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入我的账号页面", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.MyAccountPage.PAGE_TITLE.getBy());
        });
    }

    /** 在已进入设置页的前提下：点击「我的兴趣」并验证进入我的兴趣页（Android） */
    private void runAndroidSettingsInterestsFlow(AndroidDriver driver, List<ExecutionLogEntryDto> log) {
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "点击我的兴趣", () -> {
            waitForElement(driver, SettingsLocators.SettingsPage.MY_INTEREST.getBy());
            driver.findElement(SettingsLocators.SettingsPage.MY_INTEREST.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入我的兴趣页面", () -> {
            waitForElement(driver, SettingsLocators.MyInterestsPage.PAGE_TITLE.getBy());
        });
    }

    /** 在已进入设置页的前提下：点击「我的兴趣」并验证进入我的兴趣页（iOS） */
    private void runIosSettingsInterestsFlow(IOSDriver driver, List<ExecutionLogEntryDto> log) {
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "点击我的兴趣", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SettingsPage.MY_INTEREST.getBy());
            driver.findElement(com.mobile.standalone.locators.ios.settings.SettingsLocators.SettingsPage.MY_INTEREST.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入我的兴趣页面", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.MyInterestsPage.PAGE_TITLE.getBy());
        });
    }

    /** 在已进入设置页的前提下：点击「高校认证」并验证进入高校认证页（Android） */
    private void runAndroidSettingsSchoolCertFlow(AndroidDriver driver, List<ExecutionLogEntryDto> log) {
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "点击高校认证", () -> {
            waitForElement(driver, SettingsLocators.SettingsPage.SCHOOL_CERT.getBy());
            driver.findElement(SettingsLocators.SettingsPage.SCHOOL_CERT.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入高校认证页面", () -> {
            waitForElement(driver, SettingsLocators.SchoolCertPage.PAGE_TITLE.getBy());
        });
    }

    /** 在已进入设置页的前提下：点击「高校认证」并验证进入高校认证页（iOS） */
    private void runIosSettingsSchoolCertFlow(IOSDriver driver, List<ExecutionLogEntryDto> log) {
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "点击高校认证", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SettingsPage.SCHOOL_CERT.getBy());
            driver.findElement(com.mobile.standalone.locators.ios.settings.SettingsLocators.SettingsPage.SCHOOL_CERT.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入高校认证页面", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SchoolCertPage.PAGE_TITLE.getBy());
        });
    }

    /**
     * 打开侧边栏 → 若已登录则跳过登录直接进设置页，否则关闭侧边栏执行登录后再进设置页。
     * includeAccountFlow：是否在进入设置页后继续执行「我的账号」子流程。
     * includeInterestsFlow：是否在进入设置页后继续执行「我的兴趣」子流程。
     * includeSchoolCertFlow：是否在进入设置页后继续执行「高校认证」子流程。
     */
    private void openSidebarAndRunSettingsFlow(AndroidDriver driver, List<ExecutionLogEntryDto> executionLog, Task task, Device device, String deviceId, boolean includeAccountFlow, boolean includeInterestsFlow, boolean includeSchoolCertFlow) {
        performStepWithScreenshot(executionLog, "点击侧边栏", deviceId, task, device, () -> {
            waitForElement(driver, SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy());
            driver.findElement(SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        if (isAlreadyLoggedIn(driver, device)) {
            log.info("设置测试: 检测到已登录，跳过登录步骤");
            executionLog.add(ExecutionLogEntryDto.success("登录状态", "已登录，跳过登录步骤"));
            runAndroidSettingsFlowFromSidebarOpen(driver, executionLog);
            if (includeAccountFlow) runAndroidSettingsAccountFlow(driver, executionLog);
            if (includeInterestsFlow) runAndroidSettingsInterestsFlow(driver, executionLog);
            if (includeSchoolCertFlow) runAndroidSettingsSchoolCertFlow(driver, executionLog);
        } else {
            driver.findElement(SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy()).click();
            sleep(500);
            executionLog.add(ExecutionLogEntryDto.success("登录状态", "未登录，执行登录流程"));
            loginTestService.setExecutionLogForFailure(executionLog);
            loginTestService.executeLoginTest(task, device, true);
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            runAndroidSettingsFlow(driver, executionLog, task.getId());
            if (includeAccountFlow) runAndroidSettingsAccountFlow(driver, executionLog);
            if (includeInterestsFlow) runAndroidSettingsInterestsFlow(driver, executionLog);
            if (includeSchoolCertFlow) runAndroidSettingsSchoolCertFlow(driver, executionLog);
        }
    }

    private void openSidebarAndRunSettingsFlow(IOSDriver driver, List<ExecutionLogEntryDto> executionLog, Task task, Device device, String deviceId, boolean includeAccountFlow, boolean includeInterestsFlow, boolean includeSchoolCertFlow) {
        performStepWithScreenshot(executionLog, "点击侧边栏", deviceId, task, device, () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy());
            driver.findElement(com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        if (isAlreadyLoggedIn(driver, device)) {
            log.info("设置测试: 检测到已登录，跳过登录步骤");
            executionLog.add(ExecutionLogEntryDto.success("登录状态", "已登录，跳过登录步骤"));
            runIosSettingsFlowFromSidebarOpen(driver, executionLog);
            if (includeAccountFlow) runIosSettingsAccountFlow(driver, executionLog);
            if (includeInterestsFlow) runIosSettingsInterestsFlow(driver, executionLog);
            if (includeSchoolCertFlow) runIosSettingsSchoolCertFlow(driver, executionLog);
        } else {
            driver.findElement(com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy()).click();
            sleep(500);
            executionLog.add(ExecutionLogEntryDto.success("登录状态", "未登录，执行登录流程"));
            loginTestService.setExecutionLogForFailure(executionLog);
            loginTestService.executeLoginTest(task, device, true);
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            runIosSettingsFlow(driver, executionLog, task.getId());
            if (includeAccountFlow) runIosSettingsAccountFlow(driver, executionLog);
            if (includeInterestsFlow) runIosSettingsInterestsFlow(driver, executionLog);
            if (includeSchoolCertFlow) runIosSettingsSchoolCertFlow(driver, executionLog);
        }
    }

    /** 侧边栏已打开时，在短超时内检查是否出现「主页」以判断已登录 */
    private boolean isAlreadyLoggedIn(AppiumDriver driver, Device device) {
        By homepageBy = Device.isAndroidOrPogAndroid(device.getPlatform())
                ? SettingsLocators.SideMenu.HOMEPAGE.getBy()
                : com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.HOMEPAGE.getBy();
        return waitForElementOptional(driver, homepageBy, CHECK_LOGIN_TIMEOUT_SECONDS);
    }

    private static boolean waitForElementOptional(AppiumDriver driver, By locator, int timeoutSeconds) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds))
                    .until(ExpectedConditions.visibilityOfElementLocated(locator));
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /** 侧边栏已打开且已登录时：验证主页 → 点击设置 → 验证设置页 */
    private void runAndroidSettingsFlowFromSidebarOpen(AndroidDriver driver, List<ExecutionLogEntryDto> log) {
        performStep(log, "验证已登录(主页)", () -> waitForElement(driver, SettingsLocators.SideMenu.HOMEPAGE.getBy()));
        performStep(log, "点击设置入口", () -> {
            waitForElement(driver, SettingsLocators.SideMenu.SETTINGS.getBy());
            driver.findElement(SettingsLocators.SideMenu.SETTINGS.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入设置页面", () -> waitForElement(driver, SettingsLocators.SettingsPage.SETTINGS_TITLE.getBy()));
    }

    /** 侧边栏已打开且已登录时：验证主页 → 点击设置 → 验证设置页 */
    private void runIosSettingsFlowFromSidebarOpen(IOSDriver driver, List<ExecutionLogEntryDto> log) {
        performStep(log, "验证已登录(主页)", () -> waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.HOMEPAGE.getBy()));
        performStep(log, "点击设置入口", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.SETTINGS.getBy());
            driver.findElement(com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.SETTINGS.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入设置页面", () -> waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SettingsPage.SETTINGS_TITLE.getBy()));
    }

    private void runAndroidSettingsFlow(AndroidDriver driver, List<ExecutionLogEntryDto> log, String taskId) {
        performStep(log, "点击侧边栏", () -> {
            waitForElement(driver, SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy());
            driver.findElement(SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证已登录(主页)", () -> {
            waitForElement(driver, SettingsLocators.SideMenu.HOMEPAGE.getBy());
        });
        performStep(log, "点击设置入口", () -> {
            waitForElement(driver, SettingsLocators.SideMenu.SETTINGS.getBy());
            driver.findElement(SettingsLocators.SideMenu.SETTINGS.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入设置页面", () -> {
            waitForElement(driver, SettingsLocators.SettingsPage.SETTINGS_TITLE.getBy());
        });
    }

    private void runIosSettingsFlow(IOSDriver driver, List<ExecutionLogEntryDto> log, String taskId) {
        performStep(log, "点击侧边栏", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy());
            driver.findElement(com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.SIDE_MENU_BUTTON.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证已登录(主页)", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.HOMEPAGE.getBy());
        });
        performStep(log, "点击设置入口", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.SETTINGS.getBy());
            driver.findElement(com.mobile.standalone.locators.ios.settings.SettingsLocators.SideMenu.SETTINGS.getBy()).click();
        });
        sleep(SLEEP_AFTER_CLICK_MS);
        performStep(log, "验证进入设置页面", () -> {
            waitForElement(driver, com.mobile.standalone.locators.ios.settings.SettingsLocators.SettingsPage.SETTINGS_TITLE.getBy());
        });
    }

    private static void performStep(List<ExecutionLogEntryDto> log, String stepName, Runnable action) {
        try {
            action.run();
            log.add(ExecutionLogEntryDto.success(stepName, ""));
        } catch (Exception e) {
            log.add(ExecutionLogEntryDto.fail(stepName, e.getMessage(), null));
            throw e;
        }
    }

    /** 执行步骤并截图（用于启动应用程序、点击侧边栏等关键步骤） */
    private void performStepWithScreenshot(List<ExecutionLogEntryDto> log, String stepName, String deviceId, Task task, Device device, Runnable action) {
        try {
            action.run();
            log.add(ExecutionLogEntryDto.success(stepName, ""));
            PlatformDriver driver = device != null ? platformRouter.getDriver(device) : null;
            takeStepScreenshotIfPossible(driver, deviceId, task, log);
        } catch (Exception e) {
            log.add(ExecutionLogEntryDto.fail(stepName, e.getMessage(), null));
            throw e;
        }
    }

    private void takeStepScreenshotIfPossible(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> log) {
        if (driver != null && deviceId != null && task != null && log != null && !log.isEmpty()) {
            loginTestService.takeScreenshotForLastEntry(driver, deviceId, task, log);
        }
    }

    private static void waitForElement(AppiumDriver driver, By locator) {
        new WebDriverWait(driver, Duration.ofSeconds(WAIT_SECONDS))
                .until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
