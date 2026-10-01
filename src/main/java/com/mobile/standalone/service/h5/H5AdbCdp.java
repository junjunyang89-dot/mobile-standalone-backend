package com.mobile.standalone.service.h5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 不靠 Chromedriver：自己 {@code adb forward} 设备上的 {@code *devtools*} 抽象套接字，再读 {@code /json}。
 */
@Slf4j
public final class H5AdbCdp {

    private static final ObjectMapper JSON = new ObjectMapper();

    private H5AdbCdp() {
    }

    public static final class Session implements AutoCloseable {
        private final String wsUrl;
        private final String pageUrl;
        private final List<Integer> ports;
        private final String deviceId;

        Session(String deviceId, String wsUrl, String pageUrl, List<Integer> ports) {
            this.deviceId = deviceId;
            this.wsUrl = wsUrl;
            this.pageUrl = pageUrl;
            this.ports = ports;
        }

        public String wsUrl() {
            return wsUrl;
        }

        public String pageUrl() {
            return pageUrl;
        }

        @Override
        public void close() {
            for (Integer port : ports) {
                try {
                    new ProcessBuilder("adb", "-s", deviceId, "forward", "--remove", "tcp:" + port)
                            .redirectErrorStream(true)
                            .start()
                            .waitFor();
                } catch (Exception ignored) {
                    // 转发残留不挡下次
                }
            }
        }
    }

    public static Session open(String deviceId, String wantUrl) throws Exception {
        String unix = adbOut(deviceId, "shell", "cat", "/proc/net/unix");
        List<String> sockets = parseAbstractSockets(unix);
        if (sockets.isEmpty()) {
            throw new IllegalStateException("设备上没有 *devtools* 套接字。WebView 调试没开，或进程还没起来");
        }
        List<Integer> forwarded = new ArrayList<>();
        Exception last = null;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        for (String sock : sockets) {
            int port = 0;
            try {
                port = freePort();
                String fwd = adbOut(deviceId, "forward", "tcp:" + port, "localabstract:" + sock);
                log.info("H5 adb forward {} -> {} ({})", port, sock, fwd.trim());
                forwarded.add(port);
                HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2 || resp.body() == null || resp.body().isBlank()) {
                    continue;
                }
                JsonNode arr = JSON.readTree(resp.body());
                Hit hit = pickJsonPage(arr, wantUrl, port);
                if (hit != null) {
                    log.info("H5 CDP via adb: sock={} port={} url={}", sock, port, hit.pageUrl);
                    return new Session(deviceId, hit.wsUrl, hit.pageUrl, forwarded);
                }
            } catch (Exception e) {
                last = e;
                log.debug("devtools {} 不可用: {}", sock, e.getMessage());
            }
        }
        for (Integer p : forwarded) {
            try {
                adbOut(deviceId, "forward", "--remove", "tcp:" + p);
            } catch (Exception ignored) {
                // ignore
            }
        }
        throw last != null
                ? new IllegalStateException("adb 已看到调试套接字，但 /json 对不上当前页: " + last.getMessage(), last)
                : new IllegalStateException("adb 调试套接字上没有可用的 H5 page");
    }

    record Hit(String wsUrl, String pageUrl) {
    }

    static List<String> parseAbstractSockets(String unixTable) {
        Set<String> names = new LinkedHashSet<>();
        if (unixTable == null) {
            return List.of();
        }
        for (String line : unixTable.split("\n")) {
            int at = line.lastIndexOf('@');
            if (at < 0) {
                continue;
            }
            String name = line.substring(at + 1).trim();
            if (name.isEmpty()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("devtools") || lower.contains("webview_devtools")
                    || lower.contains("xweb") || lower.contains("x5")) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    static Hit pickJsonPage(JsonNode arr, String wantUrl, int localPort) {
        if (arr == null || !arr.isArray()) {
            return null;
        }
        Hit exact = null;
        Hit visible = null;
        Hit hash = null;
        Hit any = null;
        for (JsonNode n : arr) {
            String type = n.path("type").asText("page");
            if (!"page".equals(type) && !"iframe".equals(type)) {
                continue;
            }
            String url = n.path("url").asText("");
            String ws = n.path("webSocketDebuggerUrl").asText("");
            if (ws.isBlank() || url.isBlank() || "about:blank".equalsIgnoreCase(url)) {
                continue;
            }
            String retargeted = H5CdpWs.retarget(H5CdpWs.rewriteLoopback(ws), "127.0.0.1", localPort);
            Hit hit = new Hit(retargeted, url);
            if (any == null) {
                any = hit;
            }
            if (url.contains("#") && hash == null) {
                hash = hit;
            }
            String desc = n.path("description").asText("");
            if (desc.contains("\"visible\":true") && visible == null) {
                visible = hit;
            }
            if (wantUrl != null && !wantUrl.isBlank() && urlsClose(url, wantUrl)) {
                exact = hit;
            }
        }
        return exact != null ? exact : (visible != null ? visible : (hash != null ? hash : any));
    }

    static boolean urlsClose(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        return stripHash(a).equals(stripHash(b)) && !stripHash(a).isEmpty();
    }

    private static String stripHash(String url) {
        int i = url.indexOf('#');
        return i < 0 ? url : url.substring(0, i);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String adbOut(String deviceId, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("adb");
        cmd.add("-s");
        cmd.add(deviceId);
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        boolean done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes());
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("adb 超时: " + cmd);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("adb 失败: " + out.trim());
        }
        return out;
    }
}
