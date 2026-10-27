package com.mobile.standalone.service.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.util.Locale;

/**
 * ChatGPT 版失败归因：{@code diagnosis.ai.provider=openai} 时启用。
 * <p>
 * OpenAI 和千问走的是同一套 {@code /chat/completions} 流式接口，请求体和结论解析完全复用，
 * 这里只换地址、密钥、模型和代理（见 {@link OpenAiSettings}）。
 */
@Service
@ConditionalOnProperty(name = "diagnosis.ai.provider", havingValue = "openai")
public class OpenAiDiagnosisClient extends QwenDiagnosisClient {

    private final OpenAiSettings openAi;

    public OpenAiDiagnosisClient(OpenAiSettings openAi) {
        this.openAi = openAi;
    }

    @Override
    protected String endpointBase() {
        return openAi.apiBase();
    }

    @Override
    protected String bearerKey() {
        return openAi.apiKey();
    }

    @Override
    protected HttpClient httpClient() {
        return openAi.http();
    }

    @Override
    protected String providerLabel() {
        return "ChatGPT";
    }

    @Override
    public String modelId() {
        return openAi.diagnosisModel();
    }

    /** o 系列和 gpt-5 系列只接受默认温度 */
    @Override
    protected boolean sendTemperature() {
        String m = modelId().toLowerCase(Locale.ROOT);
        return !(m.matches("^o\\d.*") || m.startsWith("gpt-5"));
    }
}
