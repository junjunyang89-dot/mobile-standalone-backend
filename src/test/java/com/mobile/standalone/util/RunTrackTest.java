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
    }
}
