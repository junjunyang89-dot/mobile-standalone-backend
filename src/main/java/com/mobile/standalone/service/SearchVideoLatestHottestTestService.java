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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 搜索-最新/最热视频播放测试（功能基础质量 - Android）。
 * 步骤：启动应用 → 判断登录 → 未登录则登录 → 点击搜索放大镜 → 输入 Kak1 → 点击搜索 →
 * 点击视频 Tab → 判断最新 → 随机选 1-5 视频播放 → 无论是否加载超时均点击返回至视频页 →
 * 判断最热 → 点击最热 → 随机选 1-5 视频播放 → 无论是否加载超时均点击返回至视频页 →
 * 校验返回最热页 → 持续向上滑动直至出现 srl_classics_title。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchVideoLatestHottestTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String SEARCH_KEYWORD = "Kak1";
    private static final String SEARCH_ICON_ID = "id:com.pwrd.steam.esports:id/ivSearch";
    private static final String SEARCH_BUTTON_ID = "id:com.pwrd.steam.esports:id/tv_search";
    private static final String SEARCH_INPUT_ID = "id:com.pwrd.steam.esports:id/et_search";
    private static final String SEARCH_INPUT_FALLBACK = "xpath:(//android.widget.EditText)[1]";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** 视频 Tab：模糊匹配 视频(数字) */
    private static final String TAB_VIDEO_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and contains(@text,'视频')]";
    /** 最新 */
    private static final String LATEST_XPATH = "xpath://android.widget.TextView[@text='最新']";
    /** 最热 */
    private static final String HOTTEST_XPATH = "xpath://android.widget.TextView[@text='最热']";
    /** 视频封面 ImageView，索引 1-5 随机 */
    private static final String VIDEO_IMAGE_BASE = "xpath:(//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/iv_image'])";
    /** 未找到该帖子或加载超时 */
    private static final String LOAD_TIMEOUT_XPATH = "xpath://android.widget.TextView[@text='未找到该帖子或加载超时']";
    /** 返回按钮：多种选择器，按顺序尝试（加载超时页可能使用不同结构） */
    private static final String[] BACK_BUTTON_SELECTORS = {
            "xpath://android.view.View[@text=\"\ue903\"]",
            "id:com.pwrd.steam.esports:id/title_bar_back_icon",
            "id:com.pwrd.steam.esports:id/ps_iv_left_back",
            "xpath://android.widget.TextView[@text='返回']",
            "xpath://android.widget.ImageView[contains(@content-desc,'返回') or contains(@content-desc,'back')]"
    };
    /** 滑动目标元素 */
    private static final String SRL_CLASSICS_TITLE_XPATH = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/srl_classics_title']";
    /** 滑动基准元素：在 tv_nickname[1] 位置执行向上滑动 */
    private static final String SCROLL_ANCHOR_XPATH = "xpath:(//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_nickname'])[1]";

    private static final int WAIT_AFTER_SEARCH_MS = 5000;
    private static final int WAIT_AFTER_CLICK_MS = 2000;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int MAX_SCROLL_ATTEMPTS = 30;
    private static final int MAX_BACK_RETRIES = 8;
    private static final Random RANDOM = new Random();

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isSearchVideoLatestHottestSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeSearchVideoLatestHottestTest(Task task, Device device) {
        log.info("搜索-最新/最热视频播放测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isSearchVideoLatestHottestSupported(task, app, device)) {
            throw new RuntimeException("搜索-最新/最热视频播放测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("搜索-最新/最热视频播放测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "搜索-最新/最热视频播放测试", packageOrBundle, deviceId);

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
            // 1) 点击搜索放大镜
            logAndScreenshot(executionLog, "点击搜索", "点击放大镜搜索图标");
            if (!driver.clickElement(deviceId, SEARCH_ICON_ID)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击搜索", "未找到或无法点击搜索图标", SEARCH_ICON_ID));
                throw new RuntimeException("点击搜索图标失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击搜索", "已点击 ivSearch"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(1500);

            // 2) 输入 Kak1
            String inputSelector = driver.isElementPresent(deviceId, SEARCH_INPUT_ID) ? SEARCH_INPUT_ID : SEARCH_INPUT_FALLBACK;
            if (!driver.waitForElement(deviceId, inputSelector, 8)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待搜索输入框", "未找到搜索输入框", inputSelector));
                throw new RuntimeException("未找到搜索输入框");
            }
            executionLog.add(ExecutionLogEntryDto.success("输入搜索关键词", "等待输入框就绪"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.inputTextToElement(deviceId, inputSelector, SEARCH_KEYWORD)) {
                executionLog.add(ExecutionLogEntryDto.fail("输入搜索关键词", "输入失败", inputSelector));
                throw new RuntimeException("输入搜索关键词失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("输入搜索关键词", "已输入: " + SEARCH_KEYWORD));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(500);

            // 3) 点击搜索按钮
            if (!driver.clickElement(deviceId, SEARCH_BUTTON_ID)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击搜索按钮", "未找到或无法点击", SEARCH_BUTTON_ID));
                throw new RuntimeException("点击搜索按钮失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击搜索按钮", "已点击 tv_search"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_SEARCH_MS);

            // 4) 点击视频 Tab（模糊匹配 视频(数字)）
            logAndScreenshot(executionLog, "点击视频Tab", "点击视频(?) Tab");
            if (!driver.clickElement(deviceId, TAB_VIDEO_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击视频Tab", "未找到视频 Tab", TAB_VIDEO_XPATH));
                throw new RuntimeException("点击视频 Tab 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击视频Tab", "已点击视频 Tab"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            // 5) 判断是否出现「最新」
            if (driver.isElementPresent(deviceId, LATEST_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("判断最新", "已出现「最新」"));
                takeStepScreenshot(driver, deviceId, task, executionLog);

                // 6) 随机选择视频播放（1-5）
                int videoIndex = 1 + RANDOM.nextInt(5);
                String videoXpath = VIDEO_IMAGE_BASE + "[" + videoIndex + "]";
                executionLog.add(ExecutionLogEntryDto.success("随机选视频", "随机选择第 " + videoIndex + " 个视频播放"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                if (driver.clickElement(deviceId, videoXpath)) {
                    executionLog.add(ExecutionLogEntryDto.success("播放视频", "已点击第 " + videoIndex + " 个视频"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    sleep(WAIT_AFTER_CLICK_MS);

                    // 7) 若出现「未找到该帖子或加载超时」立即点击返回；无论何种情况均确保返回至视频页面
                    ensureBackToVideoPage(driver, deviceId, task, executionLog, "最新");
                } else {
                    executionLog.add(ExecutionLogEntryDto.fail("播放视频", "未找到或无法点击第 " + videoIndex + " 个视频", videoXpath));
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("判断最新", "未出现「最新」，继续后续步骤"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            }

            // 8) 判断是否出现「最热」
            if (driver.isElementPresent(deviceId, HOTTEST_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("判断最热", "已出现「最热」"));
                takeStepScreenshot(driver, deviceId, task, executionLog);

                // 9) 点击最热
                if (driver.clickElement(deviceId, HOTTEST_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击最热", "已点击「最热」"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    sleep(WAIT_AFTER_CLICK_MS);

                    // 10) 随机选择视频播放（1-5）
                    int videoIndex2 = 1 + RANDOM.nextInt(5);
                    String videoXpath2 = VIDEO_IMAGE_BASE + "[" + videoIndex2 + "]";
                    executionLog.add(ExecutionLogEntryDto.success("随机选视频(最热)", "随机选择第 " + videoIndex2 + " 个视频播放"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    if (driver.clickElement(deviceId, videoXpath2)) {
                        executionLog.add(ExecutionLogEntryDto.success("播放视频(最热)", "已点击第 " + videoIndex2 + " 个视频"));
                        takeStepScreenshot(driver, deviceId, task, executionLog);
                        sleep(WAIT_AFTER_CLICK_MS);

                        // 11) 若出现「未找到该帖子或加载超时」立即点击返回；无论何种情况均确保返回至视频页面
                        ensureBackToVideoPage(driver, deviceId, task, executionLog, "最热");
                    } else {
                        executionLog.add(ExecutionLogEntryDto.fail("播放视频(最热)", "未找到或无法点击第 " + videoIndex2 + " 个视频", videoXpath2));
                    }
                } else {
                    executionLog.add(ExecutionLogEntryDto.fail("点击最热", "未找到或无法点击「最热」", HOTTEST_XPATH));
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("判断最热", "未出现「最热」，继续后续步骤"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            }

            // 12) 返回至最热页面后不再点击，直接执行向上滑动（不依赖「最热」元素，确保滑动一定会执行）
            sleep(1500);
            executionLog.add(ExecutionLogEntryDto.success("向上滑动", "在 tv_nickname[1] 位置处向上滚动滑动，直至出现 srl_classics_title"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            scrollUpUntilElement(driver, deviceId, task, executionLog);

            executionLog.add(ExecutionLogEntryDto.success("报告", "搜索-最新/最热视频播放测试执行完成,正在汇总数据并生成报告"));
            executionLog.add(ExecutionLogEntryDto.success("报告", "报告已生成,任务完成"));
            executionLog.add(ExecutionLogEntryDto.success("测试服务", "测试服务执行完毕"));
            FunctionalTestLogHelper.appendSummary(executionLog);

            String screenshotPath = "/tmp/screenshots/search_video_lh_" + task.getId() + "_" + System.currentTimeMillis() + ".png";
            try {
                Files.createDirectories(Paths.get(screenshotPath).getParent());
            } catch (Exception e) {
                log.warn("创建截图目录失败: {}", e.getMessage());
            }
            String savedPath = driver.takeScreenshot(deviceId, screenshotPath);
            return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
        } catch (Exception e) {
            log.error("搜索-最新/最热视频播放测试执行失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            throw new RuntimeException("搜索-最新/最热视频播放测试执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 确保返回至视频页面。若出现「未找到该帖子或加载超时」立即点击返回；
     * 循环点击返回直至回到视频页面（视频Tab或最热可见），最多重试 MAX_BACK_RETRIES 次。
     * 优先尝试多种返回按钮选择器，均失败时使用设备返回键兜底。
     */
    private void ensureBackToVideoPage(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog, String context) {
        for (int i = 0; i < MAX_BACK_RETRIES; i++) {
            if (isOnVideoPage(driver, deviceId)) {
                if (i > 0) {
                    executionLog.add(ExecutionLogEntryDto.success("返回视频页(" + context + ")", "已返回至视频页面"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                }
                return;
            }
            if (driver.isElementPresent(deviceId, LOAD_TIMEOUT_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("检测加载超时(" + context + ")", "出现「未找到该帖子或加载超时」，立即点击返回"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                sleep(1000);
            }
            boolean clicked = tryClickBackButton(driver, deviceId, task, executionLog, context, i + 1);
            if (!clicked) {
                executionLog.add(ExecutionLogEntryDto.fail("点击返回(" + context + ")", "所有返回按钮选择器均失败，已尝试设备返回键", null));
                takeStepScreenshot(driver, deviceId, task, executionLog);
            }
            sleep(800);
        }
        executionLog.add(ExecutionLogEntryDto.success("返回尝试结束(" + context + ")", "已尝试返回 " + MAX_BACK_RETRIES + " 次，继续后续步骤"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    private boolean tryClickBackButton(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog, String context, int attempt) {
        for (String selector : BACK_BUTTON_SELECTORS) {
            if (driver.clickElement(deviceId, selector)) {
                executionLog.add(ExecutionLogEntryDto.success("点击返回(" + context + ")", "第 " + attempt + " 次点击返回成功"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                return true;
            }
        }
        if (driver.pressBack(deviceId)) {
            executionLog.add(ExecutionLogEntryDto.success("设备返回键(" + context + ")", "第 " + attempt + " 次使用设备返回键"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            return true;
        }
        return false;
    }

    private boolean isOnVideoPage(PlatformDriver driver, String deviceId) {
        return driver.isElementPresent(deviceId, TAB_VIDEO_XPATH) || driver.isElementPresent(deviceId, HOTTEST_XPATH) || driver.isElementPresent(deviceId, LATEST_XPATH);
    }

    private void scrollUpUntilElement(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        int centerX = 540;
        int startY = 1200;
        int endY = 800;
        int[] anchor = driver.getElementCenter(deviceId, SCROLL_ANCHOR_XPATH);
        if (anchor != null && anchor.length >= 2) {
            centerX = anchor[0];
            int centerY = anchor[1];
            startY = Math.min(centerY + 200, 1800);
            endY = Math.max(centerY - 200, 200);
        }
        for (int i = 0; i < MAX_SCROLL_ATTEMPTS; i++) {
            if (driver.isElementPresent(deviceId, SRL_CLASSICS_TITLE_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("滑动完成", "已出现 srl_classics_title，共滑动 " + (i + 1) + " 次"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                return;
            }
            driver.swipe(deviceId, centerX, startY, centerX, endY, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向上滑动", "第 " + (i + 1) + " 次滑动"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(500);
        }
        executionLog.add(ExecutionLogEntryDto.success("滑动结束", "滑动 " + MAX_SCROLL_ATTEMPTS + " 次后未找到 srl_classics_title，继续完成测试"));
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    private void logAndScreenshot(List<ExecutionLogEntryDto> executionLog, String step, String message) {
        executionLog.add(ExecutionLogEntryDto.success(step, message));
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("search_video_lh", task != null ? task.getId() : null, stepIndex);
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
