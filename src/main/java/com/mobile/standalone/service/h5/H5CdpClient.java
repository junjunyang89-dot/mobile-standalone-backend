package com.mobile.standalone.service.h5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简 CDP：只跑 {@code Runtime.evaluate}，用来在 X5 页里找节点（不依赖 Chromedriver）。
 */
@Slf4j
public final class H5CdpClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private H5CdpClient() {
    }

    public static JsonNode evaluate(String wsUrl, String expression, Duration timeout) throws Exception {
        if (wsUrl == null || wsUrl.isBlank()) {
            throw new IllegalArgumentException("缺少 webSocketDebuggerUrl");
        }
        String target = H5CdpWs.rewriteLoopback(wsUrl);
        HttpClient http = HttpClient.newHttpClient();
        Listener listener = new Listener();
        WebSocket ws = http.newWebSocketBuilder()
                .connectTimeout(timeout)
                .buildAsync(URI.create(target), listener)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            send(ws, listener.nextId(), "Runtime.enable", null);
            int id = listener.nextId();
            ObjectNode params = JSON.createObjectNode();
            params.put("expression", expression);
            params.put("returnByValue", true);
            params.put("awaitPromise", true);
            CompletableFuture<JsonNode> fut = listener.expect(id);
            send(ws, id, "Runtime.evaluate", params);
            JsonNode resp = fut.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            JsonNode err = resp.path("error");
            if (!err.isMissingNode() && !err.isNull()) {
                throw new IllegalStateException("CDP error: " + err);
            }
            JsonNode ex = resp.path("result").path("exceptionDetails");
            if (!ex.isMissingNode() && !ex.isNull() && ex.size() > 0) {
                throw new IllegalStateException("H5 脚本异常: " + ex.path("text").asText());
            }
            return resp.path("result").path("result").path("value");
        } finally {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ws.abort();
            }
        }
    }

    private static void send(WebSocket ws, int id, String method, JsonNode params) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        ws.sendText(msg.toString(), true);
    }

    private static final class Listener implements WebSocket.Listener {
        private final AtomicInteger ids = new AtomicInteger(1);
        private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final StringBuilder buf = new StringBuilder();

        int nextId() {
            return ids.getAndIncrement();
        }

        CompletableFuture<JsonNode> expect(int id) {
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            pending.put(id, f);
            return f;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String raw = buf.toString();
                buf.setLength(0);
                try {
                    JsonNode n = JSON.readTree(raw);
                    if (n.has("id")) {
                        CompletableFuture<JsonNode> f = pending.remove(n.get("id").asInt());
                        if (f != null) {
                            f.complete(n);
                        }
                    }
                } catch (Exception e) {
                    log.debug("CDP 帧解析失败: {}", e.getMessage());
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            pending.values().forEach(f -> f.completeExceptionally(error));
        }
    }
}
