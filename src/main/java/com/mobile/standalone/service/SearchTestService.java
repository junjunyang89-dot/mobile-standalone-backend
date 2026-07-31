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
 * 搜索自动化测试服务（功能基础质量 - 搜索场景）。
 * 先根据登录测试判断是否登录，未登录则执行登录；已登录则点击搜索、输入关键词、点击搜索按钮，
 * 等待后验证结果元素（tv_nickname 且 text=Kak1）出现即成功。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String SEARCH_KEYWORD = "Kak1";
    private static final String SEARCH_ICON_ID = "id:com.pwrd.steam.esports:id/ivSearch";
    private static final String SEARCH_BUTTON_ID = "id:com.pwrd.steam.esports:id/tv_search";
    /** 搜索输入框，常见 id；若无则尝试首个 EditText */
    private static final String SEARCH_INPUT_ID = "id:com.pwrd.steam.esports:id/et_search";
    private static final String SEARCH_INPUT_FALLBACK = "xpath:(//android.widget.EditText)[1]";
    /** 搜索结果昵称元素，resource-id=tv_nickname 且 text=Kak1 即成功 */
    private static final String RESULT_NICKNAME_XPATH = "xpath://android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_nickname' and @text='" + SEARCH_KEYWORD + "']";
    private static final String RESULT_NICKNAME_ID = "id:com.pwrd.steam.esports:id/tv_nickname";
    private static final String LOGIN_UNLOCK_XPATH = "xpath://android.widget.TextView[@text='登录注册解锁更多功能']";
    private static final int WAIT_AFTER_SEARCH_MS = 5000;
    private static final int VERIFY_TIMEOUT_SECONDS = 15;
    private static final int POLL_INTERVAL_MS = 500;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isSearchTestSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        if (!Device.isAndroidOrPogAndroid(device.getPlatform()) && !Device.usesIosAutomation(device.getPlatform())) return false;
        return app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    /**
     * 执行搜索测试：先登录（若未登录），再执行搜索并验证结果。
     */
    public FunctionalTestExecutionResult executeSearchTest(Task task, Device device) {
        log.info("搜索测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isSearchTestSupported(task, app, device)) {
            throw new RuntimeException("搜索测试仅支持完美世界电竞应用(包名含 steam.esports)，当前: " + (app.getPackageName() != null ? app.getPackageName() : "无包名"));
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("搜索测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "搜索测试", packageOrBundle, deviceId);

        // 1) 先执行登录测试（启动应用 + 判断登录 + 未登录则登录）
        executionLog.add(ExecutionLogEntryDto.success("搜索测试", "先执行登录测试，确保已登录"));
        try {
            FunctionalTestExecutionResult loginResult = loginTestService.executeLoginTest(task, device, false);
            if (loginResult != null && loginResult.getExecutionLog() != null) {
                executionLog.addAll(loginResult.getExecutionLog());
            }
        } catch (Exception e) {
            log.error("登录测试失败，无法继续搜索测试", e);
            executionLog.add(ExecutionLogEntryDto.fail("登录测试", "登录失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()), null));
            throw new RuntimeException("搜索测试前置登录失败: " + e.getMessage(), e);
        }

        PlatformDriver driver = platformRouter.getDriver(device);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "登录后无法获取平台驱动", null));
            throw new RuntimeException("无法获取平台驱动");
        }

        try {
            // 2) 点击搜索图标
            executionLog.add(ExecutionLogEntryDto.success("搜索测试", "点击搜索图标"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (!driver.clickElement(deviceId, SEARCH_ICON_ID)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击搜索图标", "未找到或无法点击搜索图标", SEARCH_ICON_ID));
                throw new RuntimeException("点击搜索图标失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击搜索图标", "已点击 ivSearch"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(1500);

            // 3) 等待输入框并输入关键词
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

            // 4) 点击搜索按钮
            if (!driver.clickElement(deviceId, SEARCH_BUTTON_ID)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击搜索按钮", "未找到或无法点击搜索按钮", SEARCH_BUTTON_ID));
                throw new RuntimeException("点击搜索按钮失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击搜索按钮", "已点击 tv_search"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_SEARCH_MS);

            // 5) 验证结果：出现 tv_nickname 且 text=Kak1
            boolean found = waitForResultElement(driver, deviceId, executionLog, task);
            if (!found) {
                executionLog.add(ExecutionLogEntryDto.fail("验证搜索结果", "未找到昵称为 " + SEARCH_KEYWORD + " 的结果元素(tv_nickname)", RESULT_NICKNAME_XPATH));
                throw new RuntimeException("搜索测试失败：未找到预期结果元素(tv_nickname, text=" + SEARCH_KEYWORD + ")");
            }
            executionLog.add(ExecutionLogEntryDto.success("验证搜索结果", "已找到昵称 " + SEARCH_KEYWORD + "，搜索测试成功"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            executionLog.add(ExecutionLogEntryDto.success("报告", "搜索测试执行完成,正在汇总数据并生成报告"));
            executionLog.add(ExecutionLogEntryDto.success("报告", "报告已生成,任务完成"));
            executionLog.add(ExecutionLogEntryDto.success("测试服务", "测试服务执行完毕"));
            FunctionalTestLogHelper.appendSummary(executionLog);

            String screenshotPath = "/tmp/screenshots/search_" + task.getId() + "_" + System.currentTimeMillis() + ".png";
            try {
                Files.createDirectories(Paths.get(screenshotPath).getParent());
            } catch (Exception e) {
                log.warn("创建截图目录失败: {}", e.getMessage());
            }
            String savedPath = driver.takeScreenshot(deviceId, screenshotPath);
            return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
        } catch (Exception e) {
            log.error("搜索测试执行失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            throw new RuntimeException("搜索测试执行失败: " + e.getMessage(), e);
        }
    }

    private boolean waitForResultElement(PlatformDriver driver, String deviceId, List<ExecutionLogEntryDto> logs, Task task) {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (driver.isElementPresent(deviceId, RESULT_NICKNAME_XPATH)) {
                return true;
            }
            String text = driver.getElementText(deviceId, RESULT_NICKNAME_ID);
            if (SEARCH_KEYWORD.equals(text)) {
                return true;
            }
            sleep(POLL_INTERVAL_MS);
        }
        return false;
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            String path = "/tmp/screenshots/search_" + (task != null && task.getId() != null ? task.getId() : "unknown") + "_step" + logs.size() + "_" + System.currentTimeMillis() + ".png";
            String saved = driver.takeScreenshot(deviceId, path);
            if (saved != null && !logs.isEmpty()) {
                logs.get(logs.size() - 1).setScreenshotPath(saved);
            }
        } catch (Exception e) {
            log.debug("搜索测试步骤截图失败: {}", e.getMessage());
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
