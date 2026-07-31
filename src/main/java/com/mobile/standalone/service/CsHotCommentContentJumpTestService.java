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
 * CS-热评内容跳转测试（功能基础质量 - Android）。
 * 步骤：启动→登录→CS→热评→若出现评论内容(vg_quote)则点击。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsHotCommentContentJumpTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** CS 元素 */
    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    /** 热评 元素 */
    private static final String HOT_COMMENT_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='热评']";
    /** 评论内容元素（首条） */
    private static final String QUOTE_CONTENT_XPATH = "xpath:(//android.widget.FrameLayout[@resource-id='com.pwrd.steam.esports:id/vg_quote'])[1]";

    private static final int WAIT_AFTER_LAUNCH_MS = 3000;
    private static final int WAIT_FOR_CS_ELEMENT_SECONDS = 15;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_AFTER_CLICK_MS = 2000;
    private static final int WAIT_FOR_HOT_COMMENT_SECONDS = 5;
    private static final int WAIT_FOR_QUOTE_SECONDS = 5;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsHotCommentContentJumpSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsHotCommentContentJumpTest(Task task, Device device) {
        log.info("CS-热评内容跳转测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isCsHotCommentContentJumpSupported(task, app, device)) {
            throw new RuntimeException("CS-热评内容跳转测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-热评内容跳转测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-热评内容跳转测试", packageOrBundle, deviceId);

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
            sleep(WAIT_AFTER_LAUNCH_MS);
            CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_hot_comment_content_jump", 0);
            executionLog.add(ExecutionLogEntryDto.success("等待页面就绪", "启动后延迟 " + WAIT_AFTER_LAUNCH_MS + " ms"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            if (!ensureCsElementAndClick(driver, deviceId, task, executionLog)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待CS元素", "超时未找到 CS 元素", CS_XPATH));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                throw new RuntimeException("CS 元素未出现");
            }
            sleep(WAIT_AFTER_CLICK_MS);

            executionLog.add(ExecutionLogEntryDto.success("判断热评", "判断是否出现 热评 元素"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.waitForElement(deviceId, HOT_COMMENT_XPATH, WAIT_FOR_HOT_COMMENT_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.fail("判断热评", "未出现 热评 元素", HOT_COMMENT_XPATH));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                throw new RuntimeException("热评 元素未出现");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击热评", "热评 已出现，点击进入热评页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, HOT_COMMENT_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击热评", "无法点击 热评 元素", HOT_COMMENT_XPATH));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                throw new RuntimeException("点击 热评 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击热评", "已点击 热评"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            executionLog.add(ExecutionLogEntryDto.success("判断评论内容", "判断是否出现热评评论内容"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (driver.waitForElement(deviceId, QUOTE_CONTENT_XPATH, WAIT_FOR_QUOTE_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.success("点击评论内容", "评论内容已出现，执行点击"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                if (driver.clickElement(deviceId, QUOTE_CONTENT_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击评论内容", "已点击评论内容，完成内容跳转"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                } else {
                    executionLog.add(ExecutionLogEntryDto.fail("点击评论内容", "无法点击评论内容", QUOTE_CONTENT_XPATH));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("评论内容", "未出现评论内容元素，跳过点击"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            }

            FunctionalTestLogHelper.appendSummary(executionLog);
            CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_hot_comment_content_jump", executionLog.size() - 1);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-热评内容跳转测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            FunctionalTestLogHelper.appendSummary(executionLog);
            CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_hot_comment_content_jump", executionLog.size() - 1);
            throw e;
        }
    }

    private boolean ensureCsElementAndClick(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        for (int retry = 0; retry < MAX_CS_RETRIES; retry++) {
            executionLog.add(ExecutionLogEntryDto.success("等待CS元素", "第 " + (retry + 1) + " 次尝试"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
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

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        CsScreenshotHelper.takeStepScreenshot(driver, deviceId, task, logs, "cs_hot_comment_content_jump");
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
