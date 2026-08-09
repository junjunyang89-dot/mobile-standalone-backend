package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面编码规范化。
 * <p>
 * 这一层存在的理由：模型不会每次都听话。带引号、带句号、多写一句解释都出现过。
 * 编码是流程里引用元素的键，一旦存进去一个带引号的，改起来要连着流程一起改——
 * 所以宁可在这里兜死，也不要相信提示词。
 */
class PageCodeSuggesterTest {

    @Test
    void 正常返回原样大写() {
        assertEquals("LOGIN_SMS_CODE", PageCodeSuggester.normalize("LOGIN_SMS_CODE"));
        assertEquals("SIDEBAR", PageCodeSuggester.normalize("sidebar"));
    }

    @Test
    void 去掉引号和反引号() {
        assertEquals("LOGIN_SMS", PageCodeSuggester.normalize("\"LOGIN_SMS\""));
        assertEquals("LOGIN_SMS", PageCodeSuggester.normalize("`LOGIN_SMS`"));
    }

    /** 模型有时会在下面补一句「这个编码表示……」 */
    @Test
    void 只取第一行() {
        assertEquals("MATCH_DETAIL",
                PageCodeSuggester.normalize("MATCH_DETAIL\n这个编码表示赛事详情页"));
    }

    @Test
    void 空格连字符标点都变下划线() {
        assertEquals("MATCH_SCHEDULE_DETAIL",
                PageCodeSuggester.normalize("match schedule-detail."));
    }

    @Test
    void 连续下划线合并且首尾不留() {
        assertEquals("A_B", PageCodeSuggester.normalize("__a___b__"));
    }

    /** 编码要当标识符用，数字开头会在不少地方出问题 */
    @Test
    void 数字开头补前缀() {
        assertTrue(PageCodeSuggester.normalize("404_PAGE").startsWith("P_"));
    }

    @Test
    void 超长截断且不留尾部下划线() {
        String out = PageCodeSuggester.normalize("A".repeat(30) + "_" + "B".repeat(30));

        assertTrue(out.length() <= 40, out);
        assertTrue(!out.endsWith("_"), out);
    }

    @Test
    void 中文会被清空而不是留下乱码() {
        // 全中文说明模型没翻译，正常化后为空——调用方据此退回随机码，
        // 而不是存一个 "___" 这样的垃圾编码进去
        assertEquals("", PageCodeSuggester.normalize("输入验证码"));
    }

    @Test
    void 空输入不抛() {
        assertNull(PageCodeSuggester.normalize(null));
        assertEquals("", PageCodeSuggester.normalize("   "));
    }

    @Test
    void 提示词里带上了要翻译的名字和例子() {
        String p = PageCodeSuggester.prompt("输入验证码");

        assertTrue(p.contains("输入验证码"), p);
        assertTrue(p.contains("LOGIN_SMS_CODE"), "给例子比讲规则管用");
        assertTrue(p.contains("只输出编码本身"), "不约束输出格式，模型会带一堆解释回来");
    }
}
