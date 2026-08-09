package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析模型从 Inspector 截图读出来的元素草稿。
 * <p>
 * 这一层的立场是<b>不相信模型听话</b>：它会包 markdown、会加解释、会漏字段、会写别的把握等级。
 * 提示词只能提高听话的概率，剥壳和兜底才能保证结果。
 * <p>
 * 最要紧的一条是 <b>没有定位值就整条丢掉</b>：留一条只有名字的元素，
 * 人存进去、挂到流程上，跑起来才发现找不到——那时候已经离录入很远了。
 */
class InspectorShotReaderTest {

    private static final String GOOD = """
            [{"nameEn":"login_phone_input","nameZh":"手机号输入框",
              "locatorType":"ID","locatorValue":"com.pwrd.steam.esports:id/et_phone",
              "fallbacks":["//android.widget.EditText[1]"],
              "visualHint":"请输入手机号","confidence":"HIGH","note":""}]
            """;

    @Test
    void 正常解析一条元素() {
        List<InspectorShotReader.Draft> out = InspectorShotReader.parse(GOOD);

        assertEquals(1, out.size());
        InspectorShotReader.Draft d = out.get(0);
        assertEquals("login_phone_input", d.getNameEn());
        assertEquals("手机号输入框", d.getNameZh());
        assertEquals("ID", d.getLocatorType());
        assertEquals("com.pwrd.steam.esports:id/et_phone", d.getLocatorValue());
        assertEquals(1, d.getFallbacks().size());
        assertEquals("HIGH", d.getConfidence());
    }

    /** 模型很爱包一层 ```json，还爱在前面写一句「好的，我识别到：」 */
    @Test
    void 剥掉markdown和前后废话() {
        String wrapped = "好的，我识别到以下元素：\n```json\n" + GOOD + "\n```\n希望有帮助！";

        assertEquals(1, InspectorShotReader.parse(wrapped).size());
    }

    /**
     * 本组核心。定位值是这条记录唯一不可替代的东西——
     * 只有名字的元素存进去，挂到流程上，跑起来才发现找不到。
     */
    @Test
    void 没有定位值的整条丢掉() {
        String bad = """
                [{"nameEn":"a","nameZh":"某按钮","locatorType":"ID","locatorValue":""},
                 {"nameEn":"b","nameZh":"另一个","locatorType":"ID","locatorValue":"id/ok"}]
                """;

        List<InspectorShotReader.Draft> out = InspectorShotReader.parse(bad);

        assertEquals(1, out.size(), "没有定位值的那条不该留下");
        assertEquals("id/ok", out.get(0).getLocatorValue());
    }

    // ==================== 归一化 ====================

    @Test
    void 定位类型的各种写法都认() {
        assertEquals("ID", InspectorShotReader.normalizeType("resource-id"));
        assertEquals("ID", InspectorShotReader.normalizeType("resourceId"));
        assertEquals("ACCESSIBILITY_ID", InspectorShotReader.normalizeType("content-desc"));
        assertEquals("ACCESSIBILITY_ID", InspectorShotReader.normalizeType("name"));
        assertEquals("CLASS_NAME", InspectorShotReader.normalizeType("class"));
    }

    /** 认不出来时按 XPATH：它最通用、人一眼能看出对不对；猜成 ID 会让人以为已经对了 */
    @Test
    void 认不出的类型退回XPATH() {
        assertEquals("XPATH", InspectorShotReader.normalizeType(""));
        assertEquals("XPATH", InspectorShotReader.normalizeType(null));
    }

    /** 把握程度上宁可保守：没给或给了别的词，一律按 LOW，逼人去核一眼 */
    @Test
    void 把握程度默认最低() {
        assertEquals("LOW", InspectorShotReader.normalizeConfidence(null));
        assertEquals("LOW", InspectorShotReader.normalizeConfidence("比较确定"));
        assertEquals("HIGH", InspectorShotReader.normalizeConfidence("high"));
    }

    @Test
    void 英文键归一成小写下划线() {
        assertEquals("login_phone_input", InspectorShotReader.normalizeKey("Login Phone-Input"));
        assertEquals("btn_ok", InspectorShotReader.normalizeKey("__btn__ok__"));
        assertNull(InspectorShotReader.normalizeKey("中文"));
    }

    @Test
    void 空输入和垃圾返回不抛() {
        assertTrue(InspectorShotReader.parse(null).isEmpty());
        assertTrue(InspectorShotReader.parse("").isEmpty());
        assertTrue(InspectorShotReader.parse("我看不清这张图").isEmpty());
        assertTrue(InspectorShotReader.parse("[不是合法 JSON").isEmpty());
    }

    @Test
    void 提示词交代了照抄和不许猜() {
        assertTrue(InspectorShotReader.PROMPT.contains("逐字符照抄"), "定位值错一个字符就永远找不到");
        assertTrue(InspectorShotReader.PROMPT.contains("Inspector"), "要告诉模型它在看什么");
        assertTrue(InspectorShotReader.PROMPT.contains("resource-id"), "要给出定位器优先级");
    }
}
