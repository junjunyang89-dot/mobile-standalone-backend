package com.mobile.standalone.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * ChatGPT（OpenAI 官方接口或兼容中转）的连接配置。
 * <p>
 * 通用对话和失败归因共用这一份：同一把 key、同一个代理，没必要让人在两处各填一遍。
 * <p>
 * 代理必须显式配：Java 的 HttpClient 不读系统代理和 {@code HTTPS_PROXY} 环境变量，
 * 本机浏览器能上 OpenAI 不代表后端能连上——不配的表现是每次都连接超时，看不出是网络问题。
 */
@Component
public class OpenAiSettings {

    static final String DEFAULT_BASE = "https://api.openai.com/v1";
    static final String DEFAULT_MODEL = "gpt-4.1";

    @Value("${ai.openai.api-key:}")
    private String apiKey;

    @Value("${ai.openai.api-base:" + DEFAULT_BASE + "}")
    private String apiBase;

    @Value("${ai.openai.model:" + DEFAULT_MODEL + "}")
    private String model;

    /** 失败归因用的模型；空则跟 {@link #model} 走。归因要看截图，得选带视觉能力的 */
    @Value("${ai.openai.diagnosis-model:}")
    private String diagnosisModel;

    /** HTTP 代理，如 {@code http://127.0.0.1:7890}；空 = 直连 */
    @Value("${ai.openai.proxy:}")
    private String proxy;

    private volatile HttpClient http;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String apiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    public String apiBase() {
        String v = apiBase == null || apiBase.isBlank() ? DEFAULT_BASE : apiBase.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    public String model() {
        return model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    public String diagnosisModel() {
        return diagnosisModel == null || diagnosisModel.isBlank() ? model() : diagnosisModel.trim();
    }

    public HttpClient http() {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                if (http == null) {
                    HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20));
                    InetSocketAddress addr = parseProxy(proxy);
                    if (addr != null) {
                        b.proxy(ProxySelector.of(addr));
                    }
                    http = b.build();
                }
                c = http;
            }
        }
        return c;
    }

    /**
     * 只认 HTTP 代理：{@code http://host:port} 或 {@code host:port}。
     * <p>
     * SOCKS 和带账号密码的代理 HttpClient 都接不住（后者 JDK 默认禁用隧道 Basic 认证），
     * 与其静默直连去超时，不如启动后第一次调用就报清楚。
     */
    static InetSocketAddress parseProxy(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (!v.contains("://")) {
            v = "http://" + v;
        }
        URI uri = URI.create(v);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("ai.openai.proxy 只支持 HTTP 代理，不支持 " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("ai.openai.proxy 不支持带账号密码的代理");
        }
        if (uri.getHost() == null || uri.getPort() <= 0) {
            throw new IllegalArgumentException("ai.openai.proxy 格式应为 http://主机:端口");
        }
        return new InetSocketAddress(uri.getHost(), uri.getPort());
    }
}
