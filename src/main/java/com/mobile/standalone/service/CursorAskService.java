package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 平台「AI助手」→ 本机 Cursor SDK（scripts/cursor-ask）。
 * 不经过千问；多轮靠 agentId 挂在 session 上。
 */
@Slf4j
@Service
public class CursorAskService {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 一张图 base64 后的上限（约 6MB 原图），页面上已压缩过，超了多半是没压的原图 */
    private static final int IMAGE_MAX_CHARS = 8_000_000;
    private static final int IMAGE_MAX_COUNT = 4;

    @Value("${cursor.ask.enabled:true}")
    private boolean enabled;

    @Value("${cursor.ask.api-key:${CURSOR_API_KEY:}}")
    private String apiKey;

    @Value("${cursor.ask.cwd:${user.home}/projects/qa_coding/app_coding}")
    private String defaultCwd;

    /** 环境搭建默认工作区：要能同时看到服务源码、k8s-config、skills */
    @Value("${cursor.ask.k8s-cwd:${user.home}/projects/qa_coding}")
    private String k8sCwd;

    @Value("${cursor.ask.model:default}")
    private String model;

    @Value("${cursor.ask.node:node}")
    private String nodeBin;

    @Value("${cursor.ask.script:scripts/cursor-ask/ask.mjs}")
    private String scriptRel;

    @Value("${cursor.ask.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${cursor.ask.k8s-timeout-seconds:600}")
    private int k8sTimeoutSeconds;

    /** sessionId → Cursor agentId */
    private final ConcurrentHashMap<String, String> agents = new ConcurrentHashMap<>();

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        Path script = resolveScript();
        boolean keyOk = apiKey != null && !apiKey.isBlank();
        boolean scriptOk = Files.isRegularFile(script);
        boolean sdkOk = sdkInstalled(script);
        m.put("enabled", enabled);
        m.put("configured", isConfigured());
        m.put("provider", "cursor-sdk");
        m.put("model", model);
        m.put("cwd", defaultCwd);
        m.put("k8sCwd", k8sCwd);
        m.put("hasApiKey", keyOk);
        m.put("scriptReady", scriptOk);
        m.put("sdkInstalled", sdkOk);
        if (!keyOk) {
            m.put("hint", "请在 application.yml 配 cursor.ask.api-key，或 export CURSOR_API_KEY");
        } else if (!sdkOk) {
            m.put("hint", "在 scripts/cursor-ask 执行 npm install");
        } else if (!scriptOk) {
            m.put("hint", "找不到 scripts/cursor-ask/ask.mjs");
        }
        return m;
    }

    /** 页面编码 / 元素 key 等工具调用前先看一眼，别再绕去千问 */
    public boolean isConfigured() {
        Path script = resolveScript();
        return enabled
                && apiKey != null && !apiKey.isBlank()
                && Files.isRegularFile(script)
                && sdkInstalled(script);
    }

    /**
     * 短问短答：不挂多轮会话，给页面编码、元素 key 这类「只要一句话」的工具用。
     * <p>
     * 和主菜单 AI 助手同一条 Cursor 通道，不再走千问。
     */
    public String complete(String prompt) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Cursor 未就绪：配 cursor.ask.api-key（或 CURSOR_API_KEY），并在 scripts/cursor-ask 执行 npm install");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        String sid = "util-" + java.util.UUID.randomUUID();
        try {
            Map<String, Object> r = chat(prompt.trim(), sid, resolveUtilityCwd(), "ask");
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                throw new IllegalStateException(String.valueOf(
                        r.getOrDefault("error", "Cursor 调用失败")));
            }
            Object ans = r.get("answer");
            String text = ans == null ? "" : String.valueOf(ans).trim();
            if (text.isBlank()) {
                throw new IllegalStateException("Cursor 未返回内容");
            }
            return text;
        } finally {
            reset(sid, "ask");
        }
    }

    /** 工具调用优先用配置的业务目录；没有就退到当前后端工程目录 */
    private String resolveUtilityCwd() {
        for (String c : List.of(defaultCwd, k8sCwd, System.getProperty("user.dir", "."))) {
            if (c == null || c.isBlank()) continue;
            Path p = Path.of(c.trim());
            if (Files.isDirectory(p)) {
                return p.toAbsolutePath().normalize().toString();
            }
        }
        throw new IllegalStateException("找不到可用工作目录（cursor.ask.cwd / user.dir）");
    }

    private static boolean sdkInstalled(Path script) {
        if (script == null || script.getParent() == null) return false;
        return Files.isDirectory(script.getParent().resolve("node_modules/@cursor/sdk"));
    }

    public Map<String, Object> chat(String message, String sessionId, String cwd) throws Exception {
        return chat(message, sessionId, cwd, "ask");
    }

    public Map<String, Object> chat(String message, String sessionId, String cwd, String mode) throws Exception {
        return chat(message, sessionId, cwd, mode, List.of(), List.of());
    }
    /**
     * @param dirs   除 cwd 外还能读的代码目录（一单涉及的其它后端 / H5 / 客户端），不存在的跳过
     * @param images 截图：每项 {data: base64, mimeType: image/png}
     */
    public Map<String, Object> chat(String message, String sessionId, String cwd, String mode,
                                    List<String> dirs, List<Map<String, String>> images) throws Exception {
        if (!enabled) {
            throw new IllegalStateException("cursor.ask 已关闭");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 CURSOR_API_KEY / cursor.ask.api-key");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("要问什么？");
        }
        String useMode = mode == null || mode.isBlank() ? "ask" : mode.trim().toLowerCase();
        if (!"ask".equals(useMode) && !"k8s".equals(useMode)) {
            throw new IllegalArgumentException("mode 仅支持 ask / k8s");
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId.trim();
        String workDefault = "k8s".equals(useMode) ? k8sCwd : defaultCwd;
        String work = (cwd == null || cwd.isBlank()) ? workDefault : cwd.trim();
        Path workPath = Path.of(work);
        if (!Files.isDirectory(workPath)) {
            throw new IllegalArgumentException("代码目录不存在：" + work);
        }
        Path script = resolveScript();
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("找不到 Cursor 桥脚本：" + script);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiKey", apiKey.trim());
        payload.put("cwd", workPath.toAbsolutePath().normalize().toString());
        payload.put("message", message.trim());
        payload.put("model", model);
        payload.put("mode", useMode);
        List<String> extra = new ArrayList<>();
        for (String d : dirs == null ? List.<String>of() : dirs) {
            if (d != null && !d.isBlank() && Files.isDirectory(Path.of(d.trim()))) {
                String abs = Path.of(d.trim()).toAbsolutePath().normalize().toString();
                if (!extra.contains(abs) && !abs.equals(payload.get("cwd"))) {
                    extra.add(abs);
                }
            }
        }
        if (!extra.isEmpty()) {
            payload.put("dirs", extra);
        }
        List<Map<String, String>> imgs = new ArrayList<>();
        for (Map<String, String> img : images == null ? List.<Map<String, String>>of() : images) {
            String data = img == null ? null : img.get("data");
            String mime = img == null ? null : img.get("mimeType");
            if (data == null || data.isBlank() || mime == null || !mime.startsWith("image/")) {
                continue;
            }
            if (data.length() > IMAGE_MAX_CHARS) {
                throw new IllegalArgumentException("图片太大了，换一张小点的截图");
            }
            imgs.add(Map.of("data", data, "mimeType", mime));
        }
        if (imgs.size() > IMAGE_MAX_COUNT) {
            throw new IllegalArgumentException("一次最多 " + IMAGE_MAX_COUNT + " 张图");
        }
        if (!imgs.isEmpty()) {
            payload.put("images", imgs);
        }
        // k8s / ask 会话隔离，避免只读 agent 被拿去写文件
        String agentKey = useMode + ":" + sid;
        String existing = agents.get(agentKey);
        if (existing != null && !existing.isBlank()) {
            payload.put("agentId", existing);
        }

        int waitSec = "k8s".equals(useMode) ? k8sTimeoutSeconds : timeoutSeconds;
        JsonNode result = runBridge(script, payload, waitSec);
        boolean ok = result.path("ok").asBoolean(false);
        String agentId = text(result, "agentId");
        if (agentId != null && !agentId.isBlank()) {
            agents.put(agentKey, agentId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("sessionId", sid);
        out.put("mode", useMode);
        out.put("agentId", agentId == null ? "" : agentId);
        out.put("runId", text(result, "runId"));
        out.put("status", text(result, "status"));
        out.put("answer", text(result, "answer"));
        out.put("model", "Cursor SDK · " + model);
        out.put("cwd", workPath.toAbsolutePath().normalize().toString());
        if (!ok) {
            String err = text(result, "error");
            out.put("error", err == null ? "Cursor Agent 运行失败" : err);
            if (out.get("answer") == null || String.valueOf(out.get("answer")).isBlank()) {
                out.put("answer", "调用失败：" + out.get("error"));
            }
        }
        return out;
    }

    /** 清掉会话对应的 agent，下次提问会新建一轮 */
    public void reset(String sessionId) {
        reset(sessionId, null);
    }

    public void reset(String sessionId, String mode) {
        if (sessionId == null || sessionId.isBlank()) {
            if (mode == null || mode.isBlank()) {
                agents.clear();
            } else {
                String prefix = mode.trim().toLowerCase() + ":";
                agents.keySet().removeIf(k -> k.startsWith(prefix));
            }
            return;
        }
        String sid = sessionId.trim();
        if (mode == null || mode.isBlank()) {
            agents.remove("ask:" + sid);
            agents.remove("k8s:" + sid);
            agents.remove(sid); // 兼容旧键
        } else {
            agents.remove(mode.trim().toLowerCase() + ":" + sid);
        }
    }

    private JsonNode runBridge(Path script, Map<String, Object> payload, int waitSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                nodeBin,
                script.toAbsolutePath().toString());
        pb.directory(script.getParent().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (OutputStreamWriter w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(JSON.writeValueAsString(payload));
            w.flush();
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
        }
        boolean finished = p.waitFor(Math.max(30, waitSec), TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("Cursor Agent 超时（" + waitSec + "s），可在 cursor.ask.*-timeout-seconds 调大");
        }
        String raw = sb.toString().trim();
        // 进程可能往 stdout 混了 npm 噪音，取最后一行 JSON
        String jsonLine = lastJsonLine(raw);
        if (jsonLine == null) {
            throw new IllegalStateException("桥接无输出。exit=" + p.exitValue()
                    + (raw.isBlank() ? "" : ("\n" + raw.substring(0, Math.min(500, raw.length())))));
        }
        try {
            return JSON.readTree(jsonLine);
        } catch (Exception e) {
            throw new IllegalStateException("桥接返回不是 JSON：" + jsonLine, e);
        }
    }

    private static String lastJsonLine(String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<String> lines = raw.lines().toList();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String s = lines.get(i).trim();
            if (s.startsWith("{") && s.endsWith("}")) return s;
        }
        String t = raw.trim();
        return t.startsWith("{") ? t : null;
    }

    private Path resolveScript() {
        Path rel = Path.of(scriptRel);
        if (rel.isAbsolute()) return rel.normalize();
        Path fromCwd = Path.of("").toAbsolutePath().resolve(rel).normalize();
        if (Files.isRegularFile(fromCwd)) return fromCwd;
        // IDEA 工作目录有时在子模块
        Path fromUser = Path.of(System.getProperty("user.dir", ".")).resolve(rel).normalize();
        return fromUser;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s == null || s.isBlank() || "null".equals(s) ? null : s;
    }
}
