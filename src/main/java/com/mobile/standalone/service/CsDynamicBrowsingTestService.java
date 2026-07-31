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

/**
 * CS-动态页浏览测试（功能基础质量 - Android）。
 * 步骤：启动应用 → 未登录则登录/已登录则继续 → 判断 CS 元素出现，未出现则点击进入 → 点击动态 → 进入 CS-动态页后向上滑动5次、向下滑动5次，循环直到出现「关注更多战队选手」。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsDynamicBrowsingTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** CS 元素 */
    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    /** 动态 元素：第 2 个 ivImage */
    private static final String DYNAMIC_XPATH = "xpath:(//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/ivImage'])[2]";
    /** 关注更多战队选手 */
    private static final String TARGET_ELEMENT_XPATH = "xpath://android.view.View[@text='关注更多战队选手']";

    private static final int WAIT_AFTER_LAUNCH_MS = 3000;
    private static final int WAIT_FOR_CS_ELEMENT_SECONDS = 15;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_AFTER_CLICK_MS = 2000;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int SCROLL_UP_COUNT = 5;
    private static final int SCROLL_DOWN_COUNT = 5;
    private static final int SCROLL_INTERVAL_MS = 500;
    private static final int MAX_SCROLL_LOOPS = 30;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsDynamicBrowsingSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsDynamicBrowsingTest(Task task, Device device) {
        log.info("CS-动态页浏览测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isCsDynamicBrowsingSupported(task, app, device)) {
            throw new RuntimeException("CS-动态页浏览测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-动态页浏览测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-动态页浏览测试", packageOrBundle, deviceId);

        executionLog.add(ExecutionLogEntryDto.success("前置登录", "未登录则执行登录，已登录则继续"));
        try {
            FunctionalTestExecutionResult loginResult = loginTestService.executeLoginTest(task, device, false);
            if (loginResult != null && loginResult.getExecutionLog() != null) {
                executionLog.addAll(loginResult.getExecutionLog());
            }
        } catch (Exception e) {
            log.error("登录测试失败，无法继续", e);
            executionLog.add(ExecutionLogEntryDto.fail("登录测试", "登录失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()), null));
            throw new RuntimeException("前置登录失败: " + e.getMessage(), e);
        }

        PlatformDriver driver = platformRouter.getDriver(device);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "登录后无法获取平台驱动", null));
            throw new RuntimeException("无法获取平台驱动");
        }

        try {
            // 0) 启动后延迟
            executionLog.add(ExecutionLogEntryDto.success("等待页面就绪", "启动后延迟 " + WAIT_AFTER_LAUNCH_MS + " ms"));
            sleep(WAIT_AFTER_LAUNCH_MS);
            takeStepScreenshot(driver, deviceId, task, executionLog);

            // 1) 判断 CS 元素出现，未出现则尝试返回后重试，出现则点击
            if (!ensureCsElementAndClick(driver, deviceId, task, executionLog)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待CS元素", "超时未找到 CS 元素", CS_XPATH));
                throw new RuntimeException("CS 元素未出现");
            }
            sleep(WAIT_AFTER_CLICK_MS);

            // 2) 点击 动态
            executionLog.add(ExecutionLogEntryDto.success("点击动态", "点击 动态 元素"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, DYNAMIC_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击动态", "未找到或无法点击 动态 元素", DYNAMIC_XPATH));
                throw new RuntimeException("点击 动态 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击动态", "已点击 动态，进入 CS-动态 页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 3) 向上滑动5次、向下滑动5次，循环直到出现「关注更多战队选手」
            scrollUntilTargetAppears(driver, deviceId, task, executionLog);

            FunctionalTestLogHelper.appendSummary(executionLog);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-动态页浏览测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            FunctionalTestLogHelper.appendSummary(executionLog);
            throw e;
        }
    }

    /** 确保 CS 元素出现并点击：未出现则尝试返回后重试 */
    private boolean ensureCsElementAndClick(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        for (int retry = 0; retry < MAX_CS_RETRIES; retry++) {
            executionLog.add(ExecutionLogEntryDto.success("等待CS元素", "第 " + (retry + 1) + " 次尝试，等待 CS 元素出现"));
            if (driver.waitForElement(deviceId, CS_XPATH, WAIT_FOR_CS_ELEMENT_SECONDS / MAX_CS_RETRIES)) {
                executionLog.add(ExecutionLogEntryDto.success("等待CS元素", "CS 元素已出现"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                if (driver.clickElement(deviceId, CS_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击CS", "已点击 CS 元素"));
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

    private void scrollUntilTargetAppears(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        int centerX = 540;
        int startYUp = 1200;
        int endYUp = 800;
        int startYDown = 800;
        int endYDown = 1200;

        for (int loop = 0; loop < MAX_SCROLL_LOOPS; loop++) {
            // 每轮先执行向上5次、向下5次滑动，再检查目标元素（不在一开始检查，确保滑动必定执行）
            for (int i = 0; i < SCROLL_UP_COUNT; i++) {
                driver.swipe(deviceId, centerX, startYUp, centerX, endYUp, SCROLL_DURATION_MS);
                executionLog.add(ExecutionLogEntryDto.success("向上滑动", "第 " + (loop + 1) + " 轮 第 " + (i + 1) + " 次"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                sleep(SCROLL_INTERVAL_MS);
                if (driver.isElementPresent(deviceId, TARGET_ELEMENT_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("滑动完成", "已出现「关注更多战队选手」"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    return;
                }
            }
            for (int i = 0; i < SCROLL_DOWN_COUNT; i++) {
                driver.swipe(deviceId, centerX, startYDown, centerX, endYDown, SCROLL_DURATION_MS);
                executionLog.add(ExecutionLogEntryDto.success("向下滑动", "第 " + (loop + 1) + " 轮 第 " + (i + 1) + " 次"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                sleep(SCROLL_INTERVAL_MS);
                if (driver.isElementPresent(deviceId, TARGET_ELEMENT_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("滑动完成", "已出现「关注更多战队选手」"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    return;
                }
            }
        }
        executionLog.add(ExecutionLogEntryDto.success("滑动结束", "滑动 " + MAX_SCROLL_LOOPS + " 轮后未找到「关注更多战队选手」，继续完成测试"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("cs_dynamic_browsing", task != null ? task.getId() : null, stepIndex);
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
