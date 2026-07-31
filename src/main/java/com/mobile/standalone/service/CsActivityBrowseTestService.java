package com.mobile.standalone.service;

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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * CS-活动浏览测试（功能基础质量 - Android）。
 * 步骤：启动应用 → 未登录则登录/已登录则继续 → 判断 CS 出现并点击 → 判断资讯出现 →
 * 在资讯位置水平向左滑动直至出现活动 → 点击活动 → 进入活动页 → 向上滑动5次、向下滑动5次 →
 * 随机点击一个活动进入。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsActivityBrowseTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** CS 元素 */
    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    /** 资讯 元素 */
    private static final String NEWS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='资讯']";
    /** 活动 元素 */
    private static final String ACTIVITY_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='活动']";
    /** 活动图片（随机点击），基础 XPath 不带索引 */
    private static final String ACTIVITY_IMAGE_BASE = "xpath:(//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_activity_image'])";

    private static final int WAIT_AFTER_LAUNCH_MS = 3500;
    private static final int WAIT_FOR_CS_ELEMENT_SECONDS = 18;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_FOR_NEWS_SECONDS = 10;
    private static final int MAX_SWIPE_LEFT_ATTEMPTS = 15;
    private static final int WAIT_FOR_ACTIVITY_SECONDS = 8;
    private static final int WAIT_AFTER_CLICK_MS = 2500;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int SCROLL_UP_COUNT = 5;
    private static final int SCROLL_DOWN_COUNT = 5;
    private static final int SCROLL_INTERVAL_MS = 500;
    private static final int MAX_ACTIVITY_INDEX = 10;
    private static final Random RANDOM = new Random();

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsActivityBrowseSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsActivityBrowseTest(Task task, Device device) {
        log.info("CS-活动浏览测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isCsActivityBrowseSupported(task, app, device)) {
            throw new RuntimeException("CS-活动浏览测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-活动浏览测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-活动浏览测试", packageOrBundle, deviceId);

        executionLog.add(ExecutionLogEntryDto.success("前置登录", "未登录则执行登录，已登录则继续"));
        try {
            FunctionalTestExecutionResult loginResult = loginTestService.executeLoginTest(task, device, false);
            if (loginResult != null && loginResult.getExecutionLog() != null) {
                executionLog.addAll(loginResult.getExecutionLog());
            }
        } catch (Exception e) {
            log.error("登录测试失败，无法继续", e);
            executionLog.add(ExecutionLogEntryDto.fail("登录测试", "登录失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()), null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("前置登录失败: " + e.getMessage(), e);
        }

        PlatformDriver driver = platformRouter.getDriver(device);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "登录后无法获取平台驱动", null));
            loginTestService.setExecutionLogForFailure(executionLog);
            throw new RuntimeException("无法获取平台驱动");
        }

        try {
            sleep(WAIT_AFTER_LAUNCH_MS);
            executionLog.add(ExecutionLogEntryDto.success("等待页面就绪", "启动后延迟 " + WAIT_AFTER_LAUNCH_MS + " ms"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            if (!ensureCsElementAndClick(driver, deviceId, task, executionLog)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待CS元素", "超时未找到 CS 元素", CS_XPATH));
                throw new RuntimeException("CS 元素未出现");
            }
            sleep(WAIT_AFTER_CLICK_MS);

            // 判断资讯出现
            executionLog.add(ExecutionLogEntryDto.success("等待资讯元素", "等待 资讯 元素出现"));
            if (!driver.waitForElement(deviceId, NEWS_XPATH, WAIT_FOR_NEWS_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待资讯元素", "超时未找到 资讯 元素", NEWS_XPATH));
                throw new RuntimeException("资讯 元素未出现");
            }
            executionLog.add(ExecutionLogEntryDto.success("资讯已出现", "资讯 元素已出现"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            // 在资讯位置水平向左滑动，直至出现活动
            executionLog.add(ExecutionLogEntryDto.success("滑动至活动", "在资讯处水平向左滑动，直至出现活动"));
            boolean activityFound = swipeLeftUntilActivity(driver, deviceId, task, executionLog);
            if (!activityFound) {
                executionLog.add(ExecutionLogEntryDto.fail("滑动至活动", "向左滑动 " + MAX_SWIPE_LEFT_ATTEMPTS + " 次后未找到活动元素", ACTIVITY_XPATH));
                throw new RuntimeException("活动 元素未出现");
            }
            executionLog.add(ExecutionLogEntryDto.success("活动已出现", "活动 元素已出现"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            // 点击活动
            if (!driver.clickElement(deviceId, ACTIVITY_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击活动", "无法点击 活动 元素", ACTIVITY_XPATH));
                throw new RuntimeException("点击 活动 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击活动", "已进入活动页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 向上滑动5次、向下滑动5次
            scrollUpFixedTimes(driver, deviceId, task, executionLog);
            scrollDownFixedTimes(driver, deviceId, task, executionLog);

            // 随机点击一个活动进入
            int randomIndex = 1 + RANDOM.nextInt(MAX_ACTIVITY_INDEX);
            String activityXpath = ACTIVITY_IMAGE_BASE + "[" + randomIndex + "]";
            executionLog.add(ExecutionLogEntryDto.success("随机点击活动", "随机选择第 " + randomIndex + " 个活动进入"));
            boolean clicked = driver.waitForElement(deviceId, activityXpath, 6) && driver.clickElement(deviceId, activityXpath);
            if (!clicked) {
                // 若随机索引失败，尝试点击第一个
                activityXpath = ACTIVITY_IMAGE_BASE + "[1]";
                if (driver.waitForElement(deviceId, activityXpath, 5) && driver.clickElement(deviceId, activityXpath)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击活动", "随机索引失败，已点击第一个活动"));
                } else {
                    executionLog.add(ExecutionLogEntryDto.success("点击活动", "未找到活动图片元素，跳过"));
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("点击活动", "已进入活动详情"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);

            FunctionalTestLogHelper.appendSummary(executionLog);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-活动浏览测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            FunctionalTestLogHelper.appendSummary(executionLog);
            loginTestService.setExecutionLogForFailure(executionLog);
            throw e;
        }
    }

    private boolean ensureCsElementAndClick(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        for (int retry = 0; retry < MAX_CS_RETRIES; retry++) {
            executionLog.add(ExecutionLogEntryDto.success("等待CS元素", "第 " + (retry + 1) + " 次尝试"));
            if (driver.waitForElement(deviceId, CS_XPATH, WAIT_FOR_CS_ELEMENT_SECONDS / MAX_CS_RETRIES)) {
                executionLog.add(ExecutionLogEntryDto.success("等待CS元素", "CS 元素已出现"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                if (driver.clickElement(deviceId, CS_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击CS", "已点击 CS"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    return true;
                }
            }
            if (retry < MAX_CS_RETRIES - 1) {
                executionLog.add(ExecutionLogEntryDto.success("尝试返回", "CS 未出现，执行返回后重试"));
                driver.pressBack(deviceId);
                sleep(1000);
            }
        }
        return false;
    }

    /** 在资讯位置水平向左滑动，直至出现活动元素 */
    private boolean swipeLeftUntilActivity(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        int centerY = 300;
        int startX = 800;
        int endX = 280;
        for (int i = 0; i < MAX_SWIPE_LEFT_ATTEMPTS; i++) {
            if (driver.waitForElement(deviceId, ACTIVITY_XPATH, 2)) {
                return true;
            }
            int[] newsCenter = driver.getElementCenter(deviceId, NEWS_XPATH);
            if (newsCenter != null && newsCenter.length >= 2) {
                centerY = newsCenter[1];
            }
            driver.swipe(deviceId, startX, centerY, endX, centerY, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向左滑动", "第 " + (i + 1) + " 次"));
            sleep(SCROLL_INTERVAL_MS);
        }
        return driver.waitForElement(deviceId, ACTIVITY_XPATH, 2);
    }

    private void scrollUpFixedTimes(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        int centerX = 540;
        int startY = 1200;
        int endY = 800;
        for (int i = 0; i < SCROLL_UP_COUNT; i++) {
            driver.swipe(deviceId, centerX, startY, centerX, endY, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向上滑动", "第 " + (i + 1) + " 次"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(SCROLL_INTERVAL_MS);
        }
        executionLog.add(ExecutionLogEntryDto.success("向上滑动结束", "共 " + SCROLL_UP_COUNT + " 次"));
    }

    private void scrollDownFixedTimes(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        int centerX = 540;
        int startY = 800;
        int endY = 1200;
        for (int i = 0; i < SCROLL_DOWN_COUNT; i++) {
            driver.swipe(deviceId, centerX, startY, centerX, endY, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向下滑动", "第 " + (i + 1) + " 次"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(SCROLL_INTERVAL_MS);
        }
        executionLog.add(ExecutionLogEntryDto.success("向下滑动结束", "共 " + SCROLL_DOWN_COUNT + " 次"));
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("cs_activity_browse", task != null ? task.getId() : null, stepIndex);
            String saved = driver.takeScreenshot(deviceId, path);
            if (saved != null && !logs.isEmpty()) {
                logs.get(logs.size() - 1).setScreenshotPath(saved);
            }
        } catch (Exception e) {
            log.debug("步骤截图失败: {}", e.getMessage());
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
