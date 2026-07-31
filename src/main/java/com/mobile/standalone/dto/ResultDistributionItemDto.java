package com.mobile.standalone.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 概览-测试结果分布：按分类的通过/告警/不通过/设备依赖数量（用于柱状图）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResultDistributionItemDto {
    /** 分类名，如「OS兼容」「设备兼容」「登录」 */
    private String categoryName;
    /** 通过数量 */
    private int passed;
    /** 告警数量 */
    private int warning;
    /** 不通过数量 */
    private int failed;
    /** 设备依赖数量（兼容性测试用） */
    private int deviceDependent;
}
