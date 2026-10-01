package com.mobile.standalone.service.h5;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H5DomClickTest {

    @Test
    void 文案里的引号被编进JS字符串() {
        String expr = H5DomClick.expression("请输入\"搜索\"", null);
        assertTrue(expr.contains("请输入\\\"搜索\\\"") || expr.contains("请输入\\u0022搜索\\u0022"));
        assertFalse(expr.contains("const text=请输入"));
    }

    @Test
    void jsonString包住反斜杠() {
        assertTrue(H5DomClick.jsonString("a\\b").contains("\\\\"));
    }

    @Test
    void 写入内容编进脚本() {
        String expr = H5DomClick.expression("请输入搜索关键词", null, "AK47");
        assertTrue(expr.contains("AK47"));
        assertTrue(expr.contains("const typeValue="));
    }

    @Test
    void 脚本Map能解析成Hit() {
        H5DomClick.Hit hit = H5DomClick.fromRaw(java.util.Map.of(
                "ok", true, "x", 10, "y", 20, "vw", 100, "vh", 200, "tag", "INPUT"));
        assertTrue(hit.ok());
        assertEquals("INPUT", hit.tag());
    }
}
