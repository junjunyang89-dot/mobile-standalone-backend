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
 * CS-动态-关注更多战队选手测试（功能基础质量 - Android）。
 * 步骤：启动→登录→CS→动态→选择关注按钮→选择关注页(未出现则跳过)→选手/战队/赛事/其他/选手(向上滑动2次)→返回。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsDynamicFollowMoreTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** CS 元素 */
    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    /** 动态 元素 */
    private static final String DYNAMIC_XPATH = "xpath:(//android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/ivImage'])[2]";
    /** 选择关注 按钮（CS-动态页） */
    private static final String SELECT_FOLLOW_BTN_XPATH = "xpath://android.view.View[@resource-id='app']/android.view.View[8]/android.view.View";
    /** 选择关注 页面标题 */
    private static final String SELECT_FOLLOW_PAGE_XPATH = "xpath://android.view.View[@text='选择关注']";
    /** 取消关注 按钮 */
    private static final String UNFOLLOW_BTN_XPATH = "xpath:(//android.widget.Button[@text='取消关注'])[1]";
    /** + 关注 按钮 */
    private static final String FOLLOW_BTN_XPATH = "xpath://android.widget.Button[@text='+ 关注']";
    /** 战队 */
    private static final String TEAM_TAB_XPATH = "xpath://android.view.View[@text='战队']";
    /** 赛事 */
    private static final String EVENT_TAB_XPATH = "xpath://android.view.View[@text='赛事']";
    /** 其他 */
    private static final String OTHER_TAB_XPATH = "xpath://android.view.View[@text='其他']";
    /** 选手 */
    private static final String PLAYER_TAB_XPATH = "xpath://android.view.View[@text='选手']";
    /** 返回 按钮 */
    private static final String BACK_BTN_XPATH = "xpath://android.widget.TextView[@text='\ue903']";

    private static final int WAIT_AFTER_LAUNCH_MS = 3000;
    private static final int WAIT_FOR_CS_ELEMENT_SECONDS = 15;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_AFTER_CLICK_MS = 2000;
    private static final int WAIT_FOR_SELECT_FOLLOW_SECONDS = 8;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int SCROLL_INTERVAL_MS = 500;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsDynamicFollowMoreSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsDynamicFollowMoreTest(Task task, Device device) {
        log.info("CS-动态-关注更多战队选手测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isCsDynamicFollowMoreSupported(task, app, device)) {
            throw new RuntimeException("CS-动态-关注更多战队选手测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-动态-关注更多战队选手测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-动态-关注更多战队选手测试", packageOrBundle, deviceId);

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
            executionLog.add(ExecutionLogEntryDto.success("等待页面就绪", "启动后延迟 " + WAIT_AFTER_LAUNCH_MS + " ms"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            if (!ensureCsElementAndClick(driver, deviceId, task, executionLog)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待CS元素", "超时未找到 CS 元素", CS_XPATH));
                throw new RuntimeException("CS 元素未出现");
            }
            sleep(WAIT_AFTER_CLICK_MS);

            executionLog.add(ExecutionLogEntryDto.success("点击动态", "点击 动态 元素"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, DYNAMIC_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击动态", "未找到或无法点击 动态 元素", DYNAMIC_XPATH));
                throw new RuntimeException("点击 动态 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击动态", "已点击 动态"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            executionLog.add(ExecutionLogEntryDto.success("点击选择关注", "在 CS-动态 页判断并点击「选择关注」按钮"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.waitForElement(deviceId, SELECT_FOLLOW_BTN_XPATH, WAIT_FOR_SELECT_FOLLOW_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击选择关注", "未找到「选择关注」按钮", SELECT_FOLLOW_BTN_XPATH));
                throw new RuntimeException("选择关注 按钮未出现");
            }
            if (!driver.clickElement(deviceId, SELECT_FOLLOW_BTN_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击选择关注", "无法点击「选择关注」按钮", null));
                throw new RuntimeException("点击 选择关注 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击选择关注", "已点击，进入选择关注页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            if (!driver.waitForElement(deviceId, SELECT_FOLLOW_PAGE_XPATH, WAIT_FOR_SELECT_FOLLOW_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.success("选择关注页", "未出现「选择关注」页面，后续步骤不执行"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                FunctionalTestLogHelper.appendSummary(executionLog);
                return new FunctionalTestExecutionResult(null, executionLog, null);
            }
            executionLog.add(ExecutionLogEntryDto.success("选择关注页", "已出现「选择关注」页面，继续执行"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            doFollowOrUnfollowThenFollow(driver, deviceId, task, executionLog, "首项");

            executionLog.add(ExecutionLogEntryDto.success("点击战队", "点击 战队 Tab"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (driver.clickElement(deviceId, TEAM_TAB_XPATH)) {
                sleep(WAIT_AFTER_CLICK_MS);
                doFollowOrUnfollowThenFollow(driver, deviceId, task, executionLog, "战队");
            }

            executionLog.add(ExecutionLogEntryDto.success("点击赛事", "点击 赛事 Tab"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (driver.clickElement(deviceId, EVENT_TAB_XPATH)) {
                sleep(WAIT_AFTER_CLICK_MS);
                doFollowOrUnfollowThenFollow(driver, deviceId, task, executionLog, "赛事");
            }

            executionLog.add(ExecutionLogEntryDto.success("点击其他", "点击 其他 Tab"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            driver.clickElement(deviceId, OTHER_TAB_XPATH);
            sleep(WAIT_AFTER_CLICK_MS);

            executionLog.add(ExecutionLogEntryDto.success("点击选手", "点击 选手 Tab"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (driver.clickElement(deviceId, PLAYER_TAB_XPATH)) {
                sleep(WAIT_AFTER_CLICK_MS);
                int centerX = 540;
                int startY = 1200;
                int endY = 800;
                for (int i = 0; i < 2; i++) {
                    driver.swipe(deviceId, centerX, startY, centerX, endY, SCROLL_DURATION_MS);
                    executionLog.add(ExecutionLogEntryDto.success("向上滑动", "选手页第 " + (i + 1) + " 次"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    sleep(SCROLL_INTERVAL_MS);
                }
            }

            executionLog.add(ExecutionLogEntryDto.success("点击返回", "点击返回按钮"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            driver.clickElement(deviceId, BACK_BTN_XPATH);
            takeStepScreenshot(driver, deviceId, task, executionLog);

            FunctionalTestLogHelper.appendSummary(executionLog);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-动态-关注更多战队选手测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            FunctionalTestLogHelper.appendSummary(executionLog);
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

    private void doFollowOrUnfollowThenFollow(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog, String tabName) {
        if (driver.isElementPresent(deviceId, UNFOLLOW_BTN_XPATH)) {
            executionLog.add(ExecutionLogEntryDto.success(tabName + "-取消关注", "点击取消关注"));
            driver.clickElement(deviceId, UNFOLLOW_BTN_XPATH);
            sleep(500);
        }
        if (driver.isElementPresent(deviceId, FOLLOW_BTN_XPATH)) {
            executionLog.add(ExecutionLogEntryDto.success(tabName + "-关注", "点击 + 关注"));
            driver.clickElement(deviceId, FOLLOW_BTN_XPATH);
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(500);
        }
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("cs_dynamic_follow_more", task != null ? task.getId() : null, stepIndex);
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
