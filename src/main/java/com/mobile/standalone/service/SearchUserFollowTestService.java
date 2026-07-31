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

/**
 * 搜索用户添加关注测试（功能基础质量 - 搜索用户关注场景）。
 * 步骤：启动应用 → 判断登录 → 未登录则登录 → 点击搜索放大镜 → 输入 Kak1 → 点击搜索 →
 * 校验搜索结果页出现 Kak1 → 点击「更多」→ 进入用户 Tab → 校验用户 Kak1 出现 →
 * 若已关注则先取消再关注，否则直接点击关注。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchUserFollowTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String SEARCH_KEYWORD = "Kak1";
    private static final String SEARCH_ICON_ID = "id:com.pwrd.steam.esports:id/ivSearch";
    private static final String SEARCH_BUTTON_ID = "id:com.pwrd.steam.esports:id/tv_search";
    private static final String SEARCH_INPUT_ID = "id:com.pwrd.steam.esports:id/et_search";
    private static final String SEARCH_INPUT_FALLBACK = "xpath:(//android.widget.EditText)[1]";

    /** 搜索结果页是否出现 Kak1 文本（UiSelector instance(3) 对应 xpath 第 4 个匹配） */
    private static final String XPATH_KAK1_IN_RESULTS = "xpath:(//*[@text='Kak1'])[4]";
    /** 更多按钮：(//android.widget.LinearLayout[@resource-id='com.pwrd.steam.esports:id/vg_more'])[2] */
    private static final String XPATH_MORE_BUTTON = "xpath:(//android.widget.LinearLayout[@resource-id='com.pwrd.steam.esports:id/vg_more'])[2]";
    /** 用户 Tab 页中昵称为 Kak1 的元素 */
    private static final String XPATH_NICKNAME_KAK1 = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_nickname' and @text='Kak1']";
    /** 关注按钮（第一个） */
    private static final String XPATH_FOLLOW_BUTTON = "xpath:(//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_follow'])[1]";
    /** 已关注按钮（需先点击取消关注） */
    private static final String XPATH_FOLLOWED_BUTTON = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_follow' and @text='已关注']";

    private static final int WAIT_AFTER_SEARCH_MS = 5000;
    private static final int WAIT_FOR_ELEMENT_MS = 3000;
    private static final int CLICK_INTERVAL_MS = 800;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isSearchUserFollowSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    /**
     * 执行搜索用户添加关注测试。
     */
    public FunctionalTestExecutionResult executeSearchUserFollowTest(Task task, Device device) {
        log.info("搜索用户添加关注测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isSearchUserFollowSupported(task, app, device)) {
            throw new RuntimeException("搜索用户添加关注测试仅支持完美世界电竞 Android 应用，当前: " + (app.getPackageName() != null ? app.getPackageName() : "无包名"));
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("搜索用户添加关注测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "搜索用户添加关注测试", packageOrBundle, deviceId);

        executionLog.add(ExecutionLogEntryDto.success("搜索用户添加关注", "先执行登录测试，确保已登录"));
        try {
            FunctionalTestExecutionResult loginResult = loginTestService.executeLoginTest(task, device, false);
            if (loginResult != null && loginResult.getExecutionLog() != null) {
                executionLog.addAll(loginResult.getExecutionLog());
            }
        } catch (Exception e) {
            log.error("登录测试失败，无法继续搜索用户添加关注测试", e);
            executionLog.add(ExecutionLogEntryDto.fail("登录测试", "登录失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()), null));
            throw new RuntimeException("搜索用户添加关注测试前置登录失败: " + e.getMessage(), e);
        }

        PlatformDriver driver = platformRouter.getDriver(device);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "登录后无法获取平台驱动", null));
            throw new RuntimeException("无法获取平台驱动");
        }

        try {
            // 1) 点击搜索放大镜
            executionLog.add(ExecutionLogEntryDto.success("搜索用户添加关注", "点击搜索放大镜"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, SEARCH_ICON_ID)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击搜索放大镜", "未找到或无法点击", SEARCH_ICON_ID));
                throw new RuntimeException("点击搜索放大镜失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击搜索放大镜", "已点击"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(1500);

            // 2) 输入 Kak1
            String inputSelector = driver.isElementPresent(deviceId, SEARCH_INPUT_ID) ? SEARCH_INPUT_ID : SEARCH_INPUT_FALLBACK;
            if (!driver.waitForElement(deviceId, inputSelector, 8)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待搜索输入框", "未找到搜索输入框", inputSelector));
                throw new RuntimeException("未找到搜索输入框");
            }
            executionLog.add(ExecutionLogEntryDto.success("搜索输入框已出现", "元素已定位"));
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
            executionLog.add(ExecutionLogEntryDto.success("点击搜索按钮", "已点击"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_SEARCH_MS);

            // 4) 校验搜索结果页是否出现 Kak1 文本
            if (!driver.waitForElement(deviceId, XPATH_KAK1_IN_RESULTS, 10)) {
                executionLog.add(ExecutionLogEntryDto.fail("校验搜索结果", "未出现 Kak1 文本，后续步骤不再执行", XPATH_KAK1_IN_RESULTS));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                return finishWithScreenshot(driver, deviceId, task, executionLog);
            }
            executionLog.add(ExecutionLogEntryDto.success("校验搜索结果", "已出现 Kak1 文本"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(500);

            // 5) 点击「更多」按钮
            executionLog.add(ExecutionLogEntryDto.success("搜索用户添加关注", "点击更多按钮"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, XPATH_MORE_BUTTON)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击更多", "未找到或无法点击", XPATH_MORE_BUTTON));
                throw new RuntimeException("点击更多失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击更多", "已点击，进入用户Tab"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_FOR_ELEMENT_MS);

            // 6) 校验用户 Tab 页是否出现昵称 Kak1
            if (!driver.waitForElement(deviceId, XPATH_NICKNAME_KAK1, 8)) {
                executionLog.add(ExecutionLogEntryDto.fail("校验用户Tab", "未出现用户 Kak1", XPATH_NICKNAME_KAK1));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                return finishWithScreenshot(driver, deviceId, task, executionLog);
            }
            executionLog.add(ExecutionLogEntryDto.success("校验用户Tab", "已出现用户 Kak1"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(500);

            // 7) 判断是否已关注：若出现「已关注」则先点击取消，再点击关注
            if (driver.isElementPresent(deviceId, XPATH_FOLLOWED_BUTTON)) {
                executionLog.add(ExecutionLogEntryDto.success("搜索用户添加关注", "当前已关注，先点击取消关注"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                if (!driver.clickElement(deviceId, XPATH_FOLLOWED_BUTTON)) {
                    executionLog.add(ExecutionLogEntryDto.fail("取消关注", "未找到或无法点击已关注按钮", XPATH_FOLLOWED_BUTTON));
                } else {
                    executionLog.add(ExecutionLogEntryDto.success("取消关注", "已点击"));
                }
                takeStepScreenshot(driver, deviceId, task, executionLog);
                sleep(CLICK_INTERVAL_MS);
            }

            // 8) 点击关注按钮
            executionLog.add(ExecutionLogEntryDto.success("搜索用户添加关注", "点击关注按钮"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, XPATH_FOLLOW_BUTTON)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击关注", "未找到或无法点击关注按钮", XPATH_FOLLOW_BUTTON));
                throw new RuntimeException("点击关注失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击关注", "已点击，添加关注完成"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            executionLog.add(ExecutionLogEntryDto.success("报告", "搜索用户添加关注测试执行完成,正在汇总数据并生成报告"));
            executionLog.add(ExecutionLogEntryDto.success("报告", "报告已生成,任务完成"));
            executionLog.add(ExecutionLogEntryDto.success("测试服务", "测试服务执行完毕"));
            FunctionalTestLogHelper.appendSummary(executionLog);

            String screenshotPath = ScreenshotNamingHelper.buildStepPath("search_user_follow", task.getId(), executionLog.size());
            try { Files.createDirectories(Paths.get(screenshotPath).getParent()); } catch (Exception e) { log.warn("创建截图目录失败: {}", e.getMessage()); }
            String savedPath = driver.takeScreenshot(deviceId, screenshotPath);
            return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
        } catch (Exception e) {
            log.error("搜索用户添加关注测试执行失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            throw new RuntimeException("搜索用户添加关注测试执行失败: " + e.getMessage(), e);
        }
    }

    private FunctionalTestExecutionResult finishWithScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        executionLog.add(ExecutionLogEntryDto.success("报告", "搜索用户添加关注测试因校验未通过而终止"));
        FunctionalTestLogHelper.appendSummary(executionLog);
        String screenshotPath = ScreenshotNamingHelper.buildStepPath("search_user_follow", task.getId(), executionLog.size());
        try { Files.createDirectories(Paths.get(screenshotPath).getParent()); } catch (Exception ignored) {}
        String savedPath = driver.takeScreenshot(deviceId, screenshotPath);
        return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("search_user_follow", task != null ? task.getId() : null, stepIndex);
            String saved = driver.takeScreenshot(deviceId, path);
            if (saved != null && !logs.isEmpty()) {
                logs.get(logs.size() - 1).setScreenshotPath(saved);
            }
        } catch (Exception e) {
            log.debug("搜索用户添加关注步骤截图失败: {}", e.getMessage());
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
