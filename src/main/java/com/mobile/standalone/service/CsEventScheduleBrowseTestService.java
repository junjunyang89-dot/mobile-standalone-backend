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
 * CS-赛事-赛程浏览测试（功能基础质量 - Android）。
 * 步骤：启动应用 → 未登录则登录/已登录则继续 → 判断 CS 出现并点击 →
 * 判断赛事出现并点击 → 进入 CS-赛事-赛程页 → 向上滑动4次、向下滑动4次 →
 * 判断今天出现 → 在今天水平位置向左滑4次 → 点击周×（模糊匹配）→ 在周×水平位置向右滑4次 → 点击周×。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsEventScheduleBrowseTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** CS 元素 */
    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    /** 赛事 元素 */
    private static final String EVENT_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='赛事']";
    /** 今天 元素 */
    private static final String TODAY_XPATH = "xpath://android.widget.TextView[@text='今天']";
    /** 周×（周一/周二/.../周日）模糊匹配，取第一个 */
    private static final String WEEKDAY_XPATH = "xpath:(//android.widget.TextView[contains(@text,'周')])[1]";

    private static final int WAIT_AFTER_LAUNCH_MS = 3500;
    private static final int WAIT_FOR_CS_ELEMENT_SECONDS = 18;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_FOR_ELEMENT_SECONDS = 10;
    private static final int WAIT_AFTER_CLICK_MS = 2500;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int SCROLL_UP_COUNT = 4;
    private static final int SCROLL_DOWN_COUNT = 4;
    private static final int HORIZONTAL_SWIPE_COUNT = 4;
    private static final int SCROLL_INTERVAL_MS = 500;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsEventScheduleBrowseSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsEventScheduleBrowseTest(Task task, Device device) {
        log.info("CS-赛事-赛程浏览测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isCsEventScheduleBrowseSupported(task, app, device)) {
            throw new RuntimeException("CS-赛事-赛程浏览测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-赛事-赛程浏览测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-赛事-赛程浏览测试", packageOrBundle, deviceId);

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

            // 判断赛事出现并点击
            executionLog.add(ExecutionLogEntryDto.success("等待赛事元素", "等待 赛事 元素出现"));
            if (!driver.waitForElement(deviceId, EVENT_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待赛事元素", "超时未找到 赛事 元素", EVENT_XPATH));
                throw new RuntimeException("赛事 元素未出现");
            }
            if (!driver.clickElement(deviceId, EVENT_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击赛事", "无法点击 赛事 元素", EVENT_XPATH));
                throw new RuntimeException("点击 赛事 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击赛事", "已进入 CS-赛事-赛程 页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 向上滑动4次、向下滑动4次
            scrollUpFixedTimes(driver, deviceId, task, executionLog);
            scrollDownFixedTimes(driver, deviceId, task, executionLog);

            // 判断今天出现
            executionLog.add(ExecutionLogEntryDto.success("等待今天元素", "判断是否出现 今天"));
            if (driver.waitForElement(deviceId, TODAY_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.success("今天已出现", "今天 元素已出现"));
                int[] todayCenter = driver.getElementCenter(deviceId, TODAY_XPATH);
                int centerY = (todayCenter != null && todayCenter.length >= 2) ? todayCenter[1] : 300;
                // 在今天水平位置向左滑4次
                for (int i = 0; i < HORIZONTAL_SWIPE_COUNT; i++) {
                    driver.swipe(deviceId, 800, centerY, 280, centerY, SCROLL_DURATION_MS);
                    executionLog.add(ExecutionLogEntryDto.success("向左滑动", "第 " + (i + 1) + " 次"));
                    sleep(SCROLL_INTERVAL_MS);
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("今天", "未找到今天元素，跳过日期滑动"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);

            // 点击周×（模糊匹配 周一/周二/.../周日）
            executionLog.add(ExecutionLogEntryDto.success("点击周×", "模糊匹配 周× 并点击"));
            if (driver.waitForElement(deviceId, WEEKDAY_XPATH, 6) && driver.clickElement(deviceId, WEEKDAY_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("点击周×", "已点击周×"));
                String weekdayText = driver.getElementText(deviceId, WEEKDAY_XPATH);
                if (weekdayText != null) executionLog.add(ExecutionLogEntryDto.success("周×", "当前为 " + weekdayText));
            } else {
                executionLog.add(ExecutionLogEntryDto.success("点击周×", "未找到周×元素，跳过"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 在周×水平位置向右滑4次
            if (driver.waitForElement(deviceId, WEEKDAY_XPATH, 3)) {
                int[] weekdayCenter = driver.getElementCenter(deviceId, WEEKDAY_XPATH);
                if (weekdayCenter != null && weekdayCenter.length >= 2) {
                    int centerY = weekdayCenter[1];
                    for (int i = 0; i < HORIZONTAL_SWIPE_COUNT; i++) {
                        driver.swipe(deviceId, 280, centerY, 800, centerY, SCROLL_DURATION_MS);
                        executionLog.add(ExecutionLogEntryDto.success("向右滑动", "第 " + (i + 1) + " 次"));
                        sleep(SCROLL_INTERVAL_MS);
                    }
                }
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);

            // 再次点击周×
            executionLog.add(ExecutionLogEntryDto.success("再次点击周×", "模糊匹配 周× 并点击"));
            if (driver.waitForElement(deviceId, WEEKDAY_XPATH, 5) && driver.clickElement(deviceId, WEEKDAY_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("点击周×", "已点击周×"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);

            FunctionalTestLogHelper.appendSummary(executionLog);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-赛事-赛程浏览测试失败", e);
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
            String path = ScreenshotNamingHelper.buildStepPath("cs_event_schedule_browse", task != null ? task.getId() : null, stepIndex);
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
