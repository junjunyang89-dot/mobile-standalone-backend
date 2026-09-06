package com.mobile.standalone.service.driver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickTapPointTest {

    @Test
    void centerIsDefault() {
        assertArrayEquals(new int[]{500, 40}, ClickTapPoint.of(0, 0, 1000, 80, 50, 50));
        assertFalse(ClickTapPoint.isCustom(50, 50));
    }

    @Test
    void newestTabIsRightSide() {
        assertArrayEquals(new int[]{900, 40}, ClickTapPoint.of(0, 0, 1000, 80, 90, 50));
        assertTrue(ClickTapPoint.isCustom(90, 50));
    }

    @Test
    void clickOnBarMapsBackToPercent() {
        assertArrayEquals(new int[]{90, 50}, ClickTapPoint.percentOf(0, 0, 1000, 80, 900, 40));
        assertArrayEquals(new int[]{64, 50}, ClickTapPoint.percentOf(0, 0, 1000, 80, 640, 40));
    }

    @Test
    void percentsClampToEdgeInset() {
        assertArrayEquals(new int[]{1, 1}, ClickTapPoint.of(0, 0, 1000, 80, -10, -10));
        assertArrayEquals(new int[]{999, 79}, ClickTapPoint.of(0, 0, 1000, 80, 200, 200));
    }
}
