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
 * CS-评论热评内容测试（功能基础质量 - Android）。
 * 步骤：启动→登录→CS→热评→点击评论内容(tv_comment_content)→进入评论页→向上滑动（出现「已显示全部」则停止，未出现则滑动几次后也停止）→输入不错→表情包→选择不上班→发布→点击关闭→点击返回(返回至 CS-热评 页面)。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsCommentHotCommentContentTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** CS 元素 */
    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    /** 热评 元素 */
    private static final String HOT_COMMENT_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='热评']";
    /** 评论内容元素（模糊匹配：tv_comment_content 任意文本） */
    private static final String COMMENT_CONTENT_XPATH = "xpath:(//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_comment_content'])[1]";
    /** 评论页标识（?条回复，模糊匹配） */
    private static final String REPLY_COUNT_XPATH = "xpath://android.view.View[contains(@text,'条回复')]";
    /** 评论输入框：j-comment-comp 下的 View[4] 为主，其余为备选 */
    private static final String[] REPLY_INPUT_XPATHS = {
        "xpath://android.view.View[@resource-id='j-comment-comp']/android.view.View/android.view.View[4]",
        "xpath:(//android.view.View[contains(@text,'回复')])[1]",
        "xpath:(//android.widget.EditText[contains(@hint,'回复')])[1]",
        "xpath:(//*[contains(@content-desc,'回复')])[1]",
        "xpath:(//android.widget.EditText)[last()]",
    };
    /** 表情包按钮 */
    private static final String EMOJI_BTN_XPATH = "xpath://android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_emoji']";
    /** 表情「不上班」 */
    private static final String EMOJI_ITEM_XPATH = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/text_view' and @text='不上班']";
    /** 发布按钮 */
    private static final String SEND_BTN_XPATH = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_comment_send']";
    /** 关闭按钮（评论发布完成后点击） */
    private static final String CLOSE_BTN_XPATH = "xpath://android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_close']";
    /** 返回按钮（返回至 CS-热评 页面） */
    private static final String BACK_BTN_XPATH = "xpath://android.view.View[@text='\ue903']";

    private static final String COMMENT_TEXT = "不错";
    /** 向上滑动：出现「已显示全部」则停止；未出现则滑动几次后也停止 */
    private static final int MAX_SCROLL_ATTEMPTS = 10;

    /** 已显示全部（向上滑动直到出现则停止） */
    private static final String ALL_LOADED_XPATH = "xpath://android.view.View[@text='已显示全部']";

    private static final int WAIT_AFTER_LAUNCH_MS = 3000;
    private static final int WAIT_FOR_CS_ELEMENT_SECONDS = 15;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_AFTER_CLICK_MS = 2000;
    private static final int WAIT_FOR_HOT_COMMENT_SECONDS = 5;
    private static final int WAIT_FOR_ELEMENT_SECONDS = 5;
    private static final int WAIT_FOR_REPLY_INPUT_SECONDS = 12;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int SCROLL_INTERVAL_MS = 500;
    private static final int WAIT_AFTER_INPUT_CLICK_MS = 800;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsCommentHotCommentContentSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsCommentHotCommentContentTest(Task task, Device device) {
        log.info("CS-评论热评内容测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isCsCommentHotCommentContentSupported(task, app, device)) {
            throw new RuntimeException("CS-评论热评内容测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-评论热评内容测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-评论热评内容测试", packageOrBundle, deviceId);

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
            CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_comment_hot_content", 0);
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

            executionLog.add(ExecutionLogEntryDto.success("判断评论内容", "判断是否出现热评评论内容(tv_comment_content)"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.waitForElement(deviceId, COMMENT_CONTENT_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.success("评论内容", "未出现评论内容元素，跳过"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                FunctionalTestLogHelper.appendSummary(executionLog);
                CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_comment_hot_content", executionLog.size() - 1);
                return new FunctionalTestExecutionResult(null, executionLog, null);
            }
            executionLog.add(ExecutionLogEntryDto.success("点击评论内容", "评论内容已出现，执行点击"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, COMMENT_CONTENT_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击评论内容", "无法点击评论内容", COMMENT_CONTENT_XPATH));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                FunctionalTestLogHelper.appendSummary(executionLog);
                CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_comment_hot_content", executionLog.size() - 1);
                return new FunctionalTestExecutionResult(null, executionLog, null);
            }
            executionLog.add(ExecutionLogEntryDto.success("点击评论内容", "已点击，等待进入评论页"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            executionLog.add(ExecutionLogEntryDto.success("判断评论页", "判断是否出现「?条回复」进入热评评论页"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.waitForElement(deviceId, REPLY_COUNT_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.success("评论页", "未出现「条回复」标识，跳过评论发布"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                FunctionalTestLogHelper.appendSummary(executionLog);
                CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_comment_hot_content", executionLog.size() - 1);
                return new FunctionalTestExecutionResult(null, executionLog, null);
            }
            executionLog.add(ExecutionLogEntryDto.success("评论页", "已进入热评评论页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            scrollUpUntilAllLoaded(driver, deviceId, task, executionLog);
            scrollDownToShowInput(driver, deviceId, task, executionLog);
            executeCommentReplyAndPublish(driver, deviceId, task, executionLog);

            FunctionalTestLogHelper.appendSummary(executionLog);
            CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_comment_hot_content", executionLog.size() - 1);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-评论热评内容测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            FunctionalTestLogHelper.appendSummary(executionLog);
            CsScreenshotHelper.takeScreenshotForEntry(driver, deviceId, task, executionLog, "cs_comment_hot_content", executionLog.size() - 1);
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

    private void scrollUpUntilAllLoaded(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        int centerX = 540;
        int startYUp = 1200;
        int endYUp = 800;

        for (int i = 0; i < MAX_SCROLL_ATTEMPTS; i++) {
            if (driver.isElementPresent(deviceId, ALL_LOADED_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("向上滑动", "已出现「已显示全部」，停止滑动"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                return;
            }
            driver.swipe(deviceId, centerX, startYUp, centerX, endYUp, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向上滑动", "评论页第 " + (i + 1) + " 次，寻找「已显示全部」"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(SCROLL_INTERVAL_MS);
        }
        executionLog.add(ExecutionLogEntryDto.success("向上滑动结束", "滑动 " + MAX_SCROLL_ATTEMPTS + " 次后未找到「已显示全部」，停止滑动"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    /** 向下滑动若干次，使底部评论输入框进入可视区域 */
    private void scrollDownToShowInput(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        int centerX = 540;
        int startYDown = 800;
        int endYDown = 1200;
        int scrollDownCount = 3;
        executionLog.add(ExecutionLogEntryDto.success("向下滑动", "使底部评论输入框进入可视区域"));
        for (int i = 0; i < scrollDownCount; i++) {
            driver.swipe(deviceId, centerX, startYDown, centerX, endYDown, SCROLL_DURATION_MS);
            sleep(SCROLL_INTERVAL_MS);
        }
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    /** 依次尝试多种选择器，返回第一个找到的元素对应的选择器。每个选择器等待约 3 秒。 */
    private String findReplyInputSelector(PlatformDriver driver, String deviceId, List<ExecutionLogEntryDto> executionLog) {
        int waitPerSelector = Math.max(2, WAIT_FOR_REPLY_INPUT_SECONDS / REPLY_INPUT_XPATHS.length);
        for (int i = 0; i < REPLY_INPUT_XPATHS.length; i++) {
            String xpath = REPLY_INPUT_XPATHS[i];
            if (driver.waitForElement(deviceId, xpath, waitPerSelector)) {
                executionLog.add(ExecutionLogEntryDto.success("评论输入框", "使用选择器 " + (i + 1) + "/" + REPLY_INPUT_XPATHS.length + " 找到"));
                return xpath;
            }
        }
        return null;
    }

    private void executeCommentReplyAndPublish(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        executionLog.add(ExecutionLogEntryDto.success("判断评论输入框", "判断是否出现回复输入框(尝试多种选择器)"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
        String replyInputSelector = findReplyInputSelector(driver, deviceId, executionLog);
        if (replyInputSelector == null) {
            executionLog.add(ExecutionLogEntryDto.fail("评论输入框", "未找到回复输入框(已尝试 View.text/EditText.hint/content-desc/EditText)，跳过评论发布", null));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            return;
        }
        executionLog.add(ExecutionLogEntryDto.success("点击评论输入框", "必须先点击输入框才能执行后续输入"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
        if (!driver.clickElement(deviceId, replyInputSelector)) {
            executionLog.add(ExecutionLogEntryDto.fail("点击评论输入框", "无法点击回复输入框，后续输入无法执行", replyInputSelector));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            return;
        }
        executionLog.add(ExecutionLogEntryDto.success("点击评论输入框", "已点击，等待键盘弹出"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
        sleep(WAIT_AFTER_INPUT_CLICK_MS);
        executionLog.add(ExecutionLogEntryDto.success("输入评论", "输入「" + COMMENT_TEXT + "」"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
        boolean inputOk = driver.inputText(deviceId, COMMENT_TEXT);
        if (!inputOk) {
            executionLog.add(ExecutionLogEntryDto.success("输入评论", "全局输入失败，尝试直接向输入框元素输入"));
            inputOk = driver.inputTextToElement(deviceId, replyInputSelector, COMMENT_TEXT);
        }
        if (!inputOk) {
            executionLog.add(ExecutionLogEntryDto.fail("输入评论", "无法输入评论内容（需先点击输入框聚焦）", null));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            return;
        }
        executionLog.add(ExecutionLogEntryDto.success("输入评论", "已输入「" + COMMENT_TEXT + "」"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
        sleep(300);
        executionLog.add(ExecutionLogEntryDto.success("点击表情包", "点击表情包按钮弹出表情选择"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
        if (!driver.clickElement(deviceId, EMOJI_BTN_XPATH)) {
            executionLog.add(ExecutionLogEntryDto.success("点击表情包", "未找到表情包按钮，跳过表情"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
        } else {
            sleep(500);
            executionLog.add(ExecutionLogEntryDto.success("选择表情", "选择表情「不上班」"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (driver.waitForElement(deviceId, EMOJI_ITEM_XPATH, WAIT_FOR_ELEMENT_SECONDS) && driver.clickElement(deviceId, EMOJI_ITEM_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("选择表情", "已选择表情「不上班」"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            } else {
                executionLog.add(ExecutionLogEntryDto.success("选择表情", "未找到表情「不上班」，继续发布"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            }
        }
        sleep(300);
        executionLog.add(ExecutionLogEntryDto.success("点击发布", "点击发布按钮"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
        if (driver.isElementPresent(deviceId, SEND_BTN_XPATH) && driver.clickElement(deviceId, SEND_BTN_XPATH)) {
            executionLog.add(ExecutionLogEntryDto.success("点击发布", "已点击发布，评论已发布"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(500);
            executionLog.add(ExecutionLogEntryDto.success("点击关闭", "评论发布完成，点击关闭按钮"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (driver.waitForElement(deviceId, CLOSE_BTN_XPATH, WAIT_FOR_ELEMENT_SECONDS) && driver.clickElement(deviceId, CLOSE_BTN_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("点击关闭", "已点击关闭"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            } else {
                executionLog.add(ExecutionLogEntryDto.success("点击关闭", "未找到关闭按钮，跳过"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            }
            sleep(500);
            executionLog.add(ExecutionLogEntryDto.success("点击返回", "点击返回按钮，返回至 CS-热评 页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (driver.waitForElement(deviceId, BACK_BTN_XPATH, WAIT_FOR_ELEMENT_SECONDS) && driver.clickElement(deviceId, BACK_BTN_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("点击返回", "已点击返回"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            } else {
                executionLog.add(ExecutionLogEntryDto.success("点击返回", "未找到返回按钮，跳过"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            }
        } else {
            executionLog.add(ExecutionLogEntryDto.fail("点击发布", "无法点击发布按钮", SEND_BTN_XPATH));
            takeStepScreenshot(driver, deviceId, task, executionLog);
        }
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        CsScreenshotHelper.takeStepScreenshot(driver, deviceId, task, logs, "cs_comment_hot_content");
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
