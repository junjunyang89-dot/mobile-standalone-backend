package com.mobile.standalone.service;

import com.mobile.standalone.dto.CompatibilitySummaryDto;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.ResultDistributionItemDto;
import com.mobile.standalone.dto.TaskOverviewDto;
import com.mobile.standalone.dto.TestResultItemDto;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.entity.Device;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务详情页数据组装，与前端截图一致。
 * 包含：任务 ID/标题/备注、测试类型、应用信息、开始/持续时间、执行人、
 * 概览（兼容性满足度、测试结果分布）、检测规则表格、报告路径。
 * 支持功能基础质量测试、兼容性测试、性能基础质量测试。
 */
@Service
@RequiredArgsConstructor
public class TaskOverviewService {

    private static final String EXECUTOR_FIXED = "杨俊俊";
    private static final String RESULT_PASS = "通过";
    private static final String RESULT_FAIL = "不通过";
    private static final String RESULT_NOT_RUN = "未执行";
    private static final String RESULT_DEVICE_DEPENDENT = "设备依赖";
    /** 功能基础质量测试时，概览统计区块标题 */
    private static final String SUMMARY_TITLE_FUNCTIONAL_QUALITY = "功能质量达标率";
    /** 兼容性测试时，概览统计区块标题 */
    private static final String SUMMARY_TITLE_COMPATIBILITY = "兼容性满足度";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_STRING_STRING = new TypeReference<>() {};
    private static final TypeReference<List<ExecutionLogEntryDto>> EXECUTION_LOG_TYPE = new TypeReference<>() {};

    private final TaskMapper taskMapper;
    private final TaskExecutionMapper executionMapper;
    private final ApplicationMapper applicationMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceManager deviceManager;
    private final FunctionalTestScenarioService scenarioService;

    /**
     * 获取任务详情概览，用于前端任务详情页展示。
     * 支持 FUNCTIONAL_QUALITY_TEST、COMPATIBILITY_TEST、PERFORMANCE_BASELINE_TEST；其他类型返回 null。
     */
    public TaskOverviewDto getOverview(String taskId) {
        Task task = taskMapper.findById(taskId);
        if (task == null) {
            return null;
        }
        Task.TaskType type = task.getTaskType();
        if (type != Task.TaskType.FUNCTIONAL_QUALITY_TEST && type != Task.TaskType.COMPATIBILITY_TEST && type != Task.TaskType.PERFORMANCE_BASELINE_TEST) {
            return null;
        }

        TaskOverviewDto dto = new TaskOverviewDto();
        dto.setTaskId(task.getId());
        dto.setTitle(task.getName() != null ? task.getName() : type.getDisplayName());
        dto.setRemarks(task.getDescription());
        dto.setScenarioKey(task.getScenarioKey());
        dto.setScenarioName(scenarioService.getScenarioDisplayNameOrFallback(task));
        dto.setTestType(type == Task.TaskType.COMPATIBILITY_TEST ? "应用" : type.getDisplayName());
        dto.setExecutor(EXECUTOR_FIXED);
        dto.setApiVersion(null);

        Application app = task.getAppId() != null ? applicationMapper.findById(task.getAppId()) : null;
        if (app != null) {
            dto.setAppPackageName(app.getPackageName());
            String version = resolveAppVersion(task, app);
            dto.setAppVersion(version);
        } else {
            dto.setAppPackageName(null);
            dto.setAppVersion(null);
        }

        List<TaskExecution> executions = executionMapper.findByTaskIdOrderByStartTimeDesc(taskId);
        TaskExecution latest = executions.isEmpty() ? null : executions.get(0);

        if (latest != null) {
            dto.setStartTime(latest.getStartTime());
            dto.setDuration(formatDuration(latest.getStartTime(), latest.getEndTime()));
            dto.setReportPath(latest.getReportPath());
            dto.setStatus(latest.getStatus() != null ? latest.getStatus().name() : null);
            dto.setExecutionStatus(dto.getStatus());
            dto.setErrorMessage(latest.getErrorMessage());
            List<ExecutionLogEntryDto> discovery = ScreenshotDiscoveryService.discoverStepScreenshots(taskId);
            List<ExecutionLogEntryDto> executionLog = !discovery.isEmpty() ? discovery : parseExecutionLog(latest.getExecutionLog());
            if (executionLog != null) {
                fillScreenshotUrls(executionLog, taskId);
            }
            dto.setExecutionLog(executionLog != null ? executionLog : java.util.Collections.emptyList());
        } else {
            dto.setStartTime(null);
            dto.setDuration(null);
            dto.setReportPath(null);
            dto.setExecutionLog(java.util.Collections.emptyList());
            dto.setStatus(task.getStatus() != null ? task.getStatus().name() : null);
            dto.setExecutionStatus(dto.getStatus());
            dto.setErrorMessage(null);
        }

        if (type == Task.TaskType.FUNCTIONAL_QUALITY_TEST) {
            dto.setSummarySectionTitle(SUMMARY_TITLE_FUNCTIONAL_QUALITY);
            Boolean passed = latest == null ? null : (latest.getStatus() == TaskExecution.ExecutionStatus.COMPLETED);
            Map<String, String> executionVariables = parseExecutionVariables(latest != null ? latest.getExecutionVariables() : null);
            dto.setExecutionVariables(executionVariables);
            List<ExecutionLogEntryDto> executionLog = parseExecutionLog(latest != null ? latest.getExecutionLog() : null);
            List<TestResultItemDto> items = buildFunctionalQualityResultItems(task, passed, executionVariables, executionLog);
            dto.setTestResultItems(items);
            dto.setCompatibilitySummary(buildSummaryFromItems(items));
            dto.setResultDistribution(buildDistributionFromItems(items));
            String sk = task.getScenarioKey() != null ? task.getScenarioKey().trim().toLowerCase() : null;
            if (("cs_ended_match_detail_analysis".equals(sk) || "cs_ended_match_detail_analysis_android".equals(sk))
                    && executionLog != null && !executionLog.isEmpty()) {
                dto.setParticipatingTeams(extractParticipatingTeams(executionLog));
            }
        } else if (type == Task.TaskType.PERFORMANCE_BASELINE_TEST) {
            dto.setSummarySectionTitle("性能测试结果");
            Boolean passed = latest == null ? null : (latest.getStatus() == TaskExecution.ExecutionStatus.COMPLETED);
            List<ExecutionLogEntryDto> executionLog = parseExecutionLog(latest != null ? latest.getExecutionLog() : null);
            List<TestResultItemDto> items = buildPerformanceResultItems(passed, executionLog);
            dto.setTestResultItems(items);
            dto.setCompatibilitySummary(buildSummaryFromItems(items));
            dto.setResultDistribution(buildDistributionFromItems(items));
        } else {
            dto.setSummarySectionTitle(SUMMARY_TITLE_COMPATIBILITY);
            boolean completed = latest != null && latest.getStatus() == TaskExecution.ExecutionStatus.COMPLETED;
            List<TestResultItemDto> items = buildCompatibilityResultItems(completed);
            dto.setTestResultItems(items);
            dto.setCompatibilitySummary(buildSummaryFromItems(items));
            dto.setResultDistribution(buildDistributionFromItems(items));
        }

        return dto;
    }

    /**
     * 功能基础质量测试：按场景输出报告测项，检测规则必须与本次执行逻辑一致。
     * - 搜索：启动、登录（判断/可能跳过）、搜索（点击搜索、输入关键词、验证结果），按执行日志逐条生成，分类为 启动/登录/搜索。
     * - 设置/设置-我的账号/设置-我的兴趣/设置-高校认证：按执行日志逐条生成测项（侧边栏、设置页、子页、截图等），不套用登录测项。
     * - 当 scenario_key 为空时，根据执行日志内容推断是否为设置相关场景，避免误展示「输入手机号/验证码」。
     * - 登录等其它场景：沿用「启动、登录-输入手机号/验证码/完成登录」+ 整体 passed。
     */
    private List<TestResultItemDto> buildFunctionalQualityResultItems(Task task, Boolean passed, Map<String, String> executionVariables, List<ExecutionLogEntryDto> executionLog) {
        String scenarioKey = task != null && task.getScenarioKey() != null ? task.getScenarioKey().trim().toLowerCase() : null;
        // 搜索-最新/最热视频播放：优先按 scenarioKey 展示预定义检测规则，有完整执行日志时再按日志细化
        if ("search_video_latest_hottest".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isSearchVideoLatestHottestLog(executionLog)) {
                return buildSearchScenarioResultItems(executionLog, scenarioKey);
            }
            return buildSearchVideoLatestHottestPredefinedItems(passed);
        }
        // CS资讯页浏览：按执行日志生成，分类为 启动/登录/CS资讯
        if ("cs_news_browsing".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsNewsBrowsingLog(executionLog)) {
                return buildCsNewsBrowsingResultItems(executionLog);
            }
            return buildCsNewsBrowsingPredefinedItems(passed);
        }
        // CS-动态页浏览：按执行日志生成，分类为 启动/登录/CS-动态
        if ("cs_dynamic_browsing".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsDynamicBrowsingLog(executionLog)) {
                return buildCsDynamicBrowsingResultItems(executionLog);
            }
            return buildCsDynamicBrowsingPredefinedItems(passed);
        }
        // CS-动态-关注更多战队选手：按执行日志生成，分类为 启动/登录/关注更多
        if ("cs_dynamic_follow_more".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsDynamicFollowMoreLog(executionLog)) {
                return buildCsDynamicFollowMoreResultItems(executionLog);
            }
            return buildCsDynamicFollowMorePredefinedItems(passed);
        }
        // CS-热评-浏览：按执行日志生成，分类为 启动/登录/CS-热评-浏览
        if ("cs_hot_comment_browse".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsHotCommentBrowseLog(executionLog)) {
                return buildCsHotCommentBrowseResultItems(executionLog);
            }
            return buildCsHotCommentBrowsePredefinedItems(passed);
        }
        // CS-热评点赞：按执行日志生成，分类为 启动/登录/CS-热评点赞
        if ("cs_hot_comment_like".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsHotCommentLikeLog(executionLog)) {
                return buildCsHotCommentLikeResultItems(executionLog);
            }
            return buildCsHotCommentLikePredefinedItems(passed);
        }
        // CS-热评页面跳转至用户个人页面：按执行日志生成，分类为 启动/登录/热评跳转
        if ("cs_hot_comment_to_user_profile".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsHotCommentToUserProfileLog(executionLog)) {
                return buildCsHotCommentToUserProfileResultItems(executionLog);
            }
            return buildCsHotCommentToUserProfilePredefinedItems(passed);
        }
        // CS-热评内容跳转：按执行日志生成，分类为 启动/登录/热评内容跳转
        if ("cs_hot_comment_content_jump".equals(scenarioKey)) {
            return buildCsHotCommentContentJumpPredefinedItems(passed);
        }
        // CS-评论热评内容：按执行日志生成，分类为 启动/登录/评论热评内容
        if ("cs_comment_hot_comment_content".equals(scenarioKey)) {
            return buildCsCommentHotCommentContentPredefinedItems(passed);
        }
        // CS-视频播放|点赞|评论|分享：按执行日志生成，分类为 启动/登录/CS-视频播放/点赞/评论/分享
        if ("cs_video_play_like_comment_share".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsVideoPlayLikeCommentShareLog(executionLog)) {
                return buildCsVideoPlayLikeCommentShareResultItems(executionLog);
            }
            return buildCsVideoPlayLikeCommentSharePredefinedItems(passed);
        }
        // CS-视频浏览：按执行日志生成，分类为 启动/登录/CS-视频浏览
        if ("cs_video_browse".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsVideoBrowseLog(executionLog)) {
                return buildCsVideoBrowseResultItems(executionLog);
            }
            return buildCsVideoBrowsePredefinedItems(passed);
        }
        // CS-活动浏览：按执行日志生成，分类为 启动/登录/CS-活动
        if ("cs_activity_browse".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsActivityBrowseLog(executionLog)) {
                return buildCsActivityBrowseResultItems(executionLog);
            }
            return buildCsActivityBrowsePredefinedItems(passed);
        }
        // CS-赛事-赛程浏览：按执行日志生成，分类为 启动/登录/CS-赛事-赛程
        if ("cs_event_schedule_browse".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsEventScheduleBrowseLog(executionLog)) {
                return buildCsEventScheduleBrowseResultItems(executionLog);
            }
            return buildCsEventScheduleBrowsePredefinedItems(passed);
        }
        // CS-已结束比赛-赛事详情-数据：按执行日志生成，分类为 启动/登录/CS-赛事-赛程
        if ("cs_ended_match_detail_data".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsEndedMatchDetailDataLog(executionLog)) {
                return buildCsEndedMatchDetailDataResultItems(executionLog);
            }
            return buildCsEndedMatchDetailDataPredefinedItems(passed);
        }
        // CS-已结束比赛-赛事详情-分析：按执行日志生成，参赛队伍战队数据输出至测试结果分布
        if ("cs_ended_match_detail_analysis".equals(scenarioKey)) {
            if (executionLog != null && !executionLog.isEmpty() && isCsEndedMatchDetailAnalysisLog(executionLog)) {
                return buildCsEndedMatchDetailAnalysisResultItems(executionLog);
            }
            return buildCsEndedMatchDetailAnalysisPredefinedItems(passed);
        }
        boolean fromLog = false;
        String categoryForLog = null;
        boolean searchFromLog = false;
        if (executionLog != null && !executionLog.isEmpty()) {
            // 搜索场景：按执行日志逐条生成，每步归类为 启动/登录/搜索 等，测试结果分布展示对应分类
            if ("search".equals(scenarioKey) || "search_tabs".equals(scenarioKey) || "search_user_follow".equals(scenarioKey) || "search_video_latest_hottest".equals(scenarioKey) || isSearchLog(executionLog) || isSearchUserFollowLog(executionLog) || isSearchVideoLatestHottestLog(executionLog)) {
                searchFromLog = true;
            } else if (isSettingsOrSettingsAccountLog(executionLog)) {
                fromLog = true;
                categoryForLog = inferSettingsCategoryFromLog(executionLog);
            } else if ("settings".equals(scenarioKey)) {
                fromLog = true;
                categoryForLog = "设置";
            } else if ("settings_account".equals(scenarioKey)) {
                fromLog = true;
                categoryForLog = "设置-我的账号";
            } else if ("settings_interests".equals(scenarioKey)) {
                fromLog = true;
                categoryForLog = "设置-我的兴趣";
            } else if ("settings_school_cert".equals(scenarioKey)) {
                fromLog = true;
                categoryForLog = "设置-高校认证";
            }
        }
        if (searchFromLog) {
            String effectiveScenarioKey = scenarioKey;
            if (effectiveScenarioKey == null && isSearchVideoLatestHottestLog(executionLog)) {
                effectiveScenarioKey = "search_video_latest_hottest";
            }
            return buildSearchScenarioResultItems(executionLog, effectiveScenarioKey);
        }
        if (fromLog && categoryForLog != null) {
            List<TestResultItemDto> list = new ArrayList<>();
            for (ExecutionLogEntryDto entry : executionLog) {
                String step = entry.getStep() != null ? entry.getStep() : "步骤";
                if (isLogMetaStep(step)) continue; // 仅展示实际校验步骤，不展示头部/汇总等元信息
                String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
                list.add(new TestResultItemDto(categoryForLog, step, result, null, entry.getMessage()));
            }
            return list;
        }
        // 任务配置为设置/设置-我的账号/设置-我的兴趣/设置-高校认证但无执行日志时：按执行状态决定通过/未执行，避免达标率为 0
        if (executionLog == null || executionLog.isEmpty()) {
            String resultWhenNoLog = Boolean.TRUE.equals(passed) ? RESULT_PASS : RESULT_NOT_RUN;
            String detailWhenNoLog = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
            if ("settings".equals(scenarioKey)) {
                return List.of(new TestResultItemDto("设置", "进入设置页并截图", resultWhenNoLog, null, detailWhenNoLog));
            }
            if ("settings_account".equals(scenarioKey)) {
                return List.of(new TestResultItemDto("设置-我的账号", "进入我的账号页并截图", resultWhenNoLog, null, detailWhenNoLog));
            }
            if ("settings_interests".equals(scenarioKey)) {
                return List.of(new TestResultItemDto("设置-我的兴趣", "进入我的兴趣页并截图", resultWhenNoLog, null, detailWhenNoLog));
            }
            if ("settings_school_cert".equals(scenarioKey)) {
                return List.of(new TestResultItemDto("设置-高校认证", "进入高校认证页并截图", resultWhenNoLog, null, detailWhenNoLog));
            }
            if ("search_tabs".equals(scenarioKey)) {
                return List.of(new TestResultItemDto("搜索Tab切换", "搜索后依次切换综合/新闻/社区/视频/梗百科/选手/战队/用户", resultWhenNoLog, null, detailWhenNoLog));
            }
            if ("search_user_follow".equals(scenarioKey)) {
                return List.of(new TestResultItemDto("搜索用户添加关注", "搜索Kak1→校验结果→点击更多→用户Tab→添加关注", resultWhenNoLog, null, detailWhenNoLog));
            }
            if ("search_video_latest_hottest".equals(scenarioKey)) {
                return List.of(new TestResultItemDto("搜索-最新最热视频", "启动→登录→搜索→视频Tab→最新/最热视频播放→加载超时返回→向上滑动", resultWhenNoLog, null, detailWhenNoLog));
            }
            if ("cs_video_play_like_comment_share".equals(scenarioKey)) {
                return buildCsVideoPlayLikeCommentSharePredefinedItems(passed);
            }
            if ("cs_video_browse".equals(scenarioKey)) {
                return buildCsVideoBrowsePredefinedItems(passed);
            }
            if ("cs_activity_browse".equals(scenarioKey)) {
                return buildCsActivityBrowsePredefinedItems(passed);
            }
            if ("cs_event_schedule_browse".equals(scenarioKey)) {
                return buildCsEventScheduleBrowsePredefinedItems(passed);
            }
            if ("cs_ended_match_detail_data".equals(scenarioKey)) {
                return buildCsEndedMatchDetailDataPredefinedItems(passed);
            }
            if ("cs_ended_match_detail_analysis".equals(scenarioKey)) {
                return buildCsEndedMatchDetailAnalysisPredefinedItems(passed);
            }
        }
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String phoneMasked = executionVariables != null ? executionVariables.get("phoneNumberMasked") : null;
        String codeMasked = executionVariables != null ? executionVariables.get("verificationCodeMasked") : null;
        List<TestResultItemDto> list = new ArrayList<>();
        list.add(new TestResultItemDto("启动", "启动应用程序", result, null, null));
        list.add(new TestResultItemDto("登录", "输入手机号", result, null, phoneMasked));
        list.add(new TestResultItemDto("登录", "输入验证码", result, null, codeMasked));
        list.add(new TestResultItemDto("登录", "完成登录", result, null, null));
        return list;
    }

    /** 是否为日志中的元信息步骤（不作为检测规则展示） */
    private boolean isLogMetaStep(String step) {
        if (step == null) return true;
        String s = step.trim();
        return "开始测试".equals(s) || "测试场景".equals(s) || "测试应用".equals(s) || "测试设备".equals(s) || "汇总".equals(s);
    }

    /** 根据执行日志判断是否为「设置」或「设置-我的账号」或「设置-我的兴趣」或「设置-高校认证」场景（避免误用登录测项） */
    private boolean isSettingsOrSettingsAccountLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            String msg = entry.getMessage() != null ? entry.getMessage() : "";
            if (step.contains("设置-我的账号") || step.contains("我的账号页") || msg.contains("设置-我的账号") || msg.contains("我的账号"))
                return true;
            if (step.contains("设置-我的兴趣") || step.contains("我的兴趣页") || msg.contains("设置-我的兴趣") || msg.contains("我的兴趣"))
                return true;
            if (step.contains("设置-高校认证") || step.contains("高校认证页") || msg.contains("设置-高校认证") || msg.contains("高校认证"))
                return true;
            if (step.contains("设置测试") || step.contains("设置页面") || msg.contains("设置测试") || msg.contains("设置页"))
                return true;
        }
        return false;
    }

    /** 从执行日志推断分类：有「高校认证」→「设置-高校认证」，有「我的兴趣」→「设置-我的兴趣」，有「我的账号」→「设置-我的账号」，否则「设置」 */
    private String inferSettingsCategoryFromLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return "设置";
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            String msg = entry.getMessage() != null ? entry.getMessage() : "";
            if (step.contains("高校认证") || msg.contains("高校认证")) return "设置-高校认证";
            if (step.contains("我的兴趣") || msg.contains("我的兴趣")) return "设置-我的兴趣";
            if (step.contains("我的账号") || msg.contains("我的账号")) return "设置-我的账号";
        }
        return "设置";
    }

    /** 搜索场景：按执行日志逐条生成测项，每步归类为 启动/登录/搜索 或 搜索-最新最热视频 的细分分类 */
    private List<TestResultItemDto> buildSearchScenarioResultItems(List<ExecutionLogEntryDto> executionLog, String scenarioKey) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        boolean isVideoLatestHottest = "search_video_latest_hottest".equals(scenarioKey);
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferSearchStepCategory(step, isVideoLatestHottest);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断搜索场景下的分类：启动、登录、搜索；search_video_latest_hottest 时细分为 视频Tab、最新、最热、返回校验、滑动 */
    private String inferSearchStepCategory(String step, boolean isVideoLatestHottest) {
        if (step == null) return "搜索";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("判断登录") || s.contains("侧边栏") || s.contains("获取驱动") || s.contains("前置登录")) return "登录";
        if (isVideoLatestHottest) {
            if (s.contains("点击视频Tab") || s.contains("视频 Tab")) return "视频Tab";
            if (s.contains("判断最新") || (s.contains("随机选视频") && !s.contains("最热")) || (s.contains("播放视频") && !s.contains("最热")) || (s.contains("检测加载超时") && !s.contains("最热"))) return "最新";
            if (s.contains("判断最热") || s.contains("点击最热") || s.contains("随机选视频(最热)") || s.contains("播放视频(最热)") || s.contains("检测加载超时(最热)")) return "最热";
            if (s.contains("验证返回") || s.contains("点击返回") || s.contains("返回视频页")) return "返回校验";
            if (s.contains("向上滑动") || s.contains("滑动完成") || s.contains("滑动结束")) return "滑动";
        }
        return "搜索";
    }

    /** 执行日志是否包含搜索相关步骤 */
    private boolean isSearchLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("搜索") || step.contains("点击搜索") || step.contains("输入搜索") || step.contains("验证搜索")) return true;
        }
        return false;
    }

    /** 执行日志是否包含搜索Tab切换相关步骤 */
    private boolean isSearchTabSwitchLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("搜索Tab切换") || step.contains("点击Tab") || step.contains("校验综合Tab")) return true;
        }
        return false;
    }

    /** 执行日志是否包含搜索用户添加关注相关步骤 */
    private boolean isSearchUserFollowLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("搜索用户添加关注") || step.contains("点击关注") || step.contains("取消关注") || step.contains("校验用户Tab")) return true;
        }
        return false;
    }

    /** 执行日志是否包含搜索-最新/最热视频播放相关步骤 */
    private boolean isSearchVideoLatestHottestLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("点击视频Tab") || step.contains("判断最新") || step.contains("判断最热") || step.contains("点击最热") || step.contains("向上滑动") || step.contains("srl_classics_title")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS资讯页浏览 相关步骤 */
    private boolean isCsNewsBrowsingLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("点击CS") || step.contains("点击资讯") || step.contains("CS资讯")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-动态页浏览 相关步骤 */
    private boolean isCsDynamicBrowsingLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("点击动态") || step.contains("CS-动态") || step.contains("关注更多战队选手")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-动态-关注更多战队选手 相关步骤 */
    private boolean isCsDynamicFollowMoreLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("选择关注") || step.contains("点击战队") || step.contains("点击赛事") || step.contains("点击选手") || step.contains("关注更多")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-热评-浏览 相关步骤 */
    private boolean isCsHotCommentBrowseLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("热评") || step.contains("CS-热评-浏览")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-热评点赞 相关步骤 */
    private boolean isCsHotCommentLikeLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("点赞") || step.contains("排行5") || step.contains("CS-热评点赞")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-热评页面跳转至用户个人页面 相关步骤 */
    private boolean isCsHotCommentToUserProfileLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("排行6") || step.contains("头像") || step.contains("用户个人页面")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-视频播放|点赞|评论|分享 相关步骤 */
    private boolean isCsVideoPlayLikeCommentShareLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("点击视频播放") || step.contains("点赞") || step.contains("点击评论") || step.contains("分享") || step.contains("取消分享")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-视频浏览 相关步骤 */
    private boolean isCsVideoBrowseLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("点击视频") || step.contains("向上滑动") || step.contains("向下滑动") || step.contains("CS-视频")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-活动浏览 相关步骤 */
    private boolean isCsActivityBrowseLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("活动") || step.contains("资讯") || step.contains("向左滑动") || step.contains("随机点击活动")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-赛事-赛程浏览 相关步骤 */
    private boolean isCsEventScheduleBrowseLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("赛事") || step.contains("赛程") || step.contains("今天") || step.contains("周×") || step.contains("向右滑动") || step.contains("向左滑动")) return true;
        }
        return false;
    }

    /** 执行日志是否包含 CS-已结束比赛-赛事详情-分析 相关步骤 */
    private boolean isCsEndedMatchDetailAnalysisLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("分析") || step.contains("对阵图") || step.contains("概览") || step.contains("参赛队伍")) return true;
        }
        return false;
    }

    /** 从执行日志中提取参赛队伍名称（step=参赛队伍 且 message 为战队名，排除系统消息） */
    private List<String> extractParticipatingTeams(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return List.of();
        List<String> teams = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            String msg = entry.getMessage() != null ? entry.getMessage().trim() : "";
            if (!step.contains("参赛队伍") || msg.isEmpty()) continue;
            if (msg.startsWith("共提取") || msg.startsWith("未") || msg.startsWith("已找到")
                    || msg.contains("判断") || msg.contains("等待") || msg.contains("元素")) continue;
            teams.add(msg);
        }
        return teams;
    }

    /** 执行日志是否包含 CS-已结束比赛-赛事详情-数据 相关步骤 */
    private boolean isCsEndedMatchDetailDataLog(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null) return false;
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "";
            if (step.contains("已结束") || step.contains("地图BP") || step.contains("地图分析") || step.contains("总览") || step.contains("对位数据") || step.contains("赛后数据") || step.contains("死亡游乐园") || step.contains("基础数据")) return true;
        }
        return false;
    }

    /** CS资讯页浏览：按执行日志生成测项，分类为 启动/登录/CS资讯 */
    private List<TestResultItemDto> buildCsNewsBrowsingResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsNewsBrowsingStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** CS-动态页浏览：按执行日志生成测项，分类为 启动/登录/CS-动态 */
    private List<TestResultItemDto> buildCsDynamicBrowsingResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsDynamicBrowsingStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断 CS资讯页浏览 分类 */
    private String inferCsNewsBrowsingStepCategory(String step) {
        if (step == null) return "CS资讯";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("点击资讯") || s.contains("向上滑动") || s.contains("向下滑动")) return "CS资讯";
        return "CS资讯";
    }

    /** 根据步骤名推断 CS-动态页浏览 分类 */
    private String inferCsDynamicBrowsingStepCategory(String step) {
        if (step == null) return "CS-动态";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("尝试返回") || s.contains("点击动态") || s.contains("向上滑动") || s.contains("向下滑动") || s.contains("滑动完成")) return "CS-动态";
        return "CS-动态";
    }

    /** CS-动态-关注更多战队选手：按执行日志生成测项，分类为 启动/登录/关注更多 */
    private List<TestResultItemDto> buildCsDynamicFollowMoreResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsDynamicFollowMoreStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断 CS-动态-关注更多战队选手 分类 */
    private String inferCsDynamicFollowMoreStepCategory(String step) {
        if (step == null) return "关注更多";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("尝试返回") || s.contains("点击动态") || s.contains("选择关注") || s.contains("点击战队") || s.contains("点击赛事") || s.contains("点击其他") || s.contains("点击选手") || s.contains("关注") || s.contains("向上滑动") || s.contains("点击返回")) return "关注更多";
        return "关注更多";
    }

    /** CS-热评-浏览：按执行日志生成测项，分类为 启动/登录/CS-热评-浏览 */
    private List<TestResultItemDto> buildCsHotCommentBrowseResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsHotCommentBrowseStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断 CS-热评-浏览 分类 */
    private String inferCsHotCommentBrowseStepCategory(String step) {
        if (step == null) return "CS-热评-浏览";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("尝试返回") || s.contains("热评") || s.contains("向上滑动") || s.contains("向下滑动")) return "CS-热评-浏览";
        return "CS-热评-浏览";
    }

    /** CS-热评点赞：按执行日志生成测项，分类为 启动/登录/CS-热评点赞 */
    private List<TestResultItemDto> buildCsHotCommentLikeResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsHotCommentLikeStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断 CS-热评点赞 分类 */
    private String inferCsHotCommentLikeStepCategory(String step) {
        if (step == null) return "CS-热评点赞";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("尝试返回") || s.contains("热评") || s.contains("点赞") || s.contains("排行5") || s.contains("向下滑动")) return "CS-热评点赞";
        return "CS-热评点赞";
    }

    /** CS-热评页面跳转至用户个人页面：按执行日志生成测项，分类为 启动/登录/热评跳转 */
    private List<TestResultItemDto> buildCsHotCommentToUserProfileResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsHotCommentToUserProfileStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断 CS-热评页面跳转至用户个人页面 分类 */
    private String inferCsHotCommentToUserProfileStepCategory(String step) {
        if (step == null) return "热评跳转";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("尝试返回") || s.contains("热评") || s.contains("排行6") || s.contains("头像") || s.contains("向上滑动")) return "热评跳转";
        return "热评跳转";
    }

    /** CS-热评页面跳转至用户个人页面 预定义检测规则 */
    private List<TestResultItemDto> buildCsHotCommentToUserProfilePredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("热评跳转", "CS→热评→向上滑动至排行N", result, null, detail),
                new TestResultItemDto("热评跳转", "点击排行旁头像→进入用户个人页面", result, null, detail)
        );
    }

    /** CS-热评内容跳转 预定义检测规则 */
    private List<TestResultItemDto> buildCsHotCommentContentJumpPredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("热评内容跳转", "CS→热评→进入热评页面", result, null, detail),
                new TestResultItemDto("热评内容跳转", "若出现评论内容则点击", result, null, detail)
        );
    }

    /** CS-评论热评内容 预定义检测规则 */
    private List<TestResultItemDto> buildCsCommentHotCommentContentPredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("评论热评内容", "CS→热评→点击评论内容→进入评论页", result, null, detail),
                new TestResultItemDto("评论热评内容", "向上滑动(出现已显示全部则停止,未出现则滑动几次后也停止)→输入不错→表情包→选择不上班→发布→点击关闭→点击返回", result, null, detail)
        );
    }

    /** CS-视频播放|点赞|评论|分享：按执行日志生成测项，分类为 启动/登录/CS-视频播放/点赞/评论/分享 */
    private List<TestResultItemDto> buildCsVideoPlayLikeCommentShareResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsVideoPlayLikeCommentShareStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断 CS-视频播放|点赞|评论|分享 分类 */
    private String inferCsVideoPlayLikeCommentShareStepCategory(String step) {
        if (step == null) return "CS-视频播放";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("尝试返回") || s.contains("等待视频元素") || s.contains("点击视频") || s.contains("点击视频播放") || s.contains("视频进度条")) return "CS-视频播放";
        if (s.contains("点赞")) return "点赞";
        if (s.contains("评论") || s.contains("点击输入框") || s.contains("输入评论") || s.contains("选择表情") || s.contains("发布评论") || s.contains("关闭弹窗")) return "评论";
        if (s.contains("分享") || s.contains("取消分享")) return "分享";
        if (s.contains("返回")) return "返回";
        return "CS-视频播放";
    }

    /** CS-视频播放|点赞|评论|分享 预定义检测规则（按实际步骤） */
    private List<TestResultItemDto> buildCsVideoPlayLikeCommentSharePredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序、等待页面就绪", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("CS-视频播放", "CS→视频→选择第一个视频点击播放、判断进度条", result, null, detail),
                new TestResultItemDto("点赞", "判断点赞元素存在则点击", result, null, detail),
                new TestResultItemDto("评论", "点击评论→输入不错→表情加油加油→发布→关闭弹窗", result, null, detail),
                new TestResultItemDto("分享", "判断分享按钮→点击分享→取消", result, null, detail),
                new TestResultItemDto("返回", "点击返回", result, null, detail)
        );
    }

    /** CS-视频浏览：按执行日志生成测项，分类为 启动/登录/CS-视频浏览 */
    private List<TestResultItemDto> buildCsVideoBrowseResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsVideoBrowseStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断 CS-视频浏览 分类（按实际步骤） */
    private String inferCsVideoBrowseStepCategory(String step) {
        if (step == null) return "CS-视频浏览";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("尝试返回")) return "进入CS";
        if (s.contains("等待视频元素") || s.contains("点击视频")) return "进入CS-视频页";
        if (s.contains("向上滑动") || s.contains("向下滑动")) return "CS-视频浏览";
        return "CS-视频浏览";
    }

    /** CS-视频浏览 预定义检测规则（按实际步骤） */
    private List<TestResultItemDto> buildCsVideoBrowsePredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序、等待页面就绪", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("进入CS", "判断CS出现并点击", result, null, detail),
                new TestResultItemDto("进入CS-视频页", "判断视频出现并点击", result, null, detail),
                new TestResultItemDto("CS-视频浏览", "向上滑动浏览", result, null, detail)
        );
    }

    /** CS-活动浏览：按执行日志生成测项，分类按实际步骤细分 */
    private List<TestResultItemDto> buildCsActivityBrowseResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsActivityBrowseStepCategory(step, entry.getMessage());
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名+说明推断 CS-活动浏览 分类（按实际步骤细分） */
    private String inferCsActivityBrowseStepCategory(String step, String message) {
        if (step == null) return "CS-活动";
        String s = step.trim();
        String msg = message != null ? message : "";
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("尝试返回")) return "进入CS";
        if (s.contains("资讯") || s.contains("向左滑动") || s.contains("滑动至活动") || s.contains("活动已出现")) return "滑动至活动";
        if (s.contains("点击活动") && msg.contains("已进入活动页面")) return "进入活动页";
        if (s.contains("向上滑动") || s.contains("向下滑动")) return "活动页浏览";
        if (s.contains("随机点击活动") || (s.contains("点击活动") && (msg.contains("活动详情") || msg.contains("第一个活动") || msg.contains("跳过")))) return "进入活动详情";
        return "CS-活动";
    }

    /** CS-赛事-赛程浏览：按执行日志生成测项，分类为 启动/登录/CS-赛事-赛程 */
    private List<TestResultItemDto> buildCsEventScheduleBrowseResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsEventScheduleBrowseStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断 CS-赛事-赛程浏览 分类 */
    private String inferCsEventScheduleBrowseStepCategory(String step) {
        if (step == null) return "CS-赛事-赛程";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("尝试返回") || s.contains("赛事") || s.contains("点击赛事") || s.contains("点击周×") || s.contains("今天")) return "CS-赛事-赛程";
        if (s.contains("向上滑动") || s.contains("向下滑动") || s.contains("向左滑动") || s.contains("向右滑动")) return "CS-赛事-赛程";
        return "CS-赛事-赛程";
    }

    /** CS-已结束比赛-赛事详情-分析：按执行日志生成测项，参赛队伍战队数据输出至测试结果分布 */
    private List<TestResultItemDto> buildCsEndedMatchDetailAnalysisResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsEndedMatchDetailAnalysisStepCategory(step, entry.getMessage());
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            String msg = entry.getMessage();
            // 参赛队伍：战队名作为测试项名称，便于在检测规则表格中直接看到
            String displayName = step;
            if (step != null && step.contains("参赛队伍") && msg != null && !msg.isBlank()
                    && !msg.startsWith("共提取") && !msg.startsWith("未") && !msg.startsWith("已找到")
                    && !msg.contains("判断") && !msg.contains("等待")) {
                displayName = msg.trim();
            }
            list.add(new TestResultItemDto(category, displayName, result, null, msg));
        }
        return list;
    }

    /** 根据步骤名+说明推断 CS-已结束比赛-赛事详情-分析 分类，参赛队伍战队名归入「参赛队伍」以展示于测试结果分布 */
    private String inferCsEndedMatchDetailAnalysisStepCategory(String step, String message) {
        if (step == null) return "CS-赛事-赛程";
        String s = step.trim();
        if (s.contains("参赛队伍")) return "参赛队伍";
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("赛事") || s.contains("已结束") || s.contains("分析") || s.contains("对阵图") || s.contains("概览")) return "CS-赛事-赛程";
        return "CS-赛事-赛程";
    }

    /** CS-已结束比赛-赛事详情-分析 预定义检测规则 */
    private List<TestResultItemDto> buildCsEndedMatchDetailAnalysisPredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序、等待页面就绪", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("CS-赛事-赛程", "CS→赛事→随机点击已结束→分析→对阵图→概览", result, null, detail),
                new TestResultItemDto("参赛队伍", "提取战队数据并输出至测试结果分布", result, null, detail)
        );
    }

    /** CS-已结束比赛-赛事详情-数据：按执行日志生成测项，分类为 启动/登录/CS-赛事-赛程 */
    private List<TestResultItemDto> buildCsEndedMatchDetailDataResultItems(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return List.of();
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if (isLogMetaStep(step)) continue;
            String category = inferCsEndedMatchDetailDataStepCategory(step);
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto(category, step, result, null, entry.getMessage()));
        }
        return list;
    }

    /** 根据步骤名推断 CS-已结束比赛-赛事详情-数据 分类 */
    private String inferCsEndedMatchDetailDataStepCategory(String step) {
        if (step == null) return "CS-赛事-赛程";
        String s = step.trim();
        if (s.contains("启动") || s.contains("应用信息") || s.contains("环境初始化") || s.contains("检测规则") || s.contains("等待页面就绪")) return "启动";
        if (s.contains("登录") || s.contains("手机号") || s.contains("验证码") || s.contains("前置登录") || s.contains("获取驱动")) return "登录";
        if (s.contains("等待CS") || s.contains("点击CS") || s.contains("赛事") || s.contains("已结束") || s.contains("数据") || s.contains("地图BP") || s.contains("地图分析") || s.contains("总览") || s.contains("基础数据") || s.contains("对位数据") || s.contains("赛后数据") || s.contains("点击地图")) return "CS-赛事-赛程";
        return "CS-赛事-赛程";
    }

    /** CS-已结束比赛-赛事详情-数据 预定义检测规则 */
    private List<TestResultItemDto> buildCsEndedMatchDetailDataPredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序、等待页面就绪", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("CS-赛事-赛程", "CS→赛事→随机点击已结束", result, null, detail),
                new TestResultItemDto("CS-赛事-赛程", "数据页→若存在地图BP则执行地图分析→总览→基础数据/对位数据切换", result, null, detail),
                new TestResultItemDto("CS-赛事-赛程", "若存在地图BP则查找地图依次点击，地图页若对位数据不存在则跳过后续", result, null, detail)
        );
    }

    /** CS-赛事-赛程浏览 预定义检测规则 */
    private List<TestResultItemDto> buildCsEventScheduleBrowsePredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序、等待页面就绪", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("CS-赛事-赛程", "判断CS出现并点击", result, null, detail),
                new TestResultItemDto("CS-赛事-赛程", "判断赛事出现并点击进入赛程页", result, null, detail),
                new TestResultItemDto("CS-赛事-赛程", "向上滑动4次、向下滑动4次", result, null, detail),
                new TestResultItemDto("CS-赛事-赛程", "判断今天出现→今天处向左滑4次", result, null, detail),
                new TestResultItemDto("CS-赛事-赛程", "点击周×→周×处向右滑4次→点击周×", result, null, detail)
        );
    }

    /** CS-活动浏览 预定义检测规则（按实际步骤） */
    private List<TestResultItemDto> buildCsActivityBrowsePredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序、等待页面就绪", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("进入CS", "判断CS出现并点击", result, null, detail),
                new TestResultItemDto("滑动至活动", "判断资讯出现、向左滑动直至活动出现", result, null, detail),
                new TestResultItemDto("进入活动页", "点击活动进入活动页面", result, null, detail),
                new TestResultItemDto("活动页浏览", "向上滑动5次、向下滑动5次", result, null, detail),
                new TestResultItemDto("进入活动详情", "随机点击一个活动进入", result, null, detail)
        );
    }

    /** CS-热评点赞 预定义检测规则 */
    private List<TestResultItemDto> buildCsHotCommentLikePredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("CS-热评点赞", "CS→热评→首条点赞", result, null, detail),
                new TestResultItemDto("CS-热评点赞", "向上滑动至排行N→点击排行右侧点赞", result, null, detail)
        );
    }

    /** CS-热评-浏览 预定义检测规则 */
    private List<TestResultItemDto> buildCsHotCommentBrowsePredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("CS-热评-浏览", "CS→热评(若出现则点击)", result, null, detail),
                new TestResultItemDto("CS-热评-浏览", "热评页向上5次、向下5次滑动", result, null, detail)
        );
    }

    /** CS-动态-关注更多战队选手 预定义检测规则 */
    private List<TestResultItemDto> buildCsDynamicFollowMorePredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("关注更多", "CS→动态→点击选择关注", result, null, detail),
                new TestResultItemDto("关注更多", "选手/战队/赛事/其他/选手(向上滑动2次)", result, null, detail),
                new TestResultItemDto("关注更多", "点击返回", result, null, detail)
        );
    }

    /** CS资讯页浏览 预定义检测规则 */
    private List<TestResultItemDto> buildCsNewsBrowsingPredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("CS资讯", "等待 CS 元素出现并点击", result, null, detail),
                new TestResultItemDto("CS资讯", "点击 资讯 进入资讯页", result, null, detail),
                new TestResultItemDto("CS资讯", "向上滑动 10 次、向下滑动 6 次", result, null, detail)
        );
    }

    /** CS-动态页浏览 预定义检测规则 */
    private List<TestResultItemDto> buildCsDynamicBrowsingPredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        return List.of(
                new TestResultItemDto("启动", "启动应用程序", result, null, detail),
                new TestResultItemDto("登录", "未登录则执行登录", result, null, detail),
                new TestResultItemDto("CS-动态", "等待 CS 元素出现并点击", result, null, detail),
                new TestResultItemDto("CS-动态", "点击 动态 进入 CS-动态 页", result, null, detail),
                new TestResultItemDto("CS-动态", "向上 5 次、向下 5 次滑动直至出现「关注更多战队选手」", result, null, detail)
        );
    }

    /** 搜索-最新/最热视频播放场景的预定义检测规则（与实际测试步骤一致） */
    private List<TestResultItemDto> buildSearchVideoLatestHottestPredefinedItems(Boolean passed) {
        String result = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        String detail = Boolean.TRUE.equals(passed) ? "执行成功" : "请运行任务后查看检测规则";
        List<TestResultItemDto> list = new ArrayList<>();
        list.add(new TestResultItemDto("启动", "启动应用程序", result, null, detail));
        list.add(new TestResultItemDto("登录", "判断登录状态，未登录则执行登录", result, null, detail));
        list.add(new TestResultItemDto("搜索", "点击放大镜搜索图标", result, null, detail));
        list.add(new TestResultItemDto("搜索", "输入关键词 Kak1", result, null, detail));
        list.add(new TestResultItemDto("搜索", "点击搜索按钮", result, null, detail));
        list.add(new TestResultItemDto("视频Tab", "点击视频 Tab（模糊匹配）", result, null, detail));
        list.add(new TestResultItemDto("最新", "判断是否出现「最新」", result, null, detail));
        list.add(new TestResultItemDto("最新", "随机选 1-5 视频播放", result, null, detail));
        list.add(new TestResultItemDto("最新", "无论是否加载超时均点击返回至视频页", result, null, detail));
        list.add(new TestResultItemDto("最热", "判断是否出现「最热」", result, null, detail));
        list.add(new TestResultItemDto("最热", "点击「最热」Tab", result, null, detail));
        list.add(new TestResultItemDto("最热", "随机选 1-5 视频播放", result, null, detail));
        list.add(new TestResultItemDto("最热", "无论是否加载超时均点击返回至视频页", result, null, detail));
        list.add(new TestResultItemDto("返回校验", "验证返回至最热页面", result, null, detail));
        list.add(new TestResultItemDto("滑动", "向上滑动直至出现 srl_classics_title", result, null, detail));
        return list;
    }

    private Map<String, String> parseExecutionVariables(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return JSON.readValue(json, MAP_STRING_STRING);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private List<ExecutionLogEntryDto> parseExecutionLog(String executionLogJson) {
        if (executionLogJson == null || executionLogJson.isBlank()) return null;
        try {
            return JSON.readValue(executionLogJson, EXECUTION_LOG_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析应用版本：优先从任务关联设备上实时获取 com.pwrd.steam.esports 等应用的安装版本，
     * 失败时回退到 Application 表中的缓存版本。
     */
    private String resolveAppVersion(Task task, Application app) {
        String deviceId = task.getDeviceId();
        String packageName = app.getPackageName();
        if (deviceId == null || packageName == null || packageName.isBlank()) {
            return app.getVersion();
        }
        Device device = deviceMapper.findById(deviceId);
        if (device == null) return app.getVersion();
        if (device.getPlatform() == Device.Platform.ANDROID || device.getPlatform() == Device.Platform.HARMONYOS
                || device.getPlatform() == Device.Platform.POG_ANDROID) {
            String fromDevice = deviceManager.getAndroidAppVersion(deviceId, packageName);
            if (fromDevice != null && !fromDevice.isBlank()) return fromDevice;
        }
        return app.getVersion();
    }

    private void fillScreenshotUrls(List<ExecutionLogEntryDto> log, String taskId) {
        if (log == null || taskId == null) return;
        for (int i = 0; i < log.size(); i++) {
            ExecutionLogEntryDto entry = log.get(i);
            if (entry != null && entry.getScreenshotPath() != null && !entry.getScreenshotPath().isBlank()) {
                entry.setScreenshotUrl("functional-test/tasks/" + taskId + "/screenshots/" + i);
            }
        }
    }

    /** 性能基础质量测试：按执行日志生成测项；无日志时单条「性能遍历与资源采样」。 */
    private List<TestResultItemDto> buildPerformanceResultItems(Boolean passed, List<ExecutionLogEntryDto> executionLog) {
        String resultWhenNoLog = passed == null ? RESULT_NOT_RUN : (passed ? RESULT_PASS : RESULT_FAIL);
        if (executionLog == null || executionLog.isEmpty()) {
            return List.of(new TestResultItemDto("性能测试", "性能遍历与资源采样", resultWhenNoLog, null, "详见执行日志与 result_data"));
        }
        List<TestResultItemDto> list = new ArrayList<>();
        for (ExecutionLogEntryDto entry : executionLog) {
            String step = entry.getStep() != null ? entry.getStep() : "步骤";
            if ("开始测试".equals(step) || "测试场景".equals(step)) continue;
            String result = "success".equalsIgnoreCase(entry.getStatus()) ? RESULT_PASS : RESULT_FAIL;
            list.add(new TestResultItemDto("性能测试", step, result, null, entry.getMessage()));
        }
        return list.isEmpty() ? List.of(new TestResultItemDto("性能测试", "性能遍历与资源采样", resultWhenNoLog, null, null)) : list;
    }

    /** 兼容性测试：OS兼容 2 项 + 设备兼容 3 项（截图结构）。 */
    private List<TestResultItemDto> buildCompatibilityResultItems(boolean completed) {
        List<TestResultItemDto> list = new ArrayList<>();
        String osResult = completed ? RESULT_PASS : RESULT_FAIL;
        list.add(new TestResultItemDto("OS兼容", "应用/元服务启动无兼容性问题", osResult, null, null));
        list.add(new TestResultItemDto("OS兼容", "应用/元服务运行无兼容性问题", osResult, null, null));
        list.add(new TestResultItemDto("设备兼容", "应用/元服务在折叠屏状态切换时无兼容性问题", RESULT_DEVICE_DEPENDENT, null, null));
        list.add(new TestResultItemDto("设备兼容", "应用/元服务在折叠屏横竖屏切换时无兼容性问题", RESULT_DEVICE_DEPENDENT, null, null));
        list.add(new TestResultItemDto("设备兼容", "应用/元服务在折叠屏支架态切换时无兼容性问题", RESULT_DEVICE_DEPENDENT, null, null));
        return list;
    }

    /** 根据检测规则列表汇总：兼容性满足度。 */
    private CompatibilitySummaryDto buildSummaryFromItems(List<TestResultItemDto> items) {
        int actual = items.size();
        int passed = 0, failed = 0, warning = 0;
        for (TestResultItemDto item : items) {
            switch (item.getTestResult() == null ? "" : item.getTestResult()) {
                case RESULT_PASS -> passed++;
                case RESULT_FAIL -> failed++;
                case "告警" -> warning++;
                default -> { /* 设备依赖、未执行等不计入通过/不通过/告警 */ }
            }
        }
        return new CompatibilitySummaryDto(actual, passed, failed, warning);
    }

    /** 根据检测规则列表按分类汇总：测试结果分布（柱状图）。 */
    private List<ResultDistributionItemDto> buildDistributionFromItems(List<TestResultItemDto> items) {
        List<ResultDistributionItemDto> out = new ArrayList<>();
        String prevCategory = null;
        int passed = 0, warning = 0, failed = 0, deviceDependent = 0;
        for (TestResultItemDto item : items) {
            String cat = item.getTestItemCategory();
            if (prevCategory != null && !prevCategory.equals(cat)) {
                out.add(new ResultDistributionItemDto(prevCategory, passed, warning, failed, deviceDependent));
                passed = warning = failed = deviceDependent = 0;
            }
            prevCategory = cat;
            String r = item.getTestResult() == null ? "" : item.getTestResult();
            switch (r) {
                case RESULT_PASS -> passed++;
                case RESULT_FAIL -> failed++;
                case "告警" -> warning++;
                case RESULT_DEVICE_DEPENDENT -> deviceDependent++;
                default -> { }
            }
        }
        if (prevCategory != null) {
            out.add(new ResultDistributionItemDto(prevCategory, passed, warning, failed, deviceDependent));
        }
        return out;
    }

    private static String formatDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return null;
        }
        Duration d = Duration.between(start, end);
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return seconds + "秒";
        }
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (secs == 0) {
            return minutes + "分";
        }
        return minutes + "分" + secs + "秒";
    }
}
