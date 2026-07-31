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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CS-已结束比赛-赛事详情-分析测试（功能基础质量 - Android）。
 * 步骤：启动→登录→CS→赛事→随机点击已结束→分析→对阵图→概览→参赛队伍(提取)；
 * 后续：返回→分析→你支持哪一队→选战队→提取队员→点击队员→基础数据→滑动→查看更多战绩→返回。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsEndedMatchDetailAnalysisTestService {

    private static final String PACKAGE_ESPORTS = "steam.esports";
    private static final String TV_TEXT_BASE = "com.pwrd.steam.esports:id/tvText";

    private static final String CS_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='CS']";
    private static final String EVENT_XPATH = "xpath://android.widget.TextView[@resource-id='" + TV_TEXT_BASE + "' and @text='赛事']";
    private static final String ENDED_BASE = "xpath:(//android.view.View[@text='已结束'])";
    private static final String ANALYSIS_TAB_XPATH = "xpath:(//android.view.View[@text='分析'])[1]";
    private static final String DUIZHEN_TU_XPATH = "xpath://android.view.View[contains(@text,'对阵图')]";
    private static final String DUIZHEN_TU_EXACT_XPATH = "xpath://android.view.View[@text=' 对阵图']";
    private static final String OVERVIEW_TAB_XPATH = "xpath:(//android.view.View[@text='概览'])[1]";
    private static final String TEAM_SECTION_XPATH = "xpath://android.widget.TextView[@text='参赛队伍']";
    /**
     * 队伍数量：从 text="队伍数量:N" 元素解析 N（等价 UiSelector.text("队伍数量:24")），
     * 每个比赛 N 不同，用于确定需遍历 View[1]..View[N]。
     */
    private static final String TEAM_COUNT_XPATH = "xpath://android.view.View[contains(@text,'队伍数量:')]";
    private static final Pattern TEAM_COUNT_PATTERN = Pattern.compile("队伍数量:(\\d+)");
    /**
     * 赛事名称：从 View[@text="赛事名称:Digital Crusade DraculaN S5"] 解析，取冒号后的内容作为 WebView 的 @text。
     */
    private static final String EVENT_NAME_XPATH = "xpath://android.view.View[contains(@text,'赛事名称:')]";
    private static final Pattern EVENT_NAME_PATTERN = Pattern.compile("赛事名称:(.+)");
    /**
     * 参赛队伍：WebView[@text=赛事名]/View[2]/View[2]/View/View/View/ListView[2]/View[1]..View[N]。
     * 赛事名从「赛事名称:XXX」解析，用于精确定位 WebView。
     */
    private static final String TEAM_LIST_PATH_SUFFIX =
            "/android.view.View[2]/android.view.View[2]/android.view.View/android.view.View/android.view.View/android.widget.ListView[2]";
    private static final int MAX_TEAM_COUNT = 64;

    private static final int WAIT_AFTER_LAUNCH_MS = 3500;
    private static final int WAIT_FOR_CS_SECONDS = 18;
    private static final int MAX_CS_RETRIES = 5;
    private static final int WAIT_FOR_ELEMENT_SECONDS = 10;
    private static final int WAIT_AFTER_CLICK_MS = 2000;
    private static final int SCROLL_DURATION_MS = 400;
    private static final int SCROLL_INTERVAL_MS = 500;

    private static final String BACK_BUTTON_XPATH = "xpath://android.widget.ImageView[@resource-id='com.pwrd.steam.esports:id/title_bar_back_icon']";
    private static final String SUPPORT_WHICH_TEAM_XPATH = "xpath://android.view.View[@text='你支持哪一队？']";
    private static final String BASIC_DATA_XPATH = "xpath://android.view.View[@text='基础数据']";
    private static final String MORE_RECORDS_XPATH_1 = "xpath:(//android.widget.TextView[@text='查看更多战绩'])[1]";
    private static final String MORE_RECORDS_XPATH_2 = "xpath:(//android.widget.TextView[@text='查看更多战绩'])[2]";
    private static final String BACK_FROM_OVERVIEW_XPATH = "xpath://android.view.View[@text='\ue903']";
    /**
     * 战队成员父元素路径：WebView[@text=赛程名称]/View/View/View/View[3]/View/View[2]/View[2]/View[6]/View[4]，
     * 其下 View[1]～View[5] 各含一名成员名称。
     */
    private static final String TEAM_MEMBER_PATH_SUFFIX =
            "/android.view.View/android.view.View/android.view.View/android.view.View[3]/android.view.View/android.view.View[2]/android.view.View[2]/android.view.View[6]";
    private static final int MEMBER_SLOT_COUNT = 5;

    private static final Random RANDOM = new Random();

    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    public boolean isCsEndedMatchDetailAnalysisSupported(Task task, Application app, Device device) {
        if (task == null || app == null || app.getPackageName() == null || device == null) return false;
        return Device.isAndroidOrPogAndroid(device.getPlatform()) && app.getPackageName().contains(PACKAGE_ESPORTS);
    }

    public FunctionalTestExecutionResult executeCsEndedMatchDetailAnalysisTest(Task task, Device device) {
        log.info("CS-已结束比赛-赛事详情-分析测试开始: taskId={}, deviceId={}", task.getId(), device.getId());
        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) throw new RuntimeException("应用不存在: " + task.getAppId());
        if (!isCsEndedMatchDetailAnalysisSupported(task, app, device)) {
            throw new RuntimeException("CS-已结束比赛-赛事详情-分析测试仅支持完美世界电竞 Android 应用");
        }
        if (appiumDriverAdapter == null) {
            throw new RuntimeException("CS-已结束比赛-赛事详情-分析测试需要开启 Appium（appium.enabled=true）");
        }

        String deviceId = device.getId();
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        FunctionalTestLogHelper.prependHeader(executionLog, "CS-已结束比赛-赛事详情-分析测试", app.getPackageName(), deviceId);

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

            executionLog.add(ExecutionLogEntryDto.success("等待分析Tab", "判断是否出现 分析 元素"));
            if (!driver.waitForElement(deviceId, ANALYSIS_TAB_XPATH, WAIT_FOR_ELEMENT_SECONDS)) {
                executionLog.add(ExecutionLogEntryDto.success("分析", "未找到分析元素，不执行后续步骤"));
                FunctionalTestLogHelper.appendSummary(executionLog);
                return new FunctionalTestExecutionResult(null, executionLog, null);
            }
            if (!driver.clickElement(deviceId, ANALYSIS_TAB_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击分析", "无法点击 分析 元素", ANALYSIS_TAB_XPATH));
                throw new RuntimeException("点击 分析 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击分析", "已进入赛程-分析页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            String duizhenXpath = driver.waitForElement(deviceId, DUIZHEN_TU_EXACT_XPATH, 2) ? DUIZHEN_TU_EXACT_XPATH : DUIZHEN_TU_XPATH;
            if (!driver.waitForElement(deviceId, duizhenXpath, 4)) {
                executionLog.add(ExecutionLogEntryDto.success("对阵图", "未找到对阵图元素，不执行后续步骤"));
                FunctionalTestLogHelper.appendSummary(executionLog);
                return new FunctionalTestExecutionResult(null, executionLog, null);
            }
            if (!driver.clickElement(deviceId, duizhenXpath)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击对阵图", "无法点击 对阵图 元素", duizhenXpath));
                throw new RuntimeException("点击 对阵图 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击对阵图", "已进入对阵页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            int centerX = 540;
            driver.swipe(deviceId, centerX, 1200, centerX, 800, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向上滑动", "1次"));
            sleep(SCROLL_INTERVAL_MS);
            driver.swipe(deviceId, centerX, 800, centerX, 1200, SCROLL_DURATION_MS);
            executionLog.add(ExecutionLogEntryDto.success("向下滑动", "1次"));
            sleep(SCROLL_INTERVAL_MS);
            takeStepScreenshot(driver, deviceId, task, executionLog);

            executionLog.add(ExecutionLogEntryDto.success("等待概览", "判断是否出现 概览 元素"));
            if (!driver.waitForElement(deviceId, OVERVIEW_TAB_XPATH, 6)) {
                executionLog.add(ExecutionLogEntryDto.success("概览", "未找到概览元素，不执行后续步骤"));
                FunctionalTestLogHelper.appendSummary(executionLog);
                return new FunctionalTestExecutionResult(null, executionLog, null);
            }
            if (!driver.clickElement(deviceId, OVERVIEW_TAB_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.fail("点击概览", "无法点击 概览 元素", OVERVIEW_TAB_XPATH));
                throw new RuntimeException("点击 概览 失败");
            }
            executionLog.add(ExecutionLogEntryDto.success("点击概览", "已进入概览页面"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(WAIT_AFTER_CLICK_MS);

            executionLog.add(ExecutionLogEntryDto.success("等待参赛队伍", "判断是否出现 参赛队伍 元素"));
            if (!driver.waitForElement(deviceId, TEAM_SECTION_XPATH, 8)) {
                for (int i = 0; i < 6; i++) {
                    driver.swipe(deviceId, centerX, 1200, centerX, 800, SCROLL_DURATION_MS);
                    sleep(SCROLL_INTERVAL_MS);
                    if (driver.waitForElement(deviceId, TEAM_SECTION_XPATH, 1)) break;
                }
            }
            if (!driver.waitForElement(deviceId, TEAM_SECTION_XPATH, 2)) {
                executionLog.add(ExecutionLogEntryDto.success("参赛队伍", "未找到参赛队伍元素，不执行后续步骤"));
                FunctionalTestLogHelper.appendSummary(executionLog);
                return new FunctionalTestExecutionResult(null, executionLog, null);
            }
            executionLog.add(ExecutionLogEntryDto.success("参赛队伍", "已找到参赛队伍区域"));
            takeStepScreenshot(driver, deviceId, task, executionLog);
            sleep(1500);

            List<String> teamNames = extractTeamNames(driver, deviceId);
            String eventName = parseEventName(driver, deviceId);
            for (String name : teamNames) {
                executionLog.add(ExecutionLogEntryDto.success("参赛队伍", name));
            }
            if (teamNames.isEmpty()) {
                executionLog.add(ExecutionLogEntryDto.success("参赛队伍", "未提取到战队数据"));
            } else {
                executionLog.add(ExecutionLogEntryDto.success("参赛队伍", "共提取 " + teamNames.size() + " 支战队，已输出至测试结果分布"));
            }
            takeStepScreenshot(driver, deviceId, task, executionLog);

            executePostTeamExtractionSteps(driver, deviceId, task, executionLog, teamNames, eventName, centerX);

            FunctionalTestLogHelper.appendSummary(executionLog);
            return new FunctionalTestExecutionResult(null, executionLog, null);
        } catch (RuntimeException e) {
            log.error("CS-已结束比赛-赛事详情-分析测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            FunctionalTestLogHelper.appendSummary(executionLog);
            loginTestService.setExecutionLogForFailure(executionLog);
            throw e;
        }
    }

    /**
     * 参赛队伍提取后的后续步骤：返回→分析→你支持哪一队→选战队→提取队员→点击队员→基础数据→滑动→查看更多战绩→返回。
     */
    private void executePostTeamExtractionSteps(PlatformDriver driver, String deviceId, Task task,
                                                 List<ExecutionLogEntryDto> executionLog,
                                                 List<String> teamNames, String eventName, int centerX) {
        executionLog.add(ExecutionLogEntryDto.success("后续步骤", "点击返回按钮，返回至分析页"));
        if (!driver.clickElement(deviceId, BACK_BUTTON_XPATH)) {
            executionLog.add(ExecutionLogEntryDto.success("返回", "未找到返回按钮，跳过后续步骤"));
            return;
        }
        sleep(WAIT_AFTER_CLICK_MS);
        takeStepScreenshot(driver, deviceId, task, executionLog);

        if (!driver.waitForElement(deviceId, ANALYSIS_TAB_XPATH, 4)) {
            executionLog.add(ExecutionLogEntryDto.success("分析", "返回后未找到分析元素，不执行后续步骤"));
            return;
        }
        executionLog.add(ExecutionLogEntryDto.success("分析", "已返回至分析页"));

        if (!driver.waitForElement(deviceId, SUPPORT_WHICH_TEAM_XPATH, 3)) {
            executionLog.add(ExecutionLogEntryDto.success("你支持哪一队", "未找到该元素，不执行后续步骤"));
            return;
        }
        executionLog.add(ExecutionLogEntryDto.success("你支持哪一队", "已找到，判断分析页两支战队"));

        List<String> analysisTeams = new ArrayList<>();
        if (teamNames != null && !teamNames.isEmpty()) {
            for (String tn : teamNames) {
                String xpath = "xpath://android.widget.TextView[@text='" + escapeXpathStr(tn) + "']";
                if (driver.isElementPresent(deviceId, xpath)) analysisTeams.add(tn);
            }
        }
        if (analysisTeams.isEmpty()) {
            executionLog.add(ExecutionLogEntryDto.success("分析页战队", "未找到可点击的战队元素"));
        } else {
            String toClick = analysisTeams.get(RANDOM.nextInt(analysisTeams.size()));
            String teamXpath = "xpath://android.widget.TextView[@text='" + escapeXpathStr(toClick) + "']";
            if (driver.clickElement(deviceId, teamXpath)) {
                executionLog.add(ExecutionLogEntryDto.success("点击战队", "已点击 " + toClick));
            } else {
                executionLog.add(ExecutionLogEntryDto.success("点击战队", "点击 " + toClick + " 失败"));
            }
        }
        sleep(WAIT_AFTER_CLICK_MS);
        takeStepScreenshot(driver, deviceId, task, executionLog);

        sleep(1500);
        driver.swipe(deviceId, centerX, 1100, centerX, 900, SCROLL_DURATION_MS);
        executionLog.add(ExecutionLogEntryDto.success("向上滑动", "提取队员前小幅滑动，使子元素全部展示"));
        sleep(500);
        String memberEventName = eventName;
        if (memberEventName == null || memberEventName.isBlank()) {
            memberEventName = parseEventName(driver, deviceId);
        }
        List<String> allMembers = extractTeamMembers(driver, deviceId, memberEventName);
        saveMembersToXmlForDebug(allMembers, memberEventName, deviceId);
        if (!allMembers.isEmpty()) {
            executionLog.add(ExecutionLogEntryDto.success("战队成员", "共提取 " + allMembers.size() + " 名: " + String.join(", ", allMembers)));
            List<String> toClickMembers = shuffleAndPick(allMembers, 3);
            for (String member : toClickMembers) {
                String xpath1 = "xpath://android.view.View[@text='" + escapeXpathStr(member) + "']";
                String xpath2 = "xpath:(//android.view.View[@text='" + escapeXpathStr(member) + "'])[2]";
                if (driver.isElementPresent(deviceId, xpath1) && driver.clickElement(deviceId, xpath1)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击成员", member));
                } else if (driver.isElementPresent(deviceId, xpath2) && driver.clickElement(deviceId, xpath2)) {
                    executionLog.add(ExecutionLogEntryDto.success("点击成员", member + " [2]"));
                }
                sleep(500);
            }
        } else {
            String pathHint = "WebView[@text=赛事名]/.../View[6]/View[4或5]/View[1～5]//View[@text]";
            executionLog.add(ExecutionLogEntryDto.success("战队成员", "未提取到成员（赛事名=" + (memberEventName != null ? memberEventName : "空") + "，路径=" + pathHint + "，详见日志）"));
            savePageSourceForDebug(driver, deviceId);
        }
        takeStepScreenshot(driver, deviceId, task, executionLog);

        if (driver.waitForElement(deviceId, BASIC_DATA_XPATH, 2) && driver.clickElement(deviceId, BASIC_DATA_XPATH)) {
            executionLog.add(ExecutionLogEntryDto.success("基础数据", "已点击"));
            sleep(WAIT_AFTER_CLICK_MS);
        }

        driver.swipe(deviceId, centerX, 1200, centerX, 800, SCROLL_DURATION_MS);
        executionLog.add(ExecutionLogEntryDto.success("向上滑动", "第1次"));
        sleep(SCROLL_INTERVAL_MS);
        driver.swipe(deviceId, centerX, 1200, centerX, 800, SCROLL_DURATION_MS);
        executionLog.add(ExecutionLogEntryDto.success("向上滑动", "第2次"));
        sleep(SCROLL_INTERVAL_MS);
        takeStepScreenshot(driver, deviceId, task, executionLog);

        String moreXpath = driver.waitForElement(deviceId, MORE_RECORDS_XPATH_1, 2) ? MORE_RECORDS_XPATH_1
                : (driver.waitForElement(deviceId, MORE_RECORDS_XPATH_2, 1) ? MORE_RECORDS_XPATH_2 : null);
        if (moreXpath != null && driver.clickElement(deviceId, moreXpath)) {
            executionLog.add(ExecutionLogEntryDto.success("查看更多战绩", "已点击，进入概览页"));
            sleep(WAIT_AFTER_CLICK_MS);
            takeStepScreenshot(driver, deviceId, task, executionLog);
            if (driver.waitForElement(deviceId, BACK_FROM_OVERVIEW_XPATH, 2) && driver.clickElement(deviceId, BACK_FROM_OVERVIEW_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("返回", "已返回至分析页"));
                sleep(WAIT_AFTER_CLICK_MS);
            } else if (driver.clickElement(deviceId, BACK_BUTTON_XPATH)) {
                executionLog.add(ExecutionLogEntryDto.success("返回", "已通过标题栏返回按钮返回"));
                sleep(WAIT_AFTER_CLICK_MS);
            }
        }
        takeStepScreenshot(driver, deviceId, task, executionLog);
    }

    private static String escapeXpathStr(String s) {
        if (s == null) return "";
        return s.replace("'", "''");
    }

    private static <T> List<T> shuffleAndPick(List<T> list, int n) {
        List<T> copy = new ArrayList<>(list);
        for (int i = copy.size() - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            T t = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, t);
        }
        return copy.subList(0, Math.min(n, copy.size()));
    }

    /**
     * 提取两支队伍的战队成员。父元素与子元素分开：先定位父元素，再在父元素内用相对路径找子元素。
     * 父元素: .../View[6]/View[4或5]/View[1～5]，子元素相对路径: .//android.view.View[@text]
     */
    private static final String MEMBER_CHILD_RELATIVE_XPATH = ".//android.view.View[@text]";

    private List<String> extractTeamMembers(PlatformDriver driver, String deviceId, String eventName) {
        List<String> members = new ArrayList<>();
        String[] containerSelectors = buildMemberContainerSelectors(eventName);
        if (log.isInfoEnabled()) {
            log.info("战队成员提取: 赛事名={}, 容器选择器数={}", eventName != null ? eventName : "(空)", containerSelectors.length);
            if (containerSelectors.length > 0) {
                String parent1 = containerSelectors[0] + "/android.view.View[1]";
                log.info("父元素 XPath: {}", parent1);
                log.info("子元素相对路径(在父元素内查找): {}", MEMBER_CHILD_RELATIVE_XPATH);
                boolean parentExists = driver.isElementPresent(deviceId, parent1);
                log.info("父元素是否存在: {}", parentExists);
            }
        }
        for (String container : containerSelectors) {
            for (int i = 1; i <= MEMBER_SLOT_COUNT; i++) {
                String parentSelector = container + "/android.view.View[" + i + "]";
                List<String> texts = driver.getChildTextsFromParents(deviceId, parentSelector, MEMBER_CHILD_RELATIVE_XPATH);
                for (String t : texts) {
                    if (isValidMemberName(t) && !isNonTeamLabel(t)) members.add(t.trim());
                }
            }
        }
        if (members.isEmpty()) {
            extractMembersFallback(driver, deviceId, members);
        }
        if (members.isEmpty() && appiumDriverAdapter != null) {
            List<String> fromWebView = appiumDriverAdapter.extractMembersFromWebViewContext(deviceId);
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String s : fromWebView) {
                if (seen.size() >= 15) break;
                if (isValidMemberName(s) && !isNonTeamLabel(s) && seen.add(s.trim())) {
                    members.add(s.trim());
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(members));
    }

    /** 兜底：页面上所有 View[@text]，过滤出疑似队员名。仅当数量在 5～15 时采用（ plausible 两队共 10 人）。 */
    private void extractMembersFallback(PlatformDriver driver, String deviceId, List<String> members) {
        List<String> raw = driver.getElementsTextList(deviceId, "xpath://android.view.View[@text]");
        List<String> filtered = new ArrayList<>();
        for (String t : raw) {
            if (isValidMemberName(t) && !isNonTeamLabel(t)) filtered.add(t.trim());
        }
        int n = new LinkedHashSet<>(filtered).size();
        if (n >= 5 && n <= 15) {
            members.addAll(filtered);
            log.info("战队成员兜底提取(全页 View[@text]): 共 {} 个", n);
        }
    }

    /**
     * 构建成员父容器选择器。父路径为 WebView[@text=赛程名称]/.../View[6]/View[4] 或 View[5]，
     * 其下 View[1]～View[5] 为 5 名成员。
     */
    private String[] buildMemberContainerSelectors(String eventName) {
        List<String> list = new ArrayList<>();
        if (eventName != null && !eventName.isBlank()) {
            String esc = escapeXpathStr(eventName);
            list.add("xpath://android.webkit.WebView[@text='" + esc + "']" + TEAM_MEMBER_PATH_SUFFIX + "/android.view.View[4]");
            list.add("xpath://android.webkit.WebView[@text='" + esc + "']" + TEAM_MEMBER_PATH_SUFFIX + "/android.view.View[5]");
            list.add("xpath://android.webkit.WebView[@content-desc='" + esc + "']" + TEAM_MEMBER_PATH_SUFFIX + "/android.view.View[4]");
            list.add("xpath://android.webkit.WebView[@content-desc='" + esc + "']" + TEAM_MEMBER_PATH_SUFFIX + "/android.view.View[5]");
            list.add("xpath://android.webkit.WebView[contains(@text,'" + escapeXpathStr(eventName.length() > 6 ? eventName.substring(0, 6) : eventName) + "')]" + TEAM_MEMBER_PATH_SUFFIX + "/android.view.View[4]");
            list.add("xpath://android.webkit.WebView[contains(@text,'" + escapeXpathStr(eventName.length() > 6 ? eventName.substring(0, 6) : eventName) + "')]" + TEAM_MEMBER_PATH_SUFFIX + "/android.view.View[5]");
        }
        list.add("xpath:(//android.webkit.WebView)[1]" + TEAM_MEMBER_PATH_SUFFIX + "/android.view.View[4]");
        list.add("xpath:(//android.webkit.WebView)[1]" + TEAM_MEMBER_PATH_SUFFIX + "/android.view.View[5]");
        return list.toArray(new String[0]);
    }

    private static boolean isValidMemberName(String t) {
        if (t == null || t.isBlank()) return false;
        int len = t.trim().length();
        if (len < 2 || len > 40) return false;
        if (t.matches("^\\d+$")) return false;
        if (t.matches("^[A-Fa-f0-9-]{15,}$")) return false;
        return true;
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

    /**
     * 提取战队名称：先定位父元素（ListView 下的 View[1]..View[N]），再取各父元素下子元素 View[@text] 的文本。
     */
    private List<String> extractTeamNames(PlatformDriver driver, String deviceId) {
        List<String> teams = new ArrayList<>();
        String eventName = parseEventName(driver, deviceId);
        int count = parseTeamCount(driver, deviceId);
        if (log.isInfoEnabled()) {
            log.info("参赛队伍提取: 赛事名={}, 队伍数量={}", eventName != null ? eventName : "(未解析)", count);
        }
        extractTeamsByDirectXPath(driver, deviceId, teams);
        if (!teams.isEmpty()) {
            log.info("参赛队伍从直接 XPath 提取: 共 {} 支", teams.size());
        }
        if (teams.isEmpty()) {
            String[] listViewSelectors = buildListViewSelectors(eventName);
            for (String listViewSel : listViewSelectors) {
                String parentSel = listViewSel + "/android.view.View";
                List<String> raw = driver.getChildTextsFromParents(deviceId, parentSel, ".//android.view.View[@text]");
                if (log.isDebugEnabled()) {
                    log.debug("getChildTextsFromParents 选择器={} 结果数={}", listViewSel.length() > 80 ? listViewSel.substring(0, 80) + "..." : listViewSel, raw.size());
                }
                if (!raw.isEmpty()) {
                    log.info("getChildTextsFromParents 提取到 {} 个原始文本", raw.size());
                }
                for (String t : raw) {
                    if (t == null || t.isBlank()) continue;
                    String s = t.trim();
                    if (s.length() < 2 || s.length() > 60) continue;
                    if (isNonTeamLabel(s)) continue;
                    teams.add(s);
                }
                if (!teams.isEmpty()) break;
            }
        }
        if (teams.isEmpty()) {
            extractByIteratingViews(driver, deviceId, eventName, count, teams);
        }
        if (teams.isEmpty()) {
            collectTeamTextsFallback(driver, deviceId, buildTeamListParentXPaths(eventName)[0], teams);
        }
        if (teams.isEmpty()) {
            extractTeamsFromPageSource(driver, deviceId, count, teams);
        }
        if (teams.isEmpty() && appiumDriverAdapter != null) {
            extractTeamsFromWebViewContext(deviceId, teams);
        }
        teams.removeIf(name -> name == null || name.isBlank() || "null".equalsIgnoreCase(name));
        if (teams.isEmpty()) {
            savePageSourceForDebug(driver, deviceId);
            log.info("参赛队伍提取失败，请查看上方日志中的页面源保存路径，打开 XML 分析真实 DOM 结构");
        }
        if (log.isInfoEnabled()) {
            log.info("参赛队伍提取完成: 共 {} 支战队, 名单={}", teams.size(), teams.size() <= 10 ? teams : teams.subList(0, 10) + "...");
        }
        return teams;
    }

    /** 根据真实 DOM 结构直接提取：第二个 ListView 下所有 View[@text]，过滤非战队名 */
    private void extractTeamsByDirectXPath(PlatformDriver driver, String deviceId, List<String> teams) {
        List<String> raw = driver.getElementsTextList(deviceId, TEAM_NAMES_DIRECT_XPATH);
        for (String t : raw) {
            if (t == null || t.isBlank()) continue;
            String s = t.trim();
            if (s.length() < 2 || s.length() > 60) continue;
            if (isNonTeamLabel(s)) continue;
            teams.add(s);
        }
    }

    /** 兜底：从页面源 XML 解析 text/content-desc 属性（当 getText 返回空时） */
    private void extractTeamsFromPageSource(PlatformDriver driver, String deviceId, int expectedCount, List<String> teams) {
        String xml = driver.getPageSource(deviceId);
        if (xml == null || xml.length() < 100) {
            if (log.isDebugEnabled()) log.debug("extractTeamsFromPageSource 跳过: xml={}", xml == null ? "null" : "长度=" + xml.length());
            return;
        }
        java.util.Set<String> seen = new LinkedHashSet<>();
        for (String attr : new String[]{"text", "content-desc"}) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(attr + "=\"([^\"]{2,60})\"");
            java.util.regex.Matcher m = p.matcher(xml);
            while (m.find()) {
                String s = m.group(1).trim();
                if (isNonTeamLabel(s)) continue;
                seen.add(s);
            }
            java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(attr + "='([^']{2,60})'");
            java.util.regex.Matcher m2 = p2.matcher(xml);
            while (m2.find()) {
                String s = m2.group(1).trim();
                if (isNonTeamLabel(s)) continue;
                seen.add(s);
            }
        }
        if (!seen.isEmpty()) {
            teams.addAll(seen);
            log.info("参赛队伍从页面源 XML 解析: 共 {} 个文本", seen.size());
        }
    }

    /** 尝试切换到 WebView 上下文提取战队名 */
    private void extractTeamsFromWebViewContext(String deviceId, List<String> teams) {
        try {
            List<String> fromWebView = appiumDriverAdapter.extractTeamsFromWebViewContext(deviceId);
            if (!fromWebView.isEmpty()) {
                for (String s : fromWebView) {
                    if (s != null && !s.isBlank() && s.length() >= 2 && s.length() <= 60 && !isNonTeamLabel(s)) {
                        teams.add(s.trim());
                    }
                }
                log.info("参赛队伍从 WebView 上下文解析: 共 {} 个文本", teams.size());
            }
        } catch (Exception e) {
            log.warn("WebView 上下文提取失败: {}", e.getMessage());
        }
    }

    /** 提取失败时保存页面源到文件，便于排查。保存到项目 logs 目录。Appium 模式下会额外 dump 所有 context（含 WebView）。 */
    private void savePageSourceForDebug(PlatformDriver driver, String deviceId) {
        try {
            String xml = driver.getPageSource(deviceId);
            if (xml == null || xml.length() < 50) {
                log.info("参赛队伍提取失败，无法保存原生页面源: xml={}", xml == null ? "null" : "长度=" + xml.length());
            } else {
                java.nio.file.Path dir = java.nio.file.Path.of("logs");
                if (!java.nio.file.Files.exists(dir)) java.nio.file.Files.createDirectories(dir);
                String filename = "cs_teams_pagesource_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xml";
                java.nio.file.Path path = dir.resolve(filename);
                java.nio.file.Files.writeString(path, xml);
                log.info("参赛队伍提取失败，原生页面源已保存至 {}", path.toAbsolutePath());
            }
            if (appiumDriverAdapter != null) {
                List<String> ctxPaths = appiumDriverAdapter.dumpAllContextsForDebug(deviceId);
                if (!ctxPaths.isEmpty()) {
                    log.info("参赛队伍提取失败，已 dump 所有 context 至 {} 个文件，请打开 WebView 对应的 XML 查看战队名所在 DOM 结构", ctxPaths.size());
                }
            }
        } catch (Exception e) {
            log.warn("保存页面源失败: {}", e.getMessage());
        }
    }

    /** 将提取的战队成员名称保存为 XML 文件到 logs 目录，便于查看。 */
    private void saveMembersToXmlForDebug(List<String> members, String eventName, String deviceId) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("logs");
            if (!java.nio.file.Files.exists(dir)) java.nio.file.Files.createDirectories(dir);
            String filename = "cs_team_members_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xml";
            java.nio.file.Path path = dir.resolve(filename);
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            sb.append("<teamMembers eventName=\"").append(escapeXmlAttr(eventName)).append("\" deviceId=\"").append(escapeXmlAttr(deviceId)).append("\" count=\"").append(members.size()).append("\">\n");
            for (String m : members) {
                sb.append("  <member>").append(escapeXmlText(m)).append("</member>\n");
            }
            sb.append("</teamMembers>");
            java.nio.file.Files.writeString(path, sb.toString());
            log.info("战队成员已保存至 {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("保存战队成员 XML 失败: {}", e.getMessage());
        }
    }

    private static String escapeXmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String escapeXmlText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private int parseTeamCount(PlatformDriver driver, String deviceId) {
        String text = driver.getElementText(deviceId, TEAM_COUNT_XPATH);
        if (text == null || text.isBlank()) return 0;
        Matcher m = TEAM_COUNT_PATTERN.matcher(text.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 从 View[@text="赛事名称:Digital Crusade DraculaN S5"] 解析出「赛事名称:」后的内容；兼容末尾单引号如 S5' */
    private String parseEventName(PlatformDriver driver, String deviceId) {
        String text = driver.getElementText(deviceId, EVENT_NAME_XPATH);
        if (text == null || text.isBlank()) return null;
        Matcher m = EVENT_NAME_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        return m.group(1).trim().replaceAll("'$", "");
    }

    /**
     * 参赛队伍 ListView 在 WebView 内，为第二个 ListView（第一个是赛事信息）。
     * 每个卡片：android.view.View 下有 android.view.View[2] 带 text="战队名"。
     */
    private static final String TEAM_NAMES_DIRECT_XPATH = "xpath:(//android.webkit.WebView//android.widget.ListView)[2]//android.view.View[@text]";

    /** 构建 ListView 选择器变体，用于 getChildTextsFromParents 的父路径 */
    private String[] buildListViewSelectors(String eventName) {
        List<String> list = new ArrayList<>();
        list.add(TEAM_NAMES_DIRECT_XPATH);
        if (eventName != null && !eventName.isBlank()) {
            String escaped = eventName.replace("'", "''");
            list.add("xpath://android.webkit.WebView[@text='" + escaped + "']" + TEAM_LIST_PATH_SUFFIX);
            list.add("xpath://android.webkit.WebView[@text='" + (eventName + "'").replace("'", "''") + "']" + TEAM_LIST_PATH_SUFFIX);
            list.add("xpath://android.webkit.WebView[contains(@text,'" + (eventName.split(" ").length > 0 ? eventName.split(" ")[0] : eventName) + "')]" + TEAM_LIST_PATH_SUFFIX);
        }
        list.add("xpath://android.webkit.WebView" + TEAM_LIST_PATH_SUFFIX);
        list.add("xpath:(//android.webkit.WebView)[1]" + TEAM_LIST_PATH_SUFFIX);
        list.add("xpath:(//android.webkit.WebView//android.widget.ListView)[2]");
        list.add("xpath:(//android.webkit.WebView//android.widget.ListView)[1]");
        list.add("xpath:(//android.widget.ListView)[2]");
        list.add("xpath:(//android.widget.ListView)[1]");
        return list.toArray(new String[0]);
    }

    /** 兜底：逐项遍历 View[1]..View[N] 的 XPath 方式 */
    private void extractByIteratingViews(PlatformDriver driver, String deviceId, String eventName, int count, List<String> teams) {
        if (count <= 0 || count > MAX_TEAM_COUNT) return;
        String[] parentPaths = buildTeamListParentXPaths(eventName);
        for (String parentPath : parentPaths) {
            for (int i = 1; i <= count; i++) {
                boolean added = false;
                for (String xpath : new String[]{
                        parentPath + "/android.view.View[" + i + "]/android.view.View[@text]",
                        parentPath + "/android.view.View[" + i + "]//android.view.View[@text]"
                }) {
                    List<String> texts = driver.getElementsTextList(deviceId, xpath);
                    if (texts.isEmpty()) {
                        String single = driver.getElementText(deviceId, xpath);
                        if (single != null && !single.isBlank()) texts = List.of(single);
                    }
                    for (String t : texts) {
                        if (t == null || t.isBlank()) continue;
                        String s = t.trim();
                        if (s.length() < 2 || s.length() > 60) continue;
                        if (isNonTeamLabel(s)) continue;
                        teams.add(s);
                        added = true;
                        break;
                    }
                    if (added) break;
                }
            }
            if (!teams.isEmpty()) break;
        }
    }

    /** 构建父路径变体：WebView 的 @text 可能带末尾单引号如 "Digital Crusade DraculaN S5'" */
    private String[] buildTeamListParentXPaths(String eventName) {
        List<String> paths = new ArrayList<>();
        if (eventName != null && !eventName.isBlank()) {
            String escaped = eventName.replace("'", "''");
            paths.add("xpath://android.webkit.WebView[@text='" + escaped + "']" + TEAM_LIST_PATH_SUFFIX);
            paths.add("xpath://android.webkit.WebView[@text='" + (eventName + "'").replace("'", "''") + "']" + TEAM_LIST_PATH_SUFFIX);
            paths.add("xpath://android.webkit.WebView[contains(@text,'" + eventName.split(" ")[0] + "')]" + TEAM_LIST_PATH_SUFFIX);
        }
        paths.add("xpath://android.webkit.WebView" + TEAM_LIST_PATH_SUFFIX);
        paths.add("xpath:(//android.webkit.WebView)[1]" + TEAM_LIST_PATH_SUFFIX);
        return paths.toArray(new String[0]);
    }

    private void collectTeamTextsFallback(PlatformDriver driver, String deviceId, String parentPath, List<String> teams) {
        String[] fallbacks = {
                parentPath + "//android.view.View[@text]",
                "xpath:(//android.webkit.WebView//android.widget.ListView)[2]//android.view.View[@text]",
                "xpath://android.widget.ListView//android.view.View[@text]"
        };
        java.util.Set<String> seen = new LinkedHashSet<>();
        for (String xpath : fallbacks) {
            for (String t : driver.getElementsTextList(deviceId, xpath)) {
                if (t == null || t.isBlank()) continue;
                String s = t.trim();
                if (s.length() < 2 || s.length() > 60) continue;
                if (isNonTeamLabel(s)) continue;
                seen.add(s);
            }
            if (!seen.isEmpty()) break;
        }
        teams.addAll(seen);
    }

    private static boolean isNonTeamLabel(String s) {
        return "参赛队伍".equals(s) || "赛事信息".equals(s) || "赛事名称".equals(s) || "赛事时间".equals(s)
                || "举办地".equals(s) || "队伍数量".equals(s) || s.startsWith("队伍数量:") || s.startsWith("赛事名称:") || "奖金".equals(s)
                || "概览".equals(s) || "赛程".equals(s) || "对阵".equals(s) || "对阵图".equals(s) || "数据".equals(s)
                || "上一张".equals(s) || "下一张".equals(s)
                || "查看详情".equals(s) || "更多".equals(s) || "展开".equals(s) || "收起".equals(s)
                || "分析".equals(s) || "返回".equals(s) || "分享".equals(s)
                || s.matches("^\\d+$") || s.matches("^\\$[\\d,]+$")
                || s.matches("^[A-Fa-f0-9]{10,}\\s+\\d{10,}$")
                || s.matches("^[A-Fa-f0-9-]{20,}$");
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

    private void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task, List<ExecutionLogEntryDto> logs) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath("cs_ended_match_detail_analysis", task != null ? task.getId() : null, stepIndex);
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
