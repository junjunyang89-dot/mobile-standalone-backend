package com.mobile.standalone.service;

import com.mobile.standalone.service.PerformanceTestService.PerformanceTestExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 性能测试结果的成败信号。
 * <p>
 * 之前这个对象里根本没有失败字段，于是性能任务无论流程有没有跑通都记 COMPLETED——
 * 采样只覆盖了一半路径，报告上却是「完成」，性能数据的可信度直接归零。
 */
class PerformanceResultStatusTest {

    @Test
    void 默认是成功的() {
        PerformanceTestExecutionResult r =
                new PerformanceTestExecutionResult(null, List.of(), "{}");

        assertFalse(r.isFailed());
        assertNull(r.getErrorMessage());
    }

    @Test
    void 标记失败会带上原因() {
        PerformanceTestExecutionResult r =
                new PerformanceTestExecutionResult(null, List.of(), "{}")
                        .markFailed("场景化性能流程未通过: 点击失败: el_x");

        assertTrue(r.isFailed());
        assertEquals("场景化性能流程未通过: 点击失败: el_x", r.getErrorMessage());
    }

    @Test
    void 标记失败返回自身以便链式返回() {
        PerformanceTestExecutionResult r = new PerformanceTestExecutionResult(null, List.of(), "{}");

        assertEquals(r, r.markFailed("启动应用失败"));
    }

    /** 失败时采样数据仍要保留：残缺的数据也比没有数据强，只是不能记成「完成」 */
    @Test
    void 失败时结果数据仍然保留() {
        String data = "{\"conclusion\":\"部分采样\"}";
        PerformanceTestExecutionResult r =
                new PerformanceTestExecutionResult(null, List.of(), data).markFailed("流程未通过");

        assertTrue(r.isFailed());
        assertEquals(data, r.getResultDataJson());
    }

    /** 三参构造要保持兼容：早退分支里有近十处在用 */
    @Test
    void 三参构造仍可用且不算失败() {
        PerformanceTestExecutionResult r =
                new PerformanceTestExecutionResult("/path/shot.png", List.of(), "{}");

        assertEquals("/path/shot.png", r.getReportPath());
        assertFalse(r.isFailed());
    }
}
