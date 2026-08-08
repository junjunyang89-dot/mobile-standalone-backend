package com.mobile.standalone.service.ai;

import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Source;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 千问（DashScope OpenAI 兼容接口）的请求构造与响应解析。
 * <p>
 * 不打真实网络。重点在「模型返回的东西怎么变成能落库的结论」——这段最容易在换模型版本、
 * 返回格式不合预期时静默出错，而结论解析不出来就等于这次归因白跑了。
 */
class QwenDiagnosisClientTest {

    private QwenDiagnosisClient client;

    @BeforeEach
    void setUp() {
        client = new QwenDiagnosisClient();
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "apiKey", "sk-test");
        ReflectionTestUtils.setField(client, "model", "");
        ReflectionTestUtils.setField(client, "maxImages", 2);
        ReflectionTestUtils.setField(client, "imageMaxEdge", 1024);
    }

    /**
     * 把一段正文切成 SSE 分片喂进去——真实路径就是流式，按整包解析等于没测到。
     * 顺便夹一条 reasoning_content 分片：推理模型每次都会发，混进正文就会把 JSON 冲垮。
     */
    private Verdict parse(String content) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("data: " + chunk("reasoning_content", "让我先分析一下这个失败……"));
        for (int i = 0; i < content.length(); i += 17) {
            lines.add("data: " + chunk("content", content.substring(i, Math.min(content.length(), i + 17))));
            lines.add("");
        }
        lines.add("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        lines.add("data: [DONE]");
        return client.parseStream(lines.stream());
    }

    private static String chunk(String field, String text) {
        return "{\"choices\":[{\"delta\":{\"" + field + "\":" + quote(text) + "}}]}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void 解析出完整结论() throws Exception {
        Verdict v = parse("""
                {"category":"ELEMENT_BROKEN","confidence":85,
                 "reason":"控件树里有点击登录按钮，元素表存的定位却指向一条资讯标题",
                 "suggestion":"把主定位改成登录按钮",
                 "suggestedLocatorType":"xpath",
                 "suggestedLocatorValue":"//XCUIElementTypeButton[@name=\\"点击登录\\"]"}""");

        assertEquals(Category.ELEMENT_BROKEN, v.getCategory());
        assertEquals(85, v.getConfidence());
        assertEquals("xpath", v.getSuggestedLocatorType());
        assertTrue(v.getSuggestedLocatorValue().contains("点击登录"));
        assertEquals(Source.AI, v.getSource());
        assertEquals("qwen-vl-max", v.getModel(), "未配 model 时应落到默认视觉模型");
    }

    /** 反复叮嘱「不要包代码块」仍然会有模型包上，为此让归因失败不值当 */
    @Test
    void 结论被包在markdown代码块里也能解析() throws Exception {
        Verdict v = parse("""
                ```json
                {"category":"ENVIRONMENT","confidence":70,"reason":"WDA 未启动"}
                ```""");

        assertEquals(Category.ENVIRONMENT, v.getCategory());
        assertEquals("WDA 未启动", v.getReason());
    }

    @Test
    void 模型说六类都不合适时并进结论() throws Exception {
        Verdict v = parse("""
                {"category":"UNKNOWN","confidence":40,"reason":"证据指向账号被风控",
                 "categoryMismatch":"账号态问题，测试账号被限制登录"}""");

        assertTrue(v.getReason().contains("六类都不合适"), v.getReason());
        assertTrue(v.getReason().contains("账号被限制登录"), v.getReason());
    }

    @Test
    void 未知分类降级为UNKNOWN() throws Exception {
        Verdict v = parse("{\"category\":\"NETWORK\",\"confidence\":50,\"reason\":\"网络不通\"}");

        assertEquals(Category.UNKNOWN, v.getCategory());
        assertEquals("网络不通", v.getReason());
    }

    @Test
    void 空候选定位归一为null() throws Exception {
        Verdict v = parse("""
                {"category":"FUNCTION_BROKEN","confidence":70,"reason":"点了没反应",
                 "suggestedLocatorType":"","suggestedLocatorValue":"  "}""");

        assertNull(v.getSuggestedLocatorType());
        assertNull(v.getSuggestedLocatorValue());
    }

    /** 内容审核等原因会一个正文分片都不发，得报出来而不是当成空结论存下 */
    @Test
    void 没有返回内容时报错并带上原因() {
        Stream<String> lines = Stream.of(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}",
                "data: [DONE]");

        Exception e = assertThrows(Exception.class, () -> client.parseStream(lines));
        assertTrue(e.getMessage().contains("content_filter"), e.getMessage());
    }

    /**
     * 推理模型会先吐一大段 reasoning_content。误当正文收进来，要解析的 JSON 就被冲垮了。
     */
    @Test
    void 只收正文不收推理过程() throws Exception {
        Stream<String> lines = Stream.of(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"用户让我判断这次失败……\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"category\\\":\\\"FLAKY\\\",\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"\\\"confidence\\\":60,\\\"reason\\\":\\\"时好时坏\\\"}\"}}]}",
                "data: [DONE]");

        Verdict v = client.parseStream(lines);

        assertEquals(Category.FLAKY, v.getCategory());
        assertEquals("时好时坏", v.getReason());
    }

    /** 心跳注释、空行这些 SSE 噪声不该让解析炸掉 */
    @Test
    void 忽略非data行与解析不了的分片() throws Exception {
        Stream<String> lines = Stream.of(
                ": keep-alive",
                "",
                "data: 这不是JSON",
                "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"category\\\":\\\"ENVIRONMENT\\\",\\\"confidence\\\":50,\\\"reason\\\":\\\"WDA 未起\\\"}\"}}]}",
                "data: [DONE]");

        assertEquals(Category.ENVIRONMENT, client.parseStream(lines).getCategory());
    }

    @Test
    void 配置的模型覆盖默认值() {
        ReflectionTestUtils.setField(client, "model", "qwen3-vl-plus");

        assertEquals("qwen3-vl-plus", client.modelId());
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

        assertThrows(IllegalStateException.class, () -> client.diagnose("prompt", List.of()));
    }

    /** 非流式在近万字提示词上实测 300 秒都等不到首字节，必须流式 */
    @Test
    void 请求体开启流式() {
        assertTrue(String.valueOf(client.buildRequest("提示词", List.of())).contains("\"stream\":true"));
    }

    @Test
    void 请求体符合openai兼容格式() {
        String json = String.valueOf(client.buildRequest("提示词正文", List.of()));

        assertTrue(json.contains("\"model\":\"qwen-vl-max\""), json);
        assertTrue(json.contains("\"role\":\"user\""), json);
        assertTrue(json.contains("\"type\":\"text\""), json);
        // 归因要的是稳定复现的判断，不是多样性
        assertTrue(json.contains("\"temperature\":0"), json);
        assertTrue(json.contains("提示词正文"), json);
    }

    /** 没有 responseSchema 可用，输出格式只能靠提示词说清楚 */
    @Test
    void 提示词末尾附上json输出要求() {
        String json = String.valueOf(client.buildRequest("提示词正文", List.of()));

        assertTrue(json.contains("只输出一个 JSON 对象"), json);
        for (Category c : Category.values()) {
            assertTrue(json.contains(c.name()), "输出要求应列出 " + c.name());
        }
    }

    /** 图片读不出来时少一张还能靠控件树判，不该让整次归因失败 */
    @Test
    void 截图无法读取时跳过而不是失败() {
        String json = String.valueOf(client.buildRequest("提示词",
                List.of(Path.of("/tmp/不存在的图-" + System.nanoTime() + ".png"))));

        assertTrue(json.contains("提示词"), json);
        assertFalse(json.contains("image_url"), "读不出来就不该产生图片分片");
    }
}
