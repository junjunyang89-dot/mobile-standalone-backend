package com.mobile.standalone.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 性能基础质量测试结果（存入 task_executions.result_data）。
 * 供前端「整体评估」「遍历统计」「资源数据」「操作详情」等展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PerformanceTestResultDto {

    /** 遍历总时长（分钟） */
    private Integer traversalDurationMinutes;
    /** 执行操作总次数 */
    private Integer totalOperations;
    /** 启动次数 */
    private Integer launchCount;
    /** 点击次数 */
    private Integer clickCount;
    /** 滑动次数 */
    private Integer swipeCount;

    /** 测试结论文案，如：总共遍历 N 分钟，执行操作 M 次，发现较好体验问题 K 次 */
    private String conclusion;

    /** 资源汇总（CPU、内存） */
    private ResourceSummary resourceSummary;
    /** 按操作类型统计的问题数（响应时延、完成时延、卡顿等） */
    private IssueSummary issueSummary;
    /** 实际生效的采集引擎：ADB | SOLOX | TIDEVICE | NONE */
    private String collectorEngineUsed;
    /**
     * iOS MetricKit 测试包上报摘要（由 POST .../metrickit 写入后，读报告时合并；
     * 也可能在任务结束时已写入 result_data）。
     */
    private Object metricKitSummary;
    /** 资源采样序列（时间点 + CPU/内存），用于画曲线 */
    private List<ResourceSample> resourceSamples;
    /** 操作明细（步骤索引、类型、耗时、关联资源），用于操作详情与峰值步骤 */
    private List<OperationRecord> operations;
    /** 遍历期间按间隔截图的张数（0=未开启） */
    private Integer screenshotCount;
    /** 每张截图的时间点（index、timestampMs），供前端时间轴/遍历条展示 */
    private List<ScreenshotTimelineEntry> screenshotTimeline;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceSummary {
        private MetricStat cpuApp;   // 应用 CPU 使用率 %
        private MetricStat memoryPss; // 应用内存 PSS KB
        /** 网络接收字节数（整机），采样点累计值；前端可依时间差算速率 */
        private MetricStat networkRxBytes;
        /** 网络发送字节数（整机） */
        private MetricStat networkTxBytes;
        /** 电池温度（单位：℃ 汇总时已换算），可展示为 ℃ */
        private MetricStat temperature;
        /** 帧率 FPS（dumpsys gfxinfo） */
        private MetricStat fps;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricStat {
        private Double peak;
        private Double min;
        private Double avg;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceSample {
        private Long timestampMs;
        private Double cpuPercent;
        private Long memoryPssKb;
        private Long networkRxBytes;
        private Long networkTxBytes;
        /** 电池温度，单位 0.1℃（如 250 表示 25.0℃） */
        private Integer temperatureTenthsC;
        /** 估算 FPS */
        private Double fps;
        /** 卡顿帧占比 % */
        private Double jankPercent;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationRecord {
        private Integer index;
        private String type;       // launch, click, swipe
        private Long startTimeMs;
        private Long endTimeMs;
        private Long responseDelayMs;
        /** 加载完成时延（本步结束到下一步开始的间隔，ms），对应「操作详情」中的加载完成时延 */
        private Long completionDelayMs;
        private Double cpuAvgDuringStep;
        private Long memoryPssKbDuringStep;
        /** 操作时所在页面（如 Android Activity 短名），供前端展示「在哪个页面执行」 */
        private String pageName;
        /** 操作描述：如「向上滑」「向下滑」「点击-"关注"」，供遍历条/操作详情展示 */
        private String actionLabel;
        /** 操作前截图在 screenshotTimeline 中的 index，供「操作前」列展示；无则为 null */
        private Integer screenshotBeforeIndex;
        /** 操作后截图在 screenshotTimeline 中的 index，供「操作后」列展示；无则为 null */
        private Integer screenshotAfterIndex;
        /** 本步内白屏/黑屏时长（ms），对应「操作详情」中的黑白块/白屏时长 */
        private Long whiteScreenMs;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IssueSummary {
        private Integer responseDelayCount;
        private Integer completionDelayCount;
        private Integer stutterCount;
        /** 黑白块问题次数（截图分析得出） */
        private Integer blackWhiteBlockCount;
        /** 音视频问题次数（预留，当前为 0） */
        private Integer avCount;
        private Integer totalIssueCount;
        /** 按操作类型键（启动操作/滑动操作/点击操作/观看视频）的问题数，与前端表格一致 */
        private Map<String, OperationTypeIssueCounts> byOperationType;
    }

    /** 某操作类型的响应时延/完成时延/卡顿/音视频/黑白块数量，与前端表格列一致 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationTypeIssueCounts {
        private Integer responseDelayCount;
        private Integer completionDelayCount;
        private Integer stutterCount;
        private Integer avCount;
        private Integer blackWhiteBlockCount;
    }

    /** 截图时间轴条目：序号与时间戳，前端用 index 请求图片 /api/.../performance-screenshots/{index} */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScreenshotTimelineEntry {
        private Integer index;
        private Long timestampMs;
    }
}
