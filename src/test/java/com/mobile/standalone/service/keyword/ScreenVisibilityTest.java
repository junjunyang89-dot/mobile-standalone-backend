package com.mobile.standalone.service.keyword;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenVisibilityTest {

    @Test
    void 宽卡中心还在屏外但已露出一大块算到了() {
        // 卡 400 宽，左边 900～1300，屏宽 1080；中心 1100 在屏外
        assertTrue(ScreenVisibility.boxVisibleInRegion(
                new int[]{900, 1616, 1300, 1901}, 0, 1616, 1080, 1901));
    }

    @Test
    void 还在区域外的卡不算() {
        assertFalse(ScreenVisibility.boxVisibleInRegion(
                new int[]{1400, 1616, 1800, 1901}, 0, 1616, 1080, 1901));
    }
}
