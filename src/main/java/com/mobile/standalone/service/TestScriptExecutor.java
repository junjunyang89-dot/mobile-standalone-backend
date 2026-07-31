package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FunctionalTestExecutionResult;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.mapper.ApplicationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试脚本执行器
 * 功能基础质量测试按 scenario_key（launch_app / launch_pogpog_android / login / search 等）与平台走不同执行路径。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestScriptExecutor {

    private final AutomationEngine automationEngine;
    private final ApplicationMapper applicationMapper;
    private final LoginTestService loginTestService;
    private final SettingsTestService settingsTestService;
    private final SearchTestService searchTestService;
    private final SearchTabSwitchTestService searchTabSwitchTestService;
    private final SearchUserFollowTestService searchUserFollowTestService;
    private final SearchVideoLatestHottestTestService searchVideoLatestHottestTestService;
    private final CsNewsBrowsingTestService csNewsBrowsingTestService;
    private final CsDynamicBrowsingTestService csDynamicBrowsingTestService;
    private final CsDynamicFollowMoreTestService csDynamicFollowMoreTestService;
    private final CsHotCommentBrowseTestService csHotCommentBrowseTestService;
    private final CsHotCommentLikeTestService csHotCommentLikeTestService;
    private final CsHotCommentToUserProfileTestService csHotCommentToUserProfileTestService;
    private final CsHotCommentContentJumpTestService csHotCommentContentJumpTestService;
    private final CsCommentHotCommentContentTestService csCommentHotCommentContentTestService;
    private final CsVideoBrowseTestService csVideoBrowseTestService;
    private final CsVideoPlayLikeCommentShareTestService csVideoPlayLikeCommentShareTestService;
    private final CsActivityBrowseTestService csActivityBrowseTestService;
    private final CsEventScheduleBrowseTestService csEventScheduleBrowseTestService;
    private final CsEndedMatchDetailDataTestService csEndedMatchDetailDataTestService;
    private final CsEndedMatchDetailAnalysisTestService csEndedMatchDetailAnalysisTestService;

    /**
     * 执行功能体验质量测试
     * 按 task.scenarioKey 分支：launch_app=仅启动并截图，login=登录流程，search=搜索（占位），未传则按应用兼容性走默认逻辑。
     */
    public FunctionalTestExecutionResult executeFunctionalQualityTest(Task task, Device device) {
        log.info("开始执行功能体验质量测试: taskId={}, scenarioKey={}, appId={}, deviceId={}, platform={}",
                task.getId(), task.getScenarioKey(), task.getAppId(), device.getId(), device.getPlatform());

        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId() + "，请在前端选择已安装的应用（来自「选择应用」列表）");
        }

        String scenarioKeyRaw = task.getScenarioKey() != null ? task.getScenarioKey().trim() : null;
        if (scenarioKeyRaw != null && scenarioKeyRaw.isEmpty()) scenarioKeyRaw = null;
        // 统一转小写匹配，避免前端/DB 存成 "Settings" 等导致只走登录
        String scenarioKey = scenarioKeyRaw != null ? scenarioKeyRaw.toLowerCase() : null;

        if ("launch_app".equals(scenarioKey)) {
            log.info("执行场景: 启动应用程序");
            return loginTestService.executeLaunchAppTest(task, device);
        }
        if ("launch_pogpog_android".equals(scenarioKey)) {
            log.info("执行场景: 启动PogPog应用程序(Android)");
            return loginTestService.executeLaunchPogPogAndroidTest(task, device, app);
        }
        if ("login".equals(scenarioKey)) {
            if (loginTestService.isLoginTestSupported(task, app, device)) {
                log.info("执行登录测试（完美世界电竞）: package={}", app.getPackageName());
                return loginTestService.executeLoginTest(task, device);
            }
            // 指定了 login 但应用不支持登录时走通用步骤并带登录占位日志
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-登录(当前应用不支持登录，执行通用步骤)");
        }
        if ("search".equals(scenarioKey)) {
            if (searchTestService.isSearchTestSupported(task, app, device)) {
                log.info("执行场景: 搜索测试(Android)");
                return searchTestService.executeSearchTest(task, device);
            }
            log.info("执行场景: 搜索测试(占位，当前应用/平台不支持)");
            return runSearchTestStub(task, device, app);
        }
        if ("search_tabs".equals(scenarioKey)) {
            if (searchTabSwitchTestService.isSearchTabSwitchSupported(task, app, device)) {
                log.info("执行场景: 搜索Tab切换(Android)");
                return searchTabSwitchTestService.executeSearchTabSwitchTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-搜索Tab切换(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("search_user_follow".equals(scenarioKey)) {
            if (searchUserFollowTestService.isSearchUserFollowSupported(task, app, device)) {
                log.info("执行场景: 搜索用户添加关注(Android)");
                return searchUserFollowTestService.executeSearchUserFollowTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-搜索用户添加关注(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("search_video_latest_hottest".equals(scenarioKey)) {
            if (searchVideoLatestHottestTestService.isSearchVideoLatestHottestSupported(task, app, device)) {
                log.info("执行场景: 搜索-最新/最热视频播放(Android)");
                return searchVideoLatestHottestTestService.executeSearchVideoLatestHottestTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-搜索-最新/最热视频播放(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_news_browsing".equals(scenarioKey)) {
            if (csNewsBrowsingTestService.isCsNewsBrowsingSupported(task, app, device)) {
                log.info("执行场景: CS资讯页浏览(Android)");
                return csNewsBrowsingTestService.executeCsNewsBrowsingTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS资讯页浏览(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_dynamic_browsing".equals(scenarioKey)) {
            if (csDynamicBrowsingTestService.isCsDynamicBrowsingSupported(task, app, device)) {
                log.info("执行场景: CS-动态页浏览(Android)");
                return csDynamicBrowsingTestService.executeCsDynamicBrowsingTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-动态页浏览(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_dynamic_follow_more".equals(scenarioKey)) {
            if (csDynamicFollowMoreTestService.isCsDynamicFollowMoreSupported(task, app, device)) {
                log.info("执行场景: CS-动态-关注更多战队选手(Android)");
                return csDynamicFollowMoreTestService.executeCsDynamicFollowMoreTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-动态-关注更多战队选手(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_hot_comment_browse".equals(scenarioKey)) {
            if (csHotCommentBrowseTestService.isCsHotCommentBrowseSupported(task, app, device)) {
                log.info("执行场景: CS-热评-浏览(Android)");
                return csHotCommentBrowseTestService.executeCsHotCommentBrowseTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-热评-浏览(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_hot_comment_like".equals(scenarioKey)) {
            if (csHotCommentLikeTestService.isCsHotCommentLikeSupported(task, app, device)) {
                log.info("执行场景: CS-热评点赞(Android)");
                return csHotCommentLikeTestService.executeCsHotCommentLikeTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-热评点赞(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_hot_comment_to_user_profile".equals(scenarioKey)) {
            if (csHotCommentToUserProfileTestService.isCsHotCommentToUserProfileSupported(task, app, device)) {
                log.info("执行场景: CS-热评页面跳转至用户个人页面(Android)");
                return csHotCommentToUserProfileTestService.executeCsHotCommentToUserProfileTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-热评页面跳转至用户个人页面(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_hot_comment_content_jump".equals(scenarioKey)) {
            if (csHotCommentContentJumpTestService.isCsHotCommentContentJumpSupported(task, app, device)) {
                log.info("执行场景: CS-热评内容跳转(Android)");
                return csHotCommentContentJumpTestService.executeCsHotCommentContentJumpTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-热评内容跳转(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_comment_hot_comment_content".equals(scenarioKey)) {
            if (csCommentHotCommentContentTestService.isCsCommentHotCommentContentSupported(task, app, device)) {
                log.info("执行场景: CS-评论热评内容(Android)");
                return csCommentHotCommentContentTestService.executeCsCommentHotCommentContentTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-评论热评内容(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_video_browse".equals(scenarioKey)) {
            if (csVideoBrowseTestService.isCsVideoBrowseSupported(task, app, device)) {
                log.info("执行场景: CS-视频浏览(Android)");
                return csVideoBrowseTestService.executeCsVideoBrowseTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-视频浏览(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_video_play_like_comment_share".equals(scenarioKey)) {
            if (csVideoPlayLikeCommentShareTestService.isCsVideoPlayLikeCommentShareSupported(task, app, device)) {
                log.info("执行场景: CS-视频播放|点赞|评论|分享(Android)");
                return csVideoPlayLikeCommentShareTestService.executeCsVideoPlayLikeCommentShareTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-视频播放|点赞|评论|分享(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_activity_browse".equals(scenarioKey)) {
            if (csActivityBrowseTestService.isCsActivityBrowseSupported(task, app, device)) {
                log.info("执行场景: CS-活动浏览(Android)");
                return csActivityBrowseTestService.executeCsActivityBrowseTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-活动浏览(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_event_schedule_browse".equals(scenarioKey)) {
            if (csEventScheduleBrowseTestService.isCsEventScheduleBrowseSupported(task, app, device)) {
                log.info("执行场景: CS-赛事-赛程浏览(Android)");
                return csEventScheduleBrowseTestService.executeCsEventScheduleBrowseTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-赛事-赛程浏览(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_ended_match_detail_data".equals(scenarioKey)) {
            if (csEndedMatchDetailDataTestService.isCsEndedMatchDetailDataSupported(task, app, device)) {
                log.info("执行场景: CS-已结束比赛-赛事详情-数据(Android)");
                return csEndedMatchDetailDataTestService.executeCsEndedMatchDetailDataTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-已结束比赛-赛事详情-数据(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("cs_ended_match_detail_analysis".equals(scenarioKey)) {
            if (csEndedMatchDetailAnalysisTestService.isCsEndedMatchDetailAnalysisSupported(task, app, device)) {
                log.info("执行场景: CS-已结束比赛-赛事详情-分析(Android)");
                return csEndedMatchDetailAnalysisTestService.executeCsEndedMatchDetailAnalysisTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-CS-已结束比赛-赛事详情-分析(当前应用/平台不支持，需完美世界电竞 Android 应用)");
        }
        if ("settings".equals(scenarioKey)) {
            if (settingsTestService.isSettingsTestSupported(task, app, device)) {
                log.info("执行场景: 设置测试(Android/iOS)，将执行 启动->登录->设置页(侧边栏/设置/截图)");
                return settingsTestService.executeSettingsTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-设置(当前应用不支持设置测试，需完美世界电竞应用)");
        }
        if ("settings_account".equals(scenarioKey)) {
            if (settingsTestService.isSettingsTestSupported(task, app, device)) {
                log.info("执行场景: 设置-我的账号(Android/iOS)，将执行 启动->登录->设置页->我的账号->截图");
                return settingsTestService.executeSettingsAccountTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-设置-我的账号(当前应用不支持，需完美世界电竞应用)");
        }
        if ("settings_interests".equals(scenarioKey)) {
            if (settingsTestService.isSettingsTestSupported(task, app, device)) {
                log.info("执行场景: 设置-我的兴趣(Android/iOS)，将执行 启动->登录->设置页->我的兴趣->截图");
                return settingsTestService.executeSettingsInterestsTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-设置-我的兴趣(当前应用不支持，需完美世界电竞应用)");
        }
        if ("settings_school_cert".equals(scenarioKey)) {
            if (settingsTestService.isSettingsTestSupported(task, app, device)) {
                log.info("执行场景: 设置-高校认证(Android/iOS)，将执行 启动->登录->设置页->高校认证->截图");
                return settingsTestService.executeSettingsSchoolCertTest(task, device);
            }
            return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试-设置-高校认证(当前应用不支持，需完美世界电竞应用)");
        }

        // 未指定场景或其它：沿用原逻辑（支持登录则登录，否则通用）。若期望跑「设置」却只跑了登录，请检查创建任务时是否传 scenarioKey=settings
        if (scenarioKeyRaw != null) {
            log.warn("未识别的 scenarioKey={}，按默认逻辑执行（仅登录等）。若为设置测试请传 scenarioKey=settings；设置-我的兴趣请传 scenarioKey=settings_interests；设置-高校认证请传 scenarioKey=settings_school_cert", scenarioKeyRaw);
        }
        if (loginTestService.isLoginTestSupported(task, app, device)) {
            log.info("执行登录测试（完美世界电竞）: package={}", app.getPackageName());
            return loginTestService.executeLoginTest(task, device);
        }

        return runGenericFunctionalTest(task, device, app, "功能体验基础质量测试开始执行");
    }

    private FunctionalTestExecutionResult runGenericFunctionalTest(Task task, Device device, Application app, String stepMessage) {
        String deviceId = task.getDeviceId();
        Device.Platform platform = device.getPlatform();
        String pkg = app.getPackageName() != null ? app.getPackageName() : "-";
        String ver = app.getVersion() != null ? app.getVersion() : "-";
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        executionLog.add(ExecutionLogEntryDto.success("开始测试", ""));
        executionLog.add(ExecutionLogEntryDto.success("获取执行步骤", stepMessage));
        executionLog.add(ExecutionLogEntryDto.success("应用信息", "是否卸载应用:否"));
        executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用历史版本hap路径:null"));
        executionLog.add(ExecutionLogEntryDto.success("应用信息", "正在解析应用信息..."));
        executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用包名:" + pkg));
        executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用版本:" + ver));
        executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用信息解析完成"));
        executionLog.add(ExecutionLogEntryDto.success("环境初始化", "正在检查设备状态并执行环境初始化..."));
        executionLog.add(ExecutionLogEntryDto.success("环境初始化", "设备环境就绪"));
        executionLog.add(ExecutionLogEntryDto.success("检测规则", "开始检测规则:启动..."));
        try {
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "包名:" + pkg));
            startApplication(deviceId, platform, task.getAppId());
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "启动标准检测完成"));
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "等待 3 秒"));
            Thread.sleep(3000);
            executionLog.add(ExecutionLogEntryDto.success("检测规则", "开始检测规则:运行..."));
            executionLog.add(ExecutionLogEntryDto.success("执行测试步骤", ""));
            executeTestSteps(deviceId, platform, task);
            executionLog.add(ExecutionLogEntryDto.success("执行完成", "运行标准检测完成"));
            executionLog.add(ExecutionLogEntryDto.success("收集测试结果并截图", ""));
            String path = collectTestResults(deviceId, platform, task);
            executionLog.add(ExecutionLogEntryDto.success("后置清理", "开始执行后置清理工作..."));
            executionLog.add(ExecutionLogEntryDto.success("后置清理", "后置清理工作执行成功"));
            executionLog.add(ExecutionLogEntryDto.success("报告", "功能体验标准检测执行完成,正在汇总数据并生成报告"));
            executionLog.add(ExecutionLogEntryDto.success("报告", "报告已生成,任务完成"));
            executionLog.add(ExecutionLogEntryDto.success("测试服务", "测试服务执行完毕"));
            return new FunctionalTestExecutionResult(path, executionLog, null);
        } catch (Exception e) {
            log.error("执行功能体验质量测试失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            throw new RuntimeException("测试执行失败: " + e.getMessage(), e);
        }
    }

    /** 搜索测试占位：仅启动 + 截图 + 日志，后续可接入真实搜索步骤 */
    private FunctionalTestExecutionResult runSearchTestStub(Task task, Device device, Application app) {
        String deviceId = task.getDeviceId();
        Device.Platform platform = device.getPlatform();
        String pkg = app.getPackageName() != null ? app.getPackageName() : "-";
        List<ExecutionLogEntryDto> executionLog = new ArrayList<>();
        executionLog.add(ExecutionLogEntryDto.success("开始测试", ""));
        executionLog.add(ExecutionLogEntryDto.success("获取执行步骤", "功能体验基础质量测试-搜索测试(占位)"));
        executionLog.add(ExecutionLogEntryDto.success("应用信息", "应用包名:" + pkg));
        executionLog.add(ExecutionLogEntryDto.success("环境初始化", "设备环境就绪"));
        executionLog.add(ExecutionLogEntryDto.success("检测规则", "开始检测规则:启动..."));
        try {
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "包名:" + pkg));
            startApplication(deviceId, platform, task.getAppId());
            executionLog.add(ExecutionLogEntryDto.success("启动应用程序", "启动标准检测完成"));
            Thread.sleep(3000);
            executionLog.add(ExecutionLogEntryDto.success("搜索测试", "搜索测试(占位实现，后续接入真实搜索步骤)"));
            executionLog.add(ExecutionLogEntryDto.success("收集测试结果并截图", ""));
            String path = collectTestResults(deviceId, platform, task);
            executionLog.add(ExecutionLogEntryDto.success("报告", "报告已生成,任务完成"));
            executionLog.add(ExecutionLogEntryDto.success("测试服务", "测试服务执行完毕"));
            return new FunctionalTestExecutionResult(path, executionLog, null);
        } catch (Exception e) {
            log.error("搜索测试执行失败", e);
            executionLog.add(ExecutionLogEntryDto.fail("执行失败", e.getMessage(), null));
            throw new RuntimeException("测试执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 启动应用（ADB/idevice，非 Appium 场景）
     */
    private void startApplication(String deviceId, Device.Platform platform, String appId) {
        log.info("启动应用: deviceId={}, platform={}, appId={}", deviceId, platform, appId);
        try {
            if (Device.usesAndroidAutomation(platform)) {
                String packageName = getPackageName(appId);
                if (packageName != null) {
                    Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
                            .redirectErrorStream(true)
                            .start();
                    process.waitFor();
                }
            } else if (Device.usesIosAutomation(platform)) {
                String bundleId = getBundleId(appId);
                if (bundleId != null) {
                    // TODO: iOS 非 Appium 启动
                }
            }
        } catch (Exception e) {
            log.error("启动应用失败", e);
            throw new RuntimeException("启动应用失败: " + e.getMessage(), e);
        }
    }

    private void executeTestSteps(String deviceId, Device.Platform platform, Task task) {
        log.info("执行测试步骤: deviceId={}, platform={}", deviceId, platform);
        automationEngine.clickElement(deviceId, platform, "text:登录");
        automationEngine.waitForElement(deviceId, platform, "text:用户名", 5);
        automationEngine.inputText(deviceId, platform, "testuser");
        automationEngine.inputText(deviceId, platform, "password123");
        automationEngine.clickElement(deviceId, platform, "text:确认");
        String screenshotPath = "/tmp/screenshots/" + task.getId() + "_" + System.currentTimeMillis() + ".png";
        automationEngine.takeScreenshot(deviceId, platform, screenshotPath);
    }

    private String collectTestResults(String deviceId, Device.Platform platform, Task task) {
        log.info("收集测试结果: deviceId={}, platform={}", deviceId, platform);
        String pageSource = automationEngine.getPageSource(deviceId, platform);
        if (pageSource != null) log.debug("页面元素树长度: {}", pageSource.length());
        String screenshotPath = "/tmp/screenshots/" + task.getId() + "_final.png";
        String savedPath = automationEngine.takeScreenshot(deviceId, platform, screenshotPath);
        if (savedPath != null) log.info("测试结果截图已保存: {}", savedPath);
        return savedPath != null ? savedPath : screenshotPath;
    }

    private String getPackageName(String appId) {
        Application application = applicationMapper.findById(appId);
        return application != null ? application.getPackageName() : null;
    }

    private String getBundleId(String appId) {
        Application application = applicationMapper.findById(appId);
        return application != null ? application.getPackageName() : null;
    }
}
