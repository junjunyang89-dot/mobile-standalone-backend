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
 * 搜索顶部 Tab 切换测试（功能基础质量 - 搜索 Tab 场景）。
 * 步骤：启动应用 → 判断登录 → 未登录则登录 → 点击搜索放大镜 → 输入 Kak1 → 点击搜索按钮 →
 * 校验「综合」Tab 出现 → 依次点击 新闻、社区、视频、梗百科、选手、战队、用户 Tab。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchTabSwitchTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String SEARCH_KEYWORD = "Kak1";
    private static final String SEARCH_ICON_ID = "id:com.pwrd.steam.esports:id/ivSearch";
    private static final String SEARCH_BUTTON_ID = "id:com.pwrd.steam.esports:id/tv_search";
    private static final String SEARCH_INPUT_ID = "id:com.pwrd.steam.esports:id/et_search";
    private static final String SEARCH_INPUT_FALLBACK = "xpath:(//android.widget.EditText)[1]";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    /** 综合 Tab，需先出现才继续后续 Tab 切换 */
    private static final String TAB_ZONGHE_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='综合']";
    /** 新闻、社区、梗百科、选手、战队：精确匹配 */
    private static final String TAB_NEWS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='新闻']";
    private static final String TAB_SHEQU_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='社区']";
    private static final String TAB_GENGBAIKE_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='梗百科']";
    private static final String TAB_XUANSHOU_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='选手']";
    private static final String TAB_ZHANDUI_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='战队']";
    /** 视频(?) 用户(?) 模糊匹配：contains */
    private static final String TAB_VIDEO_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and contains(@text,'视频')]";
    private static final String TAB_USER_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and contains(@text,'用户')]";

    private static final int WAIT_AFTER_SEARCH_MS = 5000;
    private static final int WAIT_FOR_TAB_MS = 3000;
    private static final int CLICK_INTERVAL_MS = 800;

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isSearchTabSwitchSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    /**
     * 执行搜索 Tab 切换测试。
     */
    public FunctionalTestExecutionResult executeSearchTabSwitchTest(Task task, Device device) {
        log.info("搜索Tab切换测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId());
        }
        if (!isSearchTabSwitchSupported(task, app, device)) {
            throw new RuntimeException("搜索Tab切换测试仅支持完美世界电竞 Android 应用，当前: " + (app.getPackageName() != null ? app.getPackageName() : "无包名"));
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("搜索Tab切换测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        String packageOrBundle = app.getPackageName().trim();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "搜索Tab切换测试", packageOrBundle, deviceId);

        executionLog.add(ExecutionLogEntryDto.success("搜索Tab切换", "先执行登录测试，确保已登录"));
        try {
            FunctionalTestExecutionResult loginResult = loginTestService.executeLoginTest(task, device, false);
            if (loginResult != null && loginResult.getExecutionLog() != null) {
                executionLog.addAll(loginResult.getExecutionLog());
            }
        } catch (Exception e) {
            log.error("登录测试失败，无法继续搜索Tab切换测试", e);
            executionLog.add(ExecutionLogEntryDto.fail("登录测试", "登录失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()), null));
            throw new RuntimeException("搜索Tab切换测试前置登录失败: " + e.getMessage(), e);
        }

        PlatformDriver driver = platformRouter.getDriver(device);
        if (driver == null) {
            executionLog.add(ExecutionLogEntryDto.fail("获取驱动", "登录后无法获取平台驱动", null));
            throw new RuntimeException("无法获取平台驱动");
        }

        try {
            // 1) 点击搜索放大镜
            executionLog.add(ExecutionLogEntryDto.success("搜索Tab切换", "点击搜索放大镜"));
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

            // 4) 校验「综合」Tab 是否出现
            if (!driver.waitForElement(deviceId, TAB_ZONGHE_XPATH, 10)) {
                executionLog.add(ExecutionLogEntryDto.fail("校验综合Tab", "未出现「综合」元素，后续步骤不再执行", TAB_ZONGHE_XPATH));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                executionLog.add(ExecutionLogEntryDto.success("报告", "搜索Tab切换测试因综合Tab未出现而终止"));
                FunctionalTestLogHelper.appendSummary(executionLog);
                String screenshotPath = ScreenshotNamingHelper.buildStepPath("search_tabs", task.getId(), executionLog.size());
                try { Files.createDirectories(Paths.get(screenshotPath).getParent()); } catch (Exception ignored) {}
                String savedPath = driver.takeScreenshot(deviceId, screenshotPath);
                return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
            }
            executionLog.add(ExecutionLogEntryDto.success("校验综合Tab", "已出现「综合」元素"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(500);

            // 5) 依次点击 Tab：新闻、社区、视频、梗百科、选手、战队、用户
            TabStep[] tabs = {
                new TabStep("新闻", TAB_NEWS_XPATH),
                new TabStep("社区", TAB_SHEQU_XPATH),
                new TabStep("视频", TAB_VIDEO_XPATH),
                new TabStep("梗百科", TAB_GENGBAIKE_XPATH),
                new TabStep("选手", TAB_XUANSHOU_XPATH),
                new TabStep("战队", TAB_ZHANDUI_XPATH),
                new TabStep("用户", TAB_USER_XPATH)
            };
            for (TabStep tab : tabs) {
                executionLog.add(ExecutionLogEntryDto.success("点击Tab", "点击「" + tab.name + "」"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                if (!driver.clickElement(deviceId, tab.xpath)) {
                    executionLog.add(ExecutionLogEntryDto.fail("点击Tab", "未找到或无法点击「" + tab.name + "」", tab.xpath));
                    break;
                }
                executionLog.add(ExecutionLogEntryDto.success("点击Tab", "已点击「" + tab.name + "」"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                sleep(CLICK_INTERVAL_MS);
            }

            executionLog.add(ExecutionLogEntryDto.success("报告", "搜索Tab切换测试执行完成,正在汇总数据并生成报告"));
            executionLog.add(ExecutionLogEntryDto.success("报告", "报告已生成,任务完成"));
            executionLog.add(ExecutionLogEntryDto.success("测试服务", "测试服务执行完毕"));
            FunctionalTestLogHelper.appendSummary(executionLog);

            String screenshotPath = "/tmp/screenshots/search_tabs_" + task.getId() + "_final_" + System.currentTimeMillis() + ".png";
            try { Files.createDirectories(Paths.get(screenshotPath).getParent()); } catch (Exception e) { log.warn("创建截图目录失败: {}", e.getMessage()); }
            String savedPath = driver.takeScreenshot(deviceId, screenshotPath);
            return new FunctionalTestExecutionResult(savedPath != null ? savedPath : screenshotPath, executionLog, null);
        } catch (Exception e) {
            log.error("搜索Tab切换测试执行失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            throw new RuntimeException("搜索Tab切换测试执行失败: " + e.getMessage(), e);
        }
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("search_tabs", task != null ? task.getId() : null, stepIndex);
            String saved = driver.takeScreenshot(deviceId, path);
            if (saved != null && !logs.isEmpty()) {
                logs.get(logs.size() - 1).setScreenshotPath(saved);
            }
        } catch (Exception e) {
            log.debug("搜索Tab切换步骤截图失败: {}", e.getMessage());
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class TabStep {
        final String name;
        final String xpath;

        TabStep(String name, String xpath) {
            this.name = name;
            this.xpath = xpath;
        }
    }
}
