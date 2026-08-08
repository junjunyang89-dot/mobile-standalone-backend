package com.mobile.standalone.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 概览-兼容性满足度：实际测试项、通过项、不通过项、告警项。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompatibilitySummaryDto {
    /** 实际测试项 */
    private int actualTestItemCount;
    /** 测试通过项 */
    private int passedCount;
    /** 测试不通过项 */
    private int failedCount;
    /** 告警项 */
    private int warningCount;

    /**
     * 功能健康度：把归因判为「偶现」的失败排除后的通过率（0-100，null 表示与执行达标率相同）。
     * <p>
     * 只留一个数会二选一地丢信息：含偶现看不出功能是否真坏，剔除偶现又看不出脚本稳不稳。
     * 两个都给，报表上才能区分「脚本不稳」和「功能缺陷」——这两件事要找的人完全不同。
     */
    private Integer functionalHealthPercent;
    /** 被归因判为偶现、因而未计入功能健康度的项数 */
    private int flakyExcludedCount;

    public CompatibilitySummaryDto(int actualTestItemCount, int passedCount, int failedCount, int warningCount) {
        this(actualTestItemCount, passedCount, failedCount, warningCount, null, 0);
    }
}
