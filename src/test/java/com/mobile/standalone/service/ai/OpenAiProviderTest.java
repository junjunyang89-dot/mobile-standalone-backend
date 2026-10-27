package com.mobile.standalone.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 千问 / ChatGPT 切换：配置怎么生效、调用方指定的千问模型名会不会被原样发给 OpenAI */
class OpenAiProviderTest {

    private static OpenAiSettings settings(String key, String model, String proxy) {
        OpenAiSettings s = new OpenAiSettings();
        ReflectionTestUtils.setField(s, "apiKey", key);
        ReflectionTestUtils.setField(s, "apiBase", "https://api.openai.com/v1/");
        ReflectionTestUtils.setField(s, "model", model);
        ReflectionTestUtils.setField(s, "diagnosisModel", "");
        ReflectionTestUtils.setField(s, "proxy", proxy);
        return s;
    }

    private static QwenChatClient chat(String provider, OpenAiSettings openAi) {
        QwenChatClient c = new QwenChatClient();
        ReflectionTestUtils.setField(c, "apiKey", "qwen-key");
        ReflectionTestUtils.setField(c, "model", "qwen3.8-max");
        ReflectionTestUtils.setField(c, "provider", provider);
        ReflectionTestUtils.setField(c, "openAi", openAi);
        return c;
    }

    @Test
    void 代理支持带不带协议两种写法() {
        InetSocketAddress a = OpenAiSettings.parseProxy("http://127.0.0.1:7890");
        InetSocketAddress b = OpenAiSettings.parseProxy("127.0.0.1:7890");
        assertEquals(7890, a.getPort());
        assertEquals("127.0.0.1", b.getHostString());
        assertNull(OpenAiSettings.parseProxy("  "));
    }

    @Test
    void 不支持的代理启动后第一次调用就报清楚() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiSettings.parseProxy("socks5://127.0.0.1:1080"));
        assertThrows(IllegalArgumentException.class, () -> OpenAiSettings.parseProxy("http://u:p@127.0.0.1:7890"));
        assertThrows(IllegalArgumentException.class, () -> OpenAiSettings.parseProxy("http://127.0.0.1"));
    }

    @Test
    void 地址尾斜杠去掉_模型留空回默认() {
        OpenAiSettings s = settings("sk", "", "");
        assertEquals("https://api.openai.com/v1", s.apiBase());
        assertEquals(OpenAiSettings.DEFAULT_MODEL, s.model());
        assertEquals(OpenAiSettings.DEFAULT_MODEL, s.diagnosisModel());
    }

    @Test
    void 默认仍走千问() {
        QwenChatClient c = chat("qwen", settings("", "gpt-4.1", ""));
        assertFalse(c.usesOpenAi());
        assertEquals("千问", c.providerLabel());
        assertTrue(c.isConfigured());
        assertEquals("qwen3.8-max", c.modelId());
    }

    @Test
    void 切到openai后用ChatGPT的密钥和模型() {
        QwenChatClient c = chat("openai", settings("", "gpt-4.1", ""));
        assertTrue(c.usesOpenAi());
        assertEquals("ChatGPT", c.providerLabel());
        assertFalse(c.isConfigured(), "千问 key 有值也不能当成 ChatGPT 已配置");
        assertThrows(IllegalStateException.class, () -> c.chat("hi"));

        ReflectionTestUtils.setField(c, "openAi", settings("sk-x", "gpt-4.1", ""));
        assertTrue(c.isConfigured());
        assertEquals("gpt-4.1", c.modelId());
    }

    @Test
    void 调用方指定的千问模型在ChatGPT下被丢掉() {
        QwenChatClient c = chat("openai", settings("sk-x", "gpt-4.1", ""));
        assertEquals("gpt-4.1", c.effectiveModel("qwen3.6-flash"));
        assertEquals("gpt-4.1-mini", c.effectiveModel("gpt-4.1-mini"));
        String json = String.valueOf(c.buildRequest("p", List.of(), "qwen3.6-flash"));
        assertTrue(json.contains("\"model\":\"gpt-4.1\""), json);
    }

    @Test
    void 千问下调用方指定的模型照常生效() {
        QwenChatClient c = chat("qwen", settings("", "gpt-4.1", ""));
        assertEquals("qwen3.6-flash", c.effectiveModel("qwen3.6-flash"));
    }

    @Test
    void 归因走ChatGPT时用它的模型_推理模型不传温度() {
        OpenAiDiagnosisClient d = new OpenAiDiagnosisClient(settings("sk-x", "gpt-4.1", ""));
        ReflectionTestUtils.setField(d, "enabled", true);
        assertTrue(d.isConfigured());
        assertEquals("gpt-4.1", d.modelId());
        assertTrue(String.valueOf(d.buildRequest("p", List.of())).contains("\"temperature\":0"));

        OpenAiDiagnosisClient o = new OpenAiDiagnosisClient(settings("sk-x", "o4-mini", ""));
        String json = String.valueOf(o.buildRequest("p", List.of()));
        assertFalse(json.contains("temperature"), json);
        assertTrue(json.contains("\"model\":\"o4-mini\""), json);
    }

    @Test
    void 归因走ChatGPT但没填key视为未配置() {
        OpenAiDiagnosisClient d = new OpenAiDiagnosisClient(settings("", "gpt-4.1", ""));
        ReflectionTestUtils.setField(d, "enabled", true);
        assertFalse(d.isConfigured());
    }
}
