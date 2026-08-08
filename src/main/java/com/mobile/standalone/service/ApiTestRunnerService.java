package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.dto.ApiTestDtos.CaseResult;
import com.mobile.standalone.dto.ApiTestDtos.RunRequest;
import com.mobile.standalone.dto.ApiTestDtos.RunResult;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestEnv;
import com.mobile.standalone.entity.ApiUrlEntry;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.service.apitest.ApiTestVariableService;
import com.mobile.standalone.service.apitest.JsonPaths;
import com.mobile.standalone.service.apitest.VariableResolver;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口测试执行器：按环境 baseUrl 发 HTTP，断言 status / bodyIncludes / jsonPath。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTestRunnerService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BODY_PREVIEW_MAX = 4000;

    private final ApiTestCaseService caseService;
    private final ApiTestCaseMapper caseMapper;
    private final ApiUrlService apiUrlService;
    private final ApiTestRunService runService;
    private final ApiTestVariableService variableService;
    private final ApiProjectService projectService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public RunResult run(RunRequest req) {
        if (req == null || req.getCaseIds() == null || req.getCaseIds().isEmpty()) {
            throw new IllegalArgumentException("caseIds 不能为空");
        }
        String envKey = TestEnvironment.normalize(req.getEnvironment());
        if (TestEnvironment.PROD.equals(envKey) && !Boolean.TRUE.equals(req.getConfirmProd())) {
            throw new IllegalArgumentException("跑线上环境请勾选确认（confirmProd=true）");
        }
        ApiTestEnv env = caseService.getEnv(envKey);
        if (env == null) {
            throw new IllegalStateException("未配置环境: " + envKey + "，请先在「环境」里填 baseUrl");
        }
        if (Boolean.FALSE.equals(env.getEnabled())) {
            throw new IllegalStateException("环境已禁用: " + envKey);
        }
        if (env.getBaseUrl() == null || env.getBaseUrl().isBlank()) {
            throw new IllegalStateException("环境 baseUrl 为空: " + envKey);
        }

        Map<String, String> envVars = variableService.resolveValues(envKey);
        // 本轮共享：前面的用例 extract 出来的值，后面的用例能引用。
        // 作用域是「一次执行」而不是全局——留到下一轮就成了脏数据，
        // 上一轮的 token 早过期了，却会让这一轮看起来「配好了」，失败原因指向错的地方。
        Map<String, String> runtime = new LinkedHashMap<>();

        long t0 = System.currentTimeMillis();
        RunResult out = new RunResult();
        out.setEnvironment(envKey);
        int passed = 0;
        int failed = 0;
        for (String id : req.getCaseIds()) {
            ApiTestCase c = caseMapper.findById(id);
            CaseResult cr;
            if (c == null) {
                cr = new CaseResult();
                cr.setId(id);
                cr.setName(id);
                cr.setStatus("FAILED");
                cr.setError("用例不存在");
                failed++;
            } else {
                cr = executeOne(c, env, req.getTokenOverride(), req.getUrlIdOverride(), envVars, runtime);
                if ("PASSED".equals(cr.getStatus())) passed++;
                else failed++;
            }
            out.getCases().add(cr);
        }
        out.setTotal(out.getCases().size());
        out.setPassed(passed);
        out.setFailed(failed);
        out.setOk(failed == 0);
        out.setDurationMs(System.currentTimeMillis() - t0);
        out.setMessage(failed == 0
                ? "全部通过（" + envKey + "）"
                : "失败 " + failed + " / " + out.getTotal() + "（" + envKey + "）");
        try {
            runService.save(out, null);
        } catch (Exception e) {
            log.warn("[api-test] 执行历史落库失败: {}", e.getMessage());
        }
        return out;
    }

    private CaseResult executeOne(ApiTestCase c, ApiTestEnv env, String tokenOverride, String urlIdOverride,
                                  Map<String, String> envVars, Map<String, String> runtime) {
        CaseResult cr = new CaseResult();
        cr.setId(c.getId());
        cr.setName(c.getName());
        cr.setMethod(c.getMethod());
        long t0 = System.currentTimeMillis();
        try {
            String baseUrl = resolveBaseUrl(c, env, urlIdOverride);
            String url = VariableResolver.resolve(
                    buildUrl(baseUrl, c.getPath(), c.getQuery()), runtime, envVars);
            cr.setUrl(url);
            Map<String, String> headers = mergeHeaders(env, c, tokenOverride);
            headers.replaceAll((k, v) -> VariableResolver.resolve(v, runtime, envVars));
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60));
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    b.header(e.getKey(), e.getValue());
                }
            }
            String method = c.getMethod() == null ? "GET" : c.getMethod().toUpperCase();
            String body = VariableResolver.resolve(c.getBody() == null ? "" : c.getBody(), runtime, envVars);
            if ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method)) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                if (!headers.containsKey("Content-Type") && !headers.containsKey("content-type")) {
                    b.header("Content-Type", "application/json; charset=utf-8");
                }
                b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            cr.setHttpStatus(resp.statusCode());
            cr.setBodyPreview(truncate(resp.body(), BODY_PREVIEW_MAX));
            List<String> assertMsgs = new ArrayList<>();
            String assertions = withBizCodeAssertion(c, VariableResolver.resolve(c.getAssertions(), runtime, envVars));
            boolean ok = evaluateAssertions(assertions, resp.statusCode(), resp.body(), assertMsgs);
            // extract 放在断言之后、且只在通过时执行：失败的响应里抽出来的多半是错误结构，
            // 让它污染后面所有用例，会把一个失败放大成一片失败，真正的第一现场反而被埋掉
            if (ok) {
                extractInto(c, resp.body(), runtime, assertMsgs);
            }
            cr.setAssertionResults(assertMsgs);
            cr.setStatus(ok ? "PASSED" : "FAILED");
            if (!ok) {
                cr.setError(String.join("; ", assertMsgs.stream().filter(s -> s.startsWith("FAIL")).toList()));
                if (cr.getError() == null || cr.getError().isBlank()) {
                    cr.setError("断言未通过");
                }
            }
        } catch (Exception e) {
            cr.setStatus("FAILED");
            cr.setError(e.getMessage() != null ? e.getMessage() : e.toString());
            log.warn("[api-test] 执行失败 caseId={}: {}", c.getId(), cr.getError());
        } finally {
            cr.setDurationMs(System.currentTimeMillis() - t0);
        }
        return cr;
    }

    /** 优先级：本次 urlIdOverride → 用例 urlId → 环境 baseUrl */
    private String resolveBaseUrl(ApiTestCase c, ApiTestEnv env, String urlIdOverride) {
        String id = urlIdOverride != null && !urlIdOverride.isBlank()
                ? urlIdOverride.trim()
                : (c.getUrlId() != null ? c.getUrlId().trim() : "");
        if (!id.isEmpty()) {
            ApiUrlEntry u = apiUrlService.get(id);
            if (u == null) {
                throw new IllegalStateException("URL 不存在: " + id + "（请到资产管理 · URL 管理配置）");
            }
            if (Boolean.FALSE.equals(u.getEnabled())) {
                throw new IllegalStateException("URL 已禁用: " + u.getName());
            }
            if (u.getBaseUrl() == null || u.getBaseUrl().isBlank()) {
                throw new IllegalStateException("URL baseUrl 为空: " + u.getName());
            }
            return u.getBaseUrl();
        }
        return env.getBaseUrl();
    }

    static String buildUrl(String baseUrl, String path, String queryJson) throws Exception {
        String p = path == null ? "" : path.trim();
        String url;
        if (p.startsWith("http://") || p.startsWith("https://")) {
            url = p;
        } else {
            String base = ApiTestCaseService.trimSlash(baseUrl);
            if (!p.startsWith("/")) p = "/" + p;
            url = base + p;
        }
        Map<String, String> q = readStringMap(queryJson);
        if (q.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Map<String, String> mergeHeaders(ApiTestEnv env, ApiTestCase c, String tokenOverride)
            throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        out.putAll(readStringMap(env.getDefaultHeaders()));
        out.putAll(readStringMap(c.getHeaders()));
        String token = tokenOverride != null && !tokenOverride.isBlank()
                ? tokenOverride.trim()
                : (env.getDefaultToken() != null ? env.getDefaultToken().trim() : "");
        if (!token.isEmpty()) {
            // Deadlock / 网关常见：headers.token；同时兼容 Bearer
            out.putIfAbsent("token", token);
            out.putIfAbsent("Authorization", "Bearer " + token);
        }
        return out;
    }

    private static Map<String, String> readStringMap(String json) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        JsonNode n = JSON.readTree(json);
        if (n == null || !n.isObject()) return out;
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            out.put(e.getKey(), v == null || v.isNull() ? "" : (v.isTextual() ? v.asText() : v.toString()));
        }
        return out;
    }

    /**
     * 给用例自动补一条<b>业务成功码</b>断言。
     * <p>
     * 这几个后端统一用 {@code ResponseResult{code,message,result}} 包一层，业务失败也是 HTTP 200。
     * 只断言 status=200 等于只测「接口没挂」——返回 {@code {code:5009001,message:"服务调用失败"}}
     * 照样判通过。批量导进来的用例全是这个状态，跑出来会是一份 100% 通过的假报告，
     * 那比没有报告更糟：它会让人相信接口是好的。
     * <p>
     * 配在项目上而不是逐条用例上：换项目 wrapper 可能不同，改一行配置即可，已有用例一条不用动。
     * 用例自己已经断言了同一个路径时不重复补——那多半是刻意在测某个错误码。
     */
    String withBizCodeAssertion(ApiTestCase c, String assertionsJson) {
        ApiProject p = c.getProject() == null ? null : projectService.get(c.getProject());
        if (p == null || p.getSuccessCodePath() == null || p.getSuccessCodePath().isBlank()) {
            return assertionsJson;
        }
        String path = p.getSuccessCodePath().trim();
        try {
            JsonNode arr = assertionsJson == null || assertionsJson.isBlank()
                    ? JSON.createArrayNode()
                    : JSON.readTree(assertionsJson);
            if (!arr.isArray()) {
                return assertionsJson;
            }
            for (JsonNode a : arr) {
                if ("jsonPath".equals(text(a, "type")) && path.equals(text(a, "path"))) {
                    return assertionsJson;
                }
            }
            com.fasterxml.jackson.databind.node.ArrayNode copy = JSON.createArrayNode().addAll((com.fasterxml.jackson.databind.node.ArrayNode) arr);
            com.fasterxml.jackson.databind.node.ObjectNode add = copy.addObject();
            add.put("type", "jsonPath");
            add.put("path", path);
            String want = p.getSuccessCodeValue() == null ? "0" : p.getSuccessCodeValue().trim();
            // 数字码按数字比，否则 "0" 与 0 会判不等
            try {
                add.put("equals", Long.parseLong(want));
            } catch (NumberFormatException e) {
                add.put("equals", want);
            }
            return copy.toString();
        } catch (Exception e) {
            log.warn("[api-test] 业务码断言追加失败 caseId={}: {}", c.getId(), e.getMessage());
            return assertionsJson;
        }
    }

    /**
     * 按 {@code {"token":"$.result.token"}} 从响应里抽值写进本轮上下文。
     * <p>
     * 抽不到不算用例失败，但要在断言列表里留一行：不写的话，后面那条引用 {{token}} 的用例
     * 会报「变量取不到值」，人得反向猜是哪一步没抽到。
     */
    void extractInto(ApiTestCase c, String body, Map<String, String> runtime, List<String> msgs) {
        String json = c.getExtractJson();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || !n.isObject()) {
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey();
                String path = e.getValue() == null ? null : e.getValue().asText();
                String value = JsonPaths.readAsString(body, path);
                if (value == null) {
                    msgs.add("WARN extract " + name + " 取不到（" + path + "），后面引用 {{" + name + "}} 的用例会失败");
                } else {
                    runtime.put(name, value);
                    msgs.add("OK extract " + name + " = " + truncate(value, 24));
                }
            }
        } catch (Exception ex) {
            msgs.add("WARN extract 解析失败: " + ex.getMessage());
        }
    }

    private boolean evaluateAssertions(String assertionsJson, int status, String body, List<String> msgs) {
        try {
            JsonNode arr = assertionsJson == null || assertionsJson.isBlank()
                    ? JSON.createArrayNode()
                    : JSON.readTree(assertionsJson);
            if (!arr.isArray() || arr.isEmpty()) {
                boolean ok = status >= 200 && status < 300;
                msgs.add((ok ? "OK" : "FAIL") + " 默认断言 HTTP 2xx，实际=" + status);
                return ok;
            }
            boolean all = true;
            for (JsonNode a : arr) {
                String type = text(a, "type");
                boolean pass;
                String detail;
                switch (type == null ? "" : type) {
                    case "status" -> {
                        int expect = a.path("equals").asInt(-1);
                        pass = status == expect;
                        detail = "status==" + expect + " 实际=" + status;
                    }
                    case "bodyIncludes" -> {
                        String v = text(a, "value");
                        pass = body != null && v != null && body.contains(v);
                        detail = "bodyIncludes(" + truncate(v, 40) + ")";
                    }
                    case "jsonPath" -> {
                        String path = text(a, "path");
                        JsonNode node = readJsonPath(body, path);
                        if (a.has("exists")) {
                            boolean want = a.path("exists").asBoolean(true);
                            pass = want ? (node != null && !node.isMissingNode() && !node.isNull())
                                    : (node == null || node.isMissingNode() || node.isNull());
                            detail = "jsonPath " + path + " exists=" + want;
                        } else if (a.has("equals")) {
                            JsonNode expect = a.get("equals");
                            pass = node != null && jsonEquals(node, expect);
                            detail = "jsonPath " + path + "==" + expect + " 实际=" + node;
                        } else {
                            pass = node != null && !node.isMissingNode() && !node.isNull();
                            detail = "jsonPath " + path + " 存在";
                        }
                    }
                    default -> {
                        pass = false;
                        detail = "未知断言类型: " + type;
                    }
                }
                msgs.add((pass ? "OK" : "FAIL") + " " + detail);
                if (!pass) all = false;
            }
            return all;
        } catch (Exception e) {
            msgs.add("FAIL 断言解析异常: " + e.getMessage());
            return false;
        }
    }

    /** 断言与 extract 共用一份路径解析，别两边各写一份 */
    static JsonNode readJsonPath(String body, String path) throws Exception {
        return JsonPaths.read(body, path);
    }

    private static boolean jsonEquals(JsonNode actual, JsonNode expect) {
        if (actual == null || expect == null) return false;
        if (expect.isNumber() && actual.isNumber()) {
            return actual.decimalValue().compareTo(expect.decimalValue()) == 0;
        }
        if (expect.isBoolean()) return actual.isBoolean() && actual.asBoolean() == expect.asBoolean();
        if (expect.isTextual()) return expect.asText().equals(actual.asText());
        return actual.equals(expect);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
