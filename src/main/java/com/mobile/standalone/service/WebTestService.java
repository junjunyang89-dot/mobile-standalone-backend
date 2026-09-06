package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.config.WebTestProperties;
import com.mobile.standalone.dto.WebTestDtos.*;
import com.mobile.standalone.entity.UiH5Page;
import com.mobile.standalone.entity.WebTestCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebTestService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebTestProperties props;
    private final UiH5PageService uiH5PageService;
    private final WebTestCaseService webTestCaseService;

    public StatusResponse status() {
        StatusResponse s = new StatusResponse();
        s.setEnabled(props.isEnabled());
        Path dir = resolveProjectDir();
        s.setProjectDir(dir != null ? dir.toString() : props.getProjectDir());
        boolean ready = dir != null && Files.isRegularFile(dir.resolve("package.json"));
        boolean nm = ready && Files.isDirectory(dir.resolve("node_modules"));
        s.setProjectReady(ready);
        s.setNodeModulesPresent(nm);
        if (!props.isEnabled()) {
            s.setMessage("web-test.enabled=false");
        } else if (!ready) {
            s.setMessage("找不到 Playwright 工程，请确认 web-test.project-dir=" + props.getProjectDir());
        } else if (!nm) {
            s.setMessage("请先在工程目录执行: npm install && npx playwright install chromium");
        } else {
            s.setMessage("就绪：在页面编排用例步骤后执行（员工无需改代码）");
        }
        return s;
    }

    /** 执行库内编排用例（主路径） */
    public RunResult runSavedCases(List<String> caseIds, String tokenOverride) throws Exception {
        if (!props.isEnabled()) throw new IllegalStateException("web-test 已禁用");
        if (caseIds == null || caseIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一条用例");
        }
        List<CaseResult> blocked = new ArrayList<>();
        List<Map<String, Object>> payload = new ArrayList<>();
        for (String id : caseIds) {
            WebTestCase c = webTestCaseService.get(id);
            if (c == null) throw new IllegalArgumentException("用例不存在: " + id);
            String ch = normalizeChannel(c.getChannel());
            if ("MINIPROGRAM".equals(ch)) {
                CaseResult r = new CaseResult();
                r.setName(c.getName());
                r.setChannel(ch);
                r.setUrl(c.getPageUrl());
                r.setRequirementRef(c.getRequirementRef());
                r.setStatus("skipped");
                r.setError("小程序需单独引擎，本期未接入");
                blocked.add(r);
                continue;
            }
            payload.add(webTestCaseService.toRunnablePayload(c, tokenOverride));
        }
        if (payload.isEmpty()) {
            RunResult out = new RunResult();
            out.setOk(false);
            out.setMessage("没有可执行用例");
            out.setCases(blocked);
            out.setTotal(blocked.size());
            out.setSkipped(blocked.size());
            return out;
        }
        RunResult out = executePlaywright("step-run", payload);
        out.getCases().addAll(0, blocked);
        recount(out);
        return out;
    }

    /** 兼容：临时 URL 冒烟 */
    public RunResult run(RunRequest req) throws Exception {
        if (req != null && req.getCaseIds() != null && !req.getCaseIds().isEmpty()) {
            return runSavedCases(req.getCaseIds(), req.getTokenOverride());
        }
        if (!props.isEnabled()) throw new IllegalStateException("web-test 已禁用");
        if (req == null || req.getCases() == null || req.getCases().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一条用例");
        }

        List<CaseSpec> runnable = new ArrayList<>();
        List<CaseResult> blocked = new ArrayList<>();
        for (CaseSpec c : req.getCases()) {
            enrichFromH5Page(c);
            String ch = normalizeChannel(c.getChannel());
            c.setChannel(ch);
            if ("MINIPROGRAM".equals(ch)) {
                CaseResult r = new CaseResult();
                r.setName(blank(c.getName(), "小程序用例"));
                r.setChannel(ch);
                r.setUrl(c.getUrl());
                r.setStatus("skipped");
                r.setError("小程序自动化需单独引擎，本期未接入");
                blocked.add(r);
                continue;
            }
            if (c.getUrl() == null || c.getUrl().isBlank()) {
                throw new IllegalArgumentException("用例缺少 url: " + c.getName());
            }
            if (c.getName() == null || c.getName().isBlank()) c.setName(c.getUrl());
            runnable.add(c);
        }

        List<Map<String, Object>> payload = new ArrayList<>();
        for (CaseSpec c : runnable) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("pageUrl", c.getUrl());
            m.put("authMode", blank(c.getAuthMode(), "none"));
            m.put("authToken", c.getToken());
            m.put("authQueryKey", blank(c.getAuthQueryKey(), "token"));
            m.put("cookieDomain", c.getCookieDomain());
            m.put("authCookies", parseCookies(c.getAuthCookies()));
            m.put("steps", List.of(
                    Map.of("keywordCode", "OPEN_URL", "params", Map.of()),
                    Map.of("keywordCode", "ASSERT_HTML_MIN", "params", Map.of("minLength", 200))
            ));
            payload.add(m);
        }
        if (payload.isEmpty()) {
            RunResult out = new RunResult();
            out.setOk(false);
            out.setMessage("没有可执行的 H5/网站用例");
            out.setCases(blocked);
            out.setTotal(blocked.size());
            out.setSkipped(blocked.size());
            return out;
        }
        RunResult out = executePlaywright("step-run", payload);
        out.getCases().addAll(0, blocked);
        recount(out);
        return out;
    }

    private RunResult executePlaywright(String specName, List<Map<String, Object>> payload) throws Exception {
        Path dir = resolveProjectDir();
        if (dir == null || !Files.isRegularFile(dir.resolve("package.json"))) {
            throw new IllegalStateException("Playwright 工程不存在: " + props.getProjectDir());
        }

        Path casesFile = dir.resolve(".platform-cases.json");
        Files.writeString(casesFile, JSON.writeValueAsString(payload), StandardCharsets.UTF_8);

        long t0 = System.currentTimeMillis();
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getNpxCommand());
        cmd.add("playwright");
        cmd.add("test");
        cmd.add(specName);
        cmd.add("--project=chromium");
        cmd.add("--reporter=json");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("WEB_TEST_CASES_JSON", JSON.writeValueAsString(payload));
        pb.environment().put("CI", "1");

        StringBuilder logBuf = new StringBuilder();
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (logBuf.length() > 0) logBuf.append('\n');
                logBuf.append(line);
            }
        }
        boolean finished = p.waitFor(Math.max(30, props.getTimeoutSec()), TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("Playwright 执行超时（" + props.getTimeoutSec() + "s）");
        }
        int code = p.exitValue();

        RunResult out = new RunResult();
        out.setDurationMs(System.currentTimeMillis() - t0);
        out.setLogTail(tail(logBuf.toString(), 4000));
        out.setReportDir(dir.resolve("playwright-report").toString());

        JsonNode report = tryParseJsonReport(logBuf.toString());
        List<CaseResult> results = new ArrayList<>();
        if (report != null) {
            results.addAll(parseReport(report, payload));
        } else {
            for (Map<String, Object> c : payload) {
                CaseResult r = new CaseResult();
                r.setName(String.valueOf(c.get("name")));
                r.setChannel(String.valueOf(c.getOrDefault("channel", "H5")));
                r.setUrl(c.get("pageUrl") != null ? String.valueOf(c.get("pageUrl")) : null);
                r.setRequirementRef(c.get("requirementRef") != null ? String.valueOf(c.get("requirementRef")) : null);
                r.setStatus(code == 0 ? "passed" : "failed");
                if (code != 0) r.setError("详见 logTail（未能解析 JSON 报告）");
                results.add(r);
            }
        }
        out.setCases(results);
        recount(out);
        out.setOk(out.getFailed() == 0 && out.getPassed() > 0);
        out.setMessage(out.isOk()
                ? "全部通过 " + out.getPassed() + "/" + out.getTotal()
                : "失败 " + out.getFailed() + "，通过 " + out.getPassed() + "，跳过 " + out.getSkipped());
        try {
            Files.deleteIfExists(casesFile);
        } catch (Exception ignore) {
            // ignore
        }
        return out;
    }

    private void recount(RunResult out) {
        List<CaseResult> results = out.getCases() != null ? out.getCases() : List.of();
        out.setTotal(results.size());
        out.setPassed((int) results.stream().filter(x -> "passed".equals(x.getStatus())).count());
        out.setFailed((int) results.stream().filter(x -> "failed".equals(x.getStatus())).count());
        out.setSkipped((int) results.stream().filter(x -> "skipped".equals(x.getStatus())).count());
        out.setOk(out.getFailed() == 0 && out.getPassed() > 0);
    }

    /**
     * 把 JSON 形式的 cookie 配置解析成对象再下发。
     * <p>
     * 传字符串下去的话 Playwright 那边拿到的是一坨文本，得再解析一次；
     * 而且解析失败要在这里就报出来——等到浏览器里才发现，看到的只是「未登录」。
     */
    private Map<String, Object> parseCookies(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(json);
            if (!n.isObject()) {
                throw new IllegalArgumentException("authCookies 要是 JSON 对象「名→值」，实际是: " + json);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("authCookies 不是合法 JSON: " + e.getMessage());
        }
    }

    private void enrichFromH5Page(CaseSpec c) {
        if (c.getH5PageId() == null || c.getH5PageId().isBlank()) return;
        UiH5Page page = uiH5PageService.get(c.getH5PageId().trim());
        if (page == null) throw new IllegalArgumentException("H5 页面不存在: " + c.getH5PageId());
        if (c.getName() == null || c.getName().isBlank()) c.setName(page.getName());
        if (c.getUrl() == null || c.getUrl().isBlank()) c.setUrl(UiH5PageService.resolveOpenUrl(page));
        if ((c.getToken() == null || c.getToken().isBlank()) && page.getAuthToken() != null) {
            c.setToken(page.getAuthToken());
        }
        // Cookie 模式要单独判：这类页面的登录态整个在 cookie 里，auth_token 可能是空的。
        // 挂在「token 非空」这个条件下面的话，配了 cookie 也永远进不来，
        // 页面照样停在「去登录」——而且不报任何错
        if (c.getAuthCookies() == null || c.getAuthCookies().isBlank()) {
            c.setAuthCookies(page.getAuthCookies());
        }
        if (c.getCookieDomain() == null || c.getCookieDomain().isBlank()) {
            c.setCookieDomain(page.getCookieDomain());
        }
        if (c.getAuthMode() == null || c.getAuthMode().isBlank() || "none".equalsIgnoreCase(c.getAuthMode())) {
            String t = page.getAuthType() == null ? "" : page.getAuthType().trim();
            if ("TOKEN_COOKIE".equalsIgnoreCase(t)) {
                c.setAuthMode("cookie");
            } else if ("TOKEN_QUERY".equalsIgnoreCase(t) && c.getToken() != null && !c.getToken().isBlank()) {
                c.setAuthMode("query");
            }
        }
        if (page.getAuthKey() != null && !page.getAuthKey().isBlank()) c.setAuthQueryKey(page.getAuthKey());
        if (c.getChannel() == null || c.getChannel().isBlank()) c.setChannel("H5");
    }

    private List<CaseResult> parseReport(JsonNode report, List<Map<String, Object>> payload) {
        Map<String, Map<String, Object>> byName = payload.stream()
                .collect(Collectors.toMap(
                        m -> String.valueOf(m.get("name")),
                        m -> m,
                        (a, b) -> a,
                        LinkedHashMap::new));
        List<CaseResult> out = new ArrayList<>();
        JsonNode suites = report.get("suites");
        if (suites == null || !suites.isArray()) return out;
        Deque<JsonNode> q = new ArrayDeque<>();
        suites.forEach(q::add);
        while (!q.isEmpty()) {
            JsonNode s = q.poll();
            if (s.has("suites")) s.get("suites").forEach(q::add);
            JsonNode specs = s.get("specs");
            if (specs == null) continue;
            for (JsonNode spec : specs) {
                String title = text(spec, "title");
                CaseResult r = new CaseResult();
                r.setName(title);
                Map<String, Object> src = byName.get(title);
                if (src != null) {
                    r.setChannel(String.valueOf(src.getOrDefault("channel", "H5")));
                    if (src.get("pageUrl") != null) r.setUrl(String.valueOf(src.get("pageUrl")));
                    if (src.get("requirementRef") != null) r.setRequirementRef(String.valueOf(src.get("requirementRef")));
                }
                String status = "failed";
                String err = null;
                JsonNode tests = spec.get("tests");
                if (tests != null && tests.isArray() && !tests.isEmpty()) {
                    JsonNode t0 = tests.get(0);
                    JsonNode results = t0.get("results");
                    if (results != null && results.isArray() && !results.isEmpty()) {
                        JsonNode last = results.get(results.size() - 1);
                        status = text(last, "status");
                        if ("unexpected".equals(status)) status = "failed";
                        if ("expected".equals(status)) status = "passed";
                        JsonNode errors = last.get("errors");
                        if (errors != null && errors.isArray() && !errors.isEmpty()) {
                            err = text(errors.get(0), "message");
                            if (err != null && err.length() > 500) err = err.substring(0, 500) + "…";
                        }
                    }
                    if ("skipped".equals(text(t0, "status"))) status = "skipped";
                }
                if (status == null || status.isBlank()) status = "failed";
                r.setStatus(status);
                r.setError(err);
                out.add(r);
            }
        }
        return out;
    }

    private JsonNode tryParseJsonReport(String stdout) {
        if (stdout == null || stdout.isBlank()) return null;
        int idx = stdout.lastIndexOf("\n{");
        String json = idx >= 0 ? stdout.substring(idx + 1).trim() : stdout.trim();
        if (!json.startsWith("{")) {
            int brace = stdout.indexOf('{');
            if (brace < 0) return null;
            json = stdout.substring(brace).trim();
        }
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            log.debug("解析 Playwright JSON 报告失败: {}", e.getMessage());
            return null;
        }
    }

    private Path resolveProjectDir() {
        try {
            Path p = Path.of(props.getProjectDir());
            if (!p.isAbsolute()) {
                p = Path.of(System.getProperty("user.dir", ".")).resolve(p).normalize();
            }
            return p.toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeChannel(String ch) {
        if (ch == null || ch.isBlank()) return "H5";
        String u = ch.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "H5" -> "H5";
            case "WEB", "WEBSITE", "SITE" -> "WEB";
            case "MINIPROGRAM", "MINI", "WECHAT", "小程序" -> "MINIPROGRAM";
            default -> u;
        };
    }

    private static String blank(String s, String d) {
        return s == null || s.isBlank() ? d : s.trim();
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return null;
        return n.get(field).asText();
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
