package com.mobile.standalone.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行环境归一。
 * <p>
 * 核心约束是<b>存量行为不能变</b>：线上回归和已有任务都没有这个字段，
 * 一旦空值被当成测试环境，周五要用的那套报告全会标错。
 */
class TestEnvironmentTest {

    @Test
    void 空值一律按生产() {
        assertEquals(TestEnvironment.PROD, TestEnvironment.normalize(null));
        assertEquals(TestEnvironment.PROD, TestEnvironment.normalize(""));
        assertEquals(TestEnvironment.PROD, TestEnvironment.normalize("   "));
    }

    @Test
    void 识别测试环境且大小写空格不敏感() {
        assertEquals(TestEnvironment.TEST, TestEnvironment.normalize("TEST"));
        assertEquals(TestEnvironment.TEST, TestEnvironment.normalize("  test "));
        assertTrue(TestEnvironment.isTest("Test"));
    }

    /** 没见过的取值按生产处理：宁可标成生产，也不要把生产报告误标成测试 */
    @Test
    void 未知取值按生产() {
        assertEquals(TestEnvironment.PROD, TestEnvironment.normalize("STAGING"));
        assertFalse(TestEnvironment.isTest("STAGING"));
    }

    /** 任务名两边都带环境标记，列表里不用点开就能分清 */
    @Test
    void 任务名带环境标记() {
        assertEquals("[测试环境]", TestEnvironment.tag("TEST"));
        assertEquals("[生产环境]", TestEnvironment.tag("PROD"));
        assertEquals("[生产环境]", TestEnvironment.tag(null));
    }

    @Test
    void 展示名() {
        assertEquals("测试环境", TestEnvironment.label("TEST"));
        assertEquals("生产环境", TestEnvironment.label(null));
    }
}
