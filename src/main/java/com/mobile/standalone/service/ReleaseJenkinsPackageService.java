package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.config.ReleaseJenkinsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拉取各项目 prod 大包 Jenkins 最新构建状态（成功号 / 是否在打 / 最近失败）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseJenkinsPackageService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(45);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final ReleaseJenkinsProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Map<String, Object> statusForProject(String project) {
        String job = resolveJob(project);
        if (job == null) {
            return Map.of("configured", false);
        }
        return fetchJob(job);
    }

    /** 批量：project → status（未配置的项目不放进 map） */
    public Map<String, Map<String, Object>> statusForProjects(Iterable<String> projects) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (projects == null || !props.isEnabled()) return out;
        for (String p : projects) {
            if (p == null || p.isBlank()) continue;
            String job = resolveJob(p.trim());
            if (job == null) continue;
            out.put(p.trim(), fetchJob(job));
        }
        return out;
    }

    private String resolveJob(String project) {
        if (!props.isEnabled() || project == null || project.isBlank()) return null;
        Map<String, String> jobs = props.getJobs();
        if (jobs == null || jobs.isEmpty()) return null;
        String direct = jobs.get(project);
        if (direct != null && !direct.isBlank()) return direct.trim();
        for (Map.Entry<String, String> e : jobs.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(project)) {
                return e.getValue() == null ? null : e.getValue().trim();
            }
        }
        return null;
    }

    private Map<String, Object> fetchJob(String job) {
        CacheEntry hit = cache.get(job);
        if (hit != null && Instant.now().isBefore(hit.expireAt)) {
            return hit.payload;
        }
        Map<String, Object> payload = loadJob(job);
        cache.put(job, new CacheEntry(payload, Instant.now().plus(CACHE_TTL)));
        return payload;
    }

    private Map<String, Object> loadJob(String job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", true);
        out.put("job", job);
        // 内网域名 jenkins.shdev.net；勿带 tree=…[…] ，方括号易被编码坏掉导致 HTTP 400
        String jobUrl = jobUrl(job);
        out.put("jobUrl", jobUrl);
        if (blank(props.getUsername()) || blank(props.getPassword())) {
            out.put("ok", false);
            out.put("error", "未配置 Jenkins 账号");
            out.put("summary", "未配置账号");
            return out;
        }
        try {
            JsonNode last = getJson(jobUrl + "lastBuild/api/json");
            JsonNode lastOk = getJson(jobUrl + "lastSuccessfulBuild/api/json");
            fillBuild(out, "last", last, job);
            fillBuild(out, "lastSuccess", lastOk, job);
            boolean building = last != null && last.path("building").asBoolean(false);
            String lastResult = textOrNull(last, "result");
            Integer lastSuccessNum = lastOk != null && lastOk.has("number") ? lastOk.get("number").asInt() : null;
            out.put("building", building);
            out.put("lastResult", lastResult);
            out.put("lastSuccessNumber", lastSuccessNum);
            out.put("ok", lastSuccessNum != null);
            if (building) {
                out.put("summary", "打包中 #" + out.get("lastNumber"));
            } else if ("SUCCESS".equalsIgnoreCase(lastResult) && lastSuccessNum != null) {
                out.put("summary", "打包成功");
            } else if ("FAILURE".equalsIgnoreCase(lastResult) || "UNSTABLE".equalsIgnoreCase(lastResult)
                    || "ABORTED".equalsIgnoreCase(lastResult)) {
                out.put("summary", "最近 #" + out.get("lastNumber") + " " + lastResult
                        + (lastSuccessNum != null ? "（上次成功 #" + lastSuccessNum + "）" : ""));
            } else if (lastSuccessNum != null) {
                out.put("summary", "上次成功 #" + lastSuccessNum);
            } else {
                out.put("summary", "尚无成功大包");
            }
            return out;
        } catch (Exception e) {
            log.warn("[release-jenkins] 拉 {} 失败: {}", job, e.getMessage());
            out.put("ok", false);
            out.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
            out.put("summary", "拉取失败");
            return out;
        }
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", basicAuth(props.getUsername(), props.getPassword()))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Jenkins HTTP " + resp.statusCode() + " @ " + url);
        }
        return objectMapper.readTree(resp.body());
    }

    private void fillBuild(Map<String, Object> out, String prefix, JsonNode node, String job) {
        if (node == null || node.isNull()) return;
        if (node.has("number")) {
            int num = node.get("number").asInt();
            out.put(prefix + "Number", num);
            out.put(prefix + "Url", buildUrl(job, num));
        }
        if (node.has("timestamp") && !node.get("timestamp").isNull()) {
            long ts = node.get("timestamp").asLong();
            out.put(prefix + "Time", TIME_FMT.format(Instant.ofEpochMilli(ts)));
        }
        String result = textOrNull(node, "result");
        if (result != null) {
            out.put(prefix + "Result", result);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    private String jobUrl(String job) {
        return trimSlash(props.getBaseUrl()) + "/job/" + job + "/";
    }

    private String buildUrl(String job, int number) {
        return jobUrl(job) + number + "/";
    }

    private static String basicAuth(String user, String pass) {
        String raw = user + ":" + pass;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private record CacheEntry(Map<String, Object> payload, Instant expireAt) {
    }
}
