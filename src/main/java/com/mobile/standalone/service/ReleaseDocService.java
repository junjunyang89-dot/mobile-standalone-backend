package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.GenerateRequest;
import com.mobile.standalone.dto.ReleaseDocDtos.GenerateResult;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
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
import java.net.Proxy;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发版文档：按版本从 Wiki 汇总页取 JIRA 链接 → 拉单字段与注释 → 在父页下创建带表格的子页面。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReleaseDocService {

    private static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b");
    private static final Pattern SQL_LINE = Pattern.compile(
            "(?i).*\\b(SELECT|INSERT|UPDATE|ALTER|CREATE|DROP|DELETE|TRUNCATE|REPLACE)\\b.*");
    /**
     * 任务列末尾「人名 + 提测/上线」后缀。只匹配字符串末尾，每条只跑一次，避免误删正文。
     * 规则来自《Confluence发版Wiki任务列清理指南》，并覆盖「暂定提测」「提测，不跟版本」。
     */
    private static final List<Pattern> TASK_SUFFIX_PATTERNS = List.of(
            Pattern.compile(
                    "\\s*[-–]\\s*[\\u4e00-\\u9fff/]+"
                            + "\\s*[-–]\\s*\\d+\\.\\d+提测[，,]\\d+\\.\\d+(?:上线|上)(?:[，,]\\d+\\.\\d+开放入口)?\\s*$"),
            Pattern.compile(
                    "\\s*[-–]\\s*[\\u4e00-\\u9fff/]+\\s*[-–]\\s*\\d+\\.\\d+提测[，,]不跟版本\\s*$"),
            Pattern.compile(
                    "\\s*[-–]?\\s*[\\u4e00-\\u9fff]{2,4}(?:/[\\u4e00-\\u9fff]{2,4})+"
                            + "[，,]\\s*暂定?\\d+\\.\\d+提测[，,]\\d+\\.\\d+(?:上线|上)(?:[，,]\\d+\\.\\d+开放入口)?\\s*$"),
            Pattern.compile("\\s*[-–]\\s*[\\u4e00-\\u9fff/]+\\s*[-–]\\s*\\d+\\.\\d+(?:上线|上)\\s*$"),
            Pattern.compile("\\s*[-–]\\s*[\\u4e00-\\u9fff]{2,4}(?:[，,][\\u4e00-\\u9fff]+)?\\s*$")
    );
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReleaseDocProperties props;

    /** 内网 Wiki/JIRA 常为自签证书，且仅支持 TLSv1.2；JDK HttpClient 默认协商易握手失败 */
    private final HttpClient http = buildTrustAllHttpClient();

    private static HttpClient buildTrustAllHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        /** 返回 null 而非空数组，避免部分 JDK 在握手时直接中断 */
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                    }
            };
            // 公司 Wiki（nginx）实测仅 TLSv1.2 / ECDHE-RSA-AES256-GCM-SHA384
            SSLContext ssl = SSLContext.getInstance("TLSv1.2");
            ssl.init(null, trustAll, new SecureRandom());
            SSLContext.setDefault(ssl);
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            System.setProperty("https.protocols", "TLSv1.2");
            System.setProperty("jdk.tls.client.protocols", "TLSv1.2");

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
            throw new IllegalStateException("初始化发版文档 HttpClient 失败: " + e.getMessage(), e);
        }
    }

    /** 连通性探测：能否拉取汇总页 */
    public String pingWiki() {
        requireCreds();
        return fetchWikiViewHtml(props.getIssueListPageId());
    }

    /**
     * 版本归档：按 Wiki 汇总页版本标题拉全部 JIRA 单（不过滤状态，便于历史留档）。
     */
    public List<IssueRow> listIssuesForArchive(String versionLabel) {
        requireCreds();
        if (versionLabel == null || versionLabel.isBlank()) {
            throw new IllegalArgumentException("请填写版本定位文案（如 8.6 国内客户端）");
        }
        String version = versionLabel.trim();
        GenerateResult out = new GenerateResult();
        out.setVersion(version);
        String storageOrHtml = fetchWikiStoragePrefer(props.getIssueListPageId());
        List<IssueRef> refs = findIssuesUnderVersion(storageOrHtml, version, out, true);
        if (refs.isEmpty()) {
            throw new IllegalStateException("在汇总页未找到版本「" + version + "」下的 JIRA 单，请检查文案是否与 Wiki 标题一致");
        }
        log.info("[version-archive] 版本「{}」JIRA 命中 {} 单（含全部状态）", version, refs.size());
        String jiraBase = resolveJiraBase(refs);
        List<IssueRow> rows = new ArrayList<>();
        for (IssueRef ref : refs) {
            rows.add(fetchIssueRowForArchive(jiraBase, ref));
        }
        return rows;
    }

    /** 一个 JIRA 单的原始信息：备注 + 描述全文交给调用方自己解析项目 / 分支 */
    public record IssueDetail(String key, String url, String summary, String status, String assignee,
                              String qaAssignee, String text, String dbChanges, String configChanges) {
    }

    /** 按 Wiki 汇总页版本标题拉全部单（含全部状态）及备注全文 */
    public List<IssueDetail> listIssueDetails(String versionLabel) {
        requireCreds();
        if (versionLabel == null || versionLabel.isBlank()) {
            throw new IllegalArgumentException("请填写版本定位文案（如 10.9 国内H5）");
        }
        String version = versionLabel.trim();
        GenerateResult out = new GenerateResult();
        out.setVersion(version);
        String storageOrHtml = fetchWikiStoragePrefer(props.getIssueListPageId());
        List<IssueRef> refs = findIssuesUnderVersion(storageOrHtml, version, out, true);
        if (refs.isEmpty()) {
            throw new IllegalStateException("在汇总页未找到版本「" + version + "」下的 JIRA 单，请检查文案是否与 Wiki 标题一致");
        }
        String jiraBase = resolveJiraBase(refs);
        List<IssueDetail> rows = new ArrayList<>();
        for (IssueRef ref : refs) {
            rows.add(fetchIssueDetail(jiraBase, ref));
        }
        return rows;
    }

    /** 单独刷新一个单 */
    public IssueDetail fetchIssueDetail(String issueKey) {
        requireCreds();
        String key = issueKey == null ? "" : issueKey.trim().toUpperCase(Locale.ROOT);
        if (!ISSUE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("单号格式不对：" + issueKey);
        }
        return fetchIssueDetail(resolveJiraBase(List.of()), new IssueRef(key, null));
    }

    private IssueDetail fetchIssueDetail(String jiraBase, IssueRef ref) {
        try {
            String url = jiraBase + "/rest/api/2/issue/" + URLEncoder.encode(ref.key, StandardCharsets.UTF_8)
                    + "?expand=names";
            HttpResponse<String> resp = send(authGet(url));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("JIRA " + ref.key + " HTTP " + resp.statusCode());
            }
            JsonNode root = JSON.readTree(resp.body());
            JsonNode fields = root.path("fields");
            String qa = findFieldByDisplayName(fields, root.path("names"), "QA Assignee", "QA", "Qa Assignee");
            StringBuilder text = new StringBuilder();
            for (JsonNode c : fields.path("comment").path("comments")) {
                String body = c.path("body").asText("");
                if (!body.isBlank()) {
                    text.append(body).append("\n\n");
                }
            }
            String desc = fields.path("description").asText("");
            if (!desc.isBlank()) {
                text.append(desc).append("\n");
            }
            ParsedComment parsed = parseComment(text.toString());
            return new IssueDetail(ref.key, trimSlash(jiraBase) + "/browse/" + ref.key,
                    cleanTaskText(fields.path("summary").asText(ref.key)),
                    fields.path("status").path("name").asText(""),
                    fields.path("assignee").path("displayName").asText(""),
                    qa == null ? "" : qa,
                    text.toString(), parsed.db, parsed.config);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("拉取 JIRA " + ref.key + " 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重新打开并流转到 Testing，再分配给指定 QA（经办人 + QA Assignee）。
     * 典型路径：已关闭 → Reopen → 重新打开 → Start Progress → 处理中 → Submit for QA → Testing。
     */
    public String reopenAndAssign(String issueKey, String username, String comment) {
        requireCreds();
        if (issueKey == null || issueKey.isBlank()) {
            throw new IllegalArgumentException("issueKey 必填");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username 必填");
        }
        String key = issueKey.trim().toUpperCase(Locale.ROOT);
        String user = username.trim();
        String jiraBase = resolveJiraBase(List.of());
        try {
            List<String> path = new ArrayList<>();
            boolean commentSent = false;
            for (int step = 0; step < 8; step++) {
                String status = fetchIssueStatus(jiraBase, key);
                if (isTestingStatus(status)) {
                    break;
                }
                JsonNode transitions = fetchTransitions(jiraBase, key);
                JsonNode pick = pickTowardTesting(transitions);
                if (pick == null) {
                    throw new IllegalStateException(key + " 状态=" + status
                            + "，无法继续转到 Testing。当前可选: " + summarizeTransitions(transitions)
                            + (path.isEmpty() ? "" : "；已走: " + String.join(" → ", path)));
                }
                String tName = pick.path("name").asText("?");
                String toName = pick.path("to").path("name").asText("");
                String tId = pick.path("id").asText("");
                String cmt = null;
                if (!commentSent && comment != null && !comment.isBlank()) {
                    cmt = comment;
                    commentSent = true;
                }
                doTransition(jiraBase, key, tId, cmt, false);
                path.add(tName + "→" + toName);
                log.info("[release-doc] {} 流转 {}", key, path.get(path.size() - 1));
            }

            String statusAfter = fetchIssueStatus(jiraBase, key);
            if (!isTestingStatus(statusAfter)) {
                throw new IllegalStateException(key + " 结束后状态仍为「" + statusAfter + "」，期望 Testing。"
                        + " 已走: " + String.join(" → ", path));
            }

            assignIssue(jiraBase, key, user);
            path.add("assign=" + user);
            return String.join(" → ", path) + "（当前状态=" + statusAfter + "）";
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("重新打开到 Testing/分配 " + key + " 失败: " + e.getMessage(), e);
        }
    }

    private static boolean isTestingStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.trim().toLowerCase(Locale.ROOT);
        return s.equals("testing") || status.contains("测试中");
    }

    /** 优先通向 Testing；否则按 Reopen → Start Progress → Submit for QA 顺序推进 */
    private static JsonNode pickTowardTesting(JsonNode transitions) {
        if (transitions == null || !transitions.isArray()) return null;
        JsonNode toTesting = null;
        JsonNode submitQa = null;
        JsonNode startProgress = null;
        JsonNode reopen = null;
        JsonNode other = null;
        for (JsonNode t : transitions) {
            String name = t.path("name").asText("");
            String lower = name.toLowerCase(Locale.ROOT);
            String toName = t.path("to").path("name").asText("");
            String toLower = toName.toLowerCase(Locale.ROOT);
            if (toLower.equals("testing") || toName.contains("测试中")) {
                toTesting = t;
            } else if (lower.contains("submit") && lower.contains("qa")
                    || name.contains("提交") && (name.contains("QA") || name.contains("测试"))) {
                submitQa = t;
            } else if (lower.contains("start progress") || toName.contains("处理中")
                    || toLower.contains("progress") && !toLower.contains("stop")) {
                startProgress = t;
            } else if (lower.contains("reopen") || name.contains("重新打开") || name.contains("重开")
                    || toName.contains("重新打开") || toLower.contains("reopened")) {
                reopen = t;
            } else if (other == null && !isTerminalClosedStatus(toName)) {
                other = t;
            }
        }
        if (toTesting != null) return toTesting;
        if (submitQa != null) return submitQa;
        if (startProgress != null) return startProgress;
        if (reopen != null) return reopen;
        return other;
    }

    private void assignIssue(String jiraBase, String key, String username) throws Exception {
        // 经办人
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("assignee", Map.of("name", username));

        // 尝试写入 QA Assignee 自定义字段（多为多选用户，需数组）
        String qaField = findQaAssigneeFieldId(jiraBase, key);
        if (qaField != null) {
            fields.put(qaField, List.of(Map.of("name", username)));
        }

        Map<String, Object> body = Map.of("fields", fields);
        String url = jiraBase + "/rest/api/2/issue/" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", basicAuthHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            // 仅 assignee 再试一次
            Map<String, Object> bare = Map.of("fields", Map.of("assignee", Map.of("name", username)));
            HttpRequest r2 = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", basicAuthHeader())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method("PUT", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(bare)))
                    .build();
            HttpResponse<String> r2resp = send(r2);
            if (r2resp.statusCode() < 200 || r2resp.statusCode() >= 300) {
                throw new IllegalStateException("分配失败 HTTP " + resp.statusCode() + ": "
                        + truncate(resp.body(), 400));
            }
        }
        log.info("[release-doc] {} 已分配给 {}", key, username);
    }

    private String findQaAssigneeFieldId(String jiraBase, String key) {
        try {
            String url = jiraBase + "/rest/api/2/issue/" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "?expand=names&fields=*navigable";
            HttpResponse<String> resp = send(authGet(url));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            JsonNode names = JSON.readTree(resp.body()).path("names");
            if (!names.isObject()) return null;
            var it = names.fields();
            while (it.hasNext()) {
                var e = it.next();
                String display = e.getValue().asText("");
                if (display.equalsIgnoreCase("QA Assignee") || display.equalsIgnoreCase("Qa Assignee")
                        || display.equals("QA") || display.contains("QA Assignee")) {
                    return e.getKey();
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    /**
     * 通过 JIRA transition 关单。
     * Testing 等状态往往没有直接「关闭」，会先走 Resolve/已解决，再 Close（最多 5 步）。
     * @return 使用的流转路径，如 {@code Resolve Issue → Close Issue}
     */
    public String closeIssue(String issueKey, String comment) {
        requireCreds();
        if (issueKey == null || issueKey.isBlank()) {
            throw new IllegalArgumentException("issueKey 必填");
        }
        String key = issueKey.trim().toUpperCase(Locale.ROOT);
        String jiraBase = resolveJiraBase(List.of());
        List<String> path = new ArrayList<>();
        boolean commentSent = false;
        try {
            for (int step = 0; step < 5; step++) {
                String status = fetchIssueStatus(jiraBase, key);
                if (isTerminalClosedStatus(status)) {
                    if (path.isEmpty()) {
                        return "已是关闭状态(" + status + ")";
                    }
                    return String.join(" → ", path);
                }

                JsonNode transitions = fetchTransitions(jiraBase, key);
                JsonNode pick = pickCloseTransition(transitions);
                if (pick == null) {
                    pick = pickResolveTransition(transitions);
                }
                if (pick == null) {
                    throw new IllegalStateException(key + " 状态=" + status
                            + "，无可通向关闭的流转。当前可选: " + summarizeTransitions(transitions));
                }

                String tName = pick.path("name").asText("?");
                String tId = pick.path("id").asText("");
                String commentThisStep = null;
                if (!commentSent && comment != null && !comment.isBlank()) {
                    commentThisStep = comment;
                    commentSent = true;
                }
                boolean resolveLike = isResolveTransition(pick);
                doTransition(jiraBase, key, tId, commentThisStep, resolveLike);
                path.add(tName);
                log.info("[release-doc] {} 流转 {} (status was {})", key, tName, status);
            }
            String status = fetchIssueStatus(jiraBase, key);
            if (isTerminalClosedStatus(status)) {
                return String.join(" → ", path);
            }
            throw new IllegalStateException(key + " 多步流转后仍为「" + status + "」: " + String.join(" → ", path));
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("关闭 " + key + " 失败: " + e.getMessage(), e);
        }
    }

    private String fetchIssueStatus(String jiraBase, String key) throws Exception {
        String url = jiraBase + "/rest/api/2/issue/" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "?fields=status";
        HttpResponse<String> resp = send(authGet(url));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("读取状态失败 HTTP " + resp.statusCode());
        }
        return JSON.readTree(resp.body()).path("fields").path("status").path("name").asText("");
    }

    private JsonNode fetchTransitions(String jiraBase, String key) throws Exception {
        String tUrl = jiraBase + "/rest/api/2/issue/" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "/transitions?expand=transitions.fields";
        HttpResponse<String> tResp = send(authGet(tUrl));
        if (tResp.statusCode() < 200 || tResp.statusCode() >= 300) {
            throw new IllegalStateException("获取流转失败 HTTP " + tResp.statusCode() + ": "
                    + truncate(tResp.body(), 300));
        }
        return JSON.readTree(tResp.body()).path("transitions");
    }

    private static boolean isTerminalClosedStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.trim().toLowerCase(Locale.ROOT);
        return status.contains("已关闭") || s.equals("closed") || s.equals("done");
    }

    private static JsonNode pickCloseTransition(JsonNode transitions) {
        if (transitions == null || !transitions.isArray()) return null;
        for (JsonNode t : transitions) {
            if (isCloseTransition(t)) return t;
        }
        return null;
    }

    private static JsonNode pickResolveTransition(JsonNode transitions) {
        if (transitions == null || !transitions.isArray()) return null;
        for (JsonNode t : transitions) {
            if (isResolveTransition(t)) return t;
        }
        return null;
    }

    private static boolean isCloseTransition(JsonNode t) {
        String name = t.path("name").asText("");
        String lower = name.toLowerCase(Locale.ROOT);
        String toName = t.path("to").path("name").asText("");
        String toLower = toName.toLowerCase(Locale.ROOT);
        if (name.contains("关闭") || lower.contains("close") || lower.equals("done")
                || name.contains("已关闭")) {
            return true;
        }
        return toName.contains("已关闭") || toLower.equals("closed") || toLower.equals("done");
    }

    private static boolean isResolveTransition(JsonNode t) {
        String name = t.path("name").asText("");
        String lower = name.toLowerCase(Locale.ROOT);
        String toName = t.path("to").path("name").asText("");
        // 不要把 Close 再当成 Resolve
        if (isCloseTransition(t)) return false;
        if (lower.contains("resolve") || name.contains("解决") || name.contains("已解决")
                || lower.contains("fixed") || name.contains("完成") && !lower.contains("incomplete")) {
            return true;
        }
        return toName.contains("已解决") || toName.equalsIgnoreCase("resolved")
                || toName.contains("解决");
    }

    private static String summarizeTransitions(JsonNode transitions) {
        if (transitions == null || !transitions.isArray() || transitions.isEmpty()) {
            return "（无）";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode t : transitions) {
            String name = t.path("name").asText("?");
            String to = t.path("to").path("name").asText("");
            names.add(to.isBlank() ? name : name + "→" + to);
        }
        return String.join(", ", names);
    }

    private void doTransition(String jiraBase, String key, String transitionId,
                              String comment, boolean withResolution) throws Exception {
        String tUrl = jiraBase + "/rest/api/2/issue/" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "/transitions";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transition", Map.of("id", transitionId));
        if (withResolution) {
            // Testing → Resolve 常要求填 resolution，缺了会 400
            body.put("fields", Map.of("resolution", Map.of("name", "Fixed")));
        }
        if (comment != null && !comment.isBlank()) {
            body.put("update", Map.of(
                    "comment", List.of(Map.of(
                            "add", Map.of("body", comment)
                    ))
            ));
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", basicAuthHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return;
        }
        // resolution 名不对时去掉重试一次（部分项目用 Done / 完成）
        if (withResolution && resp.statusCode() == 400) {
            for (String resName : List.of("Done", "完成", "Fixed")) {
                Map<String, Object> retry = new LinkedHashMap<>();
                retry.put("transition", Map.of("id", transitionId));
                retry.put("fields", Map.of("resolution", Map.of("name", resName)));
                if (comment != null && !comment.isBlank()) {
                    retry.put("update", Map.of(
                            "comment", List.of(Map.of("add", Map.of("body", comment)))
                    ));
                }
                HttpRequest r2 = HttpRequest.newBuilder()
                        .uri(URI.create(tUrl))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", basicAuthHeader())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(retry)))
                        .build();
                HttpResponse<String> r2resp = send(r2);
                if (r2resp.statusCode() >= 200 && r2resp.statusCode() < 300) {
                    return;
                }
            }
            // 最后再不带 resolution 试一次
            Map<String, Object> bare = new LinkedHashMap<>();
            bare.put("transition", Map.of("id", transitionId));
            if (comment != null && !comment.isBlank()) {
                bare.put("update", Map.of(
                        "comment", List.of(Map.of("add", Map.of("body", comment)))
                ));
            }
            HttpRequest r3 = HttpRequest.newBuilder()
                    .uri(URI.create(tUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", basicAuthHeader())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(bare)))
                    .build();
            HttpResponse<String> r3resp = send(r3);
            if (r3resp.statusCode() >= 200 && r3resp.statusCode() < 300) {
                return;
            }
            throw new IllegalStateException("流转失败 HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body(), 400));
        }
        throw new IllegalStateException("流转失败 HTTP " + resp.statusCode() + ": "
                + truncate(resp.body(), 400));
    }

    /** 归档专用：不过滤状态 */
    private IssueRow fetchIssueRowForArchive(String jiraBase, IssueRef ref) {
        try {
            String url = jiraBase + "/rest/api/2/issue/" + URLEncoder.encode(ref.key, StandardCharsets.UTF_8)
                    + "?fields=summary,status,assignee";
            HttpResponse<String> resp = send(authGet(url));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("JIRA " + ref.key + " HTTP " + resp.statusCode());
            }
            JsonNode fields = JSON.readTree(resp.body()).path("fields");
            IssueRow row = new IssueRow();
            row.setIssueKey(ref.key);
            row.setIssueUrl(trimSlash(jiraBase) + "/browse/" + ref.key);
            row.setSummary(fields.path("summary").asText(ref.key));
            row.setStatusName(fields.path("status").path("name").asText(""));
            row.setAssignee(fields.path("assignee").path("displayName").asText(""));
            String qa = fields.path("assignee").path("displayName").asText("");
            row.setQaAssignee(qa);
            return row;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("拉取 JIRA " + ref.key + " 失败: " + e.getMessage(), e);
        }
    }

    public GenerateResult generate(GenerateRequest req) {
        GenerateResult out = new GenerateResult();
        out.setPreviewOnly(Boolean.TRUE.equals(req.getPreviewOnly()));
        if (!props.isEnabled()) {
            throw new IllegalStateException("发版文档功能已关闭（release-doc.enabled=false）");
        }
        requireCreds();
        log.info("[release-doc] 使用账号={} wiki={}（已放宽 SSL）", props.getUsername(), props.getWikiBaseUrl());
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("请填写发版文档标题");
        }
        if (req.getVersion() == null || req.getVersion().isBlank()) {
            throw new IllegalArgumentException("请填写版本定位文案（如 7.20 海外H5）");
        }
        out.setTitle(req.getTitle().trim());
        out.setVersion(req.getVersion().trim());

        // 生成时若带了人工改过的 rows，直接建页（备注格式因人而异，以人工校对为准）
        if (!out.isPreviewOnly() && req.getRows() != null && !req.getRows().isEmpty()) {
            List<IssueRow> sorted = sortRowsByQa(req.getRows());
            applyTaskTextCleanup(sorted);
            out.setRows(sorted);
            logStep(out, "使用前端校对后的 " + sorted.size() + " 行生成 Wiki（已按 QA 排序）");
            logStep(out, "在父页 " + props.getParentPageId() + " 下创建子页面");
            JsonNode created = createConfluenceChildPage(out.getTitle(), buildStorageHtml(out.getTitle(), sorted));
            fillWikiLinks(out, created);
            out.setMessage("发版文档已写入 Wiki（同标题则覆盖更新；已采用校对后的表格，并按 QA 排序）");
            return out;
        }

        logStep(out, "读取汇总页 pageId=" + props.getIssueListPageId());
        String storageOrHtml = fetchWikiStoragePrefer(props.getIssueListPageId());
        List<IssueRef> refs = findIssuesUnderVersion(storageOrHtml, out.getVersion(), out, false);
        if (refs.isEmpty()) {
            throw new IllegalStateException("在汇总页未找到版本「" + out.getVersion() + "」下的 JIRA 单，请检查文案是否与 Wiki 标题一致（如 7.20 海外H5）");
        }
        logStep(out, "定位到 " + refs.size() + " 个 JIRA 单");

        String jiraBase = resolveJiraBase(refs);
        List<IssueRow> rows = new ArrayList<>();
        int skippedStatus = 0;
        for (IssueRef ref : refs) {
            logStep(out, "拉取 " + ref.key);
            IssueRow row = fetchIssueRow(jiraBase, ref);
            if (row == null) {
                skippedStatus++;
                continue;
            }
            rows.add(row);
        }
        if (skippedStatus > 0) {
            logStep(out, "跳过 " + skippedStatus + " 单（状态不符，或备注写明不跟本版本上线）");
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("版本「" + out.getVersion() + "」下没有「"
                    + String.join(" / ", includeStatusList()) + "」状态的单子可统计");
        }
        applyTaskTextCleanup(rows);
        rows = sortRowsByQa(rows);
        out.setRows(rows);
        logStep(out, "已按 QA 排序: " + summarizeQaOrder(rows));

        if (out.isPreviewOnly()) {
            out.setMessage("预览成功（仅「" + String.join(" / ", includeStatusList())
                    + "」；已按 QA 集中排序，便于逐人检查；可在表格中直接改后再点生成）");
            return out;
        }

        logStep(out, "在父页 " + props.getParentPageId() + " 下创建子页面");
        JsonNode created = createConfluenceChildPage(out.getTitle(), buildStorageHtml(out.getTitle(), rows));
        fillWikiLinks(out, created);
        out.setMessage("发版文档已写入 Wiki（同标题则覆盖更新；表格已按 QA 排序）");
        return out;
    }

    /**
     * 按配置的 qa-sort-order 集中排序：同一个人的单子挨在一起。
     * 未命中名单的 QA 排在后面，组内保持原相对顺序。
     */
    List<IssueRow> sortRowsByQa(List<IssueRow> rows) {
        if (rows == null || rows.isEmpty()) return rows == null ? List.of() : rows;
        List<String> order = props.getQaSortOrder() != null ? props.getQaSortOrder() : List.of();
        if (order.isEmpty()) return new ArrayList<>(rows);

        record Indexed(int idx, IssueRow row) {}
        List<Indexed> indexed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            indexed.add(new Indexed(i, rows.get(i)));
        }
        indexed.sort((a, b) -> {
            int ra = qaRank(a.row.getQaAssignee(), order);
            int rb = qaRank(b.row.getQaAssignee(), order);
            if (ra != rb) return Integer.compare(ra, rb);
            // 同 rank 时按 QA 名再排，便于「未配置名单」的人也相对聚在一起
            int byName = normalizeQaKey(a.row.getQaAssignee()).compareTo(normalizeQaKey(b.row.getQaAssignee()));
            if (byName != 0) return byName;
            return Integer.compare(a.idx, b.idx);
        });
        List<IssueRow> out = new ArrayList<>(indexed.size());
        for (Indexed it : indexed) out.add(it.row);
        return out;
    }

    static int qaRank(String qa, List<String> order) {
        String key = normalizeQaKey(qa);
        if (key.isEmpty() || "无".equals(key)) return order.size() + 1000;
        for (int i = 0; i < order.size(); i++) {
            String token = normalizeQaKey(order.get(i));
            if (token.isEmpty()) continue;
            if (key.equals(token) || key.contains(token) || token.contains(key)) {
                return i;
            }
        }
        return order.size() + 500;
    }

    private static String normalizeQaKey(String qa) {
        if (qa == null) return "";
        return qa.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String summarizeQaOrder(List<IssueRow> rows) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (IssueRow r : rows) {
            String q = r.getQaAssignee();
            seen.add(q == null || q.isBlank() ? "无" : q.trim());
        }
        return String.join(" → ", seen);
    }

    /** 是否纳入发版统计：仅 include-statuses（默认 已解决 / TESTING） */
    boolean isIncludedStatus(String statusName) {
        List<String> allow = includeStatusList();
        if (allow.isEmpty()) return true;
        String key = normalizeStatusKey(statusName);
        if (key.isEmpty()) return false;
        for (String a : allow) {
            if (key.equals(normalizeStatusKey(a))) return true;
        }
        return false;
    }

    private List<String> includeStatusList() {
        List<String> list = props.getIncludeStatuses();
        if (list == null || list.isEmpty()) {
            return List.of("已解决", "TESTING");
        }
        return list;
    }

    private static String normalizeStatusKey(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private void fillWikiLinks(GenerateResult out, JsonNode created) {
        String id = created.path("id").asText("");
        out.setWikiPageId(id);
        String link = created.path("_links").path("base").asText(props.getWikiBaseUrl())
                + created.path("_links").path("webui").asText("/pages/viewpage.action?pageId=" + id);
        if (!link.contains("pageId") && !id.isBlank()) {
            link = trimSlash(props.getWikiBaseUrl()) + "/pages/viewpage.action?pageId=" + id;
        }
        out.setWikiPageUrl(link);
    }

    private void requireCreds() {
        if (props.getUsername() == null || props.getUsername().isBlank()
                || props.getPassword() == null || props.getPassword().isBlank()) {
            throw new IllegalStateException(
                    "未配置 Wiki/JIRA 账号。请在 application.yml 的 release-doc.username / password 填写后重启后端");
        }
    }

    private void logStep(GenerateResult out, String msg) {
        out.getLogs().add(msg);
        log.info("[release-doc] {}", msg);
    }

    private record IssueRef(String key, String url) {}

    /** 优先 storage（含 jira 宏 JQL），再 view/HTML */
    private String fetchWikiStoragePrefer(String pageId) {
        try {
            String url = trimSlash(props.getWikiBaseUrl()) + "/rest/api/content/" + pageId
                    + "?expand=body.storage,body.view,space";
            HttpResponse<String> resp = send(authGet(url));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode n = JSON.readTree(resp.body());
                String storage = n.path("body").path("storage").path("value").asText("");
                if (!storage.isBlank()) return storage;
                String view = n.path("body").path("view").path("value").asText("");
                if (!view.isBlank()) return view;
            }
            log.warn("Confluence REST 取页失败 status={}，改用 viewpage HTML", resp.statusCode());
        } catch (Exception e) {
            log.warn("Confluence REST 取页异常，改用 viewpage HTML: {}", e.getMessage());
        }
        return fetchWikiViewHtml(pageId);
    }

    private String fetchWikiViewHtml(String pageId) {
        try {
            String url = trimSlash(props.getWikiBaseUrl()) + "/pages/viewpage.action?pageId=" + pageId;
            HttpResponse<String> resp = send(authGet(url));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp.body();
            }
            throw new IllegalStateException("访问 Wiki 汇总页失败 HTTP " + resp.statusCode());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("访问 Wiki 汇总页失败: " + e.getMessage(), e);
        }
    }

    /**
     * Wiki 上版本区是「Jira 宏」：按标题匹配后抽出 jqlQuery，用 JIRA search 拿摘要列表（可点击的那些单）。
     * 抽不到宏时，回退 fixVersion = 版本名 的默认 JQL。
     *
     * @param allStatuses true=归档用，不过滤状态；false=发版文档用，仅 include-statuses
     */
    List<IssueRef> findIssuesUnderVersion(String html, String version, GenerateResult out) {
        return findIssuesUnderVersion(html, version, out, false);
    }

    List<IssueRef> findIssuesUnderVersion(String html, String version, GenerateResult out, boolean allStatuses) {
        String jql = extractJqlForVersion(html, version);
        if (jql == null || jql.isBlank()) {
            jql = "project = APP AND fixVersion = \"" + version.trim() + "\" AND issuetype != Sub-task order by created DESC";
            logStep(out, "未从 Wiki 宏解析到 JQL，使用默认: " + jql);
        } else {
            logStep(out, "从 Wiki Jira 宏解析到 JQL: " + jql);
        }
        return searchJiraIssues(jql, out, allStatuses);
    }

    /**
     * 在 storage XML 中定位版本标题附近的 jira 宏 jqlQuery。
     */
    String extractJqlForVersion(String html, String version) {
        if (html == null || version == null) return null;
        String v = version.trim();
        // 按 panel / h3 分段：找到包含版本标题的块，再取块内第一个 jqlQuery
        Pattern panel = Pattern.compile(
                "<ac:structured-macro[^>]*ac:name=\"panel\"[\\s\\S]*?</ac:structured-macro>",
                Pattern.CASE_INSENSITIVE);
        Matcher pm = panel.matcher(html);
        while (pm.find()) {
            String block = pm.group();
            if (block.contains(v)) {
                String jql = firstJqlIn(block);
                if (jql != null) return jql;
            }
        }
        // 回退：标题后 2000 字符内找 jqlQuery
        int idx = html.indexOf(v);
        if (idx >= 0) {
            String window = html.substring(idx, Math.min(html.length(), idx + 2500));
            String jql = firstJqlIn(window);
            if (jql != null) return jql;
        }
        return null;
    }

    private String firstJqlIn(String block) {
        Pattern jqlPat = Pattern.compile(
                "ac:name=\"jqlQuery\">([\\s\\S]*?)</ac:parameter>",
                Pattern.CASE_INSENSITIVE);
        Matcher m = jqlPat.matcher(block);
        if (!m.find()) return null;
        String raw = m.group(1).trim();
        return unescapeXml(raw).replaceAll("\\s+", " ").trim();
    }

    private static String unescapeXml(String s) {
        if (s == null) return "";
        return s.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'");
    }

    private List<IssueRef> searchJiraIssues(String jql, GenerateResult out) {
        return searchJiraIssues(jql, out, false);
    }

    private List<IssueRef> searchJiraIssues(String jql, GenerateResult out, boolean allStatuses) {
        try {
            String jiraBase = trimSlash(
                    props.getJiraBaseUrl() != null && !props.getJiraBaseUrl().isBlank()
                            ? props.getJiraBaseUrl()
                            : "https://jira.shdev.net");
            List<IssueRef> list = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            int startAt = 0;
            final int pageSize = 100;
            int total = Integer.MAX_VALUE;
            while (startAt < total) {
                String url = jiraBase + "/rest/api/2/search"
                        + "?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8)
                        + "&fields=summary,key,status"
                        + "&startAt=" + startAt
                        + "&maxResults=" + pageSize;
                HttpResponse<String> resp = send(authGet(url));
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    throw new IllegalStateException("JIRA 搜索失败 HTTP " + resp.statusCode() + ": "
                            + truncate(resp.body(), 300));
                }
                JsonNode root = JSON.readTree(resp.body());
                total = root.path("total").asInt(0);
                JsonNode issues = root.path("issues");
                if (!issues.isArray() || issues.isEmpty()) {
                    break;
                }
                for (JsonNode issue : issues) {
                    String key = issue.path("key").asText("");
                    if (key.isBlank()) continue;
                    String statusName = issue.path("fields").path("status").path("name").asText("");
                    if (!allStatuses && !isIncludedStatus(statusName)) {
                        skipped.add(key + "(" + (statusName.isBlank() ? "?" : statusName) + ")");
                        continue;
                    }
                    list.add(new IssueRef(key, jiraBase + "/browse/" + key));
                }
                startAt += issues.size();
                if (issues.size() < pageSize) {
                    break;
                }
            }
            if (!skipped.isEmpty()) {
                logStep(out, "按状态跳过 " + skipped.size() + " 单: " + String.join(", ", skipped)
                        + "（仅统计: " + String.join("、", includeStatusList()) + "）");
            }
            logStep(out, "JIRA search 命中"
                    + (allStatuses ? "（全部状态）" : "可统计")
                    + " " + list.size() + " 单 / total≈" + (total == Integer.MAX_VALUE ? "?" : total) + ": "
                    + list.stream().map(IssueRef::key).limit(30).reduce((a, b) -> a + ", " + b).orElse("-")
                    + (list.size() > 30 ? "…" : ""));
            return list;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("JIRA 搜索失败: " + e.getMessage(), e);
        }
    }

    /** @deprecated 保留给含静态 browse 链接的旧页；主路径已改为 JQL search */
    @SuppressWarnings("unused")
    List<IssueRef> findIssuesUnderVersionLegacyHtml(String html, String version, GenerateResult out) {
        Document doc = Jsoup.parse(html);
        Element anchor = null;
        for (Element el : doc.select("h1,h2,h3,h4,h5,h6,strong,b,p,span,li,div")) {
            String t = el.ownText();
            if (t != null && t.contains(version)) {
                anchor = el;
                break;
            }
            if (el.text() != null && el.text().trim().equals(version)) {
                anchor = el;
                break;
            }
        }
        if (anchor == null) {
            logStep(out, "未找到版本标题节点");
            return List.of();
        }
        Element table = null;
        Element cur = anchor;
        for (int i = 0; i < 40 && cur != null; i++) {
            Element next = cur.nextElementSibling();
            if (next == null) {
                cur = cur.parent();
                continue;
            }
            cur = next;
            if ("table".equalsIgnoreCase(cur.tagName())) {
                table = cur;
                break;
            }
            Element inner = cur.selectFirst("table");
            if (inner != null) {
                table = inner;
                break;
            }
            if (cur.tagName().matches("h[1-6]") && !cur.text().contains(version)) {
                break;
            }
        }
        if (table != null) {
            return extractIssueLinks(table, out);
        }
        Element scope = anchor.parent() != null ? anchor.parent() : anchor;
        return extractIssueLinks(scope, out);
    }

    private List<IssueRef> extractIssueLinks(Element scope, GenerateResult out) {
        Map<String, IssueRef> map = new LinkedHashMap<>();
        Elements links = scope.select("a[href]");
        for (Element a : links) {
            String href = a.attr("abs:href");
            if (href.isBlank()) href = a.attr("href");
            if (href.isBlank()) continue;
            String key = null;
            Matcher m = ISSUE_KEY.matcher(href);
            if (m.find()) key = m.group(1);
            if (key == null) {
                Matcher m2 = ISSUE_KEY.matcher(a.text());
                if (m2.find()) key = m2.group(1);
            }
            if (key == null) continue;
            if (!href.contains("browse") && !href.contains("/jira/") && !href.startsWith("http")) {
                if (href.startsWith("/")) {
                    href = trimSlash(props.getWikiBaseUrl()) + href;
                }
            }
            String browse = toBrowseUrl(href, key);
            map.putIfAbsent(key, new IssueRef(key, browse));
        }
        if (out != null) {
            logStep(out, "抽取 issue keys: " + String.join(", ", map.keySet()));
        }
        return new ArrayList<>(map.values());
    }

    private String toBrowseUrl(String href, String key) {
        if (href != null && href.contains("/browse/")) {
            int i = href.indexOf("/browse/");
            String base = href.substring(0, i);
            return base + "/browse/" + key;
        }
        String jira = props.getJiraBaseUrl();
        if (jira != null && !jira.isBlank()) {
            return trimSlash(jira) + "/browse/" + key;
        }
        // 常见：wiki 同域 jira 子域猜不了，先返回原 href
        return href != null && !href.isBlank() ? href : key;
    }

    private String resolveJiraBase(List<IssueRef> refs) {
        if (props.getJiraBaseUrl() != null && !props.getJiraBaseUrl().isBlank()) {
            return trimSlash(props.getJiraBaseUrl());
        }
        for (IssueRef r : refs) {
            if (r.url != null && r.url.contains("/browse/")) {
                return r.url.substring(0, r.url.indexOf("/browse/"));
            }
        }
        return "https://jira.shdev.net";
    }

    /**
     * @return 行数据；状态不在 include-statuses 内时返回 null（跳过已关闭/开放等）
     */
    private IssueRow fetchIssueRow(String jiraBase, IssueRef ref) {
        try {
            String url = jiraBase + "/rest/api/2/issue/" + URLEncoder.encode(ref.key, StandardCharsets.UTF_8)
                    + "?expand=names,renderedFields";
            HttpResponse<String> resp = send(authGet(url));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("JIRA " + ref.key + " HTTP " + resp.statusCode());
            }
            JsonNode root = JSON.readTree(resp.body());
            JsonNode fields = root.path("fields");
            JsonNode names = root.path("names");

            String statusName = fields.path("status").path("name").asText("");
            if (!isIncludedStatus(statusName)) {
                log.info("[release-doc] 跳过 {} 状态={}（仅统计 {}）",
                        ref.key, statusName.isBlank() ? "?" : statusName, includeStatusList());
                return null;
            }

            IssueRow row = new IssueRow();
            row.setIssueKey(ref.key);
            row.setIssueUrl(trimSlash(jiraBase) + "/browse/" + ref.key);
            row.setSummary(cleanTaskText(fields.path("summary").asText(ref.key)));

            String qa = findFieldByDisplayName(fields, names, "QA Assignee", "QA", "Qa Assignee");
            if (qa == null || qa.isBlank()) {
                qa = fields.path("assignee").path("displayName").asText("");
            }
            row.setQaAssignee(blankToNone(qa));

            StringBuilder commentText = new StringBuilder();
            JsonNode comments = fields.path("comment").path("comments");
            if (comments.isArray()) {
                for (JsonNode c : comments) {
                    String body = c.path("body").asText("");
                    if (!body.isBlank()) {
                        commentText.append(body).append("\n\n");
                    }
                }
            }
            // 描述也参与解析
            String desc = fields.path("description").asText("");
            if (!desc.isBlank()) commentText.append(desc).append("\n");

            String allText = commentText.toString();
            if (isDeferredFromThisRelease(allText)) {
                log.info("[release-doc] 跳过 {}（备注含不跟本版本上线等话术）", ref.key);
                return null;
            }

            ParsedComment parsed = parseComment(allText);
            row.setBranches(blankToNone(parsed.branches));
            row.setDbChanges(blankToNone(parsed.db));
            row.setConfigChanges(blankToNone(parsed.config));
            // 备注只保留「注意」「发版顺序」相关
            row.setRemarks(blankToNone(parsed.remarks));
            return row;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("拉取 JIRA " + ref.key + " 失败: " + e.getMessage(), e);
        }
    }

    private String findFieldByDisplayName(JsonNode fields, JsonNode names, String... wantNames) {
        if (names != null && names.isObject()) {
            var it = names.fields();
            while (it.hasNext()) {
                var e = it.next();
                String display = e.getValue().asText("");
                for (String w : wantNames) {
                    if (display.equalsIgnoreCase(w) || display.contains(w)) {
                        return stringifyField(fields.path(e.getKey()));
                    }
                }
            }
        }
        // 常见自定义字段名猜测
        for (String key : List.of("customfield_10000", "customfield_10100", "customfield_10200")) {
            String v = stringifyField(fields.path(key));
            if (v != null && !v.isBlank()) {
                // 不盲目返回
            }
        }
        return "";
    }

    private String stringifyField(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return "";
        if (n.isTextual()) return n.asText();
        if (n.has("displayName")) return n.path("displayName").asText("");
        if (n.has("name")) return n.path("name").asText("");
        if (n.has("value")) {
            JsonNode v = n.get("value");
            if (v.isTextual()) return v.asText();
            if (v.has("displayName")) return v.path("displayName").asText("");
        }
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode x : n) {
                String s = stringifyField(x);
                if (!s.isBlank()) parts.add(s);
            }
            return String.join(", ", parts);
        }
        return "";
    }

    static class ParsedComment {
        String branches = "";
        String db = "";
        String config = "";
        String remarks = "";
    }

    ParsedComment parseComment(String text) {
        ParsedComment p = new ParsedComment();
        if (text == null || text.isBlank()) return p;

        // 先整段抽取 Jira {code} / markdown ``` 代码块（完整 SQL），避免只剩 CREATE/ALTER 首行
        List<String> sql = extractSqlBlocks(text);

        String[] lines = text.split("\\R");
        Set<String> branches = new LinkedHashSet<>();
        List<String> config = new ArrayList<>();
        List<String> remarks = new ArrayList<>();
        boolean inCodeFence = false;
        StringBuilder fenceBuf = new StringBuilder();

        List<String> keywords = props.getBranchKeywords() != null ? props.getBranchKeywords() : List.of();
        // 「配置文件：qa」段跳过，仅「配置文件：prod」及未标注环境的配置参与统计
        boolean skipNonProdConfigSection = false;
        // 已被上一行项目名「跨行补全」吃掉的分支行，避免再单独记一条
        Set<Integer> consumedAsBranchValue = new LinkedHashSet<>();

        for (int li = 0; li < lines.length; li++) {
            String line = normalizeWs(lines[li].trim());
            if (!inCodeFence && isCodeFenceOpen(line)) {
                inCodeFence = true;
                fenceBuf.setLength(0);
                continue;
            }
            if (inCodeFence) {
                if (isCodeFenceCloseLine(line)) {
                    inCodeFence = false;
                    String fenceBody = fenceBuf.toString().trim();
                    // 代码块若是 spring 配置（非 SQL），收进配置变更
                    if (!fenceBody.isBlank() && !looksLikeSql(fenceBody) && looksLikeConfigBlock(fenceBody)
                            && !skipNonProdConfigSection
                            && !isNonProdConfigFileBlock(fenceBody)) {
                        addConfigUnique(config, fenceBody);
                    }
                    continue;
                }
                if (fenceBuf.length() > 0) fenceBuf.append('\n');
                fenceBuf.append(line);
                continue;
            }
            if (line.isEmpty()) continue;
            if (consumedAsBranchValue.contains(li)) continue;
            // 纯测试地址 / URL 行不参与分支、配置统计
            if (isUrlOnlyLine(line)) continue;

            String configFileEnv = extractConfigFileEnv(line);
            if (configFileEnv != null) {
                skipNonProdConfigSection = !isProdConfigFileEnv(configFileEnv);
                if (skipNonProdConfigSection) {
                    continue; // 配置文件：qa（及非 prod）整段不统计
                }
                // 配置文件：prod → 下面按正常配置行收录
            } else if (skipNonProdConfigSection) {
                if (shouldSkipInNonProdConfigSection(line, keywords)) {
                    continue;
                }
                skipNonProdConfigSection = false;
            }

            List<String> fromLine = splitBranchEntries(line, keywords);
            boolean addedConfigFromBranch = false;
            if (!fromLine.isEmpty()) {
                List<String> filled = new ArrayList<>();
                for (String entry : fromLine) {
                    String filledEntry = fillBranchValueFromNextLines(entry, lines, li, keywords, consumedAsBranchValue);
                    // 值为「增加配置 / 增加xxl-job」等时归配置，不进分支
                    if (isConfigDisguisedAsBranch(filledEntry)) {
                        addConfigUnique(config, filledEntry);
                        addedConfigFromBranch = true;
                    } else if (hasBranchLikeValue(filledEntry) || isClientBranchEntry(filledEntry)) {
                        filled.add(stripRemarkNoiseFromBranch(filledEntry));
                    } else if (looksLikeConfigLine(line)) {
                        addConfigUnique(config, line);
                        addedConfigFromBranch = true;
                    }
                }
                branches.addAll(filled);
            } else if (line.matches("(?i).*(branch|分支|上线分支).*")
                    || line.matches(".*\\b[a-z0-9._-]+/[0-9]{4}-[0-9]{2}-[0-9]{2}/APP-\\d+.*")) {
                if (!line.startsWith("http") && !isUrlOnlyLine(line)) {
                    branches.add(line);
                }
            }

            if (sql.isEmpty() && SQL_LINE.matcher(line).matches()) {
                sql.add(line);
            }

            // 配置：本行 + 「增加配置」后的 spring. 续行
            if (looksLikeConfigLine(line)) {
                if (!line.equals("无") && !line.equals("无配置") && !line.contains("库下执行")) {
                    // 已从「项目：增加配置」分支路径写入过，勿再追加原文（避免 esports-api / esportsapi 双条）
                    if (!addedConfigFromBranch) {
                        addConfigUnique(config, line);
                    }
                    if (isConfigHeaderLine(line)) {
                        li = appendFollowingConfigProps(lines, li, config);
                    }
                }
            }
            if (isRemarkLine(line)) {
                remarks.add(line);
            }
        }

        p.branches = String.join("\n", mergeClientBranches(branches));
        p.db = String.join("\n\n", sql);
        p.config = String.join("\n", config);
        p.remarks = String.join("\n", remarks);
        return p;
    }

    /**
     * iOS / Android / iOS分支 / Android分支（及「客户端」）统一合并为「客户端分支: xxx」。
     * 只要出现任一端，都改成客户端分支；多端同值去重，不同值用 " / " 拼接。
     */
    static List<String> mergeClientBranches(Set<String> branches) {
        if (branches == null || branches.isEmpty()) return List.of();
        LinkedHashSet<String> clientValues = new LinkedHashSet<>();
        List<String> others = new ArrayList<>();
        for (String entry : branches) {
            if (entry == null || entry.isBlank()) continue;
            String e = entry.trim();
            Matcher m = CLIENT_BRANCH_ENTRY.matcher(e);
            if (m.matches()) {
                String val = normalizeWs(m.group(2));
                val = val.replaceAll("[，,]\\s*(顺序发版|发版顺序|发版次序|注意).*$", "").trim();
                if (!val.isBlank() && !val.equals("无")) {
                    clientValues.add(val);
                }
            } else {
                others.add(e);
            }
        }
        List<String> out = new ArrayList<>(others);
        if (!clientValues.isEmpty()) {
            out.add(0, "客户端分支: " + String.join(" / ", clientValues));
        }
        return out;
    }

    /** iOS / Android（可带「分支」）/ 客户端(分支) : value */
    private static final Pattern CLIENT_BRANCH_ENTRY = Pattern.compile(
            "(?i)^(iOS\\s*分支|Android\\s*分支|iOS|Android|客户端\\s*分支|客户端)\\s*[:：]\\s*(.+)$");

    private static boolean isClientBranchEntry(String entry) {
        return entry != null && CLIENT_BRANCH_ENTRY.matcher(entry.trim()).matches();
    }

    /** 「apollo:」/「增加配置」后连续收属性行（允许中间空行） */
    private int appendFollowingConfigProps(String[] lines, int headerIndex, List<String> config) {
        int last = headerIndex;
        for (int j = headerIndex + 1; j < lines.length; j++) {
            String next = normalizeWs(lines[j]);
            if (next.isEmpty()) {
                // apollo 块里常见空行分隔，继续往后找属性
                continue;
            }
            if (isCodeFenceOpen(next) || isCodeFenceCloseLine(next)) break;
            if (isUrlOnlyLine(next)) break;
            // 遇到下一个「配置文件：xxx」分段则停止
            if (extractConfigFileEnv(next) != null) break;
            if (isSqlSectionStop(next) && !looksLikeConfigProp(next)) break;
            // 下一行又是分支项目名则停止
            if (startsWithAnyKeyword(next, props.getBranchKeywords()) && !looksLikeConfigProp(next)) break;
            if (looksLikeConfigProp(next)) {
                addConfigUnique(config, next);
                last = j;
                continue;
            }
            // 非属性行结束续读
            break;
        }
        return last;
    }

    /** 配置条目去重：esportsapi / esport-api / esports-api 视为同一项目 */
    private static void addConfigUnique(List<String> config, String entry) {
        if (entry == null || entry.isBlank()) return;
        String display = normalizeConfigDisplay(entry);
        String id = configIdentity(display);
        if (config.stream().anyMatch(c -> configIdentity(c).equals(id))) {
            return;
        }
        config.add(display);
    }

    /** 展示统一：仓库别名 → esports-api，空格分隔改成「项目: 说明」 */
    private static String normalizeConfigDisplay(String entry) {
        String s = normalizeWs(entry);
        // esportsapi / esport-api 开头 → esports-api
        s = s.replaceAll("(?i)^esportsapi\\b", "esports-api");
        s = s.replaceAll("(?i)^esport-api\\b", "esports-api");
        // 「esports-api 增加配置」→「esports-api: 增加配置」
        s = s.replaceAll("(?i)^(esports-api)\\s+(?=增加|新增|配置|xxl)", "$1: ");
        return s;
    }

    private static String configIdentity(String s) {
        if (s == null) return "";
        String t = normalizeWs(s).toLowerCase(Locale.ROOT).replace('：', ':');
        t = t.replace("esportsapi", "esports-api").replace("esport-api", "esports-api");
        t = t.replaceAll("esports-api\\s+(?=增加|新增|配置|xxl)", "esports-api: ");
        return t.replaceAll("\\s+", " ").trim();
    }

    private static final Pattern CONFIG_FILE_ENV = Pattern.compile(
            "(?i)配置文件\\s*[:：]\\s*([a-zA-Z0-9_\\u4e00-\\u9fff]+)");

    /**
     * 从「配置文件：qa / prod」行解析环境名；非此类行返回 null。
     */
    static String extractConfigFileEnv(String line) {
        if (line == null || !line.contains("配置文件")) return null;
        Matcher m = CONFIG_FILE_ENV.matcher(line);
        if (!m.find()) return null;
        return m.group(1).trim();
    }

    /** 仅 prod / 生产 / 线上 等计入发版配置 */
    static boolean isProdConfigFileEnv(String env) {
        if (env == null || env.isBlank()) return false;
        String e = env.trim().toLowerCase(Locale.ROOT);
        return e.equals("prod") || e.equals("production")
                || e.equals("生产") || e.equals("线上") || e.equals("正式");
    }

    /** qa 段内的配置行（spring / 增加配置等）跳过；真正的分支行则结束跳过 */
    private boolean shouldSkipInNonProdConfigSection(String line, List<String> keywords) {
        if (looksLikeSpringProp(line)) return true;
        if (isConfigHeaderLine(line)) return true;
        List<String> fromLine = splitBranchEntries(line, keywords);
        for (String entry : fromLine) {
            if (hasBranchLikeValue(entry) || isClientBranchEntry(entry)) {
                return false;
            }
            if (isConfigDisguisedAsBranch(entry)) return true;
        }
        return looksLikeConfigLine(line);
    }

    /** 代码块正文若标明配置文件：qa 则整块不收 */
    private static boolean isNonProdConfigFileBlock(String body) {
        if (body == null) return false;
        for (String line : body.split("\\R")) {
            String env = extractConfigFileEnv(normalizeWs(line));
            if (env != null) return !isProdConfigFileEnv(env);
        }
        return false;
    }

    private static boolean isConfigHeaderLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        // apollo: / Apollo / apollo配置
        if (lower.matches("apollo\\s*[:：]?\\s*") || lower.startsWith("apollo:") || lower.startsWith("apollo：")
                || lower.equals("apollo") || (lower.contains("apollo") && (t.contains("配置") || t.contains(":")))) {
            return true;
        }
        return t.contains("增加配置") || t.contains("新增配置")
                || t.matches("(?i).*增加\\s*xxl.*")
                || (t.contains("配置") && (t.contains("已加") || t.contains("测试环境")));
    }

    private static boolean looksLikeConfigLine(String line) {
        if (line == null || line.isBlank()) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        if (line.equals("无") || line.equals("无配置")) return false;
        if (line.contains("库下执行")) return false;
        if (isUrlOnlyLine(line)) return false;
        return lower.contains("apollo")
                || line.contains("配置")
                || lower.contains("xxljob")
                || lower.contains("xxl-job")
                || line.contains("开关")
                || lower.contains("config")
                || looksLikeConfigProp(line);
    }

    /** spring.* / deadlock.xxx=true 等配置属性行 */
    private static boolean looksLikeSpringProp(String line) {
        return looksLikeConfigProp(line);
    }

    private static boolean looksLikeConfigProp(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (t.matches("(?i)^spring\\.[\\w.-]+\\s*=.*")) return true;
        if (t.matches("(?i)^[a-z][\\w.-]*\\.(jdbcUrl|username|password|driver-class-name|url)\\s*=.*")) {
            return true;
        }
        // apollo 常见：deadlock.weekly.title....enabled=true（至少一段点号）
        if (t.matches("(?i)^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+\\s*=.*")) return true;
        // 宽松 key=value（排除纯 URL）
        if (t.matches("(?i)^[a-z0-9._-]+\\s*=.+") && !t.contains("://") && !t.startsWith("http")) {
            return true;
        }
        return false;
    }

    private static boolean looksLikeConfigBlock(String body) {
        if (body == null || body.isBlank()) return false;
        for (String line : body.split("\\R")) {
            if (looksLikeSpringProp(line.trim()) || looksLikeConfigLine(line.trim())) return true;
        }
        return false;
    }

    /** 测试环境地址等 URL 行，不进分支/配置 */
    private static boolean isUrlOnlyLine(String line) {
        if (line == null || line.isBlank()) return false;
        String t = line.trim();
        if (t.matches("(?i)^https?://\\S+$")) return true;
        // 「xxx地址： https://...」或整行以地址说明 + URL
        if (t.contains("http://") || t.contains("https://")) {
            if (t.contains("地址") || t.matches("(?i).*\\burl\\b.*") || t.contains("链接")) {
                return true;
            }
            // 几乎整行都是 URL 描述
            String withoutUrl = t.replaceAll("(?i)https?://\\S+", "").replaceAll("[:：\\s]", "");
            return withoutUrl.length() < 24;
        }
        return false;
    }

    /** event-center: 增加xxl-job → 配置，不是分支 */
    private static boolean isConfigDisguisedAsBranch(String entry) {
        if (entry == null) return false;
        int colon = Math.max(entry.indexOf(':'), entry.indexOf('：'));
        if (colon < 0) return false;
        String val = entry.substring(colon + 1).trim();
        if (val.isBlank()) return false;
        String lower = val.toLowerCase(Locale.ROOT);
        if (lower.contains("增加") && (lower.contains("xxl") || lower.contains("配置") || lower.contains("job"))) {
            return true;
        }
        return val.contains("配置") && !val.contains("/");
    }

    private static boolean hasBranchLikeValue(String entry) {
        if (entry == null || entry.isBlank()) return false;
        int colon = Math.max(entry.indexOf(':'), entry.indexOf('：'));
        String val = colon >= 0 ? entry.substring(colon + 1).trim() : entry.trim();
        if (val.isBlank() || val.equalsIgnoreCase("无")) return false;
        if (val.startsWith("http")) return false;
        if (isConfigDisguisedAsBranch(entry)) return false;
        return looksLikeDevBranchPath(val);
    }

    /**
     * 开发分支路径：需含拉丁字母，避免把「才华/曹键/中华」这类中文斜杠名单当成分支。
     * 同时排除 spring 配置 / jdbc URL（含 ://），避免「增加配置」下一行被当成分支。
     */
    private static boolean looksLikeDevBranchPath(String val) {
        if (val == null || val.isBlank()) return false;
        String v = val.trim();
        if (v.equalsIgnoreCase("无") || v.contains("配置") || v.toLowerCase(Locale.ROOT).contains("xxl")) {
            return false;
        }
        if (looksLikeSpringProp(v) || looksLikeConfigLine(v)) return false;
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:") || lower.contains("://") || lower.startsWith("spring.")) {
            return false;
        }
        // key=value 属性行
        if (v.matches("(?i)^[a-z][\\w.-]*\\s*=.*")) return false;
        // 纯中文（无英文字母）不视为分支
        if (!v.matches(".*[a-zA-Z].*")) return false;
        return v.contains("/")
                || v.matches("(?i).*(feat|fix|hotfix|release|chore)/.*")
                || v.matches(".*APP-\\d+.*")
                || v.matches(".*\\d{4}[-/]?\\d{2}[-/]?\\d{2}.*")
                || v.matches("(?i)^[a-z][a-z0-9._-]*\\d{6,}.*"); // dev20260723
    }

    /** 分支值去掉尾部「顺序发版」等，避免污染分支列（备注另计） */
    private static String stripRemarkNoiseFromBranch(String entry) {
        if (entry == null) return "";
        String e = entry.trim();
        e = e.replaceAll("[，,]\\s*(顺序发版|发版顺序|发版次序|注意).*$", "").trim();
        e = e.replaceAll("\\s+(顺序发版|发版顺序|发版次序)\\s*$", "").trim();
        return e;
    }

    private static boolean isCodeFenceOpen(String line) {
        if (line == null || line.isBlank()) return false;
        String t = line.trim();
        return t.startsWith("{code") || t.startsWith("```");
    }

    private static boolean isCodeFenceCloseLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        return t.equals("{code}") || t.equals("```");
    }

    /**
     * 抽取完整 SQL 块：优先 Jira {@code {code:java}...{code}}，其次 markdown ```；
     * 再兜底「xxx库下执行：」后的整段纯文本（非围栏）。
     * 块前一行若含「库下执行」则一并带上。
     */
    List<String> extractSqlBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) return blocks;

        // Jira wiki：标题 + {code}/{code:java} ... {code}（标题与围栏间可有空行）
        Pattern jiraCode = Pattern.compile(
                "(?is)(?:^|\\R)([^\r\n]*库下执行[^\r\n]*)\\s*\\R\\s*\\{code(?::[^\\}]*)?\\}\\s*\\R?(.*?)\\s*\\{code\\}");
        Matcher jm = jiraCode.matcher(text);
        while (jm.find()) {
            String header = normalizeWs(jm.group(1));
            String body = jm.group(2).replace("\r\n", "\n").replace('\r', '\n').trim();
            if (!body.isBlank()) {
                blocks.add(header.isBlank() ? body : header + "\n" + body);
            }
        }
        // 无「库下执行」标题的纯 code 块（内容像 SQL 才收）
        if (blocks.isEmpty()) {
            Pattern jiraCode2 = Pattern.compile("(?is)\\{code(?::[^\\}]*)?\\}\\s*\\R?(.*?)\\s*\\{code\\}");
            Matcher jm2 = jiraCode2.matcher(text);
            while (jm2.find()) {
                String body = jm2.group(1).replace("\r\n", "\n").replace('\r', '\n').trim();
                if (looksLikeSql(body)) {
                    int start = jm2.start();
                    String before = text.substring(Math.max(0, start - 120), start);
                    String[] prev = before.split("\\R");
                    String header = "";
                    for (int i = prev.length - 1; i >= 0; i--) {
                        String pl = normalizeWs(prev[i]);
                        if (pl.isEmpty()) continue;
                        if (pl.contains("库下执行") || pl.contains("执行：")) {
                            header = pl;
                        }
                        break;
                    }
                    blocks.add(header.isBlank() ? body : header + "\n" + body);
                }
            }
        }

        // markdown ```sql ... ```
        Pattern md = Pattern.compile("(?is)(?:^|\\R)([^\r\n]*库下执行[^\r\n]*)?\\s*\\R?```(?:sql|SQL|java)?\\s*\\R?(.*?)\\s*```");
        Matcher mm = md.matcher(text);
        while (mm.find()) {
            String header = normalizeWs(mm.group(1) != null ? mm.group(1) : "");
            String body = mm.group(2).replace("\r\n", "\n").replace('\r', '\n').trim();
            if (!body.isBlank() && looksLikeSql(body)) {
                String block = header.isBlank() ? body : header + "\n" + body;
                String tip = body.substring(0, Math.min(40, body.length()));
                if (blocks.stream().noneMatch(b -> b.contains(tip))) {
                    blocks.add(block);
                }
            }
        }

        // 无围栏：从「库下执行」行起，连吃后续 SQL 相关行直到段落结束
        if (blocks.isEmpty()) {
            blocks.addAll(extractPlainSqlAfterDbHint(text));
        }
        return blocks;
    }

    /** 「app_admin库下执行：」后直接贴 SQL（无 {code}）时的整段抽取 */
    private static List<String> extractPlainSqlAfterDbHint(String text) {
        List<String> blocks = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String header = normalizeWs(lines[i]);
            if (!header.contains("库下执行")) continue;

            StringBuilder body = new StringBuilder();
            boolean inFence = false;
            for (int j = i + 1; j < lines.length; j++) {
                String raw = lines[j];
                String t = normalizeWs(raw);
                if (!inFence && isCodeFenceOpen(t)) {
                    inFence = true;
                    continue;
                }
                if (inFence) {
                    if (isCodeFenceCloseLine(t)) inFence = false;
                    else {
                        if (body.length() > 0) body.append('\n');
                        body.append(raw.replace("\r", ""));
                    }
                    continue;
                }
                if (t.isEmpty()) {
                    // 空行：若已有 SQL 且下一非空行不像 SQL，则结束
                    String nextNonEmpty = peekNextNonEmpty(lines, j + 1);
                    if (body.length() > 0 && !looksLikeSqlContinuation(nextNonEmpty)) break;
                    if (body.length() > 0) body.append('\n');
                    continue;
                }
                if (isSqlSectionStop(t) && body.length() > 0) break;
                if (looksLikeSqlContinuation(t) || body.length() > 0 && looksLikeSqlContinuationContinuation(t)) {
                    if (body.length() > 0) body.append('\n');
                    body.append(raw.replace("\r", ""));
                    continue;
                }
                if (body.length() > 0) break;
            }
            String b = body.toString().trim();
            if (!b.isBlank() && looksLikeSql(b)) {
                blocks.add(header + "\n" + b);
            }
        }
        return blocks;
    }

    private static String peekNextNonEmpty(String[] lines, int from) {
        for (int k = from; k < lines.length; k++) {
            String t = normalizeWs(lines[k]);
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static boolean isSqlSectionStop(String line) {
        if (line == null || line.isBlank()) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        if (line.contains("库下执行")) return true;
        if (line.contains("注意") || line.contains("发版顺序") || line.contains("顺序发版")) return true;
        if (lower.contains("apollo") || lower.contains("xxl") || line.contains("配置变更") || line.contains("上线分支")) {
            return true;
        }
        return line.matches("(?i)^(branch|分支|配置|备注)\\s*[:：].*");
    }

    /** 是否像 SQL 语句/建表续行 */
    private static boolean looksLikeSqlContinuation(String line) {
        if (line == null || line.isBlank()) return false;
        if (SQL_LINE.matcher(line).matches()) return true;
        String u = line.toUpperCase(Locale.ROOT);
        return u.contains("CREATE TABLE") || u.contains("ALTER TABLE") || u.contains("DROP INDEX")
                || u.contains("ADD COLUMN") || u.contains("ADD UNIQUE") || u.contains("PRIMARY KEY")
                || u.contains("UNIQUE KEY") || u.contains("ENGINE=") || u.startsWith("KEY ")
                || u.contains("COMMENT ") || line.trim().startsWith("(") || line.trim().startsWith(")")
                || line.trim().startsWith(",");
    }

    private static boolean looksLikeSqlContinuationContinuation(String line) {
        // 建表字段行：id BIGINT ... / -- 注释
        String t = line.trim();
        if (t.startsWith("--") || t.startsWith("#")) return true;
        if (t.matches("(?i)^[`\\w]+\\s+(BIGINT|INT|VARCHAR|DATE|DATETIME|TEXT|DECIMAL|TINYINT|SMALLINT|MEDIUMTEXT|LONGTEXT)\\b.*")) {
            return true;
        }
        return looksLikeSqlContinuation(line);
    }

    private static boolean looksLikeSql(String body) {
        if (body == null || body.isBlank()) return false;
        for (String line : body.split("\\R")) {
            if (SQL_LINE.matcher(line.trim()).matches()) return true;
        }
        String u = body.toUpperCase(Locale.ROOT);
        return u.contains("CREATE TABLE") || u.contains("ALTER TABLE") || u.contains("DROP INDEX");
    }

    /** 备注仅统计「注意」「发版顺序」相关行 */
    private static boolean isRemarkLine(String line) {
        if (line == null || line.isBlank()) return false;
        return line.contains("注意")
                || line.contains("发版顺序")
                || line.contains("顺序发版")
                || line.contains("发版次序");
    }

    /**
     * 备注写明不跟本版本上线时整单不统计，例如：
     * {@code QA：暂时不跟7.23日版本上线}
     */
    static boolean isDeferredFromThisRelease(String text) {
        if (text == null || text.isBlank()) return false;
        for (String raw : text.split("\\R")) {
            String line = normalizeWs(raw);
            if (line.isBlank()) continue;
            if (DEFERRED_RELEASE.matcher(line).find()) {
                return true;
            }
        }
        // 整段再扫一次，防止话术被拆到两行
        return DEFERRED_RELEASE.matcher(normalizeWs(text.replace('\n', ' '))).find();
    }

    private static final Pattern DEFERRED_RELEASE = Pattern.compile(
            "(暂时)?不跟[^\\n]{0,40}上线"
                    + "|(暂时)?不跟[^\\n]{0,20}版本"
                    + "|不随[^\\n]{0,20}(版本|发版|上线)"
                    + "|本[期次]不上(线|版)?"
                    + "|不进本?[期次]?版本"
                    + "|不参加[^\\n]{0,20}(发版|上线)"
                    + "|本次不发版"
                    + "|本版本不上线",
            Pattern.CASE_INSENSITIVE);

    private static String normalizeWs(String s) {
        if (s == null) return "";
        // Jira 注释里常见 &nbsp; / 全角空格 / 零宽字符
        return s.replace('\u00a0', ' ')
                .replace('\u3000', ' ')
                .replace('\u2007', ' ')
                .replace('\u202f', ' ')
                .replaceAll("[\\u200b\\u200c\\u200d\\ufeff]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 形如 {@code global-video:} / 仅 {@code comment-service} 下一行才是分支名时，
     * 补全为 {@code comment-service: zhaojinfeng/2026-07-03/reply}。
     */
    private String fillBranchValueFromNextLines(String entry, String[] lines, int currentIndex,
                                                 List<String> keywords, Set<Integer> consumedAsBranchValue) {
        if (entry == null) return "";
        String e = normalizeWs(entry);
        int colon = Math.max(e.indexOf(':'), e.indexOf('：'));
        String repo;
        String val;
        if (colon < 0) {
            // 纯项目名（无冒号）也尝试读下一行分支
            if (!isBareProjectName(e, keywords)) return e;
            repo = normalizeRepoName(e);
            val = "";
        } else {
            repo = normalizeRepoName(e.substring(0, colon).trim());
            val = normalizeWs(e.substring(colon + 1));
            val = val.replaceAll("^[:：]+", "").trim();
            if (!val.isBlank()) {
                return repo + ": " + val;
            }
        }
        for (int j = currentIndex + 1; j < lines.length && j <= currentIndex + 3; j++) {
            String next = normalizeWs(lines[j]);
            if (next.isEmpty()) continue;
            // 下一行又是仓库名 / SQL / QA 则停止
            if (startsWithAnyKeyword(next, keywords)) break;
            if (SQL_LINE.matcher(next).matches() || next.contains("库下执行")) break;
            if (next.startsWith("QA") || next.startsWith("{code")) break;
            if (isUrlOnlyLine(next)) break;
            // spring 配置 /「增加配置」续行绝不当分支
            if (looksLikeSpringProp(next) || looksLikeConfigLine(next)
                    || next.toLowerCase(Locale.ROOT).contains("://")
                    || next.toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
                break;
            }
            if (!looksLikeLooseBranchToken(next)) break;
            if (isConfigDisguisedAsBranch(repo + ": " + next)) break;
            if (consumedAsBranchValue != null) {
                consumedAsBranchValue.add(j);
            }
            return repo + ": " + next;
        }
        return colon < 0 ? repo : repo + ":";
    }

    /** 是否仅为项目关键字（可带尾部冒号），无分支值 */
    private static boolean isBareProjectName(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null) return false;
        String t = text.trim().replaceAll("[:：]\\s*$", "").trim();
        if (t.isBlank()) return false;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank() || isClientSideKeyword(kw)) continue;
            if (t.equalsIgnoreCase(kw.trim()) || t.equalsIgnoreCase(normalizeRepoName(kw))) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAnyKeyword(String line, List<String> keywords) {
        if (keywords == null || line == null) return false;
        String t = line.trim();
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            if (t.regionMatches(true, 0, kw, 0, kw.length())) {
                if (t.length() == kw.length()) return true;
                char c = t.charAt(kw.length());
                if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') return true;
            }
        }
        return false;
    }

    /**
     * 把一行里的多个仓库分支拆成多条，例如：
     * {@code swift-sphere : cj/app-8766/deadlock-hero data-center: zq/2026-07-08/APP-8766}
     * → 两行。
     * 也支持 {@code app-engine、esport-api、app-community：dyl/20260707/deadlock_rating，顺序发版}
     * → 三个项目共用同一分支。
     */
    List<String> splitBranchEntries(String line, List<String> keywords) {
        List<String> result = new ArrayList<>();
        if (line == null || line.isBlank() || keywords == null || keywords.isEmpty()) {
            return result;
        }
        String trimmed = normalizeWs(stripSimpleJiraMarkup(line));
        if (trimmed.isBlank()) return result;

        // 先尝试「多项目、分隔 + 共用一个分支」
        List<String> shared = expandSharedBranchLine(trimmed, keywords);
        if (!shared.isEmpty()) {
            return shared;
        }

        List<Hit> hits = findKeywordHits(trimmed, keywords);
        if (hits.isEmpty()) return result;
        return pairKeywordHitsWithBranches(trimmed, hits);
    }

    private record Hit(int index, String keyword) {}

    /** 按词边界命中项目关键字（更长优先，避免短词误伤） */
    private List<Hit> findKeywordHits(String line, List<String> keywords) {
        List<Hit> hits = new ArrayList<>();
        if (line == null || line.isBlank() || keywords == null) return hits;
        List<String> sortedKw = new ArrayList<>(keywords);
        sortedKw.sort((a, b) -> Integer.compare(
                b == null ? 0 : b.length(),
                a == null ? 0 : a.length()));
        boolean[] covered = new boolean[line.length()];
        for (String kw : sortedKw) {
            if (kw == null || kw.isBlank()) continue;
            int from = 0;
            while (from < line.length()) {
                int i = indexOfIgnoreCase(line, kw, from);
                if (i < 0) break;
                boolean overlap = false;
                for (int p = i; p < i + kw.length() && p < covered.length; p++) {
                    if (covered[p]) {
                        overlap = true;
                        break;
                    }
                }
                boolean leftOk = i == 0 || !isIdentChar(line.charAt(i - 1));
                boolean rightOk = i + kw.length() >= line.length() || !isIdentChar(line.charAt(i + kw.length()));
                if (!overlap && leftOk && rightOk) {
                    hits.add(new Hit(i, kw));
                    for (int p = i; p < i + kw.length() && p < covered.length; p++) {
                        covered[p] = true;
                    }
                }
                from = i + Math.max(1, kw.length());
            }
        }
        hits.sort((a, b) -> Integer.compare(a.index, b.index));
        return hits;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    /**
     * 把命中的项目关键字与前后分支配对，兼容：
     * <ul>
     *   <li>{@code eventcenter fch/20260714/APP-8742}（项目在前）</li>
     *   <li>{@code fch/20260721/fixhotfeigine appuser}（分支在前）</li>
     *   <li>同一行多个：{@code eventcenter fch/A fch/B appuser}</li>
     * </ul>
     */
    private List<String> pairKeywordHitsWithBranches(String line, List<Hit> hits) {
        List<String> result = new ArrayList<>();
        if (hits == null || hits.isEmpty()) return result;
        String[] values = new String[hits.size()];

        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            int from = hit.index + hit.keyword.length();
            int to = (i + 1 < hits.size()) ? hits.get(i + 1).index : line.length();
            String after = normalizeWs(line.substring(from, to));
            after = after.replaceFirst("^[:：]\\s*", "").trim();
            List<String> afterTokens = tokenizeBranchCandidates(after);

            if (afterTokens.isEmpty()) {
                // 「esportsapi 增加配置 测试环境已加」→ 保留话术，后续归入配置列，勿跨行把 jdbcUrl 当成分支
                if (isConfigPhraseAfterProject(after)) {
                    values[i] = after;
                }
                continue;
            }

            boolean nextNeedsBefore = false;
            if (i + 1 < hits.size()) {
                Hit next = hits.get(i + 1);
                int nFrom = next.index + next.keyword.length();
                int nTo = (i + 2 < hits.size()) ? hits.get(i + 2).index : line.length();
                String nextAfter = normalizeWs(line.substring(nFrom, nTo)).replaceFirst("^[:：]\\s*", "").trim();
                nextNeedsBefore = tokenizeBranchCandidates(nextAfter).isEmpty();
            }

            if (nextNeedsBefore && afterTokens.size() >= 2) {
                values[i] = String.join(" ", afterTokens.subList(0, afterTokens.size() - 1));
                if (values[i + 1] == null) {
                    values[i + 1] = afterTokens.get(afterTokens.size() - 1);
                }
            } else if (nextNeedsBefore && afterTokens.size() == 1) {
                // 两个项目夹一个分支：优先给前面的「项目 分支」；后面若仍空再靠行首 before 补
                values[i] = afterTokens.get(0);
            } else {
                values[i] = String.join(" ", afterTokens);
            }
        }

        // 行首「分支 项目」：第一个关键字前面的分支
        if (values[0] == null || values[0].isBlank()) {
            String beforeFirst = normalizeWs(line.substring(0, hits.get(0).index));
            beforeFirst = beforeFirst.replaceAll("[,，:：]+$", "").trim();
            List<String> beforeTokens = tokenizeBranchCandidates(beforeFirst);
            if (!beforeTokens.isEmpty()) {
                values[0] = String.join(" ", beforeTokens);
            }
        }

        // 若后面的项目仍空，尝试吃掉前一个 gap 里多出来的最后段（已在 size>=2 处理）
        for (int i = 1; i < hits.size(); i++) {
            if (values[i] != null && !values[i].isBlank()) continue;
            // 再扫一次：关键字紧前的单个分支 token
            int prevEnd = hits.get(i - 1).index + hits.get(i - 1).keyword.length();
            String between = normalizeWs(line.substring(prevEnd, hits.get(i).index));
            between = between.replaceFirst("^[:：]\\s*", "").trim();
            List<String> tokens = tokenizeBranchCandidates(between);
            if (tokens.size() == 1 && (values[i - 1] == null || values[i - 1].isBlank())) {
                values[i] = tokens.get(0);
            } else if (tokens.size() == 1 && values[i - 1] != null
                    && values[i - 1].equals(tokens.get(0))) {
                // 唯一分支已被前一个项目占用：同分支也记到本项目（少见）；更常见是分行书写
                // 这里不重复占用
            } else if (tokens.size() >= 2 && values[i - 1] != null) {
                String last = tokens.get(tokens.size() - 1);
                if (!values[i - 1].endsWith(last)) {
                    values[i] = last;
                } else if (tokens.size() >= 2) {
                    values[i] = last;
                    // 若前值含多个，裁掉最后一段
                    List<String> prevToks = tokenizeBranchCandidates(values[i - 1]);
                    if (prevToks.size() >= 2 && prevToks.get(prevToks.size() - 1).equals(last)) {
                        values[i - 1] = String.join(" ", prevToks.subList(0, prevToks.size() - 1));
                    }
                }
            }
        }

        LinkedHashSet<String> dedupe = new LinkedHashSet<>();
        for (int i = 0; i < hits.size(); i++) {
            String val = values[i];
            if (val == null || val.isBlank()) {
                // 无分支值时仍保留「项目:」便于跨行补全
                dedupe.add(normalizeRepoName(hits.get(i).keyword) + ":");
                continue;
            }
            String entry = normalizeRepoName(hits.get(i).keyword) + ": " + val.trim();
            entry = stripRemarkNoiseFromBranch(entry);
            if (!entry.isBlank()) dedupe.add(entry);
        }
        return new ArrayList<>(dedupe);
    }

    private static List<String> tokenizeBranchCandidates(String text) {
        if (text == null || text.isBlank()) return List.of();
        String t = text.trim();
        t = t.replaceAll("[，,]\\s*(顺序发版|发版顺序|发版次序|注意).*$", "").trim();
        if (looksLikeSpringProp(t) || t.toLowerCase(Locale.ROOT).contains("://")) {
            return List.of();
        }
        String[] parts = t.split("[\\s,，;；]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String x = p.trim();
            if (x.isBlank()) continue;
            if (looksLikeDevBranchPath(x)) out.add(x);
        }
        if (out.isEmpty() && looksLikeDevBranchPath(t)) {
            out.add(t);
        }
        return out;
    }

    /** 项目名后的「增加配置 / 增加xxl-job」等，不是分支名 */
    private static boolean isConfigPhraseAfterProject(String after) {
        if (after == null || after.isBlank()) return false;
        String a = after.trim();
        return a.contains("增加配置") || a.contains("新增配置")
                || a.matches("(?i).*增加\\s*xxl.*")
                || (a.contains("配置") && (a.contains("已加") || a.contains("测试环境") || a.contains("生产")));
    }

    private static String stripSimpleJiraMarkup(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)\\{color[^}]*\\}", "")
                .replaceAll("(?i)\\{color\\}", "")
                // 仅去掉 [显示文字|链接] wiki 链，保留普通文本
                .replaceAll("(?i)\\[[^\\]|\\n]*\\|[^\\]\\n]+\\]", "")
                .trim();
    }

    /** 去掉任务标题末尾的「人名 / 提测 / 上线」后缀；JIRA 链接仍由调用方单独保留。 */
    static String cleanTaskText(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String cleaned = text.replace('\u00a0', ' ').strip();
        for (Pattern pat : TASK_SUFFIX_PATTERNS) {
            cleaned = pat.matcher(cleaned).replaceFirst("").strip();
        }
        return cleaned;
    }

    private static void applyTaskTextCleanup(List<IssueRow> rows) {
        if (rows == null) {
            return;
        }
        for (IssueRow row : rows) {
            row.setSummary(cleanTaskText(row.getSummary()));
        }
    }

    /**
     * {@code fch/20260721/fixhotfeigine appuser} / {@code feat/x app-user}
     * → 前面是分支，行尾项目名。
     * @deprecated 主路径已并入 {@link #pairKeywordHitsWithBranches}；保留给单测/兼容。
     */
    List<String> expandBranchThenProjectLine(String line, List<String> keywords) {
        return splitBranchEntries(line, keywords).stream()
                .filter(e -> {
                    int c = Math.max(e.indexOf(':'), e.indexOf('：'));
                    return c > 0 && c < e.length() - 1;
                })
                .toList();
    }

    private static boolean isClientSideKeyword(String kw) {
        if (kw == null) return false;
        String k = kw.trim().toLowerCase(Locale.ROOT);
        return k.equals("ios") || k.equals("android")
                || k.contains("ios") && k.contains("分支")
                || k.contains("android") && k.contains("分支")
                || k.contains("客户端");
    }

    private static boolean startsWithProjectKeyword(String text, List<String> keywords) {
        if (text == null || text.isBlank()) return false;
        String t = text.trim();
        List<String> sorted = new ArrayList<>(keywords);
        sorted.sort((a, b) -> Integer.compare(
                b == null ? 0 : b.length(),
                a == null ? 0 : a.length()));
        for (String kw : sorted) {
            if (kw == null || kw.isBlank() || isClientSideKeyword(kw)) continue;
            if (t.regionMatches(true, 0, kw, 0, kw.length())) {
                char next = t.length() > kw.length() ? t.charAt(kw.length()) : ' ';
                if (Character.isLetterOrDigit(next) || next == '-' || next == '_') continue;
                String rest = t.substring(kw.length()).trim().replaceFirst("^[:：]\\s*", "");
                return looksLikeLooseBranchToken(rest);
            }
        }
        return false;
    }

    /** 宽松判断：是否像分支路径（含 /、日期、APP-单号、feat/fix 等） */
    private static boolean looksLikeLooseBranchToken(String val) {
        return looksLikeDevBranchPath(val);
    }

    /**
     * {@code app-engine、esport-api、app-community：dyl/20260707/deadlock_rating，顺序发版}
     * → 每个项目一条，共用分支。
     */
    List<String> expandSharedBranchLine(String line, List<String> keywords) {
        List<String> result = new ArrayList<>();
        if (line == null || line.isBlank()) return result;
        // 找「项目列表：分支」——冒号前至少含一个顿号/逗号连接的项目名
        Matcher m = Pattern.compile("^(.+?)\\s*[:：]\\s*(.+)$").matcher(line.trim());
        if (!m.find()) return result;
        String left = m.group(1).trim();
        String right = m.group(2).trim();
        // 右侧若是配置话术，不当共享分支
        if (right.contains("配置") || right.toLowerCase(Locale.ROOT).contains("xxl")
                || right.contains("增加") && !right.contains("/")) {
            return result;
        }
        // 左侧需有顿号/斜杠连接多个项目，或至少命中 >=2 个关键字
        List<String> hitKws = new ArrayList<>();
        List<String> sortedKw = new ArrayList<>(keywords);
        sortedKw.sort((a, b) -> Integer.compare(
                b == null ? 0 : b.length(),
                a == null ? 0 : a.length()));
        String remain = left;
        for (String kw : sortedKw) {
            if (kw == null || kw.isBlank()) continue;
            int i = indexOfIgnoreCase(remain, kw, 0);
            if (i >= 0) {
                hitKws.add(kw);
                // 从 remain 抹掉已命中，避免短词重复
                remain = remain.substring(0, i) + " ".repeat(kw.length()) + remain.substring(i + kw.length());
            }
        }
        if (hitKws.size() < 2 && !(left.contains("、") || left.contains(","))) {
            return result;
        }
        if (hitKws.isEmpty()) return result;

        String branch = right;
        branch = branch.replaceAll("[，,]\\s*(顺序发版|发版顺序|发版次序|注意).*$", "").trim();
        branch = branch.replaceAll("[；;].*$", "").trim();
        if (!hasBranchLikeValue("x: " + branch)) return result;

        // 保持原文出现顺序
        hitKws.sort((a, b) -> Integer.compare(
                indexOfIgnoreCase(left, a, 0),
                indexOfIgnoreCase(left, b, 0)));
        for (String kw : hitKws) {
            result.add(normalizeRepoName(kw) + ": " + branch);
        }
        return result;
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        if (haystack == null || needle == null) return -1;
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    /** 兼容开发笔误：esport-api / esportsapi → esports-api；eventcenter → event-center；appuser → app-user；datacenter → data-center */
    private static String normalizeRepoName(String kw) {
        if (kw == null) return "";
        String k = kw.trim();
        if (k.equalsIgnoreCase("esport-api") || k.equalsIgnoreCase("esportsapi")) {
            return "esports-api";
        }
        if (k.equalsIgnoreCase("eventcenter") || k.equalsIgnoreCase("event_center")) {
            return "event-center";
        }
        if (k.equalsIgnoreCase("appuser") || k.equalsIgnoreCase("app_user")) {
            return "app-user";
        }
        if (k.equalsIgnoreCase("commentservice") || k.equalsIgnoreCase("comment_service")) {
            return "comment-service";
        }
        if (k.equalsIgnoreCase("datacenter") || k.equalsIgnoreCase("data_center")) {
            return "data-center";
        }
        return k;
    }

    private String normalizeBranchSegment(String segment, String keyword) {
        if (segment == null) return "";
        String s = segment.trim();
        // 去掉尾部其它说明噪音
        s = s.replaceAll("[；;。]\\s*$", "").trim();
        // 已是 keyword : value / keyword: value
        Pattern p = Pattern.compile(
                Pattern.quote(keyword) + "\\s*[:：]?\\s*(.+)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(s);
        if (m.find()) {
            String val = m.group(1).trim();
            // 「comment-service:」时正则可能把尾部冒号吃进 value，视为无分支
            val = val.replaceAll("^[:：]+", "").trim();
            // 若 value 里又粘了下一个仓库名，截断（双保险）
            for (String kw : props.getBranchKeywords() != null ? props.getBranchKeywords() : List.<String>of()) {
                if (kw == null || kw.equals(keyword) || kw.isBlank()) continue;
                int j = indexOfIgnoreCase(val, kw, 0);
                if (j > 0) {
                    val = val.substring(0, j).trim();
                }
            }
            val = stripRemarkNoiseFromBranch("x: " + val);
            if (val.startsWith("x:")) val = val.substring(2).trim();
            // 去掉前导顿号残留
            val = val.replaceAll("^[、,/\\s]+", "").trim();
            if (val.isBlank()) return normalizeRepoName(keyword) + ":";
            return normalizeRepoName(keyword) + ": " + val;
        }
        // 「comment-service:」整段未拆出 value 时仍保留冒号，便于跨行补全
        String bare = s.replaceAll("[:：]\\s*$", "").trim();
        if (bare.equalsIgnoreCase(keyword) || bare.equalsIgnoreCase(normalizeRepoName(keyword))) {
            return normalizeRepoName(keyword) + (s.matches(".*[:：]\\s*$") ? ":" : "");
        }
        return normalizeRepoName(s);
    }

    private String extractBranchToken(String line, String kw) {
        List<String> parts = splitBranchEntries(line, List.of(kw));
        return parts.isEmpty() ? line : parts.get(0);
    }

    private JsonNode createConfluenceChildPage(String title, String storageHtml) {
        try {
            String parentUrl = trimSlash(props.getWikiBaseUrl()) + "/rest/api/content/" + props.getParentPageId()
                    + "?expand=space";
            HttpResponse<String> parentResp = send(authGet(parentUrl));
            if (parentResp.statusCode() < 200 || parentResp.statusCode() >= 300) {
                throw new IllegalStateException("读取父页面失败 HTTP " + parentResp.statusCode());
            }
            JsonNode parent = JSON.readTree(parentResp.body());
            String spaceKey = parent.path("space").path("key").asText("");
            if (spaceKey.isBlank()) {
                throw new IllegalStateException("父页面未返回 space.key，无法创建子页面");
            }

            // 同标题已存在则覆盖更新（之前生成错了可重跑）
            JsonNode existing = findPageByTitle(spaceKey, title);
            if (existing != null) {
                return updateConfluencePage(existing, title, spaceKey, storageHtml);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "page");
            body.put("title", title);
            body.put("space", Map.of("key", spaceKey));
            body.put("ancestors", List.of(Map.of("id", props.getParentPageId())));
            body.put("body", Map.of(
                    "storage", Map.of(
                            "value", storageHtml,
                            "representation", "storage"
                    )
            ));

            String createUrl = trimSlash(props.getWikiBaseUrl()) + "/rest/api/content?os_authType=basic";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(createUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", basicAuthHeader())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = send(req);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return JSON.readTree(resp.body());
            }
            // 并发下可能创建时又撞标题，再尝试覆盖
            String err = resp.body() != null ? resp.body() : "";
            if (resp.statusCode() == 400 && err.contains("already exists")) {
                JsonNode again = findPageByTitle(spaceKey, title);
                if (again != null) {
                    return updateConfluencePage(again, title, spaceKey, storageHtml);
                }
            }
            throw new IllegalStateException("创建 Wiki 页面失败 HTTP " + resp.statusCode() + ": " + truncate(err, 500));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("创建 Wiki 页面失败: " + e.getMessage(), e);
        }
    }

    private JsonNode findPageByTitle(String spaceKey, String title) throws Exception {
        String url = trimSlash(props.getWikiBaseUrl()) + "/rest/api/content"
                + "?spaceKey=" + URLEncoder.encode(spaceKey, StandardCharsets.UTF_8)
                + "&title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&expand=version,space"
                + "&limit=5";
        HttpResponse<String> resp = send(authGet(url));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.warn("按标题查 Wiki 页失败 HTTP {}", resp.statusCode());
            return null;
        }
        JsonNode results = JSON.readTree(resp.body()).path("results");
        if (!results.isArray() || results.isEmpty()) return null;
        return results.get(0);
    }

    private JsonNode updateConfluencePage(JsonNode existing, String title, String spaceKey, String storageHtml)
            throws Exception {
        String id = existing.path("id").asText("");
        int ver = existing.path("version").path("number").asInt(1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("type", "page");
        body.put("title", title);
        body.put("space", Map.of("key", spaceKey));
        body.put("version", Map.of("number", ver + 1, "message", "发版文档覆盖更新"));
        body.put("body", Map.of(
                "storage", Map.of(
                        "value", storageHtml,
                        "representation", "storage"
                )
        ));
        String updateUrl = trimSlash(props.getWikiBaseUrl()) + "/rest/api/content/" + id + "?os_authType=basic";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(updateUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", basicAuthHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("覆盖更新 Wiki 页面失败 HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body(), 500));
        }
        log.info("[release-doc] 已覆盖更新同标题页面 id={} title={}", id, title);
        return JSON.readTree(resp.body());
    }

    String buildStorageHtml(String title, List<IssueRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p>").append(esc(title)).append("</p>");
        sb.append("<table><tbody>");
        sb.append("<tr>")
                .append("<th>任务</th>")
                .append("<th>上线分支</th>")
                .append("<th>DB变更</th>")
                .append("<th>配置变更</th>")
                .append("<th>QA</th>")
                .append("<th>备注</th>")
                .append("</tr>");
        for (IssueRow r : rows) {
            sb.append("<tr>");
            sb.append("<td>");
            if (r.getIssueUrl() != null && !r.getIssueUrl().isBlank()) {
                sb.append("<a href=\"").append(escAttr(r.getIssueUrl())).append("\">")
                        .append(esc(cleanTaskText(r.getSummary()))).append("</a>");
            } else {
                sb.append(esc(cleanTaskText(r.getSummary())));
            }
            sb.append("</td>");
            sb.append("<td>").append(nlToBr(r.getBranches())).append("</td>");
            sb.append("<td>").append(sqlToStorage(r.getDbChanges())).append("</td>");
            sb.append("<td>").append(nlToBr(r.getConfigChanges())).append("</td>");
            sb.append("<td>").append(esc(nullToEmpty(r.getQaAssignee()))).append("</td>");
            sb.append("<td>").append(nlToBr(r.getRemarks())).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /** SQL 用 pre 保留缩进，便于 Wiki 阅读 */
    private String sqlToStorage(String s) {
        if (s == null || s.isBlank() || "无".equals(s)) return "无";
        return "<pre>" + esc(s) + "</pre>";
    }

    private String nlToBr(String s) {
        if (s == null || s.isBlank() || "无".equals(s)) return "无";
        String[] parts = s.split("\\R");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append("<br/>");
            b.append(esc(parts[i]));
        }
        return b.toString();
    }

    private static String blankToNone(String s) {
        return s == null || s.isBlank() ? "无" : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escAttr(String s) {
        return esc(s).replace("\"", "&quot;");
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String trimSlash(String u) {
        if (u == null) return "";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private String basicAuthHeader() {
        String token = props.getUsername() + ":" + props.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest authGet(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Authorization", basicAuthHeader())
                .header("Accept", "application/json, text/html, */*")
                .header("User-Agent", "Mozilla/5.0 (compatible; ReleaseDocBot/1.0)")
                .GET()
                .build();
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (msg.contains("handshake") || msg.contains("Remote host") || msg.contains("PKIX")
                    || e instanceof javax.net.ssl.SSLHandshakeException) {
                log.warn("[release-doc] HttpClient 握手失败，改用 HttpsURLConnection 重试: {}", msg);
                return sendViaUrlConnection(req);
            }
            throw e;
        }
    }

    /** JDK HttpClient 偶发握手失败时的兜底（强制 TLSv1.2） */
    private HttpResponse<String> sendViaUrlConnection(HttpRequest req) throws Exception {
        URI uri = req.uri();
        java.net.URL url = uri.toURL();
        javax.net.ssl.HttpsURLConnection conn = (javax.net.ssl.HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        try {
            SSLContext ssl = SSLContext.getInstance("TLSv1.2");
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                    }
            };
            ssl.init(null, trustAll, new SecureRandom());
            conn.setSSLSocketFactory(ssl.getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod(req.method());
            req.headers().map().forEach((k, values) -> {
                if (!values.isEmpty() && !"host".equalsIgnoreCase(k)) {
                    conn.setRequestProperty(k, values.get(0));
                }
            });
            if (!"GET".equalsIgnoreCase(req.method()) && !"HEAD".equalsIgnoreCase(req.method())
                    && req.bodyPublisher().isPresent()) {
                throw new IllegalStateException(
                        "Wiki TLS 握手失败且当前为 " + req.method()
                                + " 请求，请重启后端后重试（已强制 TLSv1.2）。原始错误见日志");
            }
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (in != null) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            final int status = code;
            final String respBody = body;
            return new HttpResponse<>() {
                public int statusCode() { return status; }
                public HttpRequest request() { return req; }
                public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
                public java.net.http.HttpHeaders headers() {
                    return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
                }
                public String body() { return respBody; }
                public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return uri; }
                public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
            };
        } finally {
            conn.disconnect();
        }
    }

    /** 从发版文档 Wiki URL 解析 pageId，拉取页面表格为 IssueRow（供发版邮件草稿） */
    public List<IssueRow> loadIssueRowsFromWikiUrl(String wikiUrl) {
        requireCreds();
        String pageId = extractPageId(wikiUrl);
        if (pageId.isBlank()) {
            throw new IllegalArgumentException("无法从 Wiki 地址解析 pageId，请使用形如 .../viewpage.action?pageId=数字 的链接");
        }
        String html = fetchWikiStoragePrefer(pageId);
        List<IssueRow> rows = parseReleaseTable(html);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Wiki 页未解析到发版表格（任务/上线分支/DB变更…），请确认地址是否为已生成的发版文档页");
        }
        return rows;
    }

    static String extractPageId(String wikiUrl) {
        if (wikiUrl == null || wikiUrl.isBlank()) return "";
        Matcher m = Pattern.compile("[?&]pageId=(\\d+)").matcher(wikiUrl);
        if (m.find()) return m.group(1);
        // 偶发 /pages/123456 形态
        m = Pattern.compile("/pages/(\\d+)(?:\\D|$)").matcher(wikiUrl);
        if (m.find()) return m.group(1);
        return "";
    }

    /** 解析我们生成的发版表格：任务 / 上线分支 / DB变更 / 配置变更 / QA / 备注 */
    List<IssueRow> parseReleaseTable(String html) {
        List<IssueRow> rows = new ArrayList<>();
        if (html == null || html.isBlank()) return rows;
        Document doc = Jsoup.parse(html);
        for (Element table : doc.select("table")) {
            Elements trs = table.select("tr");
            if (trs.isEmpty()) continue;
            Element first = trs.get(0);
            String head = first.text();
            if (!head.contains("任务") || !head.contains("DB")) continue;

            for (int i = 1; i < trs.size(); i++) {
                Elements tds = trs.get(i).select("td");
                if (tds.size() < 6) continue;
                IssueRow row = new IssueRow();
                Element taskTd = tds.get(0);
                Element a = taskTd.selectFirst("a[href]");
                if (a != null) {
                    row.setSummary(cleanTaskText(a.text().trim()));
                    row.setIssueUrl(a.attr("abs:href"));
                    if (row.getIssueUrl().isBlank()) row.setIssueUrl(a.attr("href"));
                    Matcher km = ISSUE_KEY.matcher(row.getIssueUrl() + " " + row.getSummary());
                    if (km.find()) row.setIssueKey(km.group(1));
                } else {
                    row.setSummary(cleanTaskText(taskTd.text().trim()));
                }
                row.setBranches(cellText(tds.get(1)));
                row.setDbChanges(cellText(tds.get(2)));
                row.setConfigChanges(cellText(tds.get(3)));
                row.setQaAssignee(tds.get(4).text().trim());
                row.setRemarks(cellText(tds.get(5)));
                if (row.getSummary() != null && !row.getSummary().isBlank()) {
                    rows.add(row);
                }
            }
            if (!rows.isEmpty()) break;
        }
        return rows;
    }

    private static String cellText(Element td) {
        if (td == null) return "无";
        Element pre = td.selectFirst("pre");
        if (pre != null) {
            String t = pre.wholeText();
            return t == null || t.isBlank() ? "无" : t.trim();
        }
        String html = td.html();
        if (html != null && html.contains("<br")) {
            return Jsoup.parse(html.replaceAll("(?i)<br\\s*/?>", "\n")).text().trim();
        }
        String t = td.text().trim();
        return t.isEmpty() ? "无" : t;
    }
}
