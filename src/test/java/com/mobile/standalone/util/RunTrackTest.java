package com.mobile.standalone.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轨道：回归 / 自动化编排，与环境正交。
 * <p>
 * 最要紧的是<b>空值必须落到回归</b>——线上回归是已经在用的东西，加轨道这件事不能让它有任何变化。
 */
class RunTrackTest {

    @Test
    void 空值与无法识别的值都落到回归() {
        for (String raw : new String[]{null, "", "   ", "xxx", "REGRESSION_X"}) {
            assertEquals(RunTrack.REGRESSION, RunTrack.normalize(raw), String.valueOf(raw));
            assertFalse(RunTrack.isAutomation(raw), String.valueOf(raw));
        }
    }

    @Test
    void 自动化大小写都认() {
        assertTrue(RunTrack.isAutomation("AUTOMATION"));
        assertTrue(RunTrack.isAutomation("automation"));
        assertTrue(RunTrack.isAutomation("  Automation  "));
    }

    /** SHARED 两条轨道都收：它的语义就是「两个入口都进」 */
    @Test
    void 两个入口都进的流程在两条轨道都在候选里() {
        assertTrue(RunTrack.catalogs(RunTrack.REGRESSION).contains("SHARED"));
        assertTrue(RunTrack.catalogs(RunTrack.AUTOMATION).contains("SHARED"));
    }

    @Test
    void 回归轨道不收仅自动化编排的流程() {
        assertEquals(List.of("ONLINE", "SHARED"), RunTrack.catalogs(null));
        assertFalse(RunTrack.catalogs(RunTrack.REGRESSION).contains("AUTOMATION"));
    }

    @Test
    void 自动化轨道不收仅回归的流程() {
        assertEquals(List.of("AUTOMATION", "SHARED"), RunTrack.catalogs(RunTrack.AUTOMATION));
        assertFalse(RunTrack.catalogs(RunTrack.AUTOMATION).contains("ONLINE"));
    }

    @Test
    void 轨道与环境互不影响() {
        // 轨道只看 catalog，环境只看 environment，两个函数各管各的
        assertEquals(RunTrack.catalogs(RunTrack.REGRESSION), RunTrack.catalogs(RunTrack.REGRESSION));
        assertEquals(TestEnvironment.PROD, TestEnvironment.normalize(null));
        assertEquals(RunTrack.REGRESSION, RunTrack.normalize(TestEnvironment.PROD));
    }

    @Test
    void 标签给人看() {
        assertEquals("回归", RunTrack.label(null));
        assertEquals("自动化编排", RunTrack.label(RunTrack.AUTOMATION));
        assertEquals("兼容性测试", RunTrack.label("compatibility"));
    }

    /** 核心流程不分回归/自动化用途，兼容性测试两边都要收；装包类仍然不进 */
    @Test
    void 兼容性测试收回归和自动化两种用途的流程() {
        List<String> c = RunTrack.catalogs(RunTrack.COMPATIBILITY);
        assertTrue(c.containsAll(List.of("ONLINE", "AUTOMATION", "SHARED")));
        assertFalse(c.contains(RunTrack.INSTALL));
        assertFalse(RunTrack.isAutomation(RunTrack.COMPATIBILITY));
    }

    @Test
    void 兼容性测试按依赖排序且失败不中断整批() {
        assertTrue(RunTrack.ordersByDependency(RunTrack.COMPATIBILITY));
        assertTrue(RunTrack.continuesOnFailure(RunTrack.COMPATIBILITY));
        assertFalse(RunTrack.ordersByDependency(RunTrack.REGRESSION));
        assertFalse(RunTrack.continuesOnFailure(null));
    }
}
