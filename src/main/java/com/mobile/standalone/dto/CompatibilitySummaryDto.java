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
}
