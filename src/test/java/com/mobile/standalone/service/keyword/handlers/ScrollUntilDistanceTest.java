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
    void 向上小幅滑动从下半段起手() {
        int h = 2000;
        // distancePercent 是百分比：12% 的幅度。ScrollUntilHandler 用 ratio(0.12)*100 换算后传入
        int[] pts = UiFlowExecutor.swipeEndpoints(1080, h, "up", 12);
        assertTrue(pts[1] > pts[3], "手指应从下往上");
        // 从内容区下半段(0.62h)起手，而不是按在正中间的大卡片上
        assertEquals((int) (h * 0.62), pts[1]);
        // 往上顶 12% 一小截
        assertEquals((int) Math.round(h * 12 / 100.0), pts[1] - pts[3]);
    }

    @Test
    void 自定义百分比覆盖档位并夹紧() {
        assertEquals(0.05, ScrollUntilHandler.resolveRatio(5, "short"), 1e-9);
        assertEquals(0.12, ScrollUntilHandler.resolveRatio(0, "short"), 1e-9);
        assertEquals(0.03, ScrollUntilHandler.resolveRatio(1, "normal"), 1e-9);
        assertEquals(0.8, ScrollUntilHandler.resolveRatio(99, "normal"), 1e-9);
    }

    @Test
    void 向上极小幅滑动仍从下半段起手() {
        int h = 2000;
        int[] pts = UiFlowExecutor.swipeEndpoints(1080, h, "up", 5);
        assertTrue(pts[1] > pts[3], "手指应从下往上");
        assertEquals((int) (h * 0.62), pts[1]);
        // 极小幅也只顶 5%，起点终点都在下半屏，避开正中大卡片
        assertEquals((int) Math.round(h * 5 / 100.0), pts[1] - pts[3]);
        assertTrue(pts[3] > h / 2, "终点仍在下半屏");
    }

    @Test
    void 不给幅度时走默认大半屏_072_028() {
        int h = 1000;
        // distancePercent <= 0 表示不指定幅度，走默认的大半屏
        int[] pts = UiFlowExecutor.swipeEndpoints(1080, h, "up", 0);
        assertEquals(720, pts[1]);
        assertEquals(280, pts[3]);
    }
}
