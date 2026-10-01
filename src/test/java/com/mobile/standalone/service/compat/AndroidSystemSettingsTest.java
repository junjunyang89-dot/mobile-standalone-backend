package com.mobile.standalone.service.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AndroidSystemSettingsTest {

    @Test
    void 读深色模式状态() {
        assertEquals("no", AndroidSystemSettings.parseNightMode("Night mode: no\n"));
        assertEquals("yes", AndroidSystemSettings.parseNightMode("Night mode: yes"));
        assertEquals("auto", AndroidSystemSettings.parseNightMode("Night mode: AUTO"));
        assertNull(AndroidSystemSettings.parseNightMode("cmd: Can't find service: uimode"));
    }

    /** 没设过字体缩放时 settings get 返回 "null"，还原要回到 1.0 而不是把字符串 null 写回去 */
    @Test
    void 字体缩放读回来统一格式() {
        assertEquals("1.0", AndroidSystemSettings.normalizeFontScale("null\n"));
        assertEquals("1.0", AndroidSystemSettings.normalizeFontScale(""));
        assertEquals("1.0", AndroidSystemSettings.normalizeFontScale("1"));
        assertEquals("1.3", AndroidSystemSettings.normalizeFontScale("1.30\n"));
        assertEquals("1.15", AndroidSystemSettings.normalizeFontScale("1.15"));
    }
}
