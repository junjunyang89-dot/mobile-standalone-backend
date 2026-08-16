package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 找出流程里写死的环境相关数据。
 * <p>
 * 这份清单的全部价值在于「报出来的都值得改」。
 * 误报几条之后人就不看了，真正该改的那条也一起废掉——
 * 所以宁可漏报（少扫几个参数），也不能乱报。
 */
class FlowDataScannerTest {

    @Test
    void 认出写死的手机号() {
        List<FlowDataScanner.Hit> out = FlowDataScanner.scan("2", "text", "13800138000");

        assertEquals(1, out.size());
        assertEquals("PHONE", out.get(0).getKind());
        assertEquals("phone", out.get(0).getSuggestVariable());
        assertTrue(out.get(0).getReason().contains("登录失败"), out.get(0).getReason());
    }

    /** 已经是变量的正是我们希望的样子，不该再报 */
    @Test
    void 已经用了变量的不报() {
        assertTrue(FlowDataScanner.scan("2", "text", "${phone}").isEmpty());
        assertTrue(FlowDataScanner.scan("2", "text", "{{phone}}").isEmpty());
    }

    @Test
    void 认出写死的地址() {
        List<FlowDataScanner.Hit> out = FlowDataScanner.scan("1", "url", "https://test-api.example.com/x");

        assertEquals("URL", out.get(0).getKind());
    }

    @Test
    void 长数字按ID报出来() {
        List<FlowDataScanner.Hit> out = FlowDataScanner.scan("5", "keyword", "20260809123");

        assertEquals("ID", out.get(0).getKind());
        assertNull(out.get(0).getSuggestVariable(), "叫什么变量只有人知道，别替他起名");
        assertTrue(out.get(0).getReason().contains("不存在"), out.get(0).getReason());
    }

    /**
     * 本组核心之一。timeoutMs、retryTimes 里的数字天然就该是字面量，
     * 扫进来会把它们一起报出去——误报几条这份清单就没人看了。
     */
    @Test
    void 不扫超时和重试这类参数() {
        assertTrue(FlowDataScanner.scan("3", "timeoutMs", "30000").isEmpty());
        assertTrue(FlowDataScanner.scan("3", "retryTimes", "3").isEmpty());
        assertTrue(FlowDataScanner.scan("3", "waitMs", "20260809123").isEmpty());
    }

    /** 短数字不报：验证码位数和普通数字分不开，报了全是噪声 */
    @Test
    void 短数字不报() {
        assertTrue(FlowDataScanner.scan("3", "text", "1234").isEmpty());
        assertTrue(FlowDataScanner.scan("3", "text", "第 2 页").isEmpty());
    }

    @Test
    void 空值不抛() {
        assertTrue(FlowDataScanner.scan("1", "text", null).isEmpty());
        assertTrue(FlowDataScanner.scan("1", null, "13800138000").isEmpty());
    }

    // ==================== 「点具体某一条」类问题 ====================

    /**
     * 本组核心之二。变量解决不了这一类：
     * 那条新闻在另一个环境压根不存在，换个 ID 也没用，只能改流程写法。
     */
    @Test
    void 按文案定位的步骤给出改写建议() {
        String advice = FlowDataScanner.rewriteAdviceFor("TEXT", "CS 全球总决赛开打");

        assertNotNull(advice);
        assertTrue(advice.contains("按位置选"), advice);
        assertTrue(advice.contains("环境变量"), "确实要验特定数据时的出路也要给：" + advice);
    }

    @Test
    void xpath里按文案匹配的也算() {
        assertNotNull(FlowDataScanner.rewriteAdviceFor("XPATH", "//*[@text='某条新闻']"));
        assertNotNull(FlowDataScanner.rewriteAdviceFor("XPATH", "//*[contains(@label,'决赛')]"));
    }

    /** 按 id 定位的跟数据无关，不该提示 */
    @Test
    void 按id定位的不提示() {
        assertNull(FlowDataScanner.rewriteAdviceFor("ID", "com.x:id/btn_login"));
        assertNull(FlowDataScanner.rewriteAdviceFor("XPATH", "//android.widget.Button[1]"));
        assertNull(FlowDataScanner.rewriteAdviceFor(null, "x"));
    }
}
