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
import java.util.Random;

/**
 * CS-已结束比赛-赛事详情-数据测试（功能基础质量 - Android）。
 * 步骤：启动→登录→CS→赛事→随机点击已结束→数据页(上下滑动)→若存在地图BP则执行地图分析→总览→基础数据/对位数据→若存在地图BP则查找地图(1/2/3个)依次点击，地图页若对位数据不存在则跳过后续。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsEndedMatchDetailDataTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    private static final String EVENT_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='赛事']";
    private static final String ENDED_BASE = "xpath:(//android.view.View[@text='已结束'])";
    private static final String DATA_TAB_XPATH = "xpath:(//android.view.View[@text='数据'])[2]";
    private static final String MAP_BP_XPATH = "xpath://android.widget.TextView[@text='地图BP']";
    private static final String MAP_ANALYSIS_XPATH = "xpath://android.widget.TextView[@text='地图分析']";
    private static final String BACK_BUTTON_XPATH = "xpath://android.view.View[@text='\ue903']";
    private static final String OVERVIEW_XPATH = "xpath://android.view.View[@text='总览']";
    private static final String ALL_TAB_XPATH = "xpath://android.view.View[@text='全部']";
    private static final String T_SIDE_XPATH = "xpath://android.view.View[@text='T阵营']";
    private static final String CT_SIDE_XPATH = "xpath://android.view.View[@text='CT阵营']";
    private static final String DUWEI_DATA_XPATH = "xpath://android.view.View[@text='对位数据']";
    private static final String FIRST_KILL_XPATH = "xpath://android.view.View[@text='首杀']";
    private static final String AWP_KILL_XPATH = "xpath://android.view.View[@text='AWP击杀']";
    private static final String AFTER_MATCH_DATA_XPATH = "xpath://android.widget.TextView[@text='赛后数据']";

    private static final String[] MAP_NAMES = {"死亡游乐园", "远古遗迹", "核子危机", "炙热沙城II", "炼狱小镇", "荒漠迷城", "阿努比斯"};

    private static final int WAIT_AFTER_LAUNCH_MS = 3500;
    private static final int WAIT_FOR_CS_SECONDS = 18;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_FOR_ELEMENT_SECONDS = 10;
    private static final int WAIT_AFTER_CLICK_MS = 2000;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int SCROLL_INTERVAL_MS = 500;
    private static final int MAX_SWIPE_UNTIL = 15;
    private static final Random RANDOM = new Random();

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsEndedMatchDetailDataSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsEndedMatchDetailDataTest(Task task, Device device) {
        log.info("CS-已结束比赛-赛事详情-数据测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) throw new RuntimeException("应用不存在: " + task.getAppId());
        if (!isCsEndedMatchDetailDataSupported(task, app, device)) {
            throw new RuntimeException("CS-已结束比赛-赛事详情-数据测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-已结束比赛-赛事详情-数据测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-已结束比赛-赛事详情-数据测试", app.getPackageName(), deviceId);

        executionLog.add(ExecutionLogEntryDto.success("前置登录", "未登录则执行登录，已登录则继续"));
        try {
            FunctionalTestExecutionResult loginResult = loginTestService.executeLoginTest(task, device, false);
            if (loginResult != null && loginResult.getExecutionLog() != null) {
                executionLog.addAll(loginResult.getExecutionLog());
            }
        } catch (Exception e) {
            log.error("登录测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("登录测试", "登录失败: " + e.getMessage(), null));
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

            if (!clickRandomEndedMatch(driver, deviceId, task, executionLog)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击已结束", "未找到已结束比赛元素", ENDED_BASE));
                throw new RuntimeException("已结束 元素未出现");
            }
            sleep(WAIT_AFTER_CLICK_MS);

            if (!driver.waitForElement(deviceId, DATA_TAB_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.fail("等待数据Tab", "未进入赛程详情-数据页面", DATA_TAB_XPATH));
                throw new RuntimeException("赛程详情-数据 页面未进入");
            }
            executionLog.add(ExecutionLogEntryDto.success("进入数据页", "已进入赛程详情-数据页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);

            scrollUpDown(driver, deviceId, task, executionLog, 2, 2);

            boolean mapBpExists = driver.waitForElement(deviceId, MAP_BP_XPATH, 3);
            if (!mapBpExists) {
                for (int i = 0; i < 8 && !mapBpExists; i++) {
                    int centerX = 540;
                    driver.swipe(deviceId, centerX, 1200, centerX, 800, SCROLL_DURATION_MS);
                    sleep(SCROLL_INTERVAL_MS);
                    mapBpExists = driver.waitForElement(deviceId, MAP_BP_XPATH, 1);
                }
            }
            if (mapBpExists) {
                executionLog.add(ExecutionLogEntryDto.success("地图BP", "存在地图BP，执行地图分析流程"));
                if (driver.waitForElement(deviceId, MAP_ANALYSIS_XPATH, 4) && driver.clickElement(deviceId, MAP_ANALYSIS_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击地图分析", "进入地图分析页面"));
                    sleep(WAIT_AFTER_CLICK_MS);
                    scrollUpDown(driver, deviceId, task, executionLog, 1, 1);
                    if (driver.waitForElement(deviceId, BACK_BUTTON_XPATH, 4) && driver.clickElement(deviceId, BACK_BUTTON_XPATH)) {
                        executionLog.add(ExecutionLogEntryDto.success("返回", "已返回赛程详情-数据页面"));
                    } else {
                        driver.pressBack(deviceId);
                        executionLog.add(ExecutionLogEntryDto.success("返回", "使用返回键返回赛程详情-数据页面"));
                    }
                }
            } else {
                executionLog.add(ExecutionLogEntryDto.success("地图BP", "不存在地图BP，跳过地图分析"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            executionLog.add(ExecutionLogEntryDto.success("查找总览", "滑动直至总览出现"));
            boolean overviewFound = driver.waitForElement(deviceId, OVERVIEW_XPATH, 2)
                    || swipeUntilElement(driver, deviceId, OVERVIEW_XPATH, false, executionLog, "总览")
                    || swipeUntilElement(driver, deviceId, OVERVIEW_XPATH, true, executionLog, "总览");
            if (overviewFound && driver.clickElement(deviceId, OVERVIEW_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("点击总览", "进入总览页面"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                sleep(WAIT_AFTER_CLICK_MS);
                doOverviewOrMapPageFlow(driver, deviceId, task, executionLog, "总览");
                driver.pressBack(deviceId);
                executionLog.add(ExecutionLogEntryDto.success("返回", "已返回赛程详情-数据页面"));
                sleep(WAIT_AFTER_CLICK_MS);
            } else {
                executionLog.add(ExecutionLogEntryDto.success("总览", "未找到总览元素，跳过"));
            }

            boolean mapBpForMapsExists = driver.waitForElement(deviceId, MAP_BP_XPATH, 2);
            if (!mapBpForMapsExists) {
                for (int i = 0; i < 6 && !mapBpForMapsExists; i++) {
                    int centerX = 540;
                    driver.swipe(deviceId, centerX, 1200, centerX, 800, SCROLL_DURATION_MS);
                    sleep(SCROLL_INTERVAL_MS);
                    mapBpForMapsExists = driver.waitForElement(deviceId, MAP_BP_XPATH, 1);
                }
            }
            if (!mapBpForMapsExists) {
                executionLog.add(ExecutionLogEntryDto.success("地图BP", "不存在地图BP元素，跳过地图流程"));
            } else {
                executionLog.add(ExecutionLogEntryDto.success("地图BP", "存在地图BP元素，执行地图流程"));
                executionLog.add(ExecutionLogEntryDto.success("查找地图元素", "滑动直至地图区域出现"));
                String firstMapXpath = "xpath:(//android.view.View[@text='" + MAP_NAMES[0] + "'])[2]";
                boolean mapAreaFound = driver.waitForElement(deviceId, firstMapXpath, 2)
                        || swipeUntilElement(driver, deviceId, firstMapXpath, true, executionLog, "地图区域")
                        || swipeUntilElement(driver, deviceId, firstMapXpath, false, executionLog, "地图区域");
                if (mapAreaFound) sleep(SCROLL_INTERVAL_MS);
                List<String> visibleMaps = collectVisibleMapElements(driver, deviceId);
                for (String mapName : visibleMaps) {
                    String mapXpath = "xpath:(//android.view.View[@text='" + mapName + "'])[2]";
                    if (driver.waitForElement(deviceId, mapXpath, 4) && driver.clickElement(deviceId, mapXpath)) {
                        executionLog.add(ExecutionLogEntryDto.success("点击地图", "进入 " + mapName + " 页面"));
                        takeStepScreenshot(driver, deviceId, task, executionLog);
                        sleep(WAIT_AFTER_CLICK_MS);
                        doMapPageFlow(driver, deviceId, task, executionLog, mapName);
                        driver.pressBack(deviceId);
                        executionLog.add(ExecutionLogEntryDto.success("返回", "已返回赛程详情-数据页面"));
                        sleep(WAIT_AFTER_CLICK_MS);
                    }
                }
            }

            FunctionalTestLogHelper.appendSummary(executionLog);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-已结束比赛-赛事详情-数据测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            FunctionalTestLogHelper.appendSummary(executionLog);
            loginTestService.setExecutionLogForFailure(executionLog);
            throw e;
        }
    }

    private boolean ensureCsElementAndClick(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        for (int retry = 0; retry < MAX_CS_RETRIES; retry++) {
            executionLog.add(ExecutionLogEntryDto.success("等待CS元素", "第 " + (retry + 1) + " 次尝试"));
            if (driver.waitForElement(deviceId, CS_XPATH, WAIT_FOR_CS_SECONDS / MAX_CS_RETRIES)) {
                executionLog.add(ExecutionLogEntryDto.success("等待CS元素", "CS 元素已出现"));
                takeStepScreenshot(driver, deviceId, task, executionLog);
                if (driver.clickElement(deviceId, CS_XPATH)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击CS", "已点击 CS"));
                    takeStepScreenshot(driver, deviceId, task, executionLog);
                    return true;
                }
            }
            if (retry < MAX_CS_RETRIES - 1) {
                driver.pressBack(deviceId);
                sleep(1000);
            }
        }
        return false;
    }

    private boolean clickRandomEndedMatch(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog) {
        int[] indices = {1, 2, 3, 4, 5};
        for (int scrollAttempt = 0; scrollAttempt < 5; scrollAttempt++) {
            for (int i = indices.length - 1; i > 0; i--) {
                int j = RANDOM.nextInt(i + 1);
                int t = indices[i];
                indices[i] = indices[j];
                indices[j] = t;
            }
            for (int idx : indices) {
                String xpath = ENDED_BASE + "[" + idx + "]";
                if (driver.waitForElement(deviceId, xpath, 2) && driver.clickElement(deviceId, xpath)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击已结束", "随机选择第 " + idx + " 个已结束比赛"));
                    return true;
                }
            }
            int centerX = 540;
            driver.swipe(deviceId, centerX, 1200, centerX, 800, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向上滑动", "查找已结束比赛"));
            sleep(SCROLL_INTERVAL_MS);
        }
        return false;
    }

    private void scrollUpDown(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog, int upCount, int downCount) {
        int centerX = 540;
        for (int i = 0; i < upCount; i++) {
            driver.swipe(deviceId, centerX, 1200, centerX, 800, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向上滑动", "第 " + (i + 1) + " 次"));
            sleep(SCROLL_INTERVAL_MS);
        }
        for (int i = 0; i < downCount; i++) {
            driver.swipe(deviceId, centerX, 800, centerX, 1200, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向下滑动", "第 " + (i + 1) + " 次"));
            sleep(SCROLL_INTERVAL_MS);
        }
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    /** 总览页面流程：基础数据→对位数据→赛后数据，无对位数据条件判断 */
    private void doOverviewOrMapPageFlow(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog, String pageName) {
        clickIfPresent(driver, deviceId, ALL_TAB_XPATH, executionLog, "全部");
        clickIfPresent(driver, deviceId, T_SIDE_XPATH, executionLog, "T阵营");
        clickIfPresent(driver, deviceId, CT_SIDE_XPATH, executionLog, "CT阵营");
        executionLog.add(ExecutionLogEntryDto.success("基础数据", pageName + " 切换 全部/T阵营/CT阵营"));

        swipeUntilElement(driver, deviceId, DUWEI_DATA_XPATH, true, executionLog, "对位数据");
        clickIfPresent(driver, deviceId, ALL_TAB_XPATH, executionLog, "全部");
        clickIfPresent(driver, deviceId, FIRST_KILL_XPATH, executionLog, "首杀");
        clickIfPresent(driver, deviceId, AWP_KILL_XPATH, executionLog, "AWP击杀");
        executionLog.add(ExecutionLogEntryDto.success("对位数据", pageName + " 切换 全部/首杀/AWP击杀"));

        swipeUntilElement(driver, deviceId, AFTER_MATCH_DATA_XPATH, false, executionLog, "赛后数据");
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    /** 地图页面流程：基础数据→向上滑动至对位数据，若对位数据不存在则跳过后续；存在则执行全部/首杀/AWP击杀→向下滑动至赛后数据 */
    private void doMapPageFlow(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> executionLog, String pageName) {
        clickIfPresent(driver, deviceId, ALL_TAB_XPATH, executionLog, "全部");
        clickIfPresent(driver, deviceId, T_SIDE_XPATH, executionLog, "T阵营");
        clickIfPresent(driver, deviceId, CT_SIDE_XPATH, executionLog, "CT阵营");
        executionLog.add(ExecutionLogEntryDto.success("基础数据", pageName + " 切换 全部/T阵营/CT阵营"));

        boolean duweiFound = swipeUntilElement(driver, deviceId, DUWEI_DATA_XPATH, true, executionLog, "对位数据");
        if (!duweiFound) {
            executionLog.add(ExecutionLogEntryDto.success("对位数据", pageName + " 未找到对位数据元素，跳过后续步骤"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            return;
        }
        clickIfPresent(driver, deviceId, ALL_TAB_XPATH, executionLog, "全部");
        clickIfPresent(driver, deviceId, FIRST_KILL_XPATH, executionLog, "首杀");
        clickIfPresent(driver, deviceId, AWP_KILL_XPATH, executionLog, "AWP击杀");
        executionLog.add(ExecutionLogEntryDto.success("对位数据", pageName + " 切换 全部/首杀/AWP击杀"));

        swipeUntilElement(driver, deviceId, AFTER_MATCH_DATA_XPATH, false, executionLog, "赛后数据");
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    private void clickIfPresent(PlatformDriver driver, String deviceId, String xpath, List<ExecutionLogEntryDto> executionLog, String label) {
        if (driver.waitForElement(deviceId, xpath, 3) && driver.clickElement(deviceId, xpath)) {
            executionLog.add(ExecutionLogEntryDto.success("点击" + label, "已点击"));
            sleep(600);
        }
    }

    private boolean swipeUntilElement(PlatformDriver driver, String deviceId, String targetXpath, boolean swipeUp, List<ExecutionLogEntryDto> executionLog, String targetName) {
        int centerX = 540;
        int startY = swipeUp ? 1200 : 800;
        int endY = swipeUp ? 800 : 1200;
        for (int i = 0; i < MAX_SWIPE_UNTIL; i++) {
            if (driver.waitForElement(deviceId, targetXpath, 1)) {
                executionLog.add(ExecutionLogEntryDto.success("滑动至" + targetName, "已出现"));
                return true;
            }
            driver.swipe(deviceId, centerX, startY, centerX, endY, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("滑动", (swipeUp ? "向上" : "向下") + " 第 " + (i + 1) + " 次"));
            sleep(SCROLL_INTERVAL_MS);
        }
        return driver.waitForElement(deviceId, targetXpath, 2);
    }

    /** 按固定顺序收集可见地图元素（死亡游乐园、远古遗迹等），存在1个/2个/3个则依次点击并执行流程 */
    private List<String> collectVisibleMapElements(PlatformDriver driver, String deviceId) {
        List<String> visible = new ArrayList<>();
        for (String mapName : MAP_NAMES) {
            String xpath = "xpath:(//android.view.View[@text='" + mapName + "'])[2]";
            if (driver.waitForElement(deviceId, xpath, 1)) {
                visible.add(mapName);
            }
        }
        return visible;
    }

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("cs_ended_match_detail_data", task != null ? task.getId() : null, stepIndex);
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
