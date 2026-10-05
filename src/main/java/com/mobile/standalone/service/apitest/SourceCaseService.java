package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.dto.ApiTestDtos.CaseResult;
import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.service.ApiProjectService;
import com.mobile.standalone.service.ApiTestCaseService;
import com.mobile.standalone.service.ApiTestRunnerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按源码给接口生成参数用例：参数体检（{@link ParamInsight}）的结论 + 抓包请求当底子（{@link SourceCases}）。
 * <p>
 * GET 先在测试环境试跑一次：和源码推断一致的启用；不一致的停用并写明实际返回——
 * 不一致本身就是线索：要么源码读错了，要么测试环境跑的不是这份代码。写接口不自动跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceCaseService {

    public static final String CREATED_BY = "源码生成";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EndpointInsightService insightService;
    private final ApiEndpointService endpointService;
    private final ApiEndpointMapper endpointMapper;
    private final ApiProjectService projectService;
    private final ApiTestCaseService caseService;
    private final ApiTestCaseMapper caseMapper;
    private final ApiTestCollectionService collectionService;
    private final ApiTestRunnerService runner;

    /** @param fixed 已有的 AI「缺少 xx」用例按源码纠正了几条 */
    public record Outcome(String endpointId, String method, String path, int created, int enabled, int fixed,
                          int existed, List<String> notes, String reason) {
    }

    /** 预览：参数表和会生成的用例（不落库） */
    public Map<String, Object> preview(String endpointId) {
        ApiEndpoint e = endpointMapper.findById(endpointId);
        if (e == null) {
            throw new IllegalArgumentException("接口不存在：" + endpointId);
        }
        var res = insightService.inspect(endpointId, null, null, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("located", res.located());
        out.put("reason", res.reason());
        if (!res.located()) {
            return out;
        }
        out.put("params", res.report().params());
        out.put("cases", plan(e, res.report()));
        out.put("hasCapture", EndpointAssertionContext.queryOf(endpointService.captureMetaOf(e)) != null
                || EndpointAssertionContext.reqBodyOf(endpointService.captureMetaOf(e)) != null);
        return out;
    }

    public List<Outcome> generate(List<String> endpointIds, boolean tryRun) {
        List<Outcome> out = new ArrayList<>();
        for (String id : endpointIds) {
            ApiEndpoint e = endpointMapper.findById(id);
            if (e == null) {
                out.add(new Outcome(id, null, null, 0, 0, 0, 0, List.of(), "接口不存在"));
                continue;
            }
            try {
                var res = insightService.inspect(id, null, null, null, null);
                if (!res.located()) {
                    out.add(fail(e, res.reason()));
                    continue;
                }
                String col = collectionService.ensureDefaultLiveCollection(e.getProject()).getId();
                out.add(generate(e, res.report(), col, tryRun));
            } catch (Exception ex) {
                log.warn("[source-case] {} {} 失败: {}", e.getHttpMethod(), e.getPath(), ex.getMessage());
                out.add(fail(e, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }
        return out;
    }

    /**
     * 给一个接口落用例。AI 按抓包生成时也走这里，集合跟着那次生成走。
     *
     * @param collectionId 用例放进哪个集合
     */
    public Outcome generate(ApiEndpoint e, EndpointInsight.Report r, String collectionId, boolean tryRun) {
        List<SourceCases.Planned> planned = plan(e, r);
        if (planned.isEmpty()) {
            boolean noCapture = captureQuery(e) == null && captureBody(e) == null;
            return fail(e, noCapture ? "没有抓包请求当底子，先抓一次包"
                    : r.params().isEmpty() ? "源码里没认出客户端传的参数" : "参数在代码里怎么处理看不出来，没法写期望");
        }
        Map<String, ApiTestCase> existing = new LinkedHashMap<>();
        for (ApiTestCase c : caseMapper.findByEndpointId(e.getId())) {
            existing.putIfAbsent(c.getName(), c);
        }
        String base = e.getSummary() != null && !e.getSummary().isBlank() ? e.getSummary().trim() : e.getPath();
        String method = e.getHttpMethod() == null || "ANY".equalsIgnoreCase(e.getHttpMethod()) ? "GET"
                : e.getHttpMethod().toUpperCase(Locale.ROOT);
        boolean canRun = tryRun && "GET".equals(method);
        String headers = RequirementCaseGenerator.headersJson(
                EndpointAssertionContext.headersOf(endpointService.captureMetaOf(e)));
        int created = 0;
        int enabled = 0;
        int fixed = 0;
        int existed = 0;
        List<String> notes = new ArrayList<>();
        for (SourceCases.Planned p : planned) {
            String name = cut(base + " · " + p.name(), 120);
            if (existing.containsKey(name)) {
                existed++;
                continue;
            }
            ApiTestCase legacy = p.query() != null && p.name().contains("缺少 " + p.param())
                    ? legacyMissing(existing.values(), p.param()) : null;
            ApiTestCase c = legacy != null ? legacy : new ApiTestCase();
            if (legacy == null) {
                c.setProject(e.getProject());
                c.setName(name);
                c.setMethod(method);
                c.setPath(e.getPath());
                c.setQuery(toJson(p.query()));
                c.setBody(p.body() == null ? "" : p.body());
                c.setHeaders(headers);
                c.setEnvironment("TEST");
                c.setTestOnly(true);
                c.setEndpointId(e.getId());
                c.setCreatedBy(CREATED_BY);
                c.setSortOrder(p.suspect() ? 55 : 62);
            }
            c.setAssertions(p.assertions());
            c.setIntent(p.intent());
            c.setExpectNote(cut(p.expect(), 600));
            String status;
            boolean on;
            if (!canRun) {
                on = false;
                status = "GET".equals(method) ? "没试跑，先停用" : "写接口没自动试跑（会改测试环境数据），确认后手动跑一次";
            } else {
                CaseResult run = runner.tryRun(c, "TEST");
                if ("PASSED".equals(run.getStatus())) {
                    on = true;
                    status = "试跑结果和源码推断一致";
                } else if ("BLOCKED".equals(run.getStatus()) || run.getHttpStatus() == null) {
                    on = false;
                    status = "没跑成：" + nz(run.getError());
                } else {
                    on = false;
                    status = "试跑和源码推断不一致：" + firstFail(run) + "。要么源码读错了，要么测试环境跑的不是这份代码";
                    if (notes.size() < 3) {
                        notes.add(p.name() + "：" + firstFail(run));
                    }
                }
            }
            c.setEnabled(on);
            String head = "源码参数分析生成 · " + ("NORMAL".equals(p.intent()) ? "正常" : "异常输入")
                    + (p.suspect() ? " · 疑似问题" : "") + " · " + method + ' ' + e.getPath();
            if (legacy != null) {
                c.setDescription(cut(nz(legacy.getDescription()) + "\n已按源码纠正断言：" + p.expect()
                        + "\n状态：" + status, 2000));
                caseService.updateCase(legacy.getId(), c);
                fixed++;
            } else {
                c.setDescription(head + "\n" + p.expect() + "\n状态：" + status);
                ApiTestCase saved = caseService.createCase(c);
                if (collectionId != null) {
                    collectionService.assign(collectionId, List.of(saved.getId()));
                }
                created++;
            }
            if (on) {
                enabled++;
            }
            if (p.suspect() && notes.size() < 3) {
                notes.add(0, "疑似问题：" + p.name());
            }
        }
        return new Outcome(e.getId(), e.getHttpMethod(), e.getPath(), created, enabled, fixed, existed, notes, null);
    }

    /** AI 写的「缺少 matchId」那条：参数确实没带，但断言只看了成功码，分不清是不是在测异常 */
    private static ApiTestCase legacyMissing(java.util.Collection<ApiTestCase> cases, String param) {
        String p = param.toLowerCase(Locale.ROOT);
        for (ApiTestCase c : cases) {
            String n = c.getName() == null ? "" : c.getName().toLowerCase(Locale.ROOT);
            if (CREATED_BY.equals(c.getCreatedBy()) || c.getIntent() != null) {
                continue;
            }
            if ((n.contains("缺少" + p) || n.contains("缺少 " + p) || n.contains("缺" + p) || n.contains("不传" + p)
                    || n.contains("不传 " + p) || n.contains("missing " + p))
                    && (c.getQuery() == null || !c.getQuery().contains("\"" + param + "\""))) {
                return c;
            }
        }
        return null;
    }

    List<SourceCases.Planned> plan(ApiEndpoint e, EndpointInsight.Report r) {
        ApiProject project = projectService.get(e.getProject());
        Map<String, String> q = captureQuery(e);
        return SourceCases.plan(r, q == null ? Map.of() : CaptureAiCasePlanner.authAsVariable(q), captureBody(e),
                project == null ? null : project.getSuccessCodePath(),
                project == null ? null : project.getSuccessCodeValue(), SourceCases.MAX_CASES);
    }

    private Map<String, String> captureQuery(ApiEndpoint e) {
        return EndpointAssertionContext.queryOf(endpointService.captureMetaOf(e));
    }

    private String captureBody(ApiEndpoint e) {
        return EndpointAssertionContext.reqBodyOf(endpointService.captureMetaOf(e));
    }

    private static String firstFail(CaseResult r) {
        if (r.getAssertionResults() != null) {
            for (String s : r.getAssertionResults()) {
                if (s != null && s.startsWith("FAIL")) {
                    return cut(s.substring(4).strip(), 120);
                }
            }
        }
        return r.getError() != null ? cut(r.getError(), 120) : "HTTP " + r.getHttpStatus();
    }

    private static Outcome fail(ApiEndpoint e, String reason) {
        return new Outcome(e.getId(), e.getHttpMethod(), e.getPath(), 0, 0, 0, 0, List.of(), reason);
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
