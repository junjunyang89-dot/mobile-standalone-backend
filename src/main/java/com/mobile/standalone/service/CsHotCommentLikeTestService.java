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
 * CS-热评点赞测试（功能基础质量 - Android）。
 * 步骤：启动→登录→CS→热评→点击首条点赞→向上滑动直到排行N（模糊匹配）→出现排行元素则执行点赞（模糊匹配 iv_like 索引）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsHotCommentLikeTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** CS 元素 */
    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    /** 热评 元素 */
    private static final String HOT_COMMENT_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='热评']";
    /** 首条点赞 */
    private static final String FIRST_LIKE_XPATH = "xpath:(//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_like'])[1]";
    /** 排行元素（模糊匹配：任意整数 1、2、3...） */
    private static final String RANK_ANY_XPATH = "xpath:(//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_hot_rank'])[1]";
    /** 排行同行右侧点赞：通过父节点查找同行的 iv_like，取最后一个（最右侧） */
    private static final String RANK_ANY_LIKE_XPATH = "xpath:(//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_hot_rank'])[1]/..//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_like'][last()]";

    private static final int WAIT_AFTER_LAUNCH_MS = 3000;
    private static final int WAIT_FOR_CS_ELEMENT_SECONDS = 15;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_AFTER_CLICK_MS = 2000;
    private static final int WAIT_FOR_HOT_COMMENT_SECONDS = 5;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int SCROLL_INTERVAL_MS = 500;
    private static final int MAX_SCROLL_ATTEMPTS = 30;
    private static final int WAIT_AFTER_LIKE_MS = 1000;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsHotCommentLikeSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsHotCommentLikeTest(Task task, Device device) {
        log.info("CS-热评点赞测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isCsHotCommentLikeSupported(task, app, device)) {
            throw new RuntimeException("CS-热评点赞测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-热评点赞测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-热评点赞测试", packageOrBundle, deviceId);

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
            CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_hot_comment_like", 0);
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

            executionLog.add(ExecutionLogEntryDto.success("点击首条点赞", "若出现 iv_like[1] 则点击"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (driver.isElementPresent(deviceId, FIRST_LIKE_XPATH)) {
                if (driver.clickElement(deviceId, FIRST_LIKE_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击首条点赞", "已点击首条点赞"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    sleep(500);
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("点击首条点赞", "未找到首条点赞，继续后续步骤"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            }

            scrollUpUntilRankAndClickLike(driver, deviceId, task, executionLog);

            FunctionalTestLogHelper.appendSummary(executionLog);
            CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_hot_comment_like", executionLog.size() - 1);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-热评点赞测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            FunctionalTestLogHelper.appendSummary(executionLog);
            CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_hot_comment_like", executionLog.size() - 1);
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
                takeStepScreenshot(driver, deviceId, task, executionLog);
                driver.pressBack(deviceId);
                sleep(1000);
            }
        }
        return false;
    }

    private void scrollUpUntilRankAndClickLike(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        int centerX = 540;
        int startY = 1200;
        int endY = 800;
        String rankCheckXpath = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_hot_rank']";

        for (int i = 0; i < MAX_SCROLL_ATTEMPTS; i++) {
            if (driver.isElementPresent(deviceId, rankCheckXpath)) {
                String rankText = driver.getElementText(deviceId, RANK_ANY_XPATH);
                String rankLabel = (rankText != null && !rankText.isEmpty()) ? "排行" + rankText : "排行";
                executionLog.add(ExecutionLogEntryDto.success("找到排行", "已出现 " + rankLabel + " 元素"));
                takeStepScreenshot(driver, deviceId, task, executionLog);

                if (driver.isElementPresent(deviceId, RANK_ANY_LIKE_XPATH)) {
                    if (driver.clickElement(deviceId, RANK_ANY_LIKE_XPATH)) {
                        executionLog.add(ExecutionLogEntryDto.success("点击排行点赞", "已点击 " + rankLabel + " 右侧点赞按钮"));
                        takeStepScreenshot(driver, deviceId, task, executionLog);
                        takeFinalLikeResultScreenshot(driver, deviceId, task, executionLog);
                    } else {
                        executionLog.add(ExecutionLogEntryDto.fail("点击排行点赞", "无法点击 " + rankLabel + " 右侧点赞", RANK_ANY_LIKE_XPATH));
                        takeStepScreenshot(driver, deviceId, task, executionLog);
                        tryRankLikeByIndex(driver, deviceId, task, executionLog, rankLabel);
                    }
                } else {
                    tryRankLikeByIndex(driver, deviceId, task, executionLog, rankLabel);
                }
                return;
            }
            driver.swipe(deviceId, centerX, startY, centerX, endY, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向上滑动", "第 " + (i + 1) + " 次，寻找排行元素"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(SCROLL_INTERVAL_MS);
        }
        executionLog.add(ExecutionLogEntryDto.success("滑动结束", "滑动 " + MAX_SCROLL_ATTEMPTS + " 次后未找到排行元素"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    /** 兜底：按索引尝试点击 iv_like，找到与排行同行的点赞（模糊匹配，索引未知） */
    private void tryRankLikeByIndex(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog, String rankLabel) {
        int[] rankCenter = driver.getElementCenter(deviceId, RANK_ANY_XPATH);
        if (rankCenter == null || rankCenter.length < 2) {
            tryAnyLikeAsFallback(driver, deviceId, task, executionLog, rankLabel);
            return;
        }
        int rankY = rankCenter[1];
        int toleranceY = 80;

        for (int idx = 1; idx <= 15; idx++) {
            String likeXpath = "xpath:(//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_like'])[" + idx + "]";
            if (!driver.isElementPresent(deviceId, likeXpath)) break;
            int[] likeCenter = driver.getElementCenter(deviceId, likeXpath);
            if (likeCenter != null && likeCenter.length >= 2 && Math.abs(likeCenter[1] - rankY) <= toleranceY && likeCenter[0] > rankCenter[0]) {
                if (driver.clickElement(deviceId, likeXpath)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击排行点赞", "通过索引 " + idx + " 找到并点击 " + rankLabel + " 右侧点赞"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    takeFinalLikeResultScreenshot(driver, deviceId, task, executionLog);
                }
                return;
            }
        }
        tryAnyLikeAsFallback(driver, deviceId, task, executionLog, rankLabel);
    }

    /** 最终兜底：出现排行元素后，模糊匹配任意可见 iv_like 执行点赞 */
    private void tryAnyLikeAsFallback(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog, String rankLabel) {
        for (int idx = 1; idx <= 20; idx++) {
            String likeXpath = "xpath:(//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_like'])[" + idx + "]";
            if (!driver.isElementPresent(deviceId, likeXpath)) break;
            if (driver.clickElement(deviceId, likeXpath)) {
                executionLog.add(ExecutionLogEntryDto.success("点击排行点赞", "模糊匹配：已点击第 " + idx + " 个点赞按钮"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                takeFinalLikeResultScreenshot(driver, deviceId, task, executionLog);
                return;
            }
        }
        executionLog.add(ExecutionLogEntryDto.success("点击排行点赞", "未找到可点击的点赞按钮"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    /** 等待点赞状态更新后截取最终结果截图 */
    private void takeFinalLikeResultScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        sleep(WAIT_AFTER_LIKE_MS);
        executionLog.add(ExecutionLogEntryDto.success("点赞结果", "已执行点赞操作"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        CsScreenshotHelper.takeStepScreenshot(driver, deviceId, task, logs, "cs_hot_comment_like");
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
