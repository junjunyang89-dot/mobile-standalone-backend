package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.config.ReleaseDocProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把线上问题跟踪追加到固定 Wiki 页的表格末尾（不覆盖已有行）。
 * <p>
 * 截图会作为该页附件上传，Wiki 才能显示；本机路径 wiki 服务器访问不到。
 */
@Slf4j
@Service
public class IssueTrackingWikiService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern ISSUE_MARK = Pattern.compile("<!--issue:([^>]+)-->");
    private static final Pattern TD = Pattern.compile("(?is)<td[^>]*>(.*?)</td>");
    private static final Pattern TR = Pattern.compile("(?is)<tr[^>]*>.*?</tr>");

    private final IssueTrackingService issueTrackingService;
    private final ReleaseDocProperties wikiProps;
    private final HttpClient http = buildTrustAllHttpClient();

    @Value("${issue-tracking.wiki-page-id:119726898}")
    private String wikiPageId;

    @Value("${app.issue-image-dir:${user.home}/.mobile-standalone/uploads/issues}")
    private String imageDir;

    public IssueTrackingWikiService(
            IssueTrackingService issueTrackingService,
            ReleaseDocProperties wikiProps) {
        this.issueTrackingService = issueTrackingService;
        this.wikiProps = wikiProps;
    }

    public Map<String, Object> sync() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) issueTrackingService
                .list(null, null, null, null, null, null, null)
                .get("issues");
        if (issues == null) {
            issues = List.of();
        }
        return appendIssues(issues);
    }

    public Map<String, Object> syncOne(String id) {
        Map<String, Object> issue = issueTrackingService.getById(id);
        if (issue == null) {
            throw new IllegalArgumentException("记录不存在");
        }
        return appendIssues(List.of(issue));
    }

    private Map<String, Object> appendIssues(List<Map<String, Object>> candidates) {
        requireCreds();
        JsonNode page = fetchPage();
        String title = page.path("title").asText("线上问题汇总及跟踪");
        String spaceKey = page.path("space").path("key").asText("PA");
        int version = page.path("version").path("number").asInt(1);
        JsonNode storageNode = page.path("body").path("storage");
        if (storageNode.isMissingNode() || !storageNode.has("value")) {
            throw new IllegalStateException("读取 Wiki 页正文失败，取消追加以免覆盖已有内容");
        }
        String existing = storageNode.path("value").asText("");

        Set<String> present = extractPresentKeys(existing);
        List<Map<String, Object>> toAdd = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> issue : candidates) {
            if (alreadyOnWiki(present, issue)) {
                skipped++;
                continue;
            }
            toAdd.add(issue);
            markPresent(present, issue);
        }

        int uploaded = 0;
        JsonNode updated = page;
        String storage = existing;
        if (!toAdd.isEmpty()) {
            for (Map<String, Object> issue : toAdd) {
                uploaded += uploadImages(issue);
            }
            storage = mergeAppend(existing, toAdd);
            updated = updatePage(title, spaceKey, version, storage);
        }

        String url = trimSlash(wikiProps.getWikiBaseUrl())
                + "/pages/viewpage.action?pageId=" + wikiPageId;
        String webui = updated.path("_links").path("webui").asText("");
        if (!webui.isBlank()) {
            url = webui.startsWith("http")
                    ? webui
                    : trimSlash(wikiProps.getWikiBaseUrl()) + webui;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("wikiPageId", wikiPageId);
        out.put("wikiPageUrl", url);
        out.put("title", title);
        out.put("count", toAdd.size());
        out.put("appended", toAdd.size());
        out.put("skipped", skipped);
        out.put("uploadedImages", uploaded);
        out.put("version", updated.path("version").path("number").asInt(
                toAdd.isEmpty() ? version : version + 1));
        log.info("[issue-tracking] Wiki 追加 {} 条，跳过 {}，截图 {}", toAdd.size(), skipped, uploaded);
        return out;
    }

    private JsonNode fetchPage() {
        try {
            HttpResponse<String> resp = send(authGet(
                    restBase() + "/" + wikiPageId + "?expand=body.storage,version,space,title"));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("读取 Wiki 页失败 HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 300));
            }
            return JSON.readTree(resp.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取 Wiki 页失败: " + e.getMessage(), e);
        }
    }

    private JsonNode updatePage(String title, String spaceKey, int version, String storageHtml) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", wikiPageId);
            body.put("type", "page");
            body.put("title", title);
            body.put("space", Map.of("key", spaceKey));
            body.put("version", Map.of("number", version + 1, "message", "线上问题跟踪追加"));
            body.put("body", Map.of(
                    "storage", Map.of("value", storageHtml, "representation", "storage")));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(restBase() + "/" + wikiPageId + "?os_authType=basic"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", basicAuthHeader())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Atlassian-Token", "nocheck")
                    .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = send(req);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("写入 Wiki 页失败 HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 500));
            }
            return JSON.readTree(resp.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("写入 Wiki 页失败: " + e.getMessage(), e);
        }
    }

    private int uploadImages(Map<String, Object> issue) {
        List<String> urls = imageUrls(issue.get("images"));
        if (urls.isEmpty()) {
            return 0;
        }
        String code = str(issue.get("issueCode"));
        if (code == null) {
            code = "ISS";
        }
        int n = 0;
        for (int i = 0; i < urls.size(); i++) {
            Path file = resolveLocalImage(urls.get(i));
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            String filename = attachmentName(code, i, file.getFileName().toString());
            try {
                byte[] bytes = Files.readAllBytes(file);
                String existingId = findAttachmentId(filename);
                uploadAttachment(filename, bytes, existingId);
                n++;
            } catch (Exception e) {
                log.warn("[issue-tracking] 上传截图 {} 失败: {}", filename, e.getMessage());
            }
        }
        return n;
    }

    private String findAttachmentId(String filename) {
        try {
            String url = restBase() + "/" + wikiPageId + "/child/attachment?limit=200"
                    + "&filename=" + java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8);
            HttpResponse<String> resp = send(authGet(url));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return null;
            }
            JsonNode results = JSON.readTree(resp.body()).path("results");
            if (!results.isArray()) {
                return null;
            }
            for (JsonNode n : results) {
                if (filename.equals(n.path("title").asText())) {
                    return n.path("id").asText(null);
                }
            }
        } catch (Exception e) {
            log.debug("[issue-tracking] 查附件跳过: {}", e.getMessage());
        }
        return null;
    }

    private void uploadAttachment(String filename, byte[] bytes, String existingId) throws Exception {
        String boundary = "----IssueTrack" + UUID.randomUUID().toString().replace("-", "");
        String mime = mimeOf(filename);
        byte[] body = buildMultipart(boundary, filename, mime, bytes);
        String url;
        if (existingId != null && !existingId.isBlank()) {
            url = restBase() + "/" + wikiPageId + "/child/attachment/" + existingId + "/data?os_authType=basic";
        } else {
            url = restBase() + "/" + wikiPageId + "/child/attachment?os_authType=basic";
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", basicAuthHeader())
                .header("X-Atlassian-Token", "nocheck")
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return;
        }
        String err = resp.body() != null ? resp.body() : "";
        if (resp.statusCode() == 400 && err.contains("already exists") && existingId == null) {
            String id = findAttachmentId(filename);
            if (id != null) {
                uploadAttachment(filename, bytes, id);
                return;
            }
        }
        throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + truncate(err, 300));
    }

    private String mergeAppend(String existing, List<Map<String, Object>> toAdd) {
        String rows = buildRows(toAdd);
        if (existing == null || existing.isBlank()) {
            return syncIntro() + wrapTable(headerRow() + rows);
        }
        int tbody = lastIndexIgnoreCase(existing, "</tbody>");
        if (tbody >= 0) {
            return existing.substring(0, tbody) + rows + existing.substring(tbody);
        }
        int table = lastIndexIgnoreCase(existing, "</table>");
        if (table >= 0) {
            return existing.substring(0, table) + rows + existing.substring(table);
        }
        return existing + wrapTable(headerRow() + rows);
    }

    private static String syncIntro() {
        return "<p>由测试平台「线上问题跟踪」追加同步，新记录接在表格末尾。最后同步："
                + esc(LocalDateTime.now().format(DISPLAY_TIME)) + "</p>";
    }

    private static String wrapTable(String inner) {
        return "<table><tbody>" + inner + "</tbody></table>";
    }

    private static String headerRow() {
        return "<tr>"
                + "<th>日期</th><th>项目</th><th>Title</th><th>问题描述</th><th>涉及的接口</th>"
                + "<th>涉及的开发</th><th>涉及的测试</th>"
                + "<th>是否解决</th><th>是否验证</th><th>问题截图</th><th>备注</th>"
                + "</tr>";
    }

    private String buildRows(List<Map<String, Object>> issues) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : issues) {
            sb.append(buildRow(r));
        }
        return sb.toString();
    }

    private String buildRow(Map<String, Object> r) {
        String code = str(r.get("issueCode"));
        String id = str(r.get("id"));
        String mark = id != null ? id : (code != null ? code : "");
        return "<tr><!--issue:" + esc(mark) + "-->"
                + td(fmtDate(r.get("updatedAt"), r.get("createdAt")))
                + td(projectCell(r))
                + "<td>" + esc(str(r.get("title"))) + "</td>"
                + "<td>" + br(firstNonBlank(str(r.get("description")), str(r.get("reproductionSteps")))) + "</td>"
                + "<td>" + br(joinApis(r.get("relatedApis"))) + "</td>"
                + td(str(r.get("developers")))
                + td(str(r.get("trackingTester")))
                + td(yesNo(resolved(r)))
                + td(yesNo(truthy(r.get("verified"))))
                + "<td>" + imageCell(code, imageUrls(r.get("images"))) + "</td>"
                + "<td>" + br(str(r.get("note"))) + "</td>"
                + "</tr>";
    }

    private static Set<String> extractPresentKeys(String storage) {
        Set<String> keys = new LinkedHashSet<>();
        if (storage == null || storage.isBlank()) {
            return keys;
        }
        Matcher mark = ISSUE_MARK.matcher(storage);
        while (mark.find()) {
            String v = mark.group(1).trim();
            if (!v.isBlank()) {
                keys.add(normKey(v));
            }
        }
        Matcher tr = TR.matcher(storage);
        while (tr.find()) {
            String row = tr.group();
            if (row.contains("<th")) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            Matcher td = TD.matcher(row);
            while (td.find()) {
                cells.add(plainText(td.group(1)));
            }
            if (cells.size() >= 3 && !cells.get(2).isBlank()) {
                keys.add(normKey(cells.get(2)));
            }
        }
        return keys;
    }

    private static boolean alreadyOnWiki(Set<String> present, Map<String, Object> issue) {
        String id = str(issue.get("id"));
        if (id != null && present.contains(normKey(id))) {
            return true;
        }
        String code = str(issue.get("issueCode"));
        if (code != null && present.contains(normKey(code))) {
            return true;
        }
        String title = str(issue.get("title"));
        return title != null && present.contains(normKey(title));
    }

    private static void markPresent(Set<String> present, Map<String, Object> issue) {
        String id = str(issue.get("id"));
        if (id != null) {
            present.add(normKey(id));
        }
        String code = str(issue.get("issueCode"));
        if (code != null) {
            present.add(normKey(code));
        }
        String title = str(issue.get("title"));
        if (title != null) {
            present.add(normKey(title));
        }
    }

    private static String plainText(String html) {
        if (html == null) {
            return "";
        }
        String t = html.replaceAll("(?is)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim();
        return t.replaceAll("\\s+", " ");
    }

    private static String normKey(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static int lastIndexIgnoreCase(String s, String needle) {
        return s.toLowerCase(Locale.ROOT).lastIndexOf(needle.toLowerCase(Locale.ROOT));
    }

    private String imageCell(String code, List<String> urls) {
        if (urls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < urls.size(); i++) {
            Path file = resolveLocalImage(urls.get(i));
            String name = file != null
                    ? attachmentName(code == null ? "ISS" : code, i, file.getFileName().toString())
                    : attachmentName(code == null ? "ISS" : code, i, urls.get(i));
            sb.append("<ac:image ac:height=\"180\"><ri:attachment ri:filename=\"")
                    .append(escAttr(name))
                    .append("\" /></ac:image> ");
        }
        return sb.toString();
    }

    private Path resolveLocalImage(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String path = url.trim();
        String prefix = "/issue-images/";
        int idx = path.indexOf(prefix);
        String rel = idx >= 0 ? path.substring(idx + prefix.length()) : path.replaceFirst("^/+", "");
        Path root = Paths.get(imageDir).toAbsolutePath().normalize();
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root)) {
            return null;
        }
        return file;
    }

    private static String attachmentName(String issueCode, int index, String original) {
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) {
            ext = original.substring(dot).toLowerCase();
        }
        if (ext.isBlank()) {
            ext = ".jpg";
        }
        String code = issueCode == null ? "ISS" : issueCode.replaceAll("[^A-Za-z0-9_-]", "");
        return code + "-" + (index + 1) + ext;
    }

    @SuppressWarnings("unchecked")
    private static String joinApis(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return String.join("\n", out);
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    private static List<String> imageUrls(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out;
        }
        return List.of();
    }

    private static String projectCell(Map<String, Object> r) {
        String project = str(r.get("projectTypeLabel"));
        if (project == null) {
            project = str(r.get("projectType"));
        }
        String env = str(r.get("environmentLabel"));
        if (project != null && env != null) {
            return project + " · " + env;
        }
        return project != null ? project : (env != null ? env : "");
    }

    private static boolean resolved(Map<String, Object> r) {
        if (truthy(r.get("devFixed")) || truthy(r.get("deployed"))) {
            return true;
        }
        String st = str(r.get("status"));
        return "FIXED".equals(st) || "DEPLOYED".equals(st) || "CLOSED".equals(st);
    }

    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = String.valueOf(v);
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private static String yesNo(boolean v) {
        return v ? "是" : "否";
    }

    private static String fmtDate(Object updated, Object created) {
        String s = str(updated);
        if (s == null) {
            s = str(created);
        }
        if (s == null) {
            return "";
        }
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static String td(String s) {
        return "<td>" + esc(s) + "</td>";
    }

    private static String br(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String[] lines = s.split("\\R");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                b.append("<br/>");
            }
            b.append(esc(lines[i]));
        }
        return b.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escAttr(String s) {
        return esc(s).replace("\"", "&quot;");
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static String mimeOf(String filename) {
        String f = filename.toLowerCase();
        if (f.endsWith(".png")) {
            return "image/png";
        }
        if (f.endsWith(".gif")) {
            return "image/gif";
        }
        if (f.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static byte[] buildMultipart(String boundary, String filename, String mime, byte[] fileBytes) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headB = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailB = tail.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[headB.length + fileBytes.length + tailB.length];
        System.arraycopy(headB, 0, all, 0, headB.length);
        System.arraycopy(fileBytes, 0, all, headB.length, fileBytes.length);
        System.arraycopy(tailB, 0, all, headB.length + fileBytes.length, tailB.length);
        return all;
    }

    private void requireCreds() {
        if (wikiProps.getUsername() == null || wikiProps.getUsername().isBlank()
                || wikiProps.getPassword() == null || wikiProps.getPassword().isBlank()) {
            throw new IllegalStateException("未配置 Wiki 账号（release-doc.username/password）");
        }
        if (wikiPageId == null || wikiPageId.isBlank()) {
            throw new IllegalStateException("未配置 issue-tracking.wiki-page-id");
        }
    }

    private String restBase() {
        return trimSlash(wikiProps.getWikiBaseUrl()) + "/rest/api/content";
    }

    private String basicAuthHeader() {
        String token = wikiProps.getUsername() + ":" + wikiProps.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest authGet(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Authorization", basicAuthHeader())
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (compatible; IssueTrackBot/1.0)")
                .GET()
                .build();
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String trimSlash(String u) {
        if (u == null) {
            return "";
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static HttpClient buildTrustAllHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                    }
            };
            SSLContext ssl = SSLContext.getInstance("TLSv1.2");
            ssl.init(null, trustAll, new SecureRandom());
            javax.net.ssl.SSLParameters params = ssl.getDefaultSSLParameters();
            params.setProtocols(new String[]{"TLSv1.2"});
            params.setEndpointIdentificationAlgorithm(null);
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(ssl)
                    .sslParameters(params)
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 Wiki HttpClient 失败: " + e.getMessage(), e);
        }
    }
}
