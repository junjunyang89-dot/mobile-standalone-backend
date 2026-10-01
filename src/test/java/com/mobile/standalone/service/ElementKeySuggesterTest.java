package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementKeySuggesterTest {

    @Test
    void 正常返回小写下划线() {
        assertEquals("login_btn", ElementKeySuggester.normalize("login_btn"));
        assertEquals("activity_center", ElementKeySuggester.normalize("Activity_Center"));
    }

    @Test
    void 去掉引号和反引号() {
        assertEquals("login_btn", ElementKeySuggester.normalize("\"login_btn\""));
        assertEquals("login_btn", ElementKeySuggester.normalize("`login_btn`"));
    }

    @Test
    void 只取第一行() {
        assertEquals("activity_center",
                ElementKeySuggester.normalize("activity_center\n这个 key 表示活动中心"));
    }

    @Test
    void 空格连字符标点都变下划线() {
        assertEquals("match_schedule_detail",
                ElementKeySuggester.normalize("match schedule-detail."));
    }

    @Test
    void 数字开头加前缀() {
        assertTrue(ElementKeySuggester.normalize("404_page").startsWith("el_"));
    }

    @Test
    void 超长截断() {
        String out = ElementKeySuggester.normalize("a".repeat(30) + "_" + "b".repeat(30));
        assertTrue(out.length() <= 48);
        assertTrue(!out.endsWith("_"));
    }

    @Test
    void 纯中文规范化成空串() {
        assertEquals("", ElementKeySuggester.normalize("活动中心"));
    }

    @Test
    void 空输入() {
        assertNull(ElementKeySuggester.normalize(null));
        assertEquals("", ElementKeySuggester.normalize("   "));
    }

    @Test
    void 提示词包含中文名() {
        String p = ElementKeySuggester.prompt("活动中心");
        assertTrue(p.contains("活动中心"));
        assertTrue(p.contains("snake_case") || p.contains("下划线"));
    }
}
