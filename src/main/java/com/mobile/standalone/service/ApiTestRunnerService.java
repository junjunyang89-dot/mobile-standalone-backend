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
import com.mobile.standalone.entity.ApiTestCollection;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.service.apitest.ApiTestCollectionService;
import com.mobile.standalone.service.EnvVariableService;
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
    private final EnvVariableService variableService;
    private final ApiProjectService projectService;
    private final ApiTestCollectionService collectionService;
    private final com.mobile.standalone.service.apitest.DbAssertRunner dbAssert;

    /**
     * 被限流时的业务码（逗号分隔）。空＝只按 HTTP 429 / 503+Retry-After 判。
     * <p>
     * 不写死在代码里：910001 是被测后端 {@code MessageCodeEnum.FLOW_LIMIT} 的约定，
     * 换个项目就不是这个数了。平台是通用的，不该带某个项目的常量。
     */
    @org.springframework.beans.factory.annotation.Value("${api-test.rate-limit.throttle-codes:}")
    private String rateLimitThrottleCodesRaw;

    /**
     * 被限流策略<b>禁止</b>访问的业务码（阈值被配成负数那种）。
     * 和上面分开是因为下一步动作不同：这类重试多少次都一样，一直撞墙只会撞到超时。
     */
    @org.springframework.beans.factory.annotation.Value("${api-test.rate-limit.forbid-codes:}")
    private String rateLimitForbidCodesRaw;

    private volatile java.util.Set<String> rateLimitThrottleCodes = java.util.Set.of();
    private volatile java.util.Set<String> rateLimitForbidCodes = java.util.Set.of();

    @jakarta.annotation.PostConstruct
    void initRateLimitCodes() {
        rateLimitThrottleCodes = splitCodes(rateLimitThrottleCodesRaw);
        rateLimitForbidCodes = splitCodes(rateLimitForbidCodesRaw);
        if (!rateLimitThrottleCodes.isEmpty() || !rateLimitForbidCodes.isEmpty()) {
            log.info("[api-test] 限流码已配置：限流 {} / 禁止 {}",
                    rateLimitThrottleCodes, rateLimitForbidCodes);
        }
    }

    static java.util.Set<String> splitCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String one : raw.split(",")) {
            String t = one.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
    private final com.mobile.standalone.service.apitest.EndpointVerifyService verifyService;
    private final com.mobile.standalone.service.apitest.ApiAuthService authService;

    /** 一个项目这一轮的登录态：token 塞进哪些头，或者登录为什么失败了 */
    private record AuthCtx(String token, Map<String, String> headers, String error) {
        static final AuthCtx NONE = new AuthCtx(null, Map.of(), null);
    }

    private AuthCtx authFor(String project, String envKey) {
        var profile = authService.findForCaseProject(project);
        if (!authService.configured(profile, envKey)) {
            return AuthCtx.NONE;
        }
        try {
            String token = authService.token(profile, envKey, false).token();
            Map<String, String> headers = new LinkedHashMap<>();
            for (String h : authService.headersOf(profile)) {
                headers.put(h, token);
            }
            return new AuthCtx(token, headers, null);
        } catch (Exception e) {
            log.warn("[api-test] {} 自动登录失败: {}", project, e.getMessage());
            return new AuthCtx(null, Map.of(), e.getMessage());
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public RunResult run(RunRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        // 按集合执行：顺序由集合内的 sortOrder 决定，不由调用方传。
        // 顺序在这里是语义的一部分——登录用例排在前面，后面的用例才引用得到它 extract 出来的 token
        ApiTestCollection collection = null;
        if (req.getCollectionId() != null && !req.getCollectionId().isBlank()) {
            collection = collectionService.get(req.getCollectionId());
            if (collection == null) {
                throw new IllegalArgumentException("集合不存在: " + req.getCollectionId());
            }
            List<String> ids = collectionService.caseIdsOf(collection.getId());
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("集合「" + collection.getName() + "」里没有启用的用例");
            }
            req.setCaseIds(ids);
        }
        if (req.getCaseIds() == null || req.getCaseIds().isEmpty()) {
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

        // 变量按项目解析：全局变量 + 该项目专属变量，同名时项目专属胜出。
        // 按项目缓存而不是逐条用例去查——一个集合基本是同一个项目，
        // 这样一轮下来只查一两次；不缓存的话，300 条用例就是 300 次公网往返。
        Map<String, Map<String, String>> varsByProject = new LinkedHashMap<>();
        // 本轮共享：前面的用例 extract 出来的值，后面的用例能引用。
        // 作用域是「一次执行」而不是全局——留到下一轮就成了脏数据，
        // 上一轮的 token 早过期了，却会让这一轮看起来「配好了」，失败原因指向错的地方。
        Map<String, String> runtime = new LinkedHashMap<>();
        Map<String, AuthCtx> authByProject = new LinkedHashMap<>();

        long t0 = System.currentTimeMillis();
        RunResult out = new RunResult();
        out.setEnvironment(envKey);
        if (collection != null) {
            out.setCollectionId(collection.getId());
            out.setCollectionName(collection.getName());
        }
        int passed = 0;
        int failed = 0;
        int blocked = 0;
        int skipped = 0;
        boolean prod = TestEnvironment.PROD.equals(envKey);
        // 跑完要回写「这个接口验过没有」，得留住每条用例和它的结果
        List<com.mobile.standalone.service.apitest.EndpointVerifyService.CaseOutcome> outcomes =
                new ArrayList<>();
        for (String id : req.getCaseIds()) {
            ApiTestCase c = caseMapper.findById(id);
            CaseResult cr;
            if (prod && c != null && Boolean.TRUE.equals(c.getTestOnly())) {
                skipped++;
                continue;
            }
            if (c == null) {
                cr = new CaseResult();
                cr.setId(id);
                cr.setName(id);
                // 用例查不到是配置问题，不是被测接口的问题——断言根本没机会跑
                cr.setStatus("BLOCKED");
                cr.setError("用例不存在");
                blocked++;
            } else {
                String pj0 = c.getProject() == null ? "" : c.getProject();
                // 登一次管一轮：同项目的用例共用，别每条用例都去登录接口打一次
                AuthCtx auth = authByProject.computeIfAbsent(pj0, pj -> authFor(pj, envKey));
                Map<String, String> envVars = varsByProject.computeIfAbsent(pj0, pj -> {
                    Map<String, String> v = new LinkedHashMap<>(variableService.resolveValues(envKey, pj));
                    if (auth.token() != null) {
                        v.put("token", auth.token());
                    }
                    return v;
                });
                cr = executeOne(c, env, req.getTokenOverride(), req.getUrlIdOverride(), envVars, runtime,
                        auth.headers());
                if (auth.error() != null && !"PASSED".equals(cr.getStatus())) {
                    cr.setError("自动登录失败（" + auth.error() + "）；" + (cr.getError() == null ? "" : cr.getError()));
                }
                boolean ok = "PASSED".equals(cr.getStatus());
                boolean notRun = "BLOCKED".equals(cr.getStatus());
                if (ok) passed++;
                else if (notRun) blocked++;
                else failed++;
                // 没跑成的不回写「这个接口验过了」——它什么都没验
                if (!notRun) {
                    outcomes.add(new com.mobile.standalone.service.apitest.EndpointVerifyService
                            .CaseOutcome(c, ok));
                }
            }
            out.getCases().add(cr);
        }
        if (out.getCases().isEmpty() && skipped > 0) {
            throw new IllegalArgumentException("这 " + skipped + " 条都是只在测试跑的用例，线上不执行");
        }
        out.setTotal(out.getCases().size());
        out.setPassed(passed);
        out.setFailed(failed);
        out.setBlocked(blocked);
        out.setSkipped(skipped);
        // 没跑成也不能算 ok。ok 是给上线卡点用的，
        // 而「没有证据」和「证据说没问题」是两回事
        out.setOk(failed == 0 && blocked == 0);
        out.setDurationMs(System.currentTimeMillis() - t0);
        out.setMessage((failed == 0
                ? "全部通过（" + envKey + "）"
                : "失败 " + failed + " / " + out.getTotal() + "（" + envKey + "）")
                + (skipped > 0 ? "，跳过 " + skipped + " 条只在测试跑的" : ""));
        try {
            runService.save(out, com.mobile.standalone.auth.CurrentUser.nameOrNull(), req.getTrigger());
        } catch (Exception e) {
            log.warn("[api-test] 执行历史落库失败: {}", e.getMessage());
        }
        try {
            // 回写失败绝不能影响执行结果本身：跑完了就是跑完了，
            // 台账没更新是另一件事，不该把一次成功的执行报成失败
            verifyService.applyRunOutcome(outcomes);
        } catch (Exception e) {
            log.warn("[api-test] 回写接口验证状态失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 试跑一条还没入库的用例：带自动登录和项目变量，但不记执行历史、不回写接口验证状态。
     * 生成用例时拿它看真实返回——那一跑是在「写用例」，不是在「验接口」。
     */
    public CaseResult tryRun(ApiTestCase c, String environment) {
        return runCaseForFlow(c, environment, Map.of(), null).result();
    }

    /**
     * UI 流程桥接：按已入库用例跑一条。
     * <p>
     * 环境 / ApiAuthService 鉴权 / 项目变量 / jsonPath·db 断言 / extract 全部复用接口测试基建；
     * <b>不</b>落执行历史、<b>不</b>回写接口验证状态——这是流程里的一步，不是接口回归批次。
     * <p>
     * {@code seedRuntime} 来自 UI 流程变量（如随机进场后的 {@code matchId}），
     * 用例里写 {@code {{matchId}}} 即可引用。优先级：runtime &gt; 项目环境变量。
     *
     * @param extractJsonOverride 本步额外/覆盖的 extract（JSON 对象）；空则用用例自带的
     */
    public FlowCaseResult runCaseForFlow(String caseId, String environment,
                                         Map<String, String> seedRuntime,
                                         String extractJsonOverride) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId 不能为空");
        }
        ApiTestCase c = caseMapper.findById(caseId.trim());
        if (c == null) {
            throw new IllegalArgumentException("接口用例不存在: " + caseId.trim()
                    + "（到「接口测试」里复制用例 id）");
        }
        if (Boolean.FALSE.equals(c.getEnabled())) {
            throw new IllegalStateException("接口用例已禁用: " + c.getName());
        }
        return runCaseForFlow(c, environment, seedRuntime, extractJsonOverride);
    }

    public FlowCaseResult runCaseForFlow(ApiTestCase c, String environment,
                                         Map<String, String> seedRuntime,
                                         String extractJsonOverride) {
        String envKey = TestEnvironment.normalize(environment);
        if (TestEnvironment.PROD.equals(envKey) && Boolean.TRUE.equals(c.getTestOnly())) {
            throw new IllegalStateException("用例「" + c.getName()
                    + "」标记了只在测试跑，不能在生产环境的 UI 流程里调用");
        }
        String caseEnv = c.getEnvironment() == null ? "" : c.getEnvironment().trim().toUpperCase();
        if (!caseEnv.isEmpty() && !"ANY".equals(caseEnv) && !caseEnv.equals(envKey)) {
            throw new IllegalStateException("用例「" + c.getName() + "」适用环境是 " + caseEnv
                    + "，当前流程环境是 " + envKey + "——环境不一致会验错库/错网关");
        }
        ApiTestEnv env = caseService.getEnv(envKey);
        if (env == null || Boolean.FALSE.equals(env.getEnabled())
                || env.getBaseUrl() == null || env.getBaseUrl().isBlank()) {
            throw new IllegalStateException("环境 " + envKey + " 没配好 baseUrl");
        }
        ApiTestCase effective = withExtractOverride(c, extractJsonOverride);
        String pj = effective.getProject() == null ? "" : effective.getProject();
        AuthCtx auth = authFor(pj, envKey);
        Map<String, String> envVars = new LinkedHashMap<>(variableService.resolveValues(envKey, pj));
        if (auth.token() != null) {
            envVars.put("token", auth.token());
        }
        Map<String, String> runtime = new LinkedHashMap<>();
        if (seedRuntime != null) {
            for (Map.Entry<String, String> e : seedRuntime.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    runtime.put(e.getKey(), e.getValue());
                }
            }
        }
        Map<String, String> before = new LinkedHashMap<>(runtime);
        CaseResult cr = executeOne(effective, env, null, null, envVars, runtime, auth.headers());
        if (auth.error() != null && !"PASSED".equals(cr.getStatus())) {
            cr.setError("自动登录失败（" + auth.error() + "）；" + (cr.getError() == null ? "" : cr.getError()));
        }
        Map<String, String> extracted = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : runtime.entrySet()) {
            // 只回传本步 extract 新写入的键，避免把 seed 再灌进流程变量
            if (!before.containsKey(e.getKey())) {
                extracted.put(e.getKey(), e.getValue());
            }
        }
        // extract 只在 PASSED 时执行；失败时 extracted 为空是预期
        cr.setExtracted(extracted);
        return new FlowCaseResult(cr, extracted, envKey);
    }

    /** UI 流程调用接口用例后的结果：断言结果 + 抽到的变量 */
    public record FlowCaseResult(CaseResult result, Map<String, String> extracted, String environment) {}

    /** 本步 extract 覆盖/合并进用例；不改库里的原用例 */
    static ApiTestCase withExtractOverride(ApiTestCase c, String extractJsonOverride) {
        if (extractJsonOverride == null || extractJsonOverride.isBlank()) {
            return c;
        }
        try {
            JsonNode override = JSON.readTree(extractJsonOverride.trim());
            if (override == null || !override.isObject()) {
                throw new IllegalArgumentException("extract 必须是 JSON 对象，如 {\"teamA\":\"$.result.match.team1DTO.name\"}");
            }
            com.fasterxml.jackson.databind.node.ObjectNode merged = JSON.createObjectNode();
            if (c.getExtractJson() != null && !c.getExtractJson().isBlank()) {
                JsonNode base = JSON.readTree(c.getExtractJson());
                if (base != null && base.isObject()) {
                    base.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
                }
            }
            override.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
            ApiTestCase copy = shallowCopyCase(c);
            copy.setExtractJson(JSON.writeValueAsString(merged));
            return copy;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("extract 不是合法 JSON：" + e.getMessage());
        }
    }

    private static ApiTestCase shallowCopyCase(ApiTestCase c) {
        ApiTestCase copy = new ApiTestCase();
        copy.setId(c.getId());
        copy.setProject(c.getProject());
        copy.setEnvironment(c.getEnvironment());
        copy.setName(c.getName());
        copy.setDescription(c.getDescription());
        copy.setRequirementRef(c.getRequirementRef());
        copy.setCaseKind(c.getCaseKind());
        copy.setSourceBranch(c.getSourceBranch());
        copy.setMethod(c.getMethod());
        copy.setPath(c.getPath());
        copy.setUrlId(c.getUrlId());
        copy.setQuery(c.getQuery());
        copy.setHeaders(c.getHeaders());
        copy.setBody(c.getBody());
        copy.setAssertions(c.getAssertions());
        copy.setExtractJson(c.getExtractJson());
        copy.setCollectionId(c.getCollectionId());
        copy.setEndpointId(c.getEndpointId());
        copy.setFieldNotes(c.getFieldNotes());
        copy.setEnabled(c.getEnabled());
        copy.setTestOnly(c.getTestOnly());
        copy.setSortOrder(c.getSortOrder());
        return copy;
    }

    /* caseKind 在这里快照：见 ApiTestRunCase.caseKind 的注释——
       查询时去 join 用例表的话，用例被升级成回归用例之后，历史运行的分桶会跟着变 */
    private CaseResult executeOne(ApiTestCase c, ApiTestEnv env, String tokenOverride, String urlIdOverride,
                                  Map<String, String> envVars, Map<String, String> runtime,
                                  Map<String, String> authHeaders) {
        CaseResult cr = new CaseResult();
        cr.setId(c.getId());
        cr.setName(c.getName());
        cr.setMethod(c.getMethod());
        cr.setCaseKind(c.getCaseKind());
        long t0 = System.currentTimeMillis();
        try {
            String baseUrl = resolveBaseUrl(c, env, urlIdOverride);
            String url = VariableResolver.resolve(
                    buildUrl(baseUrl, c.getPath(), c.getQuery(),
                            s -> VariableResolver.resolve(s, runtime, envVars)),
                    runtime, envVars);
            cr.setUrl(url);
            Map<String, String> headers = mergeHeaders(env, c, tokenOverride, authHeaders);
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
            // dbDelta 要的是「调接口前后差了多少」，所以请求<b>之前</b>先查一遍基线。
            // 放在发请求前面这一步没得商量：请求发出去数据就变了，那时再查已经晚了
            String resolvedAssertions =
                    withBizCodeAssertion(c, VariableResolver.resolve(c.getAssertions(), runtime, envVars));
            Map<String, Object> dbBefore = queryDbBaseline(resolvedAssertions, env);

            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            cr.setHttpStatus(resp.statusCode());
            cr.setBodyPreview(truncate(resp.body(), BODY_PREVIEW_MAX));
            cr.setRawBody(resp.body());
            // 把实际发出去的和实际收到的都带回去。
            // 只给一句「断言失败」，人还得自己猜发了什么、收到了什么，
            // 于是拿 Postman 再手工重放一遍——那一步正是这套东西本该省掉的
            cr.setReqHeaders(new LinkedHashMap<>(headers));
            cr.setReqBody(truncate(body, BODY_PREVIEW_MAX));
            Map<String, String> respHeaders = new LinkedHashMap<>();
            resp.headers().map().forEach((k, v) ->
                    respHeaders.put(k, v == null || v.isEmpty() ? "" : String.join(", ", v)));
            cr.setRespHeaders(respHeaders);
            List<String> assertMsgs = new ArrayList<>();
            String assertions = resolvedAssertions;
            long elapsed = System.currentTimeMillis() - t0;

            // 限流要在<b>断言之前</b>判。
            //
            // 被限流的响应是一个正常的 HTTP 200，body 里是「系统繁忙，请稍后再试」——
            // 放到断言里跑的话，它会以「业务码不等于 0」被判成 FAILED，
            // 而那条用例<b>一条断言都没真正跑到被测逻辑上</b>。
            //
            // 后果是：跑太快 → 通过率下降 → 人去查用例、查断言、查环境变量，
            // 而真实原因是频率。响应看起来完全正常，这个误导极难查。
            //
            // 被测后端的限流是全局的（EntryInterceptor 挂在所有接口上，没有 addPathPatterns），
            // 所以这条路径每个接口都可能走到。
            RateLimitVerdict rl = detectRateLimit(c, resp);
            if (rl.limited()) {
                cr.setStatus("BLOCKED");
                cr.setError(rl.describe());
                cr.setAssertionResults(List.of("BLOCKED " + rl.describe()));
                log.warn("[api-test] 被限流（{}）caseId={}: {}",
                        rl.worthRetrying() ? "可降速重试" : "重试无意义", c.getId(), cr.getError());
                // 提前返回也会走 finally，durationMs 照样会被设上
                return cr;
            }
            DbContext dbCtx = queryDbAfter(assertions, env, dbBefore, assertMsgs);
            boolean ok = evaluateAssertions(assertions, resp.statusCode(), resp.body(),
                    resp.headers().map(), elapsed, dbCtx, assertMsgs);
            if (!dbCtx.errors().isEmpty()) {
                ok = false;
            }
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
            // 连不上 / 超时 / DNS / 证书：请求就没发出去或没收回来，断言一条都没跑。
            // 这类记 BLOCKED——服务挂了的时候，整批用例不该看起来像整批 bug
            boolean transportFailure = com.mobile.standalone.service.ExecutionBlockers.isTransportFailure(e);
            cr.setStatus(transportFailure ? "BLOCKED" : "FAILED");
            cr.setError(e.getMessage() != null ? e.getMessage() : e.toString());
            log.warn("[api-test] {} caseId={}: {}",
                    transportFailure ? "没跑成（环境/网络）" : "执行失败", c.getId(), cr.getError());
        } finally {
            cr.setDurationMs(System.currentTimeMillis() - t0);
        }
        return cr;
    }

    /** 优先级：本次 urlIdOverride → 用例 urlId → 项目在该环境的地址 → 环境 baseUrl */
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
        // 全局环境 baseUrl 只有一个网关，国内项目（gwapi.pwesports.cn）和海外项目（gateway.arca42.com）
        // 不在同一个网关上；不先看项目自己的地址，国内用例会被发到海外网关上 404
        ApiUrlEntry projectUrl = apiUrlService.autoPick(c.getProject(), env.getEnvironment());
        if (projectUrl != null) {
            return projectUrl.getBaseUrl();
        }
        return env.getBaseUrl();
    }

    static String buildUrl(String baseUrl, String path, String queryJson) throws Exception {
        return buildUrl(baseUrl, path, queryJson, s -> s);
    }

    /**
     * @param resolveQuery query 的键和值在<b>编码之前</b>先过一遍变量替换：
     *                     先编码的话 {@code {{steamId}}} 会变成 {@code %7B%7BsteamId%7D%7D}，
     *                     后面整条 URL 再替换时就认不出来了，变量原样发给接口
     */
    static String buildUrl(String baseUrl, String path, String queryJson,
                           java.util.function.UnaryOperator<String> resolveQuery) throws Exception {
        String p = path == null ? "" : path.trim();
        String url;
        if (p.startsWith("http://") || p.startsWith("https://")) {
            url = p;
        } else {
            String base = ApiTestCaseService.trimSlash(baseUrl);
            if (!p.startsWith("/")) p = "/" + p;
            url = base + p;
        }
        Map<String, String> q = readQuery(queryJson);
        if (q.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(resolveQuery.apply(e.getKey()), StandardCharsets.UTF_8));
            sb.append('=');
            String v = e.getValue() == null ? "" : resolveQuery.apply(e.getValue());
            sb.append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Map<String, String> mergeHeaders(ApiTestEnv env, ApiTestCase c, String tokenOverride,
                                                    Map<String, String> authHeaders) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        out.putAll(readStringMap(env.getDefaultHeaders()));
        out.putAll(readStringMap(c.getHeaders()));
        // 自动登录拿到的比环境里存的默认 token 新；这一轮手动指定的 token 仍然优先
        if ((tokenOverride == null || tokenOverride.isBlank()) && authHeaders != null && !authHeaders.isEmpty()) {
            authHeaders.forEach(out::putIfAbsent);
            return out;
        }
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

    /**
     * query 两种写法都认：JSON 对象，或 {@code a=1&b=2} 这种查询串。
     * <p>
     * 只认 JSON 的话，人从浏览器地址栏粘一串过来就会炸——而炸出来的是
     * 「Unrecognized token 'steamId'」这种 Jackson 的话，
     * 看的人根本不知道是自己 query 写错了格式，只会以为接口坏了。
     */
    static Map<String, String> readQuery(String raw) throws Exception {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (v.startsWith("{")) {
            return readStringMap(v);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : v.replaceFirst("^\\?", "").split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String val = i < 0 ? "" : pair.substring(i + 1);
            if (!k.isBlank()) {
                out.put(k.trim(), val);
            }
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
    /**
     * 这次响应是不是被限流拦掉的。
     * <p>
     * 业务码路径复用项目上已经配好的 {@code successCodePath}——限流码和业务成功码
     * 本来就在同一个位置，没必要让人再配一遍。
     * <p>
     * 限流码本身配在平台级（{@code api-test.rate-limit.*}），不硬编码在代码里：
     * 910001/920001 是被测后端的约定，换个项目就不是这两个数了。
     */
    RateLimitVerdict detectRateLimit(ApiTestCase c, HttpResponse<String> resp) {
        if (resp == null) {
            return RateLimitVerdict.NOT_LIMITED;
        }
        String path = null;
        try {
            ApiProject p = c == null || c.getProject() == null ? null : projectService.get(c.getProject());
            if (p != null && p.getSuccessCodePath() != null && !p.getSuccessCodePath().isBlank()) {
                path = p.getSuccessCodePath().trim();
            }
        } catch (Exception ignored) {
            // 查不到项目就只按 HTTP 层判（429 / 503+Retry-After），不影响主流程
        }
        return RateLimitVerdict.detect(resp.statusCode(), resp.headers().map(), resp.body(),
                path, rateLimitThrottleCodes, rateLimitForbidCodes);
    }

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

    /**
     * 数据库断言这一轮查到的东西。
     *
     * @param values 每条 {@code db} 断言的 SQL → 查出来的标量
     * @param deltas 每条 {@code dbDelta} 断言的 SQL → 调接口前后的差值
     * @param errors 查库本身失败的原因。<b>这些要让用例失败</b>——
     *               查不到就等于没验，当成通过是最坏的结果
     */
    record DbContext(Map<String, Object> values, Map<String, Object> deltas, List<String> errors) {
        static DbContext empty() {
            return new DbContext(Map.of(), Map.of(), List.of());
        }
    }

    /** 请求前的基线。只有 {@code dbDelta} 需要——普通 db 断言查一次就够 */
    private Map<String, Object> queryDbBaseline(String assertionsJson, ApiTestEnv env) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String sql : dbSqlOf(assertionsJson, "dbDelta")) {
            var r = dbAssert.query(env, sql);
            if (r.ok()) {
                out.put(sql, r.value());
            }
        }
        return out;
    }

    /** 请求后再查一遍，普通 db 取值、dbDelta 算差 */
    private DbContext queryDbAfter(String assertionsJson, ApiTestEnv env,
                                   Map<String, Object> before, List<String> msgs) {
        List<String> plain = dbSqlOf(assertionsJson, "db");
        List<String> delta = dbSqlOf(assertionsJson, "dbDelta");
        if (plain.isEmpty() && delta.isEmpty()) {
            return DbContext.empty();
        }
        List<String> errors = new ArrayList<>();
        if (!dbAssert.configured(env)) {
            // 用了数据库断言却没配库，必须报出来。静默跳过＝这条断言等于不存在，
            // 而用例还是绿的——那比没有这个功能更危险
            errors.add("用了数据库断言，但环境 " + (env == null ? "?" : env.getEnvironment())
                    + " 还没配测试库连接");
            msgs.add("FAIL " + errors.get(0));
            return new DbContext(Map.of(), Map.of(), errors);
        }
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        Map<String, Object> deltas = new java.util.LinkedHashMap<>();
        for (String sql : plain) {
            var r = dbAssert.query(env, sql);
            if (r.ok()) {
                values.put(sql, r.value());
            } else {
                errors.add("库查询失败：" + r.error());
                msgs.add("FAIL 库查询失败：" + r.error());
            }
        }
        for (String sql : delta) {
            var r = dbAssert.query(env, sql);
            if (!r.ok()) {
                errors.add("库查询失败：" + r.error());
                msgs.add("FAIL 库查询失败：" + r.error());
                continue;
            }
            Object b = before.get(sql);
            // 基线没查到就不给差值：给个 0 会被断言当成「没变化」，那是编出来的结论
            deltas.put(sql, b == null ? null : num(r.value()) - num(b));
        }
        return new DbContext(values, deltas, errors);
    }

    private List<String> dbSqlOf(String assertionsJson, String type) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode arr = assertionsJson == null || assertionsJson.isBlank()
                    ? JSON.createArrayNode() : JSON.readTree(assertionsJson);
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode a : arr) {
                if (type.equals(text(a, "type"))) {
                    String sql = text(a, "sql");
                    if (sql != null && !sql.isBlank() && !out.contains(sql)) {
                        out.add(sql);
                    }
                }
            }
        } catch (Exception ignore) {
            // 断言 JSON 坏掉由 evaluateAssertions 统一报，这里不重复报
        }
        return out;
    }

    private static double num(Object o) {
        try {
            return o == null ? 0 : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 通用算子比较。db / dbDelta 用它，比 jsonPath 那套按 key 分支的写法更好扩展。
     * <p>
     * 算子取自 {@code op} 字段，期望值取自 {@code value}：
     * {@code eq ne gt gte lt lte contains notContains exists notEmpty matches}。
     */
    private boolean compare(JsonNode a, Object actual) {
        String op = text(a, "op");
        String expect = text(a, "value");
        if (op == null || op.isBlank()) {
            op = "eq";
        }
        return switch (op) {
            case "eq" -> nullish(expect) ? actual == null
                    : actual != null && String.valueOf(actual).equals(expect);
            // 字段没取到不能算「不等于」——那是没查到，不是有值且不同。两种问题得分开
            case "ne" -> actual != null
                    && (nullish(expect) || !String.valueOf(actual).equals(expect));
            case "contains" -> actual != null && expect != null
                    && String.valueOf(actual).contains(expect);
            case "notContains" -> actual != null && expect != null
                    && !String.valueOf(actual).contains(expect);
            case "exists" -> actual != null;
            case "notEmpty" -> actual != null && !String.valueOf(actual).isEmpty();
            case "gt" -> comparable(actual, expect) && num(actual) > Double.parseDouble(expect);
            case "gte" -> comparable(actual, expect) && num(actual) >= Double.parseDouble(expect);
            case "lt" -> comparable(actual, expect) && num(actual) < Double.parseDouble(expect);
            case "lte" -> comparable(actual, expect) && num(actual) <= Double.parseDouble(expect);
            case "matches" -> {
                if (actual == null || expect == null) {
                    yield false;
                }
                try {
                    yield java.util.regex.Pattern.compile(expect)
                            .matcher(String.valueOf(actual)).find();
                } catch (Exception e) {
                    // 正则写错了就是这条断言失败，别把整次执行搞崩
                    yield false;
                }
            }
            default -> false;
        };
    }

    private String opDesc(JsonNode a) {
        String op = text(a, "op");
        return (op == null || op.isBlank() ? "eq" : op) + " " + text(a, "value");
    }

    private static boolean nullish(String expect) {
        if (expect == null) {
            return true;
        }
        String v = expect.trim().toLowerCase(java.util.Locale.ROOT);
        return v.isEmpty() || "null".equals(v) || "undefined".equals(v) || "nil".equals(v);
    }

    /**
     * 数值比较前先确认两边都是数字。
     * <p>
     * 不加这层的话，字段缺失或查出来是 "abc" 都会变成 NaN 比较、结果恒为 false——
     * 看起来像断言失败，实际是取值就没取到。两种问题得分开。
     */
    private static boolean comparable(Object actual, String expect) {
        if (actual == null || String.valueOf(actual).isBlank() || expect == null) {
            return false;
        }
        try {
            Double.parseDouble(String.valueOf(actual));
            Double.parseDouble(expect);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 老签名保留给测试与只关心状态码/响应体的调用方 */
    private boolean evaluateAssertions(String assertionsJson, int status, String body, List<String> msgs) {
        return evaluateAssertions(assertionsJson, status, body, Map.of(), 0L, DbContext.empty(), msgs);
    }

    private boolean evaluateAssertions(String assertionsJson, int status, String body,
                                       Map<String, List<String>> headers, long elapsedMs,
                                       DbContext db, List<String> msgs) {
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
                    case "header" -> {
                        String name = text(a, "name");
                        String actual = headerValue(headers, name);
                        if (a.has("exists")) {
                            boolean want = a.path("exists").asBoolean(true);
                            pass = want == (actual != null);
                            detail = "header " + name + " exists=" + want;
                        } else if (a.has("contains")) {
                            String v = text(a, "contains");
                            pass = actual != null && v != null && actual.contains(v);
                            detail = "header " + name + " 含 " + v + " 实际=" + actual;
                        } else {
                            String v = text(a, "equals");
                            pass = actual != null && actual.equals(v);
                            detail = "header " + name + "==" + v + " 实际=" + actual;
                        }
                    }
                    case "responseTime" -> {
                        // 接口自动化顺手把耗时兜住：接口「能用但很慢」是最容易在提测阶段漏掉的一类问题，
                        // 等到线上才发现时，已经不知道是哪次改动引入的
                        long max = a.path("lessThan").asLong(a.path("max").asLong(0));
                        pass = max <= 0 || elapsedMs < max;
                        detail = "耗时 < " + max + "ms 实际=" + elapsedMs + "ms";
                    }
                    case "jsonPath" -> {
                        String path = text(a, "path");
                        JsonNode node = readJsonPath(body, path);
                        if (a.has("op")) {
                            // 新写法：{path, op, value}，算子和 db 断言同一套
                            // （多了 gte/lte/matches/notContains/notEmpty）。
                            // 老写法 {path, equals} 那些继续走下面的分支，不动存量用例
                            Object actual = node == null || node.isMissingNode() || node.isNull()
                                    ? null
                                    : (node.isValueNode() ? node.asText() : node.toString());
                            pass = compare(a, actual);
                            detail = "jsonPath " + path + " " + opDesc(a) + " 实际=" + truncate(
                                    actual == null ? "（没取到）" : String.valueOf(actual), 60);
                        } else if (a.has("exists")) {
                            boolean want = a.path("exists").asBoolean(true);
                            pass = want ? (node != null && !node.isMissingNode() && !node.isNull())
                                    : (node == null || node.isMissingNode() || node.isNull());
                            detail = "jsonPath " + path + " exists=" + want;
                        } else if (a.has("equals")) {
                            JsonNode expect = a.get("equals");
                            pass = node != null && jsonEquals(node, expect);
                            detail = "jsonPath " + path + "==" + expect + " 实际=" + node;
                        } else if (a.has("notEquals")) {
                            JsonNode expect = a.get("notEquals");
                            pass = node != null && !jsonEquals(node, expect);
                            detail = "jsonPath " + path + "!=" + expect + " 实际=" + node;
                        } else if (a.has("contains")) {
                            String v = text(a, "contains");
                            String actual = node == null ? null : (node.isValueNode() ? node.asText() : node.toString());
                            pass = actual != null && v != null && actual.contains(v);
                            detail = "jsonPath " + path + " 含 " + v + " 实际=" + truncate(actual, 60);
                        } else if (a.has("gt") || a.has("lt")) {
                            boolean isGt = a.has("gt");
                            double bound = (isGt ? a.get("gt") : a.get("lt")).asDouble();
                            // 非数字节点直接算不通过，别把 null 当 0——那会让「字段没返回」冒充成「值是 0」
                            pass = node != null && node.isNumber()
                                    && (isGt ? node.asDouble() > bound : node.asDouble() < bound);
                            detail = "jsonPath " + path + (isGt ? " > " : " < ") + bound + " 实际=" + node;
                        } else if (a.has("size")) {
                            int want = a.path("size").asInt(-1);
                            pass = node != null && node.isArray() && node.size() == want;
                            detail = "jsonPath " + path + " 数组长度==" + want
                                    + " 实际=" + (node != null && node.isArray() ? node.size() : "非数组");
                        } else {
                            pass = node != null && !node.isMissingNode() && !node.isNull();
                            detail = "jsonPath " + path + " 存在";
                        }
                    }
                    case "db" -> {
                        String sql = text(a, "sql");
                        Object actual = db.values().get(sql);
                        pass = compare(a, actual);
                        detail = "db[" + truncate(sql, 60) + "] " + opDesc(a) + " 实际=" + actual;
                    }
                    case "dbDelta" -> {
                        String sql = text(a, "sql");
                        Object actual = db.deltas().get(sql);
                        // 基线没查到时 delta 是 null，不能当 0——那会把「没查到」说成「没变化」
                        pass = actual != null && compare(a, actual);
                        detail = "dbDelta[" + truncate(sql, 60) + "] " + opDesc(a)
                                + " 实际=" + (actual == null ? "基线没查到" : actual);
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

    /** 响应头名大小写不敏感（HTTP 规范如此，人写断言时不该被这个绊住） */
    private static String headerValue(Map<String, List<String>> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> v = e.getValue();
                return v == null || v.isEmpty() ? null : v.get(0);
            }
        }
        return null;
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
