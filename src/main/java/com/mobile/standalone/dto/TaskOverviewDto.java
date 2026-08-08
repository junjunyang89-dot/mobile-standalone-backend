package com.mobile.standalone.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 任务详情页接口返回结构，与前端截图一致。
 * 包含：任务 ID/标题/备注、测试类型、应用信息、开始时间、持续时间、执行人、
 * 概览（兼容性满足度、测试结果分布）、检测规则表格、报告路径等。
 */
@Data
public class TaskOverviewDto {
    /** 任务 ID（用于展示，如 Task-0113-160742，前端可再格式化） */
    private String taskId;
    /** 任务标题 */
    private String title;
    /** 备注 */
    private String remarks;
    /** 测试场景键（如 settings、settings_account、login），供前端区分本次执行场景 */
    private String scenarioKey;
    /** 测试场景展示名（如「设置-我的账号(Android)」「登录测试(iOS)」），供任务详情概览展示 */
    private String scenarioName;
    /** 测试类型（展示用，如「应用」或「功能体验基础质量测试」） */
    private String testType;
    /** 应用包名（Android package / iOS bundleId） */
    private String appPackageName;
    /** 应用版本 */
    private String appVersion;
    /** API 版本（暂无数据时可为 null 或空） */
    private String apiVersion;
    /** 开始时间 */
    private LocalDateTime startTime;
    /** 持续时间（人类可读，如「5分50秒」） */
    private String duration;
    /** 执行人（当前写死为「杨俊俊」） */
    private String executor = "杨俊俊";
    /** 报告/截图路径，用于「打开目录」「执行日志」等 */
    private String reportPath;
    /** 概览-统计区块标题，根据实际测试类型展示，如「功能质量达标率」「兼容性满足度」等 */
    private String summarySectionTitle;
    /** 概览-统计区块：实际测试项、通过项、不通过项、告警项 */
    private CompatibilitySummaryDto compatibilitySummary;
    /** 概览-测试结果分布：按分类的通过/告警/不通过数量（柱状图） */
    private List<ResultDistributionItemDto> resultDistribution;
    /** 检测规则表格：测项分类 + 测试项名称 + 测试结果 + 详细日志/变量展示 */
    private List<TestResultItemDto> testResultItems;
    /** 执行变量脱敏值，如 phoneNumberMasked、verificationCodeMasked，供前端在检测规则「详细日志」列展示 */
    private Map<String, String> executionVariables;
    /** 执行步骤日志（与任务详情/execution-log 一致），供「执行日志」弹窗展示；未执行或旧任务可为 null 或空列表 */
    private List<ExecutionLogEntryDto> executionLog;
    /** 参赛队伍名称列表（CS-已结束比赛-赛事详情-分析 场景提取的战队数据），供前端直接展示 */
    private List<String> participatingTeams;
    /** 任务状态（PENDING/RUNNING/COMPLETED/FAILED/STOPPED） */
    private String status;
    /** 执行状态，与 status 一致，供前端兼容 */
    private String executionStatus;
    /** 失败时的错误信息，便于详情页展示失败原因 */
    private String errorMessage;

    /** 执行环境：TEST / PROD。两个环境的报告混在一起时，靠它区分 */
    private String environment;
    private String environmentLabel;

    /**
     * 当前失败归因结论（人工优先）。为 null 表示还没归因过。
     * <p>
     * 跟着概览一起返回，前端不用再多打一次接口——排查失败时人本来就在这个页面上。
     */
    private com.mobile.standalone.entity.TaskFailureDiagnosis failureDiagnosis;
}
