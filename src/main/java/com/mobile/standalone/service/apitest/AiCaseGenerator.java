package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.dto.ApiTestDtos.CaseResult;
import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.service.ApiProjectService;
import com.mobile.standalone.service.ApiTestCaseService;
import com.mobile.standalone.service.ApiTestRunnerService;
import com.mobile.standalone.service.EnvVariableService;
import com.mobile.standalone.service.ai.QwenChatClient;
import com.mobile.standalone.service.apichange.ApiParam;
import com.mobile.standalone.service.apichange.JavaApiSourceParser;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * AI 生成接口用例：模型读源码出请求，平台在测试环境真跑一次，按真实返回写断言。
 * <p>
 * 断言不让模型写。模型不知道这个接口此刻返回什么，猜出来的期望值第一次跑多半是红的，
 * 而红得没道理的用例改几次，人就不再信这套东西了。模型擅长的是「读懂源码该传什么、哪里有校验」，
 * 平台擅长的是「真跑一次看结果」，各干各的。
 * <p>
 * 业务数据（比赛 id 这类）模型编不出来。批量时先跑不需要参数的查询接口，
 * 从它们的真实返回里收集 xxxId，后面的接口直接拿来用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCaseGenerator {

    public static final String CREATED_BY = "AI 生成";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CASES = 4;
    private static final int MAX_FIELD_ASSERTS = 4;
    private static final int KEEP_JOBS = 20;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", "target", "build", "out", "node_modules", "test", "tests");
    private static final Pattern HARVEST_KEY = Pattern.compile("(?i).*id$");
    private static final Set<String> AUTH_PARAMS = Set.of("token", "accesstoken", "userid", "loginuserid");

    private final QwenChatClient qwen;
    private final ApiEndpointService endpointService;
    private final ApiProjectService projectService;
    private final ApiTestCaseService caseService;
    private final ApiTestCaseMapper caseMapper;
    private final ApiTestRunnerService runner;
    private final EnvVariableService variableService;
    private final ApiTestCollectionService collectionService;

    /** 读源码出请求用不着推理大模型：实测 flash 20 秒、max 80 秒，出的用例一样 */
    @org.springframework.beans.factory.annotation.Value("${ai-case.model:qwen3.6-flash}")
    private String model;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "ai-case-gen");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------ job

    /**
     * @param dir       源码目录；空时用项目管理里配的仓库路径
     * @param tryRun    在测试环境真跑一次、按真实返回写断言。默认不跑：地址配的可能是生产
     * @param runWrites 非 GET 接口也试跑。默认不跑：会改测试环境的数据，有的还会调付费服务
     */
    public record Request(String project, List<String> endpointIds, boolean onlyUncovered,
                          boolean tryRun, boolean runWrites, String dir) {
    }

    @Data
    public static class Item {
        private String endpointId;
        private String method;
        private String path;
        private String summary;
        /** WAITING / RUNNING / ENABLED（有能直接跑的）/ PENDING（生成了但要人补）/ SKIPPED / FAILED */
        private String status = "WAITING";
        private int cases;
        private int enabled;
        private String note;
        private List<String> caseIds = new ArrayList<>();
    }

    @Data
    public static class Job {
        private String id;
        private String project;
        private int total;
        private int done;
        private int created;
        private int enabledCases;
        private int failed;
        private boolean running;
        private boolean cancelled;
        private String message;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private List<Item> items = Collections.synchronizedList(new ArrayList<>());
        @com.fasterxml.jackson.annotation.JsonIgnore
        private final Map<String, String> harvest = new ConcurrentHashMap<>();
        @com.fasterxml.jackson.annotation.JsonIgnore
        private Path root;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private Map<String, Path> classIndex;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String collectionId;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private boolean tryRun;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private boolean runWrites;
    }

    public Job start(Request req) {
        if (!qwen.isConfigured()) {
            throw new IllegalStateException("千问没配：application.yml 里 diagnosis.ai.api-key 填上密钥");
        }
        if (CaptureCatalogService.INBOX.equals(req.project())) {
            throw new IllegalArgumentException("待归类的接口先归到项目再生成");
        }
        ApiProject p = projectService.get(req.project());
        if (p == null) {
            throw new IllegalArgumentException("项目不存在: " + req.project());
        }
        Job running = active(p.getCode());
        if (running != null) {
            throw new IllegalStateException("这个项目已经有一批在生成了（" + running.getDone() + "/" + running.getTotal() + "）");
        }

        List<ApiEndpoint> targets = new ArrayList<>();
        if (req.endpointIds() != null && !req.endpointIds().isEmpty()) {
            for (String id : req.endpointIds()) {
                ApiEndpoint e = endpointService.get(id);
                if (e != null && !e.isRetired()) targets.add(e);
            }
        } else {
            for (ApiEndpoint e : endpointService.search(p.getCode(), null, null, null)) {
                if (e.isRetired() || "DEPRECATED".equalsIgnoreCase(e.getStatus())) continue;
                if (req.onlyUncovered() && !caseMapper.findByEndpointId(e.getId()).isEmpty()) continue;
                targets.add(e);
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("没有要生成的接口");
        }
        // 只靠抓包也能生成：没配源码目录时，有抓包响应的接口照样走，其余的逐条报原因
        String dir = req.dir() == null || req.dir().isBlank() ? p.getRepoPath() : req.dir().trim();
        Path root = dir == null || dir.isBlank() ? null : Path.of(dir).toAbsolutePath().normalize();
        if (root != null && !Files.isDirectory(root)) {
            if (targets.stream().noneMatch(this::hasCaptureSample)) {
                throw new IllegalArgumentException("源码目录不存在: " + root);
            }
            root = null;
        }
        if (root == null && targets.stream().noneMatch(this::hasCaptureSample)) {
            throw new IllegalArgumentException("不知道源码在哪，也没有抓包响应：先抓一次包，或在项目管理里配仓库路径");
        }

        Job job = new Job();
        job.setId("aig-" + UUID.randomUUID().toString().substring(0, 8));
        job.setProject(p.getCode());
        job.setTotal(targets.size());
        job.setRunning(true);
        job.setStartedAt(LocalDateTime.now());
        job.setRoot(root);
        job.setCollectionId(collectionService.ensureDefaultLiveCollection(p.getCode()).getId());
        job.setTryRun(req.tryRun());
        job.setRunWrites(req.runWrites());
        Map<String, ApiEndpoint> byId = new HashMap<>();
        for (ApiEndpoint e : targets) {
            Item it = new Item();
            it.setEndpointId(e.getId());
            it.setMethod(e.getHttpMethod());
            it.setPath(e.getPath());
            it.setSummary(e.getSummary());
            job.getItems().add(it);
            byId.put(e.getId(), e);
        }
        jobs.put(job.getId(), job);
        trimJobs();
        Thread t = new Thread(() -> runJob(job, byId), "ai-case-job-" + job.getId());
        t.setDaemon(true);
        t.start();
        return job;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    public Job active(String project) {
        return jobs.values().stream()
                .filter(j -> j.isRunning() && j.getProject().equals(project))
                .findFirst().orElse(null);
    }

    public Job latest(String project) {
        return jobs.values().stream()
                .filter(j -> j.getProject().equals(project))
                .max(Comparator.comparing(Job::getStartedAt))
                .orElse(null);
    }

    /** 早先生成时没归集合的，补进项目的全量集合，不然用例集合页看不到 */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void adoptOrphans() {
        try {
            Map<String, List<String>> byProject = new LinkedHashMap<>();
            for (ApiTestCase c : caseMapper.findByFilters(null, "__none__", null, false, null, null, null, null)) {
                if (CREATED_BY.equals(c.getCreatedBy()) && c.getProject() != null && !c.getProject().isBlank()) {
                    byProject.computeIfAbsent(c.getProject(), k -> new ArrayList<>()).add(c.getId());
                }
            }
            byProject.forEach((project, ids) -> {
                String colId = collectionService.ensureDefaultLiveCollection(project).getId();
                collectionService.assign(colId, ids);
                log.info("[ai-case] {} 条 AI 用例归入 {} 的全量集合", ids.size(), project);
            });
        } catch (Exception e) {
            log.warn("[ai-case] 补归集合跳过: {}", e.getMessage());
        }
    }

    public boolean cancel(String id) {
        Job j = jobs.get(id);
        if (j == null) return false;
        j.setCancelled(true);
        return true;
    }

    private void trimJobs() {
        if (jobs.size() <= KEEP_JOBS) return;
        jobs.values().stream()
                .filter(j -> !j.isRunning())
                .sorted(Comparator.comparing(Job::getStartedAt))
                .limit(jobs.size() - KEEP_JOBS)
                .map(Job::getId)
                .toList()
                .forEach(jobs::remove);
    }

    private void runJob(Job job, Map<String, ApiEndpoint> byId) {
        try {
            parseCache.clear();
            job.setClassIndex(job.getRoot() == null ? Map.of() : indexClasses(job.getRoot()));
            // 先跑不要参数的查询接口：它们的真实返回里有后面接口要的 id
            List<Item> first = new ArrayList<>();
            List<Item> rest = new ArrayList<>();
            for (Item it : new ArrayList<>(job.getItems())) {
                ApiEndpoint e = byId.get(it.getEndpointId());
                (isFreeQuery(job, e) ? first : rest).add(it);
            }
            runPhase(job, byId, first);
            runPhase(job, byId, rest);
            job.setMessage(job.isCancelled() ? "已取消" : "完成");
        } catch (Exception e) {
            log.warn("[ai-case] job {} 失败", job.getId(), e);
            job.setMessage("中断：" + e.getMessage());
        } finally {
            job.setRunning(false);
            job.setFinishedAt(LocalDateTime.now());
        }
    }

    private void runPhase(Job job, Map<String, ApiEndpoint> byId, List<Item> items) throws Exception {
        List<Future<?>> fs = new ArrayList<>();
        for (Item it : items) {
            fs.add(pool.submit(() -> {
                if (job.isCancelled()) {
                    it.setStatus("SKIPPED");
                    it.setNote("已取消");
                } else {
                    processOne(job, byId.get(it.getEndpointId()), it);
                }
                synchronized (job) {
                    job.setDone(job.getDone() + 1);
                    if ("FAILED".equals(it.getStatus())) job.setFailed(job.getFailed() + 1);
                    job.setCreated(job.getCreated() + it.getCases());
                    job.setEnabledCases(job.getEnabledCases() + it.getEnabled());
                }
            }));
        }
        for (Future<?> f : fs) {
            f.get();
        }
    }

    private boolean isFreeQuery(Job job, ApiEndpoint e) {
        if (e == null || !"GET".equalsIgnoreCase(e.getHttpMethod()) || e.getPath().contains("{")) {
            return false;
        }
        try {
            var parsed = parsed(job, e);
            return parsed != null && parsed.params().stream().noneMatch(ApiParam::required);
        } catch (Exception ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ one endpoint

    record AiCase(String name, String kind, String expect, Map<String, String> pathParams,
                  Map<String, String> query, JsonNode body, String basis) {
    }

    record AiPlan(List<AiCase> cases, List<String> dataParams) {
    }

    /** 这一条怎么判：断言、要不要启用、给人看的一句话 */
    record Verdict(String assertions, boolean enabled, String note) {
    }

    private void processOne(Job job, ApiEndpoint e, Item it) {
        it.setStatus("RUNNING");
        try {
            if (e == null) {
                throw new IllegalStateException("接口不存在了");
            }
            boolean hasAi = caseMapper.findByEndpointId(e.getId()).stream()
                    .anyMatch(c -> CREATED_BY.equals(c.getCreatedBy()));
            if (hasAi) {
                it.setStatus("SKIPPED");
                it.setNote("已经 AI 生成过");
                return;
            }
            boolean noSource = job.getRoot() == null || e.getSourceFile() == null || e.getSourceFile().isBlank();
            if (noSource && hasCaptureSample(e)) {
                processCapture(job, e, it);
                return;
            }
            if (noSource) {
                throw new IllegalStateException("没有源码也没有抓包响应：先抓一次包，或在项目管理里配仓库路径后全量扫描");
            }
            var parsed = parsed(job, e);
            String context = sourceContext(job, e, parsed);
            if (context == null) {
                throw new IllegalStateException("源码里找不到这个方法（" + e.getSourceFile() + "#" + e.getHandler() + "），重新全量扫描一次");
            }
            Map<String, String> capture = EndpointAssertionContext.queryOf(endpointService.captureMetaOf(e));
            String prompt = buildPrompt(e, parsed == null ? List.of() : parsed.params(), capture,
                    job.getHarvest(), context);
            AiPlan plan = parsePlan(qwen.chat(prompt, List.of(), model));
            if (plan.cases().isEmpty()) {
                throw new IllegalStateException("模型没给出用例");
            }

            ApiProject project = projectService.get(e.getProject());
            Map<String, String> vars = safeVars(e.getProject());
            boolean canRun = job.isTryRun() && ("GET".equalsIgnoreCase(e.getHttpMethod()) || job.isRunWrites());
            int enabled = 0;
            List<String> notes = new ArrayList<>();
            for (AiCase ac : plan.cases()) {
                List<String> filled = new ArrayList<>();
                ApiTestCase c = toCase(e, ac, plan.dataParams(), vars, capture, job.getHarvest(), filled);
                Verdict v;
                if (!job.isTryRun()) {
                    v = unrun(ac, project == null ? null : project.getSuccessCodePath(),
                            project == null ? null : project.getSuccessCodeValue(), plan.dataParams(), filled);
                } else if (!canRun) {
                    v = new Verdict(null, false, "写接口没自动试跑（会改测试环境数据），确认参数后手动跑一次");
                } else {
                    CaseResult r = runner.tryRun(c, "TEST");
                    v = judge(ac, r, project == null ? null : project.getSuccessCodePath(),
                            project == null ? null : project.getSuccessCodeValue(), plan.dataParams(), filled);
                    if (v.enabled() && r.getRawBody() != null && "SUCCESS".equals(ac.expect())) {
                        harvest(r.getRawBody(), job.getHarvest());
                    }
                }
                if (v.assertions() != null) {
                    c.setAssertions(v.assertions());
                }
                c.setEnabled(v.enabled());
                c.setDescription(describe(e, ac, v, filled));
                ApiTestCase saved = caseService.createCase(c);
                collectionService.assign(job.getCollectionId(), List.of(saved.getId()));
                it.getCaseIds().add(saved.getId());
                it.setCases(it.getCases() + 1);
                if (v.enabled()) {
                    enabled++;
                } else if (notes.size() < 2) {
                    notes.add(ac.name() + "：" + v.note());
                }
            }
            it.setEnabled(enabled);
            it.setStatus(enabled > 0 ? "ENABLED" : "PENDING");
            it.setNote(notes.isEmpty() ? (job.isTryRun() ? "全部试跑通过" : null) : String.join("；", notes));
        } catch (Exception ex) {
            log.warn("[ai-case] {} {} 失败: {}", e == null ? "?" : e.getHttpMethod(), e == null ? "?" : e.getPath(), ex.getMessage());
            it.setStatus("FAILED");
            it.setNote(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private boolean hasCaptureSample(ApiEndpoint e) {
        return e != null && EndpointAssertionContext.respSampleOf(endpointService.captureMetaOf(e)) != null;
    }

    /**
     * 只有抓包、没有源码：模型看真实请求和响应出用例和断言，断言再拿响应验一遍。
     * 不试跑；抓的是测试环境，用例只给测试环境跑。
     */
    private void processCapture(Job job, ApiEndpoint e, Item it) throws Exception {
        Map<String, Object> cap = endpointService.captureMetaOf(e);
        ApiProject project = projectService.get(e.getProject());
        var in = new CaptureAiCasePlanner.Input(
                e.getHttpMethod() == null || "ANY".equalsIgnoreCase(e.getHttpMethod()) ? "GET" : e.getHttpMethod(),
                e.getPath(), e.getSummary(),
                EndpointAssertionContext.queryOf(cap), EndpointAssertionContext.reqBodyOf(cap),
                EndpointAssertionContext.respSampleOf(cap),
                project == null ? null : project.getSuccessCodePath(),
                project == null ? null : project.getSuccessCodeValue());
        var cases = CaptureAiCasePlanner.plan(qwen.chat(CaptureAiCasePlanner.prompt(in), List.of(), model), in);
        String headers = RequirementCaseGenerator.headersJson(EndpointAssertionContext.headersOf(cap));
        int enabled = 0;
        List<String> notes = new ArrayList<>();
        for (var pc : cases) {
            ApiTestCase c = new ApiTestCase();
            c.setProject(e.getProject());
            String base = e.getSummary() != null && !e.getSummary().isBlank() ? e.getSummary().trim() : e.getPath();
            String name = base + " · " + pc.name();
            c.setName(name.length() > 120 ? name.substring(0, 120) : name);
            c.setMethod(in.method());
            c.setPath(e.getPath());
            c.setQuery(toJson(pc.query()));
            c.setBody(pc.body() == null ? "" : pc.body());
            c.setHeaders(headers);
            c.setAssertions(pc.assertions() == null ? "[]" : pc.assertions());
            c.setEnabled(pc.enabled());
            c.setEnvironment("TEST");
            c.setTestOnly(true);
            c.setEndpointId(e.getId());
            c.setCreatedBy(CREATED_BY);
            c.setSortOrder(pc.normal() ? 50 : 60);
            StringBuilder desc = new StringBuilder("AI 按抓包生成 · ")
                    .append(pc.normal() ? "正常" : "边界")
                    .append(" · ").append(in.method()).append(' ').append(e.getPath());
            if (pc.basis() != null && !pc.basis().isBlank()) {
                desc.append("\n依据：").append(pc.basis().trim());
            }
            desc.append("\n状态：").append(pc.note());
            c.setDescription(desc.toString());
            ApiTestCase saved = caseService.createCase(c);
            collectionService.assign(job.getCollectionId(), List.of(saved.getId()));
            it.getCaseIds().add(saved.getId());
            it.setCases(it.getCases() + 1);
            if (pc.enabled()) {
                enabled++;
            } else if (pc.normal() && notes.size() < 2) {
                notes.add(pc.name() + "：" + pc.note());
            }
        }
        it.setEnabled(enabled);
        it.setStatus(enabled > 0 ? "ENABLED" : "PENDING");
        it.setNote(notes.isEmpty() ? "按抓包生成，边界用例先停用" : String.join("；", notes));
    }

    private Map<String, String> safeVars(String project) {
        try {
            return variableService.resolveValues("TEST", project);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ApiTestCase toCase(ApiEndpoint e, AiCase ac, List<String> dataParams, Map<String, String> vars,
                               Map<String, String> capture, Map<String, String> harvest, List<String> filled) {
        boolean normal = "SUCCESS".equals(ac.expect());
        Map<String, String> query = new LinkedHashMap<>(ac.query());
        Map<String, String> pathParams = new LinkedHashMap<>(ac.pathParams());
        if (normal) {
            fillData(query, dataParams, vars, capture, harvest, filled);
            fillData(pathParams, dataParams, vars, capture, harvest, filled);
        }
        String path = e.getPath();
        for (var p : pathParams.entrySet()) {
            path = path.replace("{" + p.getKey() + "}", p.getValue());
        }
        String body = "";
        if (ac.body() != null && !ac.body().isNull()) {
            JsonNode b = ac.body().deepCopy();
            if (normal && b.isObject()) {
                Map<String, String> top = new LinkedHashMap<>();
                b.fields().forEachRemaining(f -> {
                    if (f.getValue().isValueNode()) top.put(f.getKey(), f.getValue().asText());
                });
                Map<String, String> before = new LinkedHashMap<>(top);
                fillData(top, dataParams, vars, capture, harvest, filled);
                top.forEach((k, v) -> {
                    if (!v.equals(before.get(k))) ((ObjectNode) b).put(k, v);
                });
            }
            body = b.toString();
        }
        ApiTestCase c = new ApiTestCase();
        c.setProject(e.getProject());
        String base = e.getSummary() != null && !e.getSummary().isBlank() ? e.getSummary().trim() : e.getPath();
        String name = base + " · " + ac.name();
        c.setName(name.length() > 120 ? name.substring(0, 120) : name);
        c.setRequirementRef(e.getHandler());
        c.setMethod(e.getHttpMethod() == null || "ANY".equalsIgnoreCase(e.getHttpMethod()) ? "GET" : e.getHttpMethod());
        c.setPath(path);
        c.setQuery(toJson(query));
        c.setBody(body);
        c.setHeaders("{\"Accept\":\"application/json\"}");
        c.setAssertions("[]");
        c.setEnvironment("ANY");
        c.setTestOnly(!"GET".equals(c.getMethod()));
        c.setCreatedBy(CREATED_BY);
        c.setSortOrder("SUCCESS".equals(ac.expect()) ? 50 : 60);
        return c;
    }

    /** 业务数据参数：项目变量 → 抓包值 → 别的接口返回里收集的，都没有就用模型填的 */
    static void fillData(Map<String, String> params, List<String> dataParams, Map<String, String> vars,
                         Map<String, String> capture, Map<String, String> harvest, List<String> filled) {
        if (dataParams == null || dataParams.isEmpty()) {
            return;
        }
        for (String name : dataParams) {
            if (!params.containsKey(name)) continue;
            if (vars.containsKey(name)) {
                params.put(name, "{{" + name + "}}");
                filled.add(name + " 用了变量 {{" + name + "}}");
            } else if (capture.get(name) != null && !capture.get(name).isBlank()) {
                params.put(name, capture.get(name));
                filled.add(name + "=" + capture.get(name) + "（抓包）");
            } else if (harvest.get(name.toLowerCase()) != null) {
                String v = harvest.get(name.toLowerCase());
                params.put(name, v);
                filled.add(name + "=" + v + "（别的接口返回里拿的）");
            }
        }
    }

    static Verdict judge(AiCase ac, CaseResult r, String codePath, String successValue,
                         List<String> dataParams, List<String> filled) {
        if ("BLOCKED".equals(r.getStatus()) || r.getHttpStatus() == null) {
            return new Verdict(null, false, "没跑成：" + nz(r.getError()));
        }
        int http = r.getHttpStatus();
        String body = r.getRawBody();
        boolean hasCode = codePath != null && !codePath.isBlank();
        String want = successValue == null || successValue.isBlank() ? "0" : successValue.trim();
        String actualCode = hasCode ? read(body, codePath) : null;
        boolean http2xx = http >= 200 && http < 300;
        boolean bizOk = http2xx && (!hasCode || want.equals(actualCode));
        String msg = firstNonNull(read(body, "$.message"), read(body, "$.msg"));
        String got = "HTTP " + http + (actualCode == null ? "" : " code=" + actualCode)
                + (msg == null || msg.isBlank() ? "" : "「" + truncate(msg, 40) + "」");

        ArrayNode asserts = JSON.createArrayNode();
        if ("SUCCESS".equals(ac.expect())) {
            if (!bizOk) {
                String hint = "";
                List<String> unknown = dataParams == null ? List.of() : dataParams.stream()
                        .filter(d -> filled.stream().noneMatch(f -> f.startsWith(d + " ") || f.startsWith(d + "=")))
                        .toList();
                if (!unknown.isEmpty()) {
                    hint = "，多半是 " + String.join("、", unknown) + " 不是真实数据，换一个真实值";
                }
                return new Verdict(null, false, "试跑返回 " + got + hint);
            }
            asserts.addObject().put("type", "status").put("equals", 200);
            if (hasCode) {
                putEquals(asserts.addObject().put("type", "jsonPath").put("path", codePath), actualCode);
            }
            addFieldAsserts(asserts, body);
            return new Verdict(asserts.toString(), true, "试跑通过");
        }
        if (bizOk) {
            return new Verdict(null, false, "预期报错，实际返回成功（" + got + "）——可能缺校验，也可能预期不对，看一眼");
        }
        asserts.addObject().put("type", "status").put("equals", http);
        if (http2xx && hasCode && actualCode != null) {
            putEquals(asserts.addObject().put("type", "jsonPath").put("path", codePath), actualCode);
        }
        return new Verdict(asserts.toString(), true, "按预期报错：" + got);
    }

    /** 不试跑：只按项目的成功码约定断言，业务数据没着落的留停用 */
    static Verdict unrun(AiCase ac, String codePath, String successValue,
                         List<String> dataParams, List<String> filled) {
        boolean hasCode = codePath != null && !codePath.isBlank();
        String want = successValue == null || successValue.isBlank() ? "0" : successValue.trim();
        ArrayNode asserts = JSON.createArrayNode();
        if ("SUCCESS".equals(ac.expect())) {
            asserts.addObject().put("type", "status").put("equals", 200);
            if (hasCode) {
                putEquals(asserts.addObject().put("type", "jsonPath").put("path", codePath), want);
            }
            List<String> unknown = dataParams == null ? List.of() : dataParams.stream()
                    .filter(d -> filled.stream().noneMatch(f -> f.startsWith(d + " ") || f.startsWith(d + "=")))
                    .toList();
            if (!unknown.isEmpty()) {
                return new Verdict(asserts.toString(), false,
                        "没试跑；" + String.join("、", unknown) + " 是模型编的，换成真实值再启用");
            }
            return new Verdict(asserts.toString(), true, "没试跑，断言按成功码写的");
        }
        if (!hasCode) {
            return new Verdict(null, false, "没试跑；项目没配成功码字段，报错用例写不了断言");
        }
        ObjectNode a = asserts.addObject().put("type", "jsonPath").put("path", codePath);
        try {
            a.put("notEquals", Long.parseLong(want));
        } catch (NumberFormatException e) {
            a.put("notEquals", want);
        }
        return new Verdict(asserts.toString(), true, "没试跑，断言业务码不是成功码");
    }

    private static void putEquals(ObjectNode a, String value) {
        try {
            a.put("equals", Long.parseLong(value));
        } catch (NumberFormatException e) {
            a.put("equals", value);
        }
    }

    /** 数据节点里挑几个有值的字段断言「存在」。值不断言：换一份数据就变 */
    static void addFieldAsserts(ArrayNode asserts, String body) {
        JsonNode root;
        try {
            root = body == null ? null : JSON.readTree(body);
        } catch (Exception e) {
            return;
        }
        if (root == null || !root.isObject()) return;
        String base = null;
        JsonNode data = null;
        for (String k : List.of("result", "data")) {
            if (root.has(k) && !root.get(k).isNull()) {
                base = "$." + k;
                data = root.get(k);
                break;
            }
        }
        if (data == null) return;
        if (data.isArray()) {
            if (data.isEmpty() || !data.get(0).isObject()) {
                asserts.addObject().put("type", "jsonPath").put("path", base).put("exists", true);
                return;
            }
            base = base + "[0]";
            data = data.get(0);
        }
        if (!data.isObject()) {
            asserts.addObject().put("type", "jsonPath").put("path", base).put("exists", true);
            return;
        }
        int n = 0;
        Iterator<Map.Entry<String, JsonNode>> it = data.fields();
        while (it.hasNext() && n < MAX_FIELD_ASSERTS) {
            var f = it.next();
            JsonNode v = f.getValue();
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isEmpty())) continue;
            if (!f.getKey().matches("[A-Za-z_][\\w]*")) continue;
            asserts.addObject().put("type", "jsonPath").put("path", base + "." + f.getKey()).put("exists", true);
            n++;
        }
        if (n == 0) {
            asserts.addObject().put("type", "jsonPath").put("path", base).put("exists", true);
        }
    }

    /** 从成功的返回里收集 xxxId，给后面的接口当真实数据用 */
    static void harvest(String body, Map<String, String> into) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return;
        }
        int[] budget = {3000};
        walk(root, into, 0, budget);
    }

    private static void walk(JsonNode n, Map<String, String> into, int depth, int[] budget) {
        if (n == null || depth > 6 || budget[0]-- <= 0) return;
        if (n.isObject()) {
            n.fields().forEachRemaining(f -> {
                JsonNode v = f.getValue();
                if (v.isValueNode() && !v.isNull() && HARVEST_KEY.matcher(f.getKey()).matches()) {
                    String s = v.asText();
                    if (!s.isBlank() && !"0".equals(s) && s.length() <= 64) {
                        into.putIfAbsent(f.getKey().toLowerCase(), s);
                    }
                } else {
                    walk(v, into, depth + 1, budget);
                }
            });
        } else if (n.isArray()) {
            int i = 0;
            for (JsonNode x : n) {
                if (i++ >= 3) break;
                walk(x, into, depth + 1, budget);
            }
        }
    }

    private static String describe(ApiEndpoint e, AiCase ac, Verdict v, List<String> filled) {
        StringBuilder sb = new StringBuilder("AI 按源码生成 · ")
                .append("SUCCESS".equals(ac.expect()) ? "正常" : "边界")
                .append(" · ").append(e.getHttpMethod()).append(' ').append(e.getPath());
        if (ac.basis() != null && !ac.basis().isBlank()) {
            sb.append("\n依据：").append(ac.basis().trim());
        }
        if (!filled.isEmpty()) {
            sb.append("\n参数：").append(String.join("；", filled));
        }
        sb.append("\n状态：").append(v.note());
        return sb.toString();
    }

    // ------------------------------------------------------------------ source

    record Parsed(List<ApiParam> params, String returnType) {
    }

    private final Map<Path, List<com.mobile.standalone.service.apichange.ApiEndpoint>> parseCache = new ConcurrentHashMap<>();

    private Parsed parsed(Job job, ApiEndpoint e) throws IOException {
        Path file = sourceFile(job, e);
        if (file == null) return null;
        var eps = parseCache.computeIfAbsent(file, f -> {
            try {
                return JavaApiSourceParser.parseEndpoints(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                return List.of();
            }
        });
        for (var p : eps) {
            if (p.handler().equals(e.getHandler()) && p.path().equals(e.getPath())) {
                return new Parsed(p.params(), p.returnType());
            }
        }
        for (var p : eps) {
            if (p.handler().equals(e.getHandler())) {
                return new Parsed(p.params(), p.returnType());
            }
        }
        return null;
    }

    private Path sourceFile(Job job, ApiEndpoint e) {
        if (job.getRoot() == null || e.getSourceFile() == null || e.getSourceFile().isBlank()) return null;
        Path f = job.getRoot().resolve(e.getSourceFile()).normalize();
        if (Files.isRegularFile(f)) return f;
        String name = Path.of(e.getSourceFile()).getFileName().toString().replace(".java", "");
        return job.getClassIndex() == null ? null : job.getClassIndex().get(name);
    }

    /** 方法本身 + 入参类（含父类）+ 它调到的 Service 实现，总长有上限 */
    private String sourceContext(Job job, ApiEndpoint e, Parsed parsed) throws IOException {
        Path file = sourceFile(job, e);
        if (file == null) return null;
        String src = Files.readString(file, StandardCharsets.UTF_8);
        String method = JavaSnippets.method(src, e.getHandler());
        if (method == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(file.getFileName()).append('\n').append(truncate(method, 6000)).append("\n\n");

        String signature = method.substring(0, Math.max(0, method.indexOf('{')))
                .replaceAll("@\\w+(\\([^)]*\\))?", " ");
        List<String> seen = new ArrayList<>();
        for (String type : JavaSnippets.typeNames(signature)) {
            appendClass(job, type, sb, seen, 0);
        }

        int services = 0;
        for (String[] call : JavaSnippets.calls(method)) {
            if (services >= 2 || sb.length() > 14000) break;
            String type = JavaSnippets.fieldType(src, call[0]);
            if (type == null) continue;
            Path impl = implOf(job, type);
            if (impl == null) continue;
            String implSrc = Files.readString(impl, StandardCharsets.UTF_8);
            String m = JavaSnippets.method(implSrc, call[1]);
            if (m == null) continue;
            sb.append("// ").append(impl.getFileName()).append('\n').append(truncate(m, 3000)).append("\n\n");
            services++;
        }
        return sb.toString();
    }

    private void appendClass(Job job, String type, StringBuilder sb, List<String> seen, int depth) throws IOException {
        if (depth > 2 || seen.contains(type) || seen.size() >= 5 || sb.length() > 12000) return;
        seen.add(type);
        Path f = job.getClassIndex().get(type);
        if (f == null) return;
        String src = Files.readString(f, StandardCharsets.UTF_8);
        if (src.contains("@RestController") || src.contains("@Service")) return;
        sb.append("// ").append(f.getFileName()).append('\n').append(JavaSnippets.fieldsOnly(src, 2500)).append("\n\n");
        String parent = JavaSnippets.superclass(src);
        if (parent != null) {
            appendClass(job, parent, sb, seen, depth + 1);
        }
    }

    private Path implOf(Job job, String type) {
        Map<String, Path> idx = job.getClassIndex();
        for (String name : List.of(type + "Impl", type.replaceFirst("^I(?=[A-Z])", "") + "Impl", type)) {
            Path p = idx.get(name);
            if (p != null) return p;
        }
        return null;
    }

    static Map<String, Path> indexClasses(Path root) throws IOException {
        Map<String, Path> idx = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return dir != root && SKIP_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String n = file.getFileName().toString();
                if (n.endsWith(".java")) {
                    idx.putIfAbsent(n.substring(0, n.length() - 5), file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return idx;
    }

    // ------------------------------------------------------------------ prompt

    static String buildPrompt(ApiEndpoint e, List<ApiParam> params, Map<String, String> capture,
                              Map<String, String> harvest, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是接口测试工程师。根据下面的 Spring 接口源码，设计这个接口的测试请求。\n\n");
        sb.append("接口：").append(e.getHttpMethod()).append(' ').append(e.getPath());
        if (e.getSummary() != null && !e.getSummary().isBlank()) {
            sb.append("（").append(e.getSummary()).append("）");
        }
        sb.append('\n');
        sb.append("登录：").append(Boolean.TRUE.equals(e.getAuthRequired()) ? "需要" : Boolean.FALSE.equals(e.getAuthRequired()) ? "不需要" : "未知")
                .append("（平台会自动带登录 token，你不用管）\n");
        if (params != null && !params.isEmpty()) {
            sb.append("方法签名里的参数：\n");
            for (ApiParam p : params) {
                sb.append("- ").append(p.source()).append(' ').append(p.name()).append(' ').append(p.type())
                        .append(p.required() ? " 必填" : "").append('\n');
            }
        }
        if (capture != null && !capture.isEmpty()) {
            sb.append("抓包时的真实参数：").append(toJson(capture)).append('\n');
        }
        if (harvest != null && !harvest.isEmpty()) {
            sb.append("已知真实数据（别的接口返回里拿的，字段名小写）：");
            int i = 0;
            for (var h : harvest.entrySet()) {
                if (i++ >= 40) break;
                sb.append(h.getKey()).append('=').append(h.getValue()).append("  ");
            }
            sb.append('\n');
        }
        sb.append("\n源码：\n```java\n").append(source).append("```\n\n");
        sb.append("""
                要求：
                1. 第一条是正常用例：kind=NORMAL，expect=SUCCESS，参数要能真实跑通。优先用抓包值和已知真实数据；\
                需要真实业务数据但你不知道的（比如某个比赛 id），按类型填一个合理的值，并把参数名写进 dataParams。
                2. 边界用例 kind=BOUNDARY，只写源码里有明确校验或分支依据的：缺必填、非法值、越界、回落默认值等。\
                没依据就不写，最多 3 条。basis 用一句话说源码依据。expect 写 ERROR 或 SUCCESS（比如非法值回落默认值是 SUCCESS）。
                3. 不要写断言，平台会按真实返回写。
                4. @PathVariable 写进 pathParams；@RequestParam、以及没加注解的对象参数的字段写进 query；@RequestBody 写进 body（JSON 对象）。
                5. 登录态相关的参数（token、accessToken、从登录态取的 userId）不要填。
                6. name 用简短中文，如「正常查询」「缺少 matchId」。

                只输出 JSON，不要解释：
                {"cases":[{"name":"","kind":"NORMAL","expect":"SUCCESS","pathParams":{},"query":{},"body":null,"basis":""}],"dataParams":[]}
                """);
        return sb.toString();
    }

    static AiPlan parsePlan(String raw) throws Exception {
        String s = raw == null ? "" : raw.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw new IllegalStateException("模型返回的不是 JSON：" + truncate(s, 120));
        }
        JsonNode root = JSON.readTree(s.substring(a, b + 1));
        List<AiCase> cases = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            if (cases.size() >= MAX_CASES) break;
            String kind = "BOUNDARY".equalsIgnoreCase(c.path("kind").asText()) ? "BOUNDARY" : "NORMAL";
            String expect = "ERROR".equalsIgnoreCase(c.path("expect").asText()) ? "ERROR" : "SUCCESS";
            String name = c.path("name").asText("").trim();
            if (name.isEmpty()) name = "NORMAL".equals(kind) ? "正常请求" : "边界";
            JsonNode body = c.get("body");
            cases.add(new AiCase(name, kind, expect, strMap(c.get("pathParams")), strMap(c.get("query")),
                    body == null || body.isNull() ? null : body, c.path("basis").asText("")));
        }
        List<String> data = new ArrayList<>();
        for (JsonNode d : root.path("dataParams")) {
            if (d.isTextual() && !d.asText().isBlank()) data.add(d.asText().trim());
        }
        return new AiPlan(cases, data);
    }

    private static Map<String, String> strMap(JsonNode n) {
        Map<String, String> out = new LinkedHashMap<>();
        if (n == null || !n.isObject()) return out;
        n.fields().forEachRemaining(f -> {
            JsonNode v = f.getValue();
            if (v == null || v.isNull() || AUTH_PARAMS.contains(f.getKey().toLowerCase())) return;
            out.put(f.getKey(), v.isValueNode() ? v.asText() : v.toString());
        });
        return out;
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String read(String body, String path) {
        try {
            return JsonPaths.readAsString(body, path);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
