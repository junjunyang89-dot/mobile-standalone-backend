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
 * CS资讯页浏览测试（功能基础质量 - Android）。
 * 步骤：启动应用 → 登录或未登录 → 点击 CS → 点击 资讯 → 进入资讯页后向上滑动 10 次、向下滑动 6 次。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsNewsBrowsingTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** CS 元素 */
    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    /** 资讯 元素 */
    private static final String NEWS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='资讯']";

    private static final int WAIT_AFTER_LAUNCH_MS = 3000;
    private static final int WAIT_FOR_CS_ELEMENT_SECONDS = 15;
    private static final int WAIT_AFTER_CLICK_MS = 2000;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int SCROLL_UP_COUNT = 10;
    private static final int SCROLL_DOWN_COUNT = 6;
    private static final int SCROLL_INTERVAL_MS = 500;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsNewsBrowsingSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsNewsBrowsingTest(Task task, Device device) {
        log.info("CS资讯页浏览测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isCsNewsBrowsingSupported(task, app, device)) {
            throw new RuntimeException("CS资讯页浏览测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS资讯页浏览测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS资讯页浏览测试", packageOrBundle, deviceId);

        executionLog.add(ExecutionLogEntryDto.success("前置登录", "先执行登录测试，确保已登录"));
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
            // 0) 启动后延迟等待，并判断 CS 元素出现
            executionLog.add(ExecutionLogEntryDto.success("等待页面就绪", "启动后延迟 " + WAIT_AFTER_LAUNCH_MS + " ms"));
            sleep(WAIT_AFTER_LAUNCH_MS);
            takeStepScreenshot(driver, deviceId, task, executionLog);
            executionLog.add(ExecutionLogEntryDto.success("等待CS元素", "等待 CS 元素出现，超时 " + WAIT_FOR_CS_ELEMENT_SECONDS + " 秒"));
            if (!driver.waitForElement(deviceId, CS_XPATH, WAIT_FOR_CS_ELEMENT_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待CS元素", "超时未找到 CS 元素", CS_XPATH));
                throw new RuntimeException("CS 元素未出现，超时 " + WAIT_FOR_CS_ELEMENT_SECONDS + " 秒");
            }
            executionLog.add(ExecutionLogEntryDto.success("等待CS元素", "CS 元素已出现"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            // 1) 点击 CS
            executionLog.add(ExecutionLogEntryDto.success("点击CS", "点击 CS 元素"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, CS_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击CS", "未找到或无法点击 CS 元素", CS_XPATH));
                throw new RuntimeException("点击 CS 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击CS", "已点击 CS"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 2) 点击 资讯
            executionLog.add(ExecutionLogEntryDto.success("点击资讯", "点击 资讯 元素"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, NEWS_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击资讯", "未找到或无法点击 资讯 元素", NEWS_XPATH));
                throw new RuntimeException("点击 资讯 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击资讯", "已点击 资讯，进入资讯页"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 3) 向上滑动 10 次，向下滑动 6 次
            scrollUpFixedTimes(driver, deviceId, task, executionLog);
            scrollDownFixedTimes(driver, deviceId, task, executionLog);

            FunctionalTestLogHelper.appendSummary(executionLog);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS资讯页浏览测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            FunctionalTestLogHelper.appendSummary(executionLog);
            throw e;
        }
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
        executionLog.add(ExecutionLogEntryDto.success("向上滑动结束", "共完成 " + SCROLL_UP_COUNT + " 次"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
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
        executionLog.add(ExecutionLogEntryDto.success("向下滑动结束", "共完成 " + SCROLL_DOWN_COUNT + " 次"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("cs_news_browsing", task != null ? task.getId() : null, stepIndex);
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
