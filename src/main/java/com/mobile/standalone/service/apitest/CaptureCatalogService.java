package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestCollection;
import com.mobile.standalone.entity.ApiUrlEntry;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.service.ApiProjectService;
import com.mobile.standalone.service.ApiTestCaseService;
import com.mobile.standalone.service.ApiUrlService;
import com.mobile.standalone.service.capture.PacketCaptureService;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 测试环境抓包 → 接口清单 → 全量用例。
 * <p>
 * 不经过需求单：需求单只在接口对比分析时挂。这条路是「先把测试环境平时用到的接口
 * 和用例都建起来」，生成的用例一律 {@code testOnly}，线上回归不会跑到它们。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptureCatalogService {

    /** 这里生成的用例打这个标记，去重只认它，人手写的同接口用例不算重复 */
    static final String CREATED_BY = "抓包";

    /** 早期版本认不出归属的接口放这里；现在收录必须人选项目，只剩老数据会用到 */
    public static final String INBOX = "capture-inbox";

    private final PacketCaptureService captureService;
    private final ApiEndpointService endpointService;
    private final ApiEndpointMapper endpointMapper;
    private final ApiProjectService projectService;
    private final ApiTestCaseService caseService;
    private final ApiTestCaseMapper caseMapper;
    private final ApiTestCollectionService collectionService;
    private final ApiUrlService urlService;

    /**
     * 网关路由「路径第一段=服务名」，逗号分隔，抄自 Apollo gateway 项目的 spring.cloud.gateway.routes。
     * 服务名按项目编码/名称对项目，对不上的忽略。
     */
    @org.springframework.beans.factory.annotation.Value("${app.api-test.gateway-routes:" + DEFAULT_GATEWAY_ROUTES + "}")
    private String gatewayRoutes;

    static final String DEFAULT_GATEWAY_ROUTES =
            "acty=app-community,avo=app-video,apppush=app-push,appuser=appuser,appdatacenter=app-data-center,"
                    + "eventcenter=event-center,robot=app-robot,gld=global-data,activitycenter=esports-activity-center,"
                    + "appdecoration=app-decoration,mallCenter=mall-center,coupon=coupon,consumer=consumer,provider=provider,"
                    + "glp=global-push,global-comment=global-comment,glc=global-community,glu=global-user,"
                    + "gla=global-assistant,global-publish=global-publish,gle=global-event,glrb=global-robot,"
                    + "glr=global-review,global-admin=global-admin,glag=global-agent,steam-agent=steam-agent,"
                    + "textassistant=text-assistant,huqiu-gateway=huqiu-gateway,pvp-csgo-mode=pvp-csgo-mode,"
                    + "pvp-sstv=pvp-sstv,pvp-vip=pvp-vip,pvp-user=pvp-user,pvp-galaxy=pvp-galaxy,pvparc=pvparc,"
                    + "pvp-usergroup=pvp-usergroup,pvp-cod=pvp-cod,pvp-message=pvp-message,pvp-warchief=pvp-warchief,"
                    + "pvp-decoration=pvp-decoration,pvp-booklet=pvp-booklet";

    static Map<String, String> parseRoutes(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null) {
            return out;
        }
        for (String item : spec.split(",")) {
            int eq = item.indexOf('=');
            if (eq > 0 && eq < item.length() - 1) {
                out.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
            }
        }
        return out;
    }

    /**
     * @param project 人在收录弹窗里选的项目；给了就以它为准，不再按路径推断
     * @param url     抓到的完整请求地址，取它的 协议://域名 回填项目的测试地址
     */
    public record FlowRef(Integer seq, String method, String path, String query, String project, String url) {
        public FlowRef(Integer seq, String method, String path, String query) {
            this(seq, method, path, query, null, null);
        }

        public FlowRef(Integer seq, String method, String path, String query, String project) {
            this(seq, method, path, query, project, null);
        }
    }

    static final String CAPTURE_URL_NAME = captureUrlName(TestEnvironment.TEST);

    /**
     * 收进接口清单，并存下这次的请求参数、请求头、请求体和响应样本。
     *
     * @param fallbackProject 行上没选项目时用它；两边都没有就不收，不按路径猜
     */
    public List<Map<String, Object>> save(String sessionId, String fallbackProject, List<FlowRef> flows) {
        String session = sessionId == null || sessionId.isBlank() ? "last" : sessionId;
        List<Map<String, Object>> out = new ArrayList<>();
        for (FlowRef f : flows) {
            if (f.path() == null || f.path().isBlank()) {
                continue;
            }
            String method = f.method() == null || f.method().isBlank() ? "GET" : f.method().toUpperCase();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", f.seq());
            row.put("method", method);
            row.put("path", f.path());
            if (ApiEndpointService.isPreflight(method)) {
                row.put("message", "OPTIONS 是浏览器跨域预检，不收录");
                out.add(row);
                continue;
            }
            String infra = InfraEndpoints.reason(f.path());
            if (infra != null) {
                row.put("message", infra + "，不收录");
                out.add(row);
                continue;
            }
            String project;
            if (f.project() != null && !f.project().isBlank()) {
                project = ApiAppCatalog.treeProjectKey(f.project().trim());
                String bad = INBOX.equals(project) ? "不能收录到「待归类」"
                        : projectService.get(project) == null ? "项目不存在：" + project : null;
                if (bad != null) {
                    row.put("message", bad);
                    out.add(row);
                    continue;
                }
                // 之前收进过待归类的，挪过来而不是再建一条：否则待归类里永远留着一份
                ApiEndpoint parked = endpointMapper.findByKey(INBOX, method, f.path());
                if (parked != null) {
                    classify(List.of(parked.getId()), project);
                }
            } else if (fallbackProject != null && !fallbackProject.isBlank()) {
                project = ApiAppCatalog.treeProjectKey(fallbackProject.trim());
                if (INBOX.equals(project) || projectService.get(project) == null) {
                    row.put("message", "项目不存在：" + project);
                    out.add(row);
                    continue;
                }
            } else {
                // 不按路径猜：同一个网关域名下挂着好几个服务，猜错了比不收更难查
                row.put("message", "没选归属项目");
                out.add(row);
                continue;
            }
            row.put("project", project);
            String id = endpointService.ensureEndpointFor(project, method, f.path(), null, null);
            if (id == null) {
                row.put("message", "收进清单失败");
                out.add(row);
                continue;
            }
            JsonNode detail = f.seq() == null ? null : captureService.body(session, f.seq());
            if (detail != null) {
                // 先存响应样本再存请求：前者会重写 capture_meta，后者是往里合并
                endpointService.applyCaptureDetail(id,
                        detail.path("reqBody").asText(null), detail.path("respBody").asText(null));
            }
            endpointService.applyCaptureRequest(project, method, f.path(), f.query(), headersOf(detail));
            endpointService.markCaptured(id);
            if (detail != null) {
                endpointService.markCaptureEnv(id, captureService.envOf(session));
            }
            ApiEndpoint e = endpointService.get(id);
            row.put("endpointId", id);
            String origin = originOf(f.url());
            if (origin != null) {
                row.put("origin", origin);
            }
            EndpointLevel level = ApiEndpointService.levelOf(e);
            row.put("level", level == null ? null : level.name());
            row.put("caseCount", e == null || e.getCaseCount() == null ? 0 : e.getCaseCount());
            out.add(row);
        }
        return out;
    }

    /**
     * 收录前先看哪些已经在清单里：重复收不会多建一条，但会用这次抓包覆盖原来的样本，
     * 人得在勾选前就知道。
     *
     * @return 每行 {method, path, owners: [{project, id, caseCount}], inbox}；
     *         owners 不含待归类，待归类单独用 inbox 标出来（再收会挪进选的项目）
     */
    public List<Map<String, Object>> knownEndpoints(List<FlowRef> flows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FlowRef f : flows) {
            if (f.path() == null || f.path().isBlank()) {
                continue;
            }
            String method = f.method() == null || f.method().isBlank() ? "GET" : f.method().toUpperCase();
            List<Map<String, Object>> owners = new ArrayList<>();
            boolean inbox = false;
            for (ApiEndpoint e : endpointMapper.findByMethodPath(method, f.path())) {
                if (INBOX.equals(e.getProject())) {
                    inbox = true;
                    continue;
                }
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("project", e.getProject());
                o.put("id", e.getId());
                o.put("caseCount", caseMapper.findByEndpointId(e.getId()).size());
                owners.add(o);
            }
            if (owners.isEmpty() && !inbox) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", method);
            row.put("path", f.path());
            row.put("owners", owners);
            row.put("inbox", inbox);
            out.add(row);
        }
        return out;
    }

    PathProjectGuesser guesser() {
        List<com.mobile.standalone.entity.ApiProject> backends = projectService.list(true).stream()
                .filter(ApiProjectKind::scansEndpoints)
                .toList();
        return new PathProjectGuesser(backends, endpointMapper.search(null, null, null, null),
                parseRoutes(gatewayRoutes == null ? DEFAULT_GATEWAY_ROUTES : gatewayRoutes));
    }

    /** 收录弹窗预填用：每行 {method, path, project, reason}，猜不出的不返回 */
    public List<Map<String, Object>> guessProjects(List<FlowRef> flows) {
        PathProjectGuesser g = guesser();
        List<Map<String, Object>> out = new ArrayList<>();
        for (FlowRef f : flows) {
            PathProjectGuesser.Guess guess = g.guess(f.url(), f.path());
            if (guess == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", f.method() == null || f.method().isBlank() ? "GET" : f.method().toUpperCase());
            row.put("path", f.path());
            row.put("project", guess.project());
            row.put("reason", guess.reason());
            out.add(row);
        }
        return out;
    }

    /**
     * 按路径看明显放错项目的接口：每行 {id, project, method, path, caseCount, suggest, reason}。
     * 源码里找到了 handler 的不算——那说明它确实在这个项目的仓库里。
     */
    public List<Map<String, Object>> misplaced() {
        PathProjectGuesser g = guesser();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiEndpoint e : endpointMapper.search(null, null, null, null)) {
            if (e.isRetired() || INBOX.equals(e.getProject())
                    || (e.getHandler() != null && !e.getHandler().isBlank())) {
                continue;
            }
            PathProjectGuesser.Guess guess = g.guess(e.getPath());
            if (guess == null || guess.project().equals(e.getProject())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("project", e.getProject());
            row.put("method", e.getHttpMethod());
            row.put("path", e.getPath());
            row.put("caseCount", e.getCaseCount() == null ? 0 : e.getCaseCount());
            row.put("suggest", guess.project());
            row.put("reason", guess.reason());
            out.add(row);
        }
        return out;
    }

    /** 清单里的 OPTIONS 预检，每行 {id, project, path, caseCount} */
    public List<Map<String, Object>> preflightEndpoints() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiEndpoint e : endpointMapper.search(null, null, null, null)) {
            if (!ApiEndpointService.isPreflight(e.getHttpMethod())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("project", e.getProject());
            row.put("path", e.getPath());
            row.put("caseCount", e.getCaseCount() == null ? 0 : e.getCaseCount());
            out.add(row);
        }
        return out;
    }

    /**
     * 删掉清单里的 OPTIONS 预检。
     *
     * @param includeCases 挂着用例的也删、连用例一起删；false 时只删没用例的，有用例的原样留着返回给人看
     */
    @Transactional
    public Map<String, Object> purgePreflight(boolean includeCases) {
        int deleted = 0;
        int casesDeleted = 0;
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : preflightEndpoints()) {
            String id = (String) row.get("id");
            if (((Number) row.get("caseCount")).intValue() > 0) {
                if (!includeCases) {
                    kept.add(row);
                    continue;
                }
                for (ApiTestCase c : caseMapper.findByEndpointId(id)) {
                    casesDeleted += caseMapper.deleteById(c.getId());
                }
            }
            deleted += endpointMapper.deleteById(id);
        }
        return Map.of("deleted", deleted, "casesDeleted", casesDeleted, "kept", kept);
    }

    /** {@code https://host[:port]}，默认端口不写；解析不出来返回 null */
    static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            String scheme = u.getScheme().toLowerCase(java.util.Locale.ROOT);
            int port = u.getPort();
            boolean defaultPort = port < 0 || ("https".equals(scheme) && port == 443)
                    || ("http".equals(scheme) && port == 80);
            return scheme + "://" + u.getHost() + (defaultPort ? "" : ":" + port);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 用抓包的真实地址覆盖项目的测试环境地址。
     * <p>
     * 不改已有的地址：内网直连那些是状态监控按 id 引用的，改了探活就断。
     * 单独维护一条「抓包回填」排在最前（sort 0），测试环境执行默认挑它；
     * 项目当前挑中的已经是这个域名就什么都不做。
     * 一个项目这次抓到多个域名时取出现最多的那个。
     *
     * @param rows {@link #save} 的返回
     */
    public List<Map<String, Object>> backfillTestUrls(List<Map<String, Object>> rows) {
        return backfillCaptureUrls(rows, TestEnvironment.TEST);
    }

    /** 同上，按抓包所在的环境回填：导入的生产抓包回填的是生产地址，不能顶掉测试环境的 */
    public List<Map<String, Object>> backfillCaptureUrls(List<Map<String, Object>> rows, String env) {
        String target = env == null || env.isBlank() ? TestEnvironment.TEST : TestEnvironment.normalize(env);
        Map<String, Map<String, Integer>> votes = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object project = r.get("project");
            Object origin = r.get("origin");
            if (r.get("endpointId") == null || project == null || origin == null || INBOX.equals(project)) {
                continue;
            }
            votes.computeIfAbsent(String.valueOf(project), k -> new LinkedHashMap<>())
                    .merge(String.valueOf(origin), 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        votes.forEach((project, byOrigin) -> {
            String origin = byOrigin.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            out.add(backfillOrFail(project, target, origin));
        });
        return out;
    }

    /** 人在收录弹窗里定的地址：哪个项目、哪个环境、用哪个域名 */
    public record UrlPlan(String project, String environment, String baseUrl) {}

    /**
     * 按人填的计划回填项目地址。域名可以不带协议（按 https），路径部分丢掉——
     * 用例的 path 是从根开始拼的，地址里带了路径会拼出两段。
     */
    public List<Map<String, Object>> applyUrlPlans(List<UrlPlan> plans) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UrlPlan p : plans) {
            String project = p.project() == null ? "" : ApiAppCatalog.treeProjectKey(p.project().trim());
            String raw = p.baseUrl() == null ? "" : p.baseUrl().trim();
            String origin = originOf(raw.contains("://") ? raw : "https://" + raw);
            String bad = project.isEmpty() || INBOX.equals(project) ? "没选项目"
                    : projectService.get(project) == null ? "项目不存在：" + project
                    : origin == null ? "地址不对：" + raw : null;
            if (bad != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("project", project);
                r.put("baseUrl", raw);
                r.put("action", "FAILED");
                r.put("message", bad);
                out.add(r);
                continue;
            }
            out.add(backfillOrFail(project, TestEnvironment.normalize(p.environment()), origin));
        }
        return out;
    }

    private Map<String, Object> backfillOrFail(String project, String env, String origin) {
        try {
            return backfillUrl(project, env, origin);
        } catch (Exception ex) {
            log.warn("[capture-catalog] 回填 {} {} 地址失败: {}", project, env, ex.getMessage());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("project", project);
            r.put("environment", env);
            r.put("baseUrl", origin);
            r.put("action", "FAILED");
            r.put("message", ex.getMessage() == null ? "回填失败" : ex.getMessage());
            return r;
        }
    }

    static String captureUrlName(String env) {
        return "抓包回填 · " + TestEnvironment.label(env);
    }

    private Map<String, Object> backfillUrl(String project, String env, String origin) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("project", project);
        r.put("environment", env);
        r.put("baseUrl", origin);
        ApiUrlEntry picked = urlService.autoPick(project, env);
        if (picked != null && picked.getBaseUrl() != null
                && origin.equalsIgnoreCase(picked.getBaseUrl().trim().replaceAll("/+$", ""))) {
            r.put("action", "KEPT");
            return r;
        }
        if (picked != null) {
            r.put("previous", picked.getBaseUrl());
        }
        String name = captureUrlName(env);
        ApiUrlEntry mine = urlService.list(project, env, null).stream()
                .filter(u -> name.equals(u.getName()))
                .findFirst().orElse(null);
        ApiUrlEntry body = new ApiUrlEntry();
        body.setProject(project);
        body.setName(name);
        body.setEnvironment(env);
        body.setBaseUrl(origin);
        body.setDescription("抓包收录时按真实请求回填；排在最前，" + TestEnvironment.label(env) + "执行默认走它");
        body.setEnabled(true);
        body.setSortOrder(0);
        if (mine == null) {
            urlService.create(body);
            r.put("action", "CREATED");
        } else {
            urlService.update(mine.getId(), body);
            r.put("action", "UPDATED");
        }
        return r;
    }

    /**
     * 人工归类：把接口挪到指定项目。
     * <p>
     * 目标项目已有同一个接口时合并进去（抓包数据以这次为准），再删掉这条——
     * 留两条的话同一个接口在清单里出现两次，用例也分散在两边。
     */
    @Transactional
    public Map<String, Object> classify(List<String> ids, String project) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("要归到哪个项目？");
        }
        String target = ApiAppCatalog.treeProjectKey(project.trim());
        if (INBOX.equals(target)) {
            throw new IllegalArgumentException("不能归到「待归类」");
        }
        if (projectService.get(target) == null) {
            throw new IllegalArgumentException("项目不存在：" + target);
        }
        int moved = 0;
        int merged = 0;
        Map<String, String> liveOf = new java.util.HashMap<>();
        for (String id : ids) {
            ApiEndpoint e = endpointMapper.findById(id);
            if (e == null || target.equals(e.getProject())) {
                continue;
            }
            String from = e.getProject();
            ApiEndpoint same = endpointMapper.findByKey(target, e.getHttpMethod(), e.getPath());
            String keepId;
            if (same == null) {
                endpointMapper.updateProject(id, target);
                keepId = id;
                moved++;
            } else {
                Map<String, Object> meta = new LinkedHashMap<>(endpointService.captureMetaOf(same));
                meta.putAll(endpointService.captureMetaOf(e));
                if (!meta.isEmpty()) {
                    same.setCaptureMeta(EndpointAssertionContext.metaJson(meta));
                }
                if (same.getFieldsSnapshot() == null && e.getFieldsSnapshot() != null) {
                    same.setFieldsSnapshot(e.getFieldsSnapshot());
                }
                if ((same.getSummary() == null || same.getSummary().isBlank()) && e.getSummary() != null) {
                    same.setSummary(e.getSummary());
                }
                endpointMapper.update(same);
                keepId = same.getId();
                merged++;
            }
            for (ApiTestCase c : caseMapper.findByEndpointId(id)) {
                c.setEndpointId(keepId);
                c.setProject(target);
                caseMapper.update(c);
                // 原项目全量集合里的跟着挪；需求测试集合按需求走，不动
                if (c.getCollectionId() != null && from != null && !INBOX.equals(from)
                        && c.getCollectionId().equals(liveOf.computeIfAbsent(from, this::liveCollectionId))) {
                    collectionService.assign(liveOf.computeIfAbsent(target, this::liveCollectionId),
                            List.of(c.getId()));
                }
            }
            if (!keepId.equals(id)) {
                endpointMapper.deleteById(id);
            }
        }
        return Map.of("moved", moved, "merged", merged, "project", target);
    }

    private String liveCollectionId(String project) {
        return collectionService.ensureDefaultLiveCollection(project).getId();
    }

    /** 路径前缀 → 清单里唯一同名接口 → 页面上选的项目 */
    String resolveProject(String fallbackProject, String method, String path) {
        String byPath = RequirementEndpointProjectResolver.inferFromPath(path);
        if (byPath != null && !byPath.isBlank()) {
            return ApiAppCatalog.treeProjectKey(byPath);
        }
        try {
            Set<String> owners = endpointMapper.findByMethodPath(method, path).stream()
                    .map(ApiEndpoint::getProject)
                    .filter(p -> p != null && !p.isBlank() && !INBOX.equals(p))
                    .collect(Collectors.toSet());
            if (owners.size() == 1) {
                return owners.iterator().next();
            }
        } catch (Exception ex) {
            log.debug("[capture-catalog] 按路径查归属失败 {} {}: {}", method, path, ex.getMessage());
        }
        return fallbackProject == null || fallbackProject.isBlank()
                ? null : ApiAppCatalog.treeProjectKey(fallbackProject.trim());
    }

    private static Map<String, String> headersOf(JsonNode detail) {
        if (detail == null || !detail.has("reqHeaders")) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        detail.get("reqHeaders").fields()
                .forEachRemaining(x -> out.put(x.getKey(), x.getValue().asText("")));
        return out;
    }

    /**
     * 按抓包样本给接口生成用例，归到项目的全量集合。
     * <p>
     * 只建不跑；环境写 TEST、{@code testOnly=true}——抓包抓的是测试环境，
     * 拿这些用例去打生产就是拿没验过的请求碰线上数据。
     */
    public Map<String, Object> generateLiveCases(List<String> endpointIds) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, List<String>> byProject = new LinkedHashMap<>();
        int created = 0;
        int reused = 0;
        int failed = 0;
        for (String id : endpointIds) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("endpointId", id);
            try {
                ApiEndpoint e = endpointService.get(id);
                if (e == null) {
                    throw new IllegalArgumentException("接口清单里没有这条");
                }
                if (INBOX.equals(e.getProject())) {
                    throw new IllegalArgumentException("还没归类，先在接口管理里归到项目");
                }
                item.put("method", e.getHttpMethod());
                item.put("path", e.getPath());
                ApiTestCase existing = existingGenerated(id);
                ApiTestCase saved = existing != null ? existing : caseService.createCase(buildCase(e));
                byProject.computeIfAbsent(e.getProject(), k -> new ArrayList<>()).add(saved.getId());
                item.put("caseId", saved.getId());
                item.put("caseName", saved.getName());
                item.put("status", existing != null ? "REUSED" : "CREATED");
                if (existing != null) {
                    reused++;
                } else {
                    created++;
                }
            } catch (RuntimeException ex) {
                failed++;
                item.put("status", "FAILED");
                item.put("message", ex.getMessage() == null ? "生成失败" : ex.getMessage());
            }
            items.add(item);
        }
        List<Map<String, Object>> collections = new ArrayList<>();
        byProject.forEach((project, caseIds) -> {
            ApiTestCollection col = collectionService.ensureDefaultLiveCollection(project);
            collectionService.assign(col.getId(), caseIds);
            collections.add(Map.of("id", col.getId(), "name", col.getName(), "project", col.getProject()));
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("reused", reused);
        out.put("failed", failed);
        out.put("collections", collections);
        out.put("items", items);
        return out;
    }

    private ApiTestCase buildCase(ApiEndpoint e) {
        var proj = projectService.get(e.getProject());
        var ctx = EndpointAssertionContext.of(e, endpointService);
        ApiCaseSkeleton.Draft draft = ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                e.getHttpMethod(), e.getPath(), e.getSummary(),
                proj == null ? null : proj.getSuccessCodePath(),
                proj == null ? null : proj.getSuccessCodeValue(),
                ctx.responseFields(), ctx.captureSamples(), ctx.nullableFields()));
        ApiTestCase c = new ApiTestCase();
        c.setProject(e.getProject());
        c.setName(draft.name());
        String desc = "由测试环境抓包生成 · " + e.getHttpMethod() + " " + e.getPath();
        if (ctx.docReference() != null) {
            desc = desc + "\n" + ctx.docReference();
        }
        c.setDescription(desc);
        c.setMethod(e.getHttpMethod());
        c.setPath(e.getPath());
        c.setHeaders(RequirementCaseGenerator.headersJson(ctx.captureHeaders()));
        c.setQuery(RequirementCaseGenerator.toQueryString(ctx.captureQuery()));
        if (!"GET".equalsIgnoreCase(e.getHttpMethod())) {
            c.setBody(EndpointAssertionContext.reqBodyOf(endpointService.captureMetaOf(e)));
        }
        c.setAssertions(draft.assertions());
        c.setEnabled(draft.enabled());
        c.setEnvironment("TEST");
        c.setTestOnly(true);
        c.setEndpointId(e.getId());
        c.setCreatedBy(CREATED_BY);
        return c;
    }

    private ApiTestCase existingGenerated(String endpointId) {
        try {
            return caseMapper.findByEndpointId(endpointId).stream()
                    .filter(x -> CREATED_BY.equals(x.getCreatedBy()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            log.debug("[capture-catalog] 查已有用例失败 {}: {}", endpointId, ex.getMessage());
            return null;
        }
    }
}
