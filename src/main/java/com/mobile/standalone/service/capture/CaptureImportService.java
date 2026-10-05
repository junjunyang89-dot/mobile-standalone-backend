package com.mobile.standalone.service.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 导入别的工具抓的包（文本 / HAR），落成一场「已结束的抓包」。
 * <p>
 * 落盘格式和 mitmdump 写的完全一样（flows.jsonl + .details/N.json + .hosts），
 * 这样历史记录、详情、收录、AI 生成用例整条链路都不用改，打开它就跟打开一场历史抓包一样。
 * 额外多一个 {@code .env} 文件记这份包是哪个环境的——生产的包只给 GET 生成用例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptureImportService {

    public static final String PLATFORM = "IMPORT";

    /** 鉴权相关的头落盘前打码：导入的多半是生产包，token 是真的 */
    private static final Set<String> SECRET_HEADERS = Set.of(
            "token", "authorization", "cookie", "set-cookie", "x-access-token", "x-auth-token",
            "accesstoken", "access-token", "proxy-authorization");
    private static final String MASK = "***";

    private final CaptureStore captureStore;
    private final ObjectMapper json = new ObjectMapper();

    public record Result(String sessionId, int flows, int hosts, String env) {
    }

    public Result importContent(String content, String fileName, String env) throws IOException {
        List<CaptureImportParser.Flow> flows = CaptureImportParser.parse(content);
        if (flows.isEmpty()) {
            throw new IllegalArgumentException(
                    "文件里没认出请求。支持两种：HAR，或「Request / 请求行 / 请求头 / ---- / Response / 状态行 / 响应头 / 响应体」这种文本");
        }
        String envValue = TestEnvironment.PROD.equalsIgnoreCase(env) ? TestEnvironment.PROD : TestEnvironment.TEST;
        String sessionId = "imp-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        Path out = captureStore.flowFileOf(sessionId);
        Path details = Path.of(out.toAbsolutePath() + ".details");
        Files.createDirectories(details);

        Map<String, Integer> seen = new LinkedHashMap<>();
        StringBuilder rows = new StringBuilder();
        int seq = 0;
        long now = System.currentTimeMillis();
        for (CaptureImportParser.Flow f : flows) {
            URI u = CaptureImportParser.uri(f.url());
            if (u == null || u.getHost() == null) {
                continue;
            }
            seq++;
            String host = u.getHost();
            seen.merge(host, 1, Integer::sum);
            Map<String, Object> row = row(seq, f, u, now);
            rows.append(json.writeValueAsString(row)).append('\n');
            Files.writeString(details.resolve(seq + ".json"), json.writeValueAsString(detail(f)),
                    StandardCharsets.UTF_8);
        }
        if (seq == 0) {
            throw new IllegalArgumentException("文件里的请求都没有完整的地址（缺域名），导入不了");
        }
        Files.writeString(out, rows.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of(out.toAbsolutePath() + ".hosts"), json.writeValueAsString(seen),
                StandardCharsets.UTF_8);
        Files.writeString(Path.of(out.toAbsolutePath() + ".env"), envValue, StandardCharsets.UTF_8);

        String hosts = cut(String.join(",", seen.keySet()), 1000);
        captureStore.open(sessionId, fileName == null || fileName.isBlank() ? null : cut(fileName.trim(), 120),
                PLATFORM, null, null, hosts, captureStore.dirOf(sessionId));
        captureStore.close(sessionId, seq, seen.size());
        log.info("[capture] 导入 {}：{} 条请求，{} 个域名，环境 {}", sessionId, seq, seen.size(), envValue);
        return new Result(sessionId, seq, seen.size(), envValue);
    }

    private static Map<String, Object> row(int seq, CaptureImportParser.Flow f, URI u, long now) {
        String path = u.getRawPath() == null || u.getRawPath().isBlank() ? "/" : u.getRawPath();
        String respType = nz(CaptureImportParser.header(f.respHeaders(), "content-type"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", seq);
        row.put("method", f.method());
        row.put("host", u.getHost());
        row.put("path", path);
        row.put("query", u.getRawQuery() == null ? "" : u.getRawQuery());
        row.put("url", f.url());
        row.put("scheme", u.getScheme() == null ? "https" : u.getScheme());
        row.put("httpVersion", f.httpVersion());
        row.put("remote", "");
        row.put("status", f.status());
        row.put("reason", nz(f.reason()));
        row.put("durationMs", f.durationMs());
        row.put("at", f.at() == null ? now : f.at());
        row.put("traceId", traceOf(f));
        row.put("reqContentType", nz(CaptureImportParser.header(f.reqHeaders(), "content-type")));
        row.put("respContentType", respType);
        row.put("reqBytes", bytes(f.reqBody()));
        row.put("respBytes", bytes(f.respBody()));
        row.put("respKind", respType.split(";")[0].trim());
        row.put("respTruncated", false);
        row.put("bodyNote", "");
        row.put("hasDetail", true);
        row.put("imported", true);
        return row;
    }

    private static Map<String, Object> detail(CaptureImportParser.Flow f) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reqHeaders", masked(f.reqHeaders()));
        d.put("reqCookies", Map.of());
        d.put("reqBody", nz(f.reqBody()));
        d.put("respHeaders", masked(f.respHeaders()));
        d.put("respCookies", Map.of());
        d.put("respBody", nz(f.respBody()));
        d.put("bodyNote", "");
        return d;
    }

    static Map<String, String> masked(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, SECRET_HEADERS.contains(k.toLowerCase(Locale.ROOT)) ? MASK : v));
        return out;
    }

    private static String traceOf(CaptureImportParser.Flow f) {
        for (String key : List.of("pwrd-request-id", "x-request-id", "traceid", "x-trace-id")) {
            String v = CaptureImportParser.header(f.respHeaders(), key);
            if (v == null) {
                v = CaptureImportParser.header(f.reqHeaders(), key);
            }
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static int bytes(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
