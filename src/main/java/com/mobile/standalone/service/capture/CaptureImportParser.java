package com.mobile.standalone.service.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把别的抓包工具导出的文件读成一条条请求/响应。
 * <p>
 * 支持两种：
 * <ul>
 *   <li><b>文本</b>——Charles / Stream 之类「复制请求和响应」得到的那种：
 *       {@code Request} 一行、请求行、请求头、（请求体）、一行横线、{@code Response}、
 *       状态行、响应头、空行、响应体。一个文件里可以连着好几段。</li>
 *   <li><b>HAR</b>——浏览器 / Charles / Proxyman 都能导出，{@code log.entries[]}。</li>
 * </ul>
 */
public final class CaptureImportParser {

    public record Flow(String method, String url, String httpVersion,
                       Map<String, String> reqHeaders, String reqBody,
                       int status, String reason,
                       Map<String, String> respHeaders, String respBody,
                       Long at, Long durationMs) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REQ_LINE = Pattern.compile(
            "^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(\\S+)(?:\\s+(HTTP/[\\d.]+))?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_LINE = Pattern.compile("^(HTTP/[\\d.]+)\\s+(\\d{3})(?:\\s+(.*))?$");
    private static final Pattern DIVIDER = Pattern.compile("^-{5,}\\s*$");
    private static final Pattern HEADER = Pattern.compile("^(:?[A-Za-z0-9!#$%&'*+.^_`|~-]+):\\s?(.*)$");

    private CaptureImportParser() {
    }

    public static List<Flow> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String s = content.startsWith("\uFEFF") ? content.substring(1) : content;
        String head = s.stripLeading();
        if (head.startsWith("{")) {
            try {
                JsonNode root = JSON.readTree(head);
                if (root.has("log")) {
                    return parseHar(root);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("看着像 HAR 但解析不了：" + e.getMessage());
            }
        }
        return parseText(s);
    }

    // ---------- 文本 ----------

    static List<Flow> parseText(String content) {
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (isRequestStart(lines, i)) {
                starts.add(i);
            }
        }
        List<Flow> out = new ArrayList<>();
        for (int k = 0; k < starts.size(); k++) {
            int from = starts.get(k);
            int to = k + 1 < starts.size() ? starts.get(k + 1) : lines.length;
            Flow f = parseTextBlock(lines, from, to);
            if (f != null) {
                out.add(f);
            }
        }
        return out;
    }

    /** 「Request」标题行后面紧跟请求行；没有标题行时请求行本身也算开头 */
    private static boolean isRequestStart(String[] lines, int i) {
        String t = lines[i].trim();
        if ("Request".equalsIgnoreCase(t)) {
            int j = nextNonBlank(lines, i + 1, lines.length);
            return j >= 0 && REQ_LINE.matcher(lines[j].trim()).matches();
        }
        if (REQ_LINE.matcher(t).matches()) {
            int p = prevNonBlank(lines, i - 1);
            return p < 0 || !"Request".equalsIgnoreCase(lines[p].trim());
        }
        return false;
    }

    private static Flow parseTextBlock(String[] lines, int from, int to) {
        int i = nextNonBlank(lines, from, to);
        if (i >= 0 && "Request".equalsIgnoreCase(lines[i].trim())) {
            i = nextNonBlank(lines, i + 1, to);
        }
        if (i < 0) {
            return null;
        }
        Matcher rl = REQ_LINE.matcher(lines[i].trim());
        if (!rl.matches()) {
            return null;
        }
        String method = rl.group(1).toUpperCase(Locale.ROOT);
        String target = rl.group(2);
        String version = rl.group(3) == null ? "HTTP/1.1" : rl.group(3);

        Map<String, String> reqHeaders = new LinkedHashMap<>();
        i = readHeaders(lines, i + 1, to, reqHeaders);

        int respAt = -1;
        int divider = -1;
        for (int j = i; j < to; j++) {
            String t = lines[j].trim();
            if (DIVIDER.matcher(t).matches()) {
                divider = j;
                continue;
            }
            if ("Response".equalsIgnoreCase(t) || STATUS_LINE.matcher(t).matches()) {
                int n = "Response".equalsIgnoreCase(t) ? nextNonBlank(lines, j + 1, to) : j;
                if (n >= 0 && STATUS_LINE.matcher(lines[n].trim()).matches()) {
                    respAt = j;
                    break;
                }
            }
        }
        int reqBodyEnd = divider >= 0 && (respAt < 0 || divider < respAt) ? divider : (respAt < 0 ? to : respAt);
        String reqBody = join(lines, i, reqBodyEnd);

        int status = 0;
        String reason = "";
        Map<String, String> respHeaders = new LinkedHashMap<>();
        String respBody = "";
        if (respAt >= 0) {
            int s = "Response".equalsIgnoreCase(lines[respAt].trim()) ? nextNonBlank(lines, respAt + 1, to) : respAt;
            Matcher sl = STATUS_LINE.matcher(lines[s].trim());
            if (sl.matches()) {
                status = Integer.parseInt(sl.group(2));
                reason = sl.group(3) == null ? "" : sl.group(3).trim();
            }
            int b = readHeaders(lines, s + 1, to, respHeaders);
            respBody = join(lines, b, to);
        }
        String url = absoluteUrl(target, reqHeaders);
        return new Flow(method, url, version, reqHeaders, reqBody, status, reason, respHeaders, respBody, null, null);
    }

    /** 读到第一个空行为止；返回空行之后那一行的下标 */
    private static int readHeaders(String[] lines, int from, int to, Map<String, String> into) {
        int i = from;
        for (; i < to; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                return i + 1;
            }
            Matcher h = HEADER.matcher(line);
            if (!h.matches() || DIVIDER.matcher(line.trim()).matches()) {
                return i;
            }
            into.merge(h.group(1).trim(), h.group(2).trim(), (a, b) -> a + ", " + b);
        }
        return i;
    }

    private static String absoluteUrl(String target, Map<String, String> headers) {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target;
        }
        String host = header(headers, "host");
        if (host == null || host.isBlank()) {
            host = header(headers, ":authority");
        }
        String path = target.startsWith("/") ? target : "/" + target;
        return "https://" + (host == null ? "unknown" : host.trim()) + path;
    }

    // ---------- HAR ----------

    static List<Flow> parseHar(JsonNode root) {
        List<Flow> out = new ArrayList<>();
        for (JsonNode e : root.path("log").path("entries")) {
            JsonNode req = e.path("request");
            JsonNode resp = e.path("response");
            String url = req.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            Map<String, String> reqHeaders = harHeaders(req.path("headers"));
            Map<String, String> respHeaders = harHeaders(resp.path("headers"));
            String reqBody = req.path("postData").path("text").asText("");
            JsonNode content = resp.path("content");
            String respBody = content.path("text").asText("");
            if ("base64".equalsIgnoreCase(content.path("encoding").asText("")) && !respBody.isEmpty()) {
                try {
                    respBody = new String(Base64.getMimeDecoder().decode(respBody), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignore) {
                    // 解不开就原样留着，至少能看出是什么
                }
            }
            Long at = null;
            String started = e.path("startedDateTime").asText("");
            if (!started.isBlank()) {
                try {
                    at = OffsetDateTime.parse(started).toInstant().toEpochMilli();
                } catch (Exception ignore) {
                    // 时间格式不标准就不要了
                }
            }
            Long duration = e.path("time").isNumber() ? Math.round(e.path("time").asDouble()) : null;
            String version = req.path("httpVersion").asText("HTTP/1.1");
            out.add(new Flow(req.path("method").asText("GET").toUpperCase(Locale.ROOT), url,
                    version.isBlank() ? "HTTP/1.1" : version, reqHeaders, reqBody,
                    resp.path("status").asInt(0), resp.path("statusText").asText(""),
                    respHeaders, respBody, at, duration));
        }
        return out;
    }

    /** HTTP/2 的 :authority / :path 这类伪头不要 */
    private static Map<String, String> harHeaders(JsonNode arr) {
        Map<String, String> m = new LinkedHashMap<>();
        for (JsonNode h : arr) {
            String name = h.path("name").asText("");
            if (name.isBlank() || name.startsWith(":")) {
                continue;
            }
            m.merge(name, h.path("value").asText(""), (a, b) -> a + ", " + b);
        }
        return m;
    }

    // ---------- 工具 ----------

    static String header(Map<String, String> headers, String name) {
        for (var en : headers.entrySet()) {
            if (en.getKey().equalsIgnoreCase(name)) {
                return en.getValue();
            }
        }
        return null;
    }

    static URI uri(String url) {
        try {
            return URI.create(url.replace(" ", "%20"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String join(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(from, 0); i < to; i++) {
            if (DIVIDER.matcher(lines[i].trim()).matches()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString().strip();
    }

    private static int nextNonBlank(String[] lines, int from, int to) {
        for (int i = from; i < to && i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static int prevNonBlank(String[] lines, int from) {
        for (int i = from; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }
}
