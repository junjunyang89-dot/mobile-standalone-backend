package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 千问生成用例的产出解析。
 * <p>
 * 这组用例几乎全在盯「模型不听话」：说了只输出 JSON，它照样会包 ```json 围栏、
 * 前面加一句「好的，以下是…」、或者直接给顶层数组、或者少给几个字段。
 * 每次生成要几十秒还要花钱，因为一层围栏就整批作废是最亏的。
 */
class QwenTestCaseGeneratorTest {

    private final QwenTestCaseGenerator gen = new QwenTestCaseGenerator(null);

    private static final String ONE_CASE = """
            {"test_cases":[{"case_id":"TC-001","title":"错误手机号登录",
            "priority":"P1","steps":[{"step":1,"action":"输入手机号"}]}]}
            """;

    // ==================== 模型不听话的各种形态 ====================

    @Test
    void 标准JSON能解析() {
        List<JsonNode> cases = gen.parseCases(ONE_CASE);

        assertEquals(1, cases.size());
        assertEquals("错误手机号登录", cases.get(0).get("title").asText());
    }

    @Test
    void 包了markdown围栏也能解析() {
        List<JsonNode> cases = gen.parseCases("```json\n" + ONE_CASE + "\n```");

        assertEquals(1, cases.size());
    }

    @Test
    void 前面带了解释文字也能解析() {
        List<JsonNode> cases = gen.parseCases("好的，以下是为登录页设计的测试用例：\n" + ONE_CASE);

        assertEquals(1, cases.size());
    }

    @Test
    void 直接返回顶层数组也能解析() {
        List<JsonNode> cases = gen.parseCases(
                "[{\"title\":\"用例A\",\"steps\":[]},{\"title\":\"用例B\",\"steps\":[]}]");

        assertEquals(2, cases.size());
    }

    @Test
    void 多包一层data也能找到() {
        List<JsonNode> cases = gen.parseCases(
                "{\"data\":{\"test_cases\":[{\"title\":\"用例A\",\"steps\":[]}]}}");

        assertEquals(1, cases.size());
    }

    // ==================== 缺字段的补齐 ====================

    /** steps 缺了会被落库层直接拒掉，补个空数组让这条还能进来让人改 */
    @Test
    void 缺steps时补空数组而不是丢掉整条() {
        List<JsonNode> cases = gen.parseCases("{\"test_cases\":[{\"title\":\"没写步骤\"}]}");

        assertEquals(1, cases.size());
        assertTrue(cases.get(0).get("steps").isArray());
        assertEquals(0, cases.get(0).get("steps").size());
    }

    @Test
    void 缺编号时顺次补上() {
        List<JsonNode> cases = gen.parseCases(
                "{\"test_cases\":[{\"title\":\"A\",\"steps\":[]},{\"title\":\"B\",\"steps\":[]}]}");

        assertEquals("TC-001", cases.get(0).get("case_id").asText());
        assertEquals("TC-002", cases.get(1).get("case_id").asText());
    }

    /** 标题是唯一不能缺的：没有标题的「用例」落进去也没人看得懂 */
    @Test
    void 没有标题的条目被丢掉() {
        List<JsonNode> cases = gen.parseCases(
                "{\"test_cases\":[{\"steps\":[]},{\"title\":\"有标题\",\"steps\":[]}]}");

        assertEquals(1, cases.size());
        assertEquals("有标题", cases.get(0).get("title").asText());
    }

    @Test
    void 模型给的层级字段会保留() {
        List<JsonNode> cases = gen.parseCases(
                "{\"test_cases\":[{\"title\":\"A\",\"steps\":[],\"test_point\":\"细化点\","
                        + "\"expected_result\":\"看到报错\"}]}");

        assertEquals("细化点", cases.get(0).get("test_point").asText());
        assertEquals("看到报错", cases.get(0).get("expected_result").asText());
    }

    // ==================== 解析不出来时不硬撑 ====================

    @Test
    void 完全不是JSON时返回空() {
        assertTrue(gen.parseCases("模型今天不想干活").isEmpty());
        assertTrue(gen.parseCases("").isEmpty());
        assertTrue(gen.parseCases(null).isEmpty());
    }

    @Test
    void 结构对但没有用例数组时返回空() {
        assertTrue(gen.parseCases("{\"message\":\"我不会\"}").isEmpty());
    }

    /** 一次几十条会开始灌水凑数，也更容易被截断 */
    @Test
    void 超过上限时截断() {
        StringBuilder sb = new StringBuilder("{\"test_cases\":[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"title\":\"用例").append(i).append("\",\"steps\":[]}");
        }
        sb.append("]}");

        assertEquals(20, gen.parseCases(sb.toString()).size());
    }

    // ==================== 提示词 ====================

    @Test
    void 提示词要求只输出JSON且给出结构() {
        String p = gen.prompt("登录页", "需求说明内容", false);

        assertTrue(p.contains("登录页"), p);
        assertTrue(p.contains("需求说明内容"), p);
        assertTrue(p.contains("只输出 JSON"), p);
        assertTrue(p.contains("test_cases"), p);
    }

    /** 给了图就要明说「按图上真实存在的控件来」，否则模型会照着常识编一个登录页 */
    @Test
    void 有截图时提示词要求不得臆造() {
        String p = gen.prompt("登录页", null, true);

        assertTrue(p.contains("截图"), p);
        assertTrue(p.contains("不要臆造"), p);
    }

    @Test
    void 没有文字素材时提示词不留空标题() {
        String p = gen.prompt("登录页", null, true);

        assertFalse(p.contains("【需求/元素说明】"), "没素材就不该留一个空段落：" + p);
    }

    // ==================== 围栏与截取 ====================

    @Test
    void 去围栏与截取JSON() {
        assertEquals("{\"a\":1}", QwenTestCaseGenerator.stripFence("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", QwenTestCaseGenerator.stripFence("{\"a\":1}"));
        assertEquals("{\"a\":1}", QwenTestCaseGenerator.sliceJson("废话{\"a\":1}后缀"));
        assertEquals("[1,2]", QwenTestCaseGenerator.sliceJson("前缀[1,2]"));
    }
}
