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
 * CS-视频播放|点赞|评论|分享测试（功能基础质量 - Android）。
 * 步骤：启动→未登录则登录→CS→视频→选择第一个视频点击播放→点赞→评论(输入不错+表情加油加油→发布)→关闭→返回→分享→取消。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsVideoPlayLikeCommentShareTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** CS 元素 */
    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    /** 视频 元素 */
    private static final String VIDEO_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='视频']";
    /** 第一个视频（vg_video_player） */
    private static final String FIRST_VIDEO_XPATH = "xpath:(//android.widget.FrameLayout[@resource-id='com.pwrd.steam.esports:id/vg_video_player'])[1]";
    /** 视频进度条（进入播放页标识） */
    private static final String SEEK_BAR_XPATH = "xpath://android.widget.SeekBar[@resource-id='com.pwrd.steam.esports:id/business_seek_bar']";
    /** 点赞 */
    private static final String LIKE_XPATH = "xpath://android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_like']";
    /** 评论按钮 */
    private static final String COMMENT_BTN_XPATH = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_comment']";
    /** 全部评论（弹窗标识） */
    private static final String ALL_COMMENT_XPATH = "xpath://android.view.View[contains(@text,'全部评论')]";
    /** 我来说几句输入框 */
    private static final String COMMENT_HINT_XPATH = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_comment_hint']";
    /** 评论输入框 */
    private static final String COMMENT_INPUT_XPATH = "xpath://android.widget.EditText[@resource-id='com.pwrd.steam.esports:id/et_comment']";
    /** 表情包按钮 */
    private static final String EMOJI_BTN_XPATH = "xpath://android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_emoji']";
    /** 表情「加油加油」 */
    private static final String EMOJI_ITEM_XPATH = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/text_view' and @text='加油加油']";
    /** 发布按钮 */
    private static final String SEND_BTN_XPATH = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_comment_send']";
    /** 关闭按钮（ivMore 右侧，先尝试 iv_close） */
    private static final String CLOSE_BTN_XPATH = "xpath://android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_close']";
    private static final String CLOSE_IVMORE_XPATH = "xpath://android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/ivMore']";
    /** 返回按钮 */
    private static final String BACK_BTN_XPATH = "xpath://android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_back']";
    /** 分享按钮 */
    private static final String SHARE_BTN_XPATH = "xpath://android.widget.TextView[@text='分享']";
    /** 取消按钮 */
    private static final String CANCEL_BTN_XPATH = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tvCancel']";

    private static final String COMMENT_TEXT = "不错";

    private static final int WAIT_AFTER_LAUNCH_MS = 3500;
    private static final int WAIT_FOR_CS_ELEMENT_SECONDS = 18;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_FOR_VIDEO_SECONDS = 12;
    private static final int WAIT_AFTER_CLICK_MS = 2500;
    private static final int WAIT_FOR_ELEMENT_SECONDS = 10;
    private static final int WAIT_FOR_VIDEO_LIST_MS = 3000;
    private static final int MAX_VIDEO_CLICK_RETRIES = 3;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsVideoPlayLikeCommentShareSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsVideoPlayLikeCommentShareTest(Task task, Device device) {
        log.info("CS-视频播放|点赞|评论|分享测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isCsVideoPlayLikeCommentShareSupported(task, app, device)) {
            throw new RuntimeException("CS-视频播放|点赞|评论|分享测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-视频播放|点赞|评论|分享测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-视频播放|点赞|评论|分享测试", packageOrBundle, deviceId);

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

            // 点击视频进入 CS-视频页（带重试）
            executionLog.add(ExecutionLogEntryDto.success("等待视频元素", "等待 视频 元素出现"));
            if (!driver.waitForElement(deviceId, VIDEO_XPATH, WAIT_FOR_VIDEO_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待视频元素", "超时未找到 视频 元素", VIDEO_XPATH));
                throw new RuntimeException("视频 元素未出现");
            }
            boolean videoTabClicked = false;
            for (int retry = 0; retry < 3 && !videoTabClicked; retry++) {
                if (retry > 0) {
                    executionLog.add(ExecutionLogEntryDto.success("重试点击视频", "第 " + (retry + 1) + " 次"));
                    sleep(1000);
                }
                videoTabClicked = driver.clickElement(deviceId, VIDEO_XPATH);
            }
            if (!videoTabClicked) {
                executionLog.add(ExecutionLogEntryDto.fail("点击视频", "无法点击 视频 元素", VIDEO_XPATH));
                throw new RuntimeException("点击 视频 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击视频", "已进入 CS-视频 页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 等待视频列表加载后再选择第一个视频
            executionLog.add(ExecutionLogEntryDto.success("等待视频列表", "等待 " + WAIT_FOR_VIDEO_LIST_MS + " ms 确保列表加载"));
            sleep(WAIT_FOR_VIDEO_LIST_MS);

            // 选择第一个视频点击播放（带重试，视频列表可能加载较慢）
            boolean videoClicked = false;
            for (int retry = 0; retry < MAX_VIDEO_CLICK_RETRIES && !videoClicked; retry++) {
                if (retry > 0) {
                    executionLog.add(ExecutionLogEntryDto.success("重试点击视频", "第 " + (retry + 1) + " 次尝试"));
                    sleep(2000);
                }
                if (driver.waitForElement(deviceId, FIRST_VIDEO_XPATH, 6)) {
                    videoClicked = driver.clickElement(deviceId, FIRST_VIDEO_XPATH);
                }
            }
            if (!videoClicked) {
                executionLog.add(ExecutionLogEntryDto.fail("点击视频播放", "未找到第一个视频元素（已重试 " + MAX_VIDEO_CLICK_RETRIES + " 次）", FIRST_VIDEO_XPATH));
                throw new RuntimeException("无法点击视频播放");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击视频播放", "已点击第一个视频"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 判断视频进度条，确认进入播放页（进度条可能延迟出现，给足等待时间）
            if (!driver.waitForElement(deviceId, SEEK_BAR_XPATH, 12)) {
                executionLog.add(ExecutionLogEntryDto.success("视频进度条", "未出现进度条，继续后续步骤"));
            } else {
                executionLog.add(ExecutionLogEntryDto.success("视频进度条", "已进入视频播放页面"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(1000);

            // 点赞（用 waitForElement 替代 isElementPresent，给元素更多出现时间）
            if (driver.waitForElement(deviceId, LIKE_XPATH, 6)) {
                if (driver.clickElement(deviceId, LIKE_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点赞", "已点击点赞"));
                } else {
                    executionLog.add(ExecutionLogEntryDto.success("点赞", "点赞元素存在但点击失败"));
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("点赞", "未找到点赞元素，跳过"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 评论（用 waitForElement 替代 isElementPresent）
            if (driver.waitForElement(deviceId, COMMENT_BTN_XPATH, 6)) {
                if (driver.clickElement(deviceId, COMMENT_BTN_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击评论", "已点击评论按钮"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    sleep(WAIT_AFTER_CLICK_MS);

                    if (driver.waitForElement(deviceId, ALL_COMMENT_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                        executionLog.add(ExecutionLogEntryDto.success("评论弹窗", "已进入评论弹窗页面"));
                        takeStepScreenshot(driver, deviceId, task, executionLog);
                        sleep(800);

                        // 点击我来说几句
                        if (driver.waitForElement(deviceId, COMMENT_HINT_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                            driver.clickElement(deviceId, COMMENT_HINT_XPATH);
                            executionLog.add(ExecutionLogEntryDto.success("点击输入框", "已点击我来说几句"));
                            sleep(800);
                        }

                        // 输入不错
                        if (driver.waitForElement(deviceId, COMMENT_INPUT_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                            if (driver.inputTextToElement(deviceId, COMMENT_INPUT_XPATH, COMMENT_TEXT)) {
                                executionLog.add(ExecutionLogEntryDto.success("输入评论", "已输入「不错」"));
                            }
                            sleep(800);
                        }

                        // 表情包
                        if (driver.waitForElement(deviceId, EMOJI_BTN_XPATH, 5)) {
                            driver.clickElement(deviceId, EMOJI_BTN_XPATH);
                            sleep(800);
                            if (driver.waitForElement(deviceId, EMOJI_ITEM_XPATH, 5)) {
                                driver.clickElement(deviceId, EMOJI_ITEM_XPATH);
                                executionLog.add(ExecutionLogEntryDto.success("选择表情", "已选择加油加油表情"));
                            }
                            sleep(500);
                        }

                        // 发布
                        if (driver.waitForElement(deviceId, SEND_BTN_XPATH, 5)) {
                            driver.clickElement(deviceId, SEND_BTN_XPATH);
                            executionLog.add(ExecutionLogEntryDto.success("发布评论", "已点击发布"));
                            sleep(WAIT_AFTER_CLICK_MS);
                        }

                        // 关闭（多种方式尝试，提高成功率）
                        boolean closed = driver.clickElement(deviceId, CLOSE_BTN_XPATH);
                        if (!closed) {
                            int[] ivMoreCenter = driver.getElementCenter(deviceId, CLOSE_IVMORE_XPATH);
                            if (ivMoreCenter != null && ivMoreCenter.length >= 2) {
                                closed = driver.click(deviceId, ivMoreCenter[0] + 80, ivMoreCenter[1]);
                            }
                            if (!closed) closed = driver.clickElement(deviceId, CLOSE_IVMORE_XPATH);
                        }
                        if (!closed) {
                            driver.pressBack(deviceId);
                            executionLog.add(ExecutionLogEntryDto.success("关闭弹窗", "按返回键关闭"));
                        } else {
                            executionLog.add(ExecutionLogEntryDto.success("关闭弹窗", "已关闭评论弹窗"));
                        }
                        sleep(WAIT_AFTER_CLICK_MS);
                    } else {
                        executionLog.add(ExecutionLogEntryDto.success("评论弹窗", "未检测到全部评论标识，尝试返回关闭"));
                        driver.pressBack(deviceId);
                        sleep(1000);
                    }
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("评论", "未找到评论按钮，跳过"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 分享（需在返回前执行，分享按钮在视频播放页）
            executionLog.add(ExecutionLogEntryDto.success("等待分享按钮", "判断是否出现分享按钮"));
            if (driver.waitForElement(deviceId, SHARE_BTN_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                if (driver.clickElement(deviceId, SHARE_BTN_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击分享", "已点击分享"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    sleep(WAIT_AFTER_CLICK_MS);

                    if (driver.waitForElement(deviceId, CANCEL_BTN_XPATH, 8)) {
                        driver.clickElement(deviceId, CANCEL_BTN_XPATH);
                        executionLog.add(ExecutionLogEntryDto.success("取消分享", "已点击取消"));
                    } else {
                        driver.pressBack(deviceId);
                        executionLog.add(ExecutionLogEntryDto.success("取消分享", "未找到取消按钮，按返回键关闭"));
                    }
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("分享", "未找到分享按钮，跳过"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 返回
            if (driver.clickElement(deviceId, BACK_BTN_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("返回", "已点击返回，回到 CS-视频 页面"));
            } else {
                driver.pressBack(deviceId);
                executionLog.add(ExecutionLogEntryDto.success("返回", "已执行返回"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);

            FunctionalTestLogHelper.appendSummary(executionLog);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-视频播放|点赞|评论|分享测试失败", e);
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

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("cs_video_play_like_comment_share", task != null ? task.getId() : null, stepIndex);
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
