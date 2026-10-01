package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiUrlEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 业务状态监控：对 URL 管理里登记的 baseUrl + 健康路径发 GET。
 * 只允许探活已登记的 urlId，避免变成内网扫描器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatusMonitorProbeService {

    private static final int CONNECT_MS = 3000;
    private static final int READ_MS = 5000;
    /** 超过该延迟仍 2xx，标为降级 */
    private static final int DEGRADED_MS = 1000;

    private final ApiUrlService urlService;

    public List<Map<String, Object>> probe(List<Map<String, Object>> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, targets.size()));
        try {
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (Map<String, Object> t : targets) {
                futures.add(CompletableFuture.supplyAsync(() -> probeOne(t), pool));
            }
            List<Map<String, Object>> out = new ArrayList<>(futures.size());
            for (CompletableFuture<Map<String, Object>> f : futures) {
                out.add(f.join());
            }
            return out;
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private Map<String, Object> probeOne(Map<String, Object> t) {
        String id = str(t.get("id"));
        String urlId = str(t.get("urlId"));
        String healthPath = str(t.get("healthPath"));
        if (healthPath.isBlank()) {
            healthPath = "/basic/healthCheck";
        }
        if (!healthPath.startsWith("/")) {
            healthPath = "/" + healthPath;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("urlId", urlId);
        row.put("checkedAt", java.time.Instant.now().toString());

        if (urlId.isBlank()) {
            fail(row, "down", null, "缺少 urlId");
            return row;
        }
        ApiUrlEntry entry = urlService.get(urlId);
        if (entry == null || entry.getBaseUrl() == null || entry.getBaseUrl().isBlank()) {
            fail(row, "down", null, "URL 不存在或已删除: " + urlId);
            return row;
        }
        if (Boolean.FALSE.equals(entry.getEnabled())) {
            fail(row, "down", null, "URL 已停用: " + urlId);
            return row;
        }

        String base = ApiTestCaseService.trimSlash(entry.getBaseUrl().trim());
        String probeUrl = base + healthPath;
        row.put("probeUrl", probeUrl);
        row.put("baseUrl", base);
        row.put("healthPath", healthPath);

        long start = System.nanoTime();
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(probeUrl).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("User-Agent", "mobile-standalone-status-monitor/1.0");

            int code = conn.getResponseCode();
            int latency = (int) ((System.nanoTime() - start) / 1_000_000L);
            // 读一点 body 方便排错，但不强制解析
            String snippet = readSnippet(conn, code);

            row.put("httpStatus", code);
            row.put("latencyMs", latency);
            if (code >= 200 && code < 400) {
                if (latency >= DEGRADED_MS) {
                    row.put("status", "degraded");
                    row.put("error", "响应偏慢 " + latency + "ms");
                } else {
                    row.put("status", "online");
                    row.put("error", null);
                }
            } else {
                row.put("status", "down");
                row.put("error", "HTTP " + code + (snippet.isBlank() ? "" : (" · " + snippet)));
            }
        } catch (java.net.SocketTimeoutException e) {
            int latency = (int) ((System.nanoTime() - start) / 1_000_000L);
            fail(row, "timeout", latency, "连接/读取超时");
        } catch (Exception e) {
            int latency = (int) ((System.nanoTime() - start) / 1_000_000L);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String status = msg.toLowerCase().contains("timed out") ? "timeout" : "down";
            fail(row, status, latency > 0 ? latency : null, msg);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return row;
    }

    private static void fail(Map<String, Object> row, String status, Integer latencyMs, String error) {
        row.put("status", status);
        row.put("latencyMs", latencyMs);
        row.put("httpStatus", null);
        row.put("error", error);
    }

    private static String readSnippet(HttpURLConnection conn, int code) {
        try {
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "";
            byte[] buf = in.readNBytes(200);
            String s = new String(buf, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            return s.length() > 120 ? s.substring(0, 120) + "…" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
