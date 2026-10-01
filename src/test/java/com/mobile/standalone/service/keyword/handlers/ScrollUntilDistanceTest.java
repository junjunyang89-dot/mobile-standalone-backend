package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.UiFlowExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrollUntilDistanceTest {

    @Test
    void 小幅行程约为屏高一成多() {
        assertEquals(0.12, ScrollUntilHandler.distanceRatio("short"), 1e-9);
        assertEquals(0.44, ScrollUntilHandler.distanceRatio("normal"), 1e-9);
        assertEquals(0.65, ScrollUntilHandler.distanceRatio("long"), 1e-9);
    }

    @Test
    void 向上小幅滑动落在中部窄带() {
        int h = 2000;
        int[] pts = UiFlowExecutor.swipeEndpoints(1080, h, "up", 0.12);
        // 起点在下、终点在上，且都靠近中线
        assertTrue(pts[1] > pts[3], "手指应从下往上");
        assertEquals((int) (h * 0.56), pts[1]);
        assertEquals((int) (h * 0.44), pts[3]);
    }

    @Test
    void 自定义百分比覆盖档位并夹紧() {
        assertEquals(0.05, ScrollUntilHandler.resolveRatio(5, "short"), 1e-9);
        assertEquals(0.12, ScrollUntilHandler.resolveRatio(0, "short"), 1e-9);
        assertEquals(0.03, ScrollUntilHandler.resolveRatio(1, "normal"), 1e-9);
        assertEquals(0.8, ScrollUntilHandler.resolveRatio(99, "normal"), 1e-9);
    }

    @Test
    void 向上极小幅滑动落在中部() {
        int h = 2000;
        int[] pts = UiFlowExecutor.swipeEndpoints(1080, h, "up", 0.05);
        assertTrue(pts[1] > pts[3], "手指应从下往上");
        assertEquals((int) (h * 0.525), pts[1]);
        assertEquals((int) (h * 0.475), pts[3]);
    }

    @Test
    void 默认半屏与历史_072_028_接近() {
        int h = 1000;
        int[] pts = UiFlowExecutor.swipeEndpoints(1080, h, "up", 0.44);
        assertEquals(720, pts[1]);
        assertEquals(280, pts[3]);
    }
}
