package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.TrackingCheckDtos.MatchResult;
import com.mobile.standalone.dto.TrackingCheckDtos.VerifyRequest;
import com.mobile.standalone.dto.TrackingCheckDtos.VerifyResult;
import com.mobile.standalone.dto.TrackingCheckDtos.WikiTrackingRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 埋点 Wiki 表 vs Kibana 日志校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingCheckService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PAGE_ID = Pattern.compile("(?:pageId=|/pages/|/content/)(\\d+)");

    private final ReleaseDocProperties wikiProps;
    private final HttpClient http = buildTrustAllHttpClient();

    public VerifyResult verify(VerifyRequest req) {
        VerifyResult out = new VerifyResult();
        if (req == null) throw new IllegalArgumentException("请求为空");
        if (req.getWikiUrl() == null || req.getWikiUrl().isBlank()) {
            throw new IllegalArgumentException("请填写埋点 Wiki URL");
        }
        if (req.getVersion() == null || req.getVersion().isBlank()) {
            throw new IllegalArgumentException("请填写版本号（如 v4.9）");
        }
        if (req.getKibanaJson() == null || req.getKibanaJson().isBlank()) {
            throw new IllegalArgumentException("请上传或粘贴 Kibana JSON 数据");
        }
        requireWikiCreds();

        String version = normalizeVersion(req.getVersion());
        String elementFilter = trimToNull(req.getElementName());
        out.setVersion(version);
        out.setElementNameFilter(elementFilter);

        String pageId = extractPageId(req.getWikiUrl());
        out.setWikiPageId(pageId);
        logStep(out, "读取 Wiki pageId=" + pageId);

        JsonNode page = fetchWikiContent(pageId);
        out.setWikiTitle(page.path("title").asText(""));
        String storage = page.path("body").path("storage").path("value").asText("");
        if (storage.isBlank()) {
            storage = page.path("body").path("view").path("value").asText("");
        }
        if (storage.isBlank()) {
            throw new IllegalStateException("Wiki 页面正文为空");
        }

        List<WikiTrackingRow> allRows = parseTrackingTables(storage);
        logStep(out, "Wiki 共解析埋点行 " + allRows.size());

        List<WikiTrackingRow> expected = filterRows(allRows, version, elementFilter);
        out.setWikiRowCount(expected.size());
        if (expected.isEmpty()) {
            throw new IllegalStateException("在 Wiki 中未找到版本「" + version + "」"
                    + (elementFilter != null ? " 且 element_name=「" + elementFilter + "」" : "")
                    + " 的埋点行，请检查版本号/名称是否与表格一致");
        }
        logStep(out, "待校验埋点 " + expected.size() + " 条");

        List<Map<String, Object>> kibanaLogs = parseKibanaJson(req.getKibanaJson());
        out.setKibanaLogCount(kibanaLogs.size());
        logStep(out, "Kibana 日志解析 " + kibanaLogs.size() + " 条");
        out.setKibanaLogCountAfterOsFilter(kibanaLogs.size());

        int found = 0;
        int missing = 0;
        for (WikiTrackingRow row : expected) {
            MatchResult mr = matchOne(row, kibanaLogs);
            out.getResults().add(mr);
            if ("found".equals(mr.getStatus()) || "partial".equals(mr.getStatus())) {
                found++;
            } else {
                missing++;
            }
        }
        out.setFoundCount(found);
        out.setMissingCount(missing);
        out.setMessage(missing == 0
                ? "全部命中：Wiki " + expected.size() + " 条埋点均在 Kibana 日志中找到"
                : "校验完成：命中 " + found + "，缺失 " + missing);
        return out;
    }

    MatchResult matchOne(WikiTrackingRow row, List<Map<String, Object>> kibanaLogs) {
        MatchResult mr = new MatchResult();
        mr.setExpected(row);
        List<Map<String, Object>> hits = new ArrayList<>();
        LinkedHashSet<String> osSet = new LinkedHashSet<>();
        for (Map<String, Object> item : kibanaLogs) {
            if (logMatchesRow(item, row)) {
                hits.add(item);
                String cat = categorizeOs(extractOs(item));
                if (!"Other".equals(cat)) {
                    osSet.add(cat);
                } else {
                    String raw = extractOs(item);
                    if (raw != null && !raw.isBlank()) osSet.add(raw);
                }
            }
        }
        mr.setMatchedLogs(hits);
        mr.setMatchedOs(new ArrayList<>(osSet));

        List<String> valueKeys = extractValueKeys(row.getValue());
        if (!hits.isEmpty() && !valueKeys.isEmpty()) {
            for (String key : valueKeys) {
                boolean anyHas = hits.stream().anyMatch(h -> propertyHasKey(h, key));
                mr.getValueChecks().add(key + (anyHas ? ": 已出现" : ": 未在命中日志 properties 中出现"));
            }
        }

        if (hits.isEmpty()) {
            mr.setStatus("missing");
            mr.setMessage("未在 Kibana 数据中找到 element_id=" + row.getElementId()
                    + " / element_name=" + row.getElementName());
        } else if (!valueKeys.isEmpty() && mr.getValueChecks().stream().anyMatch(s -> s.contains("未在"))) {
            mr.setStatus("partial");
            mr.setMessage("已找到 " + hits.size() + " 条日志（"
                    + String.join("/", osSet.isEmpty() ? List.of("未知OS") : osSet)
                    + "），但 value 约定字段未齐全");
        } else {
            mr.setStatus("found");
            mr.setMessage("已找到 " + hits.size() + " 条匹配日志（"
                    + String.join("/", osSet.isEmpty() ? List.of("未知OS") : osSet) + "）");
        }
        return mr;
    }

    @SuppressWarnings("unchecked")
    boolean logMatchesRow(Map<String, Object> item, WikiTrackingRow row) {
        Map<String, Object> props = extractProperties(item);
        if (props == null || props.isEmpty()) return false;
        String id = str(props.get("element_id"));
        String name = str(props.get("element_name"));
        String cls = str(props.get("element_class_name"));
        if (!eqLoose(id, row.getElementId())) return false;
        if (!eqLoose(name, row.getElementName())) return false;
        // class_name：两边都有值时才强制相等
        if (notBlank(row.getElementClassName()) && notBlank(cls)
                && !eqLoose(cls, row.getElementClassName())) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractProperties(Map<String, Object> item) {
        Object logObj = item.get("log");
        Map<String, Object> event = null;
        if (logObj instanceof Map<?, ?> m) {
            event = (Map<String, Object>) m;
        } else if (logObj instanceof String s && !s.isBlank()) {
            try {
                event = JSON.readValue(s, Map.class);
            } catch (Exception ignored) {
                return Map.of();
            }
        } else if (item.get("properties") instanceof Map<?, ?>) {
            return (Map<String, Object>) item.get("properties");
        } else if (item.containsKey("element_name") || item.containsKey("element_id")) {
            return item;
        }
        if (event == null) return Map.of();
        Object props = event.get("properties");
        if (props instanceof Map<?, ?> pm) {
            return (Map<String, Object>) pm;
        }
        return Map.of();
    }

    private boolean propertyHasKey(Map<String, Object> item, String key) {
        Map<String, Object> props = extractProperties(item);
        if (props.containsKey(key) && props.get(key) != null && !str(props.get(key)).isBlank()) {
            return true;
        }
        // 有些会把 value 平铺
        return item.containsKey(key) && item.get(key) != null && !str(item.get(key)).isBlank();
    }

    /** 从 `{tab_name: ...}` 这类 value 说明中抽属性名 */
    static List<String> extractValueKeys(String value) {
        if (value == null || value.isBlank()) return List.of();
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("([a-zA-Z_][\\w]*)\\s*[:：]").matcher(value);
        while (m.find()) {
            String k = m.group(1);
            if (!k.equalsIgnoreCase("http") && !k.equalsIgnoreCase("https")) {
                keys.add(k);
            }
        }
        return new ArrayList<>(keys);
    }

    List<WikiTrackingRow> parseTrackingTables(String storageHtml) {
        List<WikiTrackingRow> rows = new ArrayList<>();
        Document doc = Jsoup.parseBodyFragment(storageHtml);
        String currentSection = "";
        for (Element el : doc.body().children()) {
            if (el.tagName().matches("h[1-6]")) {
                currentSection = el.text().trim();
            }
        }
        // 遍历所有节点，维护最近标题
        currentSection = "";
        for (Element el : doc.getAllElements()) {
            if (el.tagName().matches("h[1-6]")) {
                currentSection = el.text().trim();
                continue;
            }
            if (!"table".equalsIgnoreCase(el.tagName())) continue;
            Elements trs = el.select("tr");
            if (trs.isEmpty()) continue;
            Element headerRow = trs.first();
            List<String> headers = new ArrayList<>();
            for (Element cell : headerRow.select("th,td")) {
                headers.add(normalizeHeader(cell.text()));
            }
            int idxId = indexOfHeader(headers, "element_id");
            int idxName = indexOfHeader(headers, "element_name");
            int idxClass = indexOfHeader(headers, "element_class_name");
            int idxValue = indexOfHeader(headers, "value");
            int idxVer = indexOfHeader(headers, "版本", "version");
            int idxRemark = indexOfHeader(headers, "备注", "remark");
            if (idxId < 0 || idxName < 0) continue;

            String section = currentSection;
            // 若表格前有 h3，优先用
            Element prev = el.previousElementSibling();
            while (prev != null && prev.text().isBlank()) prev = prev.previousElementSibling();
            if (prev != null && prev.tagName().matches("h[1-6]")) {
                section = prev.text().trim();
            }

            for (int i = 1; i < trs.size(); i++) {
                Elements cells = trs.get(i).select("th,td");
                if (cells.isEmpty()) continue;
                String id = cellText(cells, idxId);
                String name = cellText(cells, idxName);
                if (id.isBlank() && name.isBlank()) continue;
                WikiTrackingRow row = new WikiTrackingRow();
                row.setElementId(id);
                row.setElementName(name);
                row.setElementClassName(cellText(cells, idxClass));
                row.setValue(cellText(cells, idxValue));
                row.setVersion(normalizeVersion(cellText(cells, idxVer)));
                row.setRemarks(cellText(cells, idxRemark));
                row.setSectionTitle(section);
                rows.add(row);
            }
        }
        return rows;
    }

    private static String cellText(Elements cells, int idx) {
        if (idx < 0 || idx >= cells.size()) return "";
        return cells.get(idx).text().replace('\u00a0', ' ').trim();
    }

    private static String normalizeHeader(String h) {
        if (h == null) return "";
        return h.replace('\u00a0', ' ').trim().toLowerCase(Locale.ROOT);
    }

    private static int indexOfHeader(List<String> headers, String... names) {
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            for (String n : names) {
                if (h.equals(n.toLowerCase(Locale.ROOT)) || h.contains(n.toLowerCase(Locale.ROOT))) {
                    return i;
                }
            }
        }
        return -1;
    }

    static List<WikiTrackingRow> filterRows(List<WikiTrackingRow> all, String version, String elementName) {
        List<WikiTrackingRow> out = new ArrayList<>();
        String wantVer = normalizeVersion(version);
        for (WikiTrackingRow r : all) {
            if (!eqLoose(normalizeVersion(r.getVersion()), wantVer)) continue;
            if (elementName != null && !elementName.isBlank()
                    && !eqLoose(r.getElementName(), elementName)) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    /**
     * 支持：单对象 / 数组 / NDJSON / Kibana hits / {responses:[{hits}]}。
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parseKibanaJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isBlank()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();

        // NDJSON
        if (text.contains("\n") && !text.trim().startsWith("[")) {
            boolean ndjson = true;
            List<Map<String, Object>> lines = new ArrayList<>();
            for (String line : text.split("\\R")) {
                String l = line.trim();
                if (l.isBlank()) continue;
                if (!(l.startsWith("{") || l.startsWith("["))) {
                    ndjson = false;
                    break;
                }
                try {
                    Object o = JSON.readValue(l, Object.class);
                    collectLogs(o, lines);
                } catch (Exception e) {
                    ndjson = false;
                    break;
                }
            }
            if (ndjson && !lines.isEmpty()) return lines;
        }

        try {
            Object root = JSON.readValue(text, Object.class);
            collectLogs(root, out);
        } catch (Exception e) {
            throw new IllegalArgumentException("Kibana JSON 解析失败: " + e.getMessage(), e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void collectLogs(Object node, List<Map<String, Object>> out) {
        if (node == null) return;
        if (node instanceof List<?> list) {
            for (Object o : list) collectLogs(o, out);
            return;
        }
        if (!(node instanceof Map<?, ?> mapRaw)) return;
        Map<String, Object> map = (Map<String, Object>) mapRaw;

        // Kibana Discover export: hits.hits[]._source
        if (map.get("hits") instanceof Map<?, ?> hitsMap) {
            Object inner = ((Map<?, ?>) hitsMap).get("hits");
            if (inner instanceof List<?> hl) {
                for (Object h : hl) {
                    if (h instanceof Map<?, ?> hm && ((Map<?, ?>) hm).get("_source") != null) {
                        collectLogs(((Map<?, ?>) hm).get("_source"), out);
                    } else {
                        collectLogs(h, out);
                    }
                }
                return;
            }
        }
        if (map.get("responses") instanceof List<?> responses) {
            for (Object r : responses) collectLogs(r, out);
            return;
        }

        // Kibana _source.message = "{\"ip\":\"...\",\"log\":\"{...}\",\"timestamp\":...}"
        if (map.get("message") instanceof String msg && msg.trim().startsWith("{")) {
            try {
                Object parsed = JSON.readValue(msg, Object.class);
                collectLogs(parsed, out);
                return;
            } catch (Exception ignored) {
                // fall through
            }
        }

        // 标准 {ip,log,timestamp}
        if (map.containsKey("log") || map.containsKey("properties")
                || map.containsKey("element_name") || map.containsKey("event_name")) {
            out.add(normalizeLogEntry(new LinkedHashMap<>(map)));
        }
    }

    /**
     * 统一成 {ip, log(对象或可解析字符串), timestamp, properties...}，方便匹配与前端展示。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeLogEntry(Map<String, Object> map) {
        Map<String, Object> out = new LinkedHashMap<>(map);
        Object logObj = out.get("log");
        if (logObj instanceof String s && s.trim().startsWith("{")) {
            try {
                Object parsed = JSON.readValue(s, Object.class);
                if (parsed instanceof Map<?, ?> pm) {
                    out.put("log", parsed);
                    Object props = ((Map<?, ?>) pm).get("properties");
                    if (props instanceof Map<?, ?> && !out.containsKey("properties")) {
                        out.put("properties", props);
                    }
                }
            } catch (Exception ignored) {
                // keep raw string
            }
        }
        return out;
    }

    private JsonNode fetchWikiContent(String pageId) {
        try {
            String url = trimSlash(wikiProps.getWikiBaseUrl()) + "/rest/api/content/" + pageId
                    + "?expand=" + URLEncoder.encode("body.storage,body.view", StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", basicAuth())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("读取 Wiki 失败 HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 300));
            }
            return JSON.readTree(resp.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取 Wiki 失败: " + e.getMessage(), e);
        }
    }

    static String extractPageId(String wikiUrl) {
        Matcher m = PAGE_ID.matcher(wikiUrl);
        if (m.find()) return m.group(1);
        throw new IllegalArgumentException("无法从 Wiki URL 解析 pageId: " + wikiUrl);
    }

    private void requireWikiCreds() {
        if (wikiProps.getUsername() == null || wikiProps.getUsername().isBlank()
                || wikiProps.getPassword() == null || wikiProps.getPassword().isBlank()) {
            throw new IllegalStateException("未配置 Wiki 账号（release-doc.username/password）");
        }
    }

    private String basicAuth() {
        String raw = wikiProps.getUsername() + ":" + wikiProps.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpClient buildTrustAllHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ssl = SSLContext.getInstance("TLSv1.2");
            ssl.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(ssl)
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 TrackingCheck HttpClient 失败: " + e.getMessage(), e);
        }
    }

    private static void logStep(VerifyResult out, String msg) {
        out.getLogs().add(msg);
        log.info("[tracking-check] {}", msg);
    }

    static String normalizeVersion(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.isEmpty()) return "";
        if (!t.toLowerCase(Locale.ROOT).startsWith("v") && t.matches("\\d+(\\.\\d+)*")) {
            return "v" + t;
        }
        return t;
    }

    /** 归一化为 iOS / Android / H5；无法识别则原样 trim，空返回 null */
    static String normalizeOsFilter(String os) {
        if (os == null || os.isBlank()) return null;
        String t = os.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("ios") || lower.equals("iphone") || lower.equals("ipad")) return "iOS";
        if (lower.equals("android")) return "Android";
        if (lower.equals("h5") || lower.equals("web") || lower.equals("html5") || lower.equals("browser")) {
            return "H5";
        }
        // 用户可能直接选中文/英文选项
        if (t.equals("iOS") || t.equals("Android") || t.equals("H5")) return t;
        return t;
    }

    /** 日志 os 是否匹配所选设备端 */
    static boolean osMatches(Map<String, Object> item, String osFilter) {
        if (osFilter == null || osFilter.isBlank()) return true;
        String os = extractOs(item);
        String want = normalizeOsFilter(osFilter);
        if (want == null) return true;
        String cat = categorizeOs(os);
        return want.equalsIgnoreCase(cat);
    }

    @SuppressWarnings("unchecked")
    static String extractOs(Map<String, Object> item) {
        if (item == null) return "";
        Object direct = item.get("os");
        if (direct != null && !str(direct).isBlank()) return str(direct);
        Object logObj = item.get("log");
        if (logObj instanceof Map<?, ?> m) {
            Object o = m.get("os");
            if (o != null) return str(o);
        }
        return "";
    }

    /** 将日志里的 os 归类为 iOS / Android / H5 / Other */
    static String categorizeOs(String os) {
        if (os == null || os.isBlank()) return "Other";
        String lower = os.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("ios") || lower.contains("iphone") || lower.contains("ipad")) return "iOS";
        if (lower.contains("android")) return "Android";
        if (lower.contains("h5") || lower.contains("web") || lower.contains("html5")
                || lower.contains("browser") || lower.equals("pc") || lower.contains("javascript")) {
            return "H5";
        }
        return "Other";
    }

    private static boolean eqLoose(String a, String b) {
        return normalizeKey(a).equals(normalizeKey(b));
    }

    private static String normalizeKey(String s) {
        if (s == null) return "";
        return s.replace('\u00a0', ' ').trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
