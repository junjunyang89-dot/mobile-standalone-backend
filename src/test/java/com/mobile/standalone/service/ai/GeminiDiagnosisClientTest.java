package com.mobile.standalone.service.ai;

import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Source;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gemini 响应解析与请求构造。
 * <p>
 * 不打真实网络，只覆盖「模型返回的东西怎么变成能落库的结论」——这段最容易在模型换版本、
 * 返回内容不合预期时静默出错，而归因结论解析不出来就等于这次归因白跑了。
 */
class GeminiDiagnosisClientTest {

    private GeminiDiagnosisClient client;

    @BeforeEach
    void setUp() {
        client = new GeminiDiagnosisClient();
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "model", "gemini-2.5-flash");
        ReflectionTestUtils.setField(client, "maxImages", 2);
        ReflectionTestUtils.setField(client, "imageMaxEdge", 1024);
    }

    private Verdict parse(String innerJson) {
        String body = """
                {"candidates":[{"content":{"parts":[{"text":%s}]},"finishReason":"STOP"}]}
                """.formatted(quote(innerJson));
        return ReflectionTestUtils.invokeMethod(client, "parseVerdict", body);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void 解析出完整结论() {
        Verdict v = parse("""
                {"category":"ELEMENT_BROKEN","confidence":85,
                 "reason":"控件树里有点击登录按钮，但元素表存的定位指向一条资讯标题",
                 "suggestion":"把主定位改成登录按钮",
                 "suggestedLocatorType":"xpath",
                 "suggestedLocatorValue":"//XCUIElementTypeButton[@name=\\"点击登录\\"]"}""");

        assertEquals(Category.ELEMENT_BROKEN, v.getCategory());
        assertEquals(85, v.getConfidence());
        assertTrue(v.getReason().contains("控件树"));
        assertEquals("xpath", v.getSuggestedLocatorType());
        assertTrue(v.getSuggestedLocatorValue().contains("点击登录"));
        assertEquals(Source.AI, v.getSource());
        assertEquals("gemini-2.5-flash", v.getModel());
    }

    /**
     * 分类不够用是拿来改分类的信号。埋在日志里没人看，得并进结论让人在页面上直接看见。
     */
    @Test
    void 模型说六类都不合适时并进结论() {
        Verdict v = parse("""
                {"category":"UNKNOWN","confidence":40,"reason":"证据指向账号被风控",
                 "categoryMismatch":"账号态问题，测试账号被限制登录"}""");

        assertTrue(v.getReason().contains("六类都不合适"), v.getReason());
        assertTrue(v.getReason().contains("账号被限制登录"), v.getReason());
        assertTrue(v.getReason().contains("证据指向账号被风控"), v.getReason());
    }

    /** 模型换版本后可能吐出没见过的枚举值，不能因此让整条归因失败 */
    @Test
    void 未知分类降级为UNKNOWN而不是抛异常() {
        Verdict v = parse("{\"category\":\"NETWORK_ISSUE\",\"confidence\":50,\"reason\":\"网络不通\"}");

        assertEquals(Category.UNKNOWN, v.getCategory());
        assertEquals("网络不通", v.getReason());
    }

    @Test
    void 缺分类时按证据不足处理() {
        Verdict v = parse("{\"confidence\":10,\"reason\":\"看不出来\"}");

        assertEquals(Category.UNKNOWN, v.getCategory());
    }

    /** 空字符串跟没填是一个意思，不能存成空串再传到定位串上去 */
    @Test
    void 空候选定位归一为null() {
        Verdict v = parse("""
                {"category":"FUNCTION_BROKEN","confidence":70,"reason":"点了没反应",
                 "suggestedLocatorType":"","suggestedLocatorValue":"  "}""");

        assertNull(v.getSuggestedLocatorType());
        assertNull(v.getSuggestedLocatorValue());
    }

    /** 安全过滤等原因会返回没有 parts 的候选，得报出来而不是当成空结论存下 */
    @Test
    void 没有返回内容时报错并带上原因() {
        String body = "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}";

        Exception e = assertThrows(Exception.class,
                () -> ReflectionTestUtils.invokeMethod(client, "parseVerdict", body));
        assertTrue(e.getMessage().contains("SAFETY"), e.getMessage());
    }

    @Test
    void 未配置密钥时视为不可用() {
        ReflectionTestUtils.setField(client, "apiKey", "");
        assertFalse(client.isConfigured());

        ReflectionTestUtils.setField(client, "apiKey", "k");
        ReflectionTestUtils.setField(client, "enabled", false);
        assertFalse(client.isConfigured());
    }

    /** 没配就调用要给出明确提示，而不是发一个必然 401 的请求 */
    @Test
    void 未配置时调用直接拒绝() {
        ReflectionTestUtils.setField(client, "enabled", false);

        assertThrows(IllegalStateException.class, () -> client.diagnose("prompt", java.util.List.of()));
    }

    @Test
    void 请求体带上结构化输出约束() {
        var body = ReflectionTestUtils.invokeMethod(
                client, "buildRequest", "提示词", java.util.List.<java.nio.file.Path>of());
        String json = String.valueOf(body);

        assertTrue(json.contains("responseSchema"), json);
        assertTrue(json.contains("application/json"), json);
        // 归因要的是稳定复现的判断，不是多样性
        assertTrue(json.contains("\"temperature\":0"), json);
        for (Category c : Category.values()) {
            assertTrue(json.contains(c.name()), "枚举应约束到 " + c.name());
        }
    }

    /** 图片读不出来时少一张还能靠控件树判，不该让整次归因失败 */
    @Test
    void 截图无法读取时跳过而不是失败() {
        var body = ReflectionTestUtils.invokeMethod(client, "buildRequest", "提示词",
                java.util.List.of(java.nio.file.Path.of("/tmp/不存在的图-" + System.nanoTime() + ".png")));

        assertTrue(String.valueOf(body).contains("提示词"));
    }
}
