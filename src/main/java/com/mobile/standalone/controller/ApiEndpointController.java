package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.service.apitest.ApiCaseSkeleton;
import com.mobile.standalone.service.apitest.ApiEndpointService;
import com.mobile.standalone.service.apitest.ApiEndpointStatus;
import com.mobile.standalone.service.apitest.CaseReadiness;
import com.mobile.standalone.service.apitest.EndpointAssertionContext;
import com.mobile.standalone.service.apitest.EndpointLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 接口清单。回答的是「有哪些接口、覆盖到没有」——
 * 「这个接口对不对」是用例的事，在 {@code /api-test} 那边。
 */
@RestController
@RequestMapping("/api-endpoints")
@RequiredArgsConstructor
public class ApiEndpointController {

    private final ApiEndpointService endpointService;
    private final com.mobile.standalone.service.ApiTestCaseService caseService;
    private final com.mobile.standalone.service.ApiProjectService projectService;
    private final com.mobile.standalone.mapper.ApiTestCaseMapper caseMapper;
    private final com.mobile.standalone.service.EnvVariableService variableService;
    private final com.mobile.standalone.service.apitest.OpenApiImportService openApiImportService;
    private final com.mobile.standalone.service.apitest.InterfaceDocImportService docImportService;
    private final com.mobile.standalone.service.apitest.ApiAuthService authService;
    private final com.mobile.standalone.service.apitest.CaptureCatalogService captureCatalogService;
    private final com.mobile.standalone.service.apitest.ControllerBackfillService controllerBackfillService;
    private final com.mobile.standalone.service.apitest.EndpointInsightService insightService;
    private final com.mobile.standalone.service.apitest.SourceCaseService sourceCaseService;
    private final com.mobile.standalone.service.apitest.SourceScanService sourceScanService;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static Object parseJson(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String changeFlag,
            @RequestParam(required = false) String status) {

        List<ApiEndpoint> rows = endpointService.search(project, keyword, changeFlag, status);
        Map<String, List<Map<String, Object>>> branchesById = endpointService.branchesOf(
                project == null || project.isBlank() ? null
                        : com.mobile.standalone.service.apitest.ApiAppCatalog.matchProjectCodes(project));
        // 项目配了业务码的话，执行时每条用例都会自动带上 code 断言——
        // 只看存库的断言文本会把它们全判成「仅探活」，而页面上恰好又建议人去配业务码
        java.util.Map<String, Boolean> bizCodeByProject = new java.util.HashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        // 统计按「算出来的状态」分组，页面顶部的筛选片就是它
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ApiEndpointStatus s : ApiEndpointStatus.values()) {
            counts.put(s.name(), 0);
        }
        Map<String, Integer> levelCounts = new LinkedHashMap<>();
        for (EndpointLevel l : EndpointLevel.values()) {
            levelCounts.put(l.name(), 0);
        }
        for (ApiEndpoint e : rows) {
            boolean hasBizCode = bizCodeByProject.computeIfAbsent(
                    e.getProject() == null ? "" : e.getProject(), this::projectHasBizCode);
            ApiEndpointStatus st = endpointService.statusOf(e, hasBizCode);
            counts.merge(st.name(), 1, Integer::sum);
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", e.getId());
            vo.put("project", e.getProject());
            // 旧写法的项目编码（data-center 之类）在左侧树里归到同一个节点，页面按它分组
            vo.put("treeKey", com.mobile.standalone.service.ApiProjectService.inventoryProjectKey(e.getProject()));
            vo.put("httpMethod", e.getHttpMethod());
            vo.put("path", e.getPath());
            // 和 path 不同时说明客户端调的路径带网关前缀，页面上把代码里的路径也给出来
            vo.put("codePath", e.getCodePath());
            vo.put("goneAt", e.getGoneAt());
            vo.put("goneRef", e.getGoneRef());
            vo.put("branches", branchesById.getOrDefault(e.getId(), List.of()));
            vo.put("handler", e.getHandler());
            vo.put("sourceFile", e.getSourceFile());
            vo.put("tablesJson", e.getTablesJson());
            vo.put("authRequired", e.getAuthRequired());
            vo.put("callerStatus", e.getCallerStatus());
            vo.put("retiredAt", e.getRetiredAt());
            vo.put("retiredBy", e.getRetiredBy());
            vo.put("callers", parseJson(e.getCallersJson()));
            vo.put("summary", e.getSummary());
            vo.put("caseCount", e.getCaseCount() == null ? 0 : e.getCaseCount());
            vo.put("strongCaseCount", e.getStrongCaseCount() == null ? 0 : e.getStrongCaseCount());
            vo.put("enabledCaseCount", e.getEnabledCaseCount() == null ? 0 : e.getEnabledCaseCount());
            vo.put("changeFlag", e.getChangeFlag());
            vo.put("changeNote", e.getChangeNote());
            vo.put("sourceBranch", e.getSourceBranch());
            vo.put("lastSyncedAt", e.getLastSyncedAt());
            vo.put("statusCode", st.name());
            vo.put("statusLabel", st.label());
            vo.put("statusHint", st.hint());
            // 待办的才高亮：已覆盖和未覆盖是常态，全高亮等于没高亮
            vo.put("actionable", st.actionable());
            vo.put("hasCaptureMeta", e.getCaptureMeta() != null && !e.getCaptureMeta().isBlank());
            vo.put("hasDocMeta", e.getDocMeta() != null && !e.getDocMeta().isBlank());
            EndpointLevel.Verdict auto = EndpointLevel.of(e.getCallerStatus(), e.getCallersJson(),
                    ApiEndpointService.isCaptured(e));
            EndpointLevel manual = EndpointLevel.parse(e.getLevelManual());
            EndpointLevel level = manual != null ? manual : auto.level();
            vo.put("level", level == null ? null : level.name());
            vo.put("levelLabel", level == null ? null : level.label());
            vo.put("levelAuto", auto.level() == null ? null : auto.level().name());
            vo.put("levelReason", manual != null
                    ? "人工定为" + manual.label() + (e.getLevelSetBy() == null ? "" : "（" + e.getLevelSetBy() + "）")
                            + "；自动判断：" + auto.reason()
                    : auto.reason());
            vo.put("levelManual", manual != null);
            if (level != null && e.getRetiredAt() == null) {
                levelCounts.merge(level.name(), 1, Integer::sum);
            }
            items.add(vo);
        }
        return ResponseEntity.ok(Map.of(
                "total", items.size(),
                "counts", counts,
                "levelCounts", levelCounts,
                "items", items));
    }

    private boolean projectHasBizCode(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return false;
        }
        try {
            var p = projectService.get(projectCode);
            return p != null && p.getSuccessCodePath() != null && !p.getSuccessCodePath().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 变更分析里点开一个接口：按 项目 + 方法 + 路径 找到清单里的那一行和挂在它下面的用例。
     * <p>
     * 分析报告里的路径是代码里的，清单里的可能带网关前缀，所以精确对不上时按后缀再找一次。
     * 用例顺带判一下「是不是只验了状态码」——必测清单里那句「先看是不是只验了 200」，这里直接给结论。
     */
    @GetMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestParam String project, @RequestParam String method,
                                    @RequestParam String path) {
        String m = method.trim().toUpperCase(java.util.Locale.ROOT);
        String p = path.trim();
        String seg = p.substring(p.lastIndexOf('/') + 1);
        List<ApiEndpoint> rows = endpointService.search(project, seg.isBlank() ? p : seg, null, null);
        java.util.function.Predicate<ApiEndpoint> sameMethod = e -> m.equalsIgnoreCase(e.getHttpMethod())
                || "ANY".equalsIgnoreCase(e.getHttpMethod());
        ApiEndpoint hit = rows.stream().filter(sameMethod).filter(e -> p.equals(e.getPath())).findFirst()
                .orElseGet(() -> rows.stream().filter(sameMethod)
                        .filter(e -> e.getPath() != null && (e.getPath().endsWith(p) || p.endsWith(e.getPath())))
                        .findFirst().orElse(null));
        if (hit == null) {
            return ResponseEntity.ok(Map.of("found", false,
                    "message", "接口清单里还没有这个接口：先在接口管理里全量扫描一次源码"));
        }
        boolean bizCode = projectHasBizCode(hit.getProject());
        List<Map<String, Object>> cases = new ArrayList<>();
        for (var c : caseMapper.findByEndpointId(hit.getId())) {
            List<String> types = assertionTypes(c.getAssertions());
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", c.getId());
            vo.put("name", c.getName());
            vo.put("enabled", c.getEnabled());
            vo.put("environment", c.getEnvironment());
            vo.put("intent", c.getIntent());
            vo.put("createdBy", c.getCreatedBy());
            vo.put("assertCount", types.size());
            vo.put("assertTypes", types);
            vo.put("onlyStatus", types.stream().allMatch(t -> "status".equals(t) || "responseTime".equals(t)));
            cases.add(vo);
        }
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("id", hit.getId());
        ep.put("project", hit.getProject());
        ep.put("httpMethod", hit.getHttpMethod());
        ep.put("path", hit.getPath());
        ep.put("summary", hit.getSummary());
        return ResponseEntity.ok(Map.of("found", true, "endpoint", ep, "bizCode", bizCode, "cases", cases));
    }

    static List<String> assertionTypes(String assertions) {
        List<String> out = new ArrayList<>();
        if (assertions == null || assertions.isBlank()) {
            return out;
        }
        try {
            for (var a : JSON.readTree(assertions)) {
                out.add(a.path("type").asText(""));
            }
        } catch (Exception ignore) {
            // 断言不是合法 JSON 就当没有
        }
        return out;
    }

    /** 接口清单左侧树：分类 → 项目 + 接口数（环境在页面顶部分开选） */
    @GetMapping("/tree")
    public ResponseEntity<Map<String, Object>> tree() {
        List<ApiEndpoint> all = endpointService.search(null, null, null, null);
        return ResponseEntity.ok(projectService.buildEndpointInventoryTree(all));
    }

    /**
     * 上传开发给的接口文档（Confluence 导出的 .docx）。
     * <p>
     * 文档补的是抓包补不了的那一半：必填、默认值、枚举回落、边界、哪些字段可空。
     * 只有抓包的话，用例就是把这次调用重放一遍——边界一个都验不到。
     */
    @PostMapping("/import-doc")
    public ResponseEntity<?> importDoc(@RequestParam("project") String project,
                                       @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "没收到文件"));
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "目前只认 .docx（Confluence 导出）。其它格式先另存一份"));
        }
        try (var in = file.getInputStream()) {
            var r = docImportService.importDoc(project, in, name);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("matched", r.matched());
            // 文档里有、清单里没有的要报出来：多半是还没被抓包或对比分析覆盖到的漏测点
            out.put("unmatched", r.unmatched());
            // 按「同名透传」推断着套过去的，单独报——这是推断不是文档写的，人要能核
            out.put("aliasApplied", r.aliasApplied());
            out.put("branches", r.branches());
            out.put("details", r.details());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", e.getMessage() == null ? "导入失败" : e.getMessage()));
        }
    }

    @GetMapping("/statuses")
    public ResponseEntity<List<Map<String, Object>>> statuses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiEndpointStatus s : ApiEndpointStatus.values()) {
            out.add(Map.of(
                    "code", s.name(),
                    "label", s.label(),
                    "hint", s.hint(),
                    "actionable", s.actionable()));
        }
        return ResponseEntity.ok(out);
    }

    /** 标记 / 取消废弃，body: {ids: [...], retired: true|false}。署名取登录人，不认请求体 */
    @PostMapping("/retire")
    public ResponseEntity<?> retire(@RequestBody Map<String, Object> body,
                                    jakarta.servlet.http.HttpServletRequest request) {
        List<String> ids = body != null && body.get("ids") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "ids 不能为空"));
        }
        boolean retired = body.get("retired") == null || Boolean.parseBoolean(String.valueOf(body.get("retired")));
        int n = endpointService.setRetired(ids, retired,
                com.mobile.standalone.auth.CurrentUser.nameOrNull(request));
        return ResponseEntity.ok(Map.of("ok", true, "updated", n));
    }

    /** 人工改自动化级别，body: {ids: [...], level: "CORE"|"IMPORTANT"|"NORMAL"|"UNUSED"|null}，null＝回到自动 */
    @PostMapping("/level")
    public ResponseEntity<?> setLevel(@RequestBody Map<String, Object> body,
                                      jakarta.servlet.http.HttpServletRequest request) {
        List<String> ids = body != null && body.get("ids") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "ids 不能为空"));
        }
        Object raw = body.get("level");
        EndpointLevel level = raw == null ? null : EndpointLevel.parse(String.valueOf(raw));
        if (raw != null && !String.valueOf(raw).isBlank() && level == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "不认识的级别：" + raw));
        }
        int n = endpointService.setLevel(ids, level,
                com.mobile.standalone.auth.CurrentUser.nameOrNull(request));
        return ResponseEntity.ok(Map.of("ok", true, "updated", n));
    }

    /** 按路径前缀看明显放错项目的接口（源码里找得到的不算） */
    @GetMapping("/misplaced")
    public ResponseEntity<?> misplaced() {
        return ResponseEntity.ok(Map.of("items", captureCatalogService.misplaced()));
    }

    /** 接口连同用例挪到另一个项目（待归类的归类也走这里）。body: {ids: [...], project} */
    @PostMapping("/classify")
    public ResponseEntity<?> classify(@RequestBody Map<String, Object> body) {
        List<String> ids = body != null && body.get("ids") instanceof List<?> list
                ? list.stream().map(String::valueOf).filter(s -> !s.isBlank()).distinct().toList()
                : List.of();
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "ids 不能为空"));
        }
        try {
            return ResponseEntity.ok(captureCatalogService.classify(ids,
                    body.get("project") == null ? null : String.valueOf(body.get("project"))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 按抓包样本生成用例到项目全量集合，只在测试环境跑。body: {ids: [...]} */
    @PostMapping("/generate-live-cases")
    public ResponseEntity<?> generateLiveCases(@RequestBody Map<String, Object> body) {
        List<String> ids = body != null && body.get("ids") instanceof List<?> list
                ? list.stream().map(String::valueOf).filter(s -> !s.isBlank()).distinct().toList()
                : List.of();
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "ids 不能为空"));
        }
        return ResponseEntity.ok(captureCatalogService.generateLiveCases(ids));
    }

    /**
     * 从这条接口生成一条用例骨架。
     * <p>
     * 凑不出有意义的断言时<b>生成为「停用」</b>——生成一条只验 {@code status=200} 又直接启用的用例，
     * 就是把清单要标出来的问题一键复制一遍，而且每条都会在报告里显示成绿的。
     */
    @PostMapping("/{id}/generate-case")
    public ResponseEntity<?> generateCase(@PathVariable String id) {
        ApiEndpoint e = endpointService.get(id);
        if (e == null) {
            return ResponseEntity.notFound().build();
        }
        if (e.isRetired()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "这个接口已标记废弃，不再生成用例。确实还在用的话先取消废弃"));
        }
        if ("DEPRECATED".equalsIgnoreCase(e.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "这个接口在 master 上已经找不到了，先确认是有意下线还是分支合错，别给它补用例"));
        }
        var project = projectService.get(e.getProject());
        var ctx = EndpointAssertionContext.of(e, endpointService);
        ApiCaseSkeleton.Draft draft = ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                e.getHttpMethod(), e.getPath(), e.getSummary(),
                project == null ? null : project.getSuccessCodePath(),
                project == null ? null : project.getSuccessCodeValue(),
                ctx.responseFields(), ctx.captureSamples(), ctx.nullableFields()));

        com.mobile.standalone.entity.ApiTestCase c = new com.mobile.standalone.entity.ApiTestCase();
        c.setProject(e.getProject());
        c.setName(draft.name());
        String desc = "由接口清单生成 · " + e.getHttpMethod() + " " + e.getPath();
        if (ctx.docReference() != null) {
            desc = desc + "\n" + ctx.docReference();
        }
        c.setDescription(desc);
        c.setRequirementRef(e.getHandler());
        c.setMethod(e.getHttpMethod());
        c.setPath(e.getPath());
        c.setHeaders("{\"Accept\":\"application/json\"}");
        c.setAssertions(draft.assertions());
        c.setEnabled(draft.enabled());
        c.setEnvironment("ANY");
        c.setCreatedBy("接口清单");
        try {
            var saved = caseService.createCase(c);
            return ResponseEntity.ok(Map.of(
                    "case", saved,
                    "enabled", draft.enabled(),
                    "note", draft.note()));
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", ex.getMessage() == null ? "生成失败" : ex.getMessage()));
        }
    }

    /**
     * 挂在这个接口上的用例，逐条算「还差什么」。
     * <p>
     * 生成出来的用例是停用的，补完之后没人知道它已经能启用了——
     * 清单上就一直挂着「有用例 · 但没在验」，而人以为自己还没补完。
     */
    private List<Map<String, Object>> readinessOf(ApiEndpoint e) {
        String endpointId = e.getId();
        var profile = authService.findForCaseProject(e.getProject());
        boolean authCovered = Boolean.FALSE.equals(e.getAuthRequired())
                || authService.configured(profile, "TEST") || authService.configured(profile, "PROD");
        Set<String> vars = new HashSet<>();
        try {
            // 变量在哪个环境定义的不重要：用例里写 {{token}} 只要有地方定义就跑得起来
            for (String env : List.of("TEST", "PROD", "ANY")) {
                vars.addAll(variableService.resolveValues(env).keySet());
            }
        } catch (Exception ignore) {
            // 拿不到变量就当没定义，宁可多报一条「还差」
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var c : caseMapper.findByEndpointId(endpointId)) {
            var r = CaseReadiness.check(c, vars, authCovered);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("enabled", Boolean.TRUE.equals(c.getEnabled()));
            m.put("ready", r.ready());
            m.put("todos", r.todos());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        ApiEndpoint e = endpointService.get(id);
        if (e == null) {
            return ResponseEntity.notFound().build();
        }
        ApiEndpointStatus st = endpointService.statusOf(e, projectHasBizCode(e.getProject()));
        Map<String, Object> vo = new HashMap<>();
        vo.put("endpoint", e);
        vo.put("statusCode", st.name());
        vo.put("statusLabel", st.label());
        vo.put("statusHint", st.hint());
        // 「有用例 · 但没在验」只说了状态，没说该做什么。
        // 把每条停用用例还差什么列出来，人才知道补哪里才能启用
        vo.put("cases", readinessOf(e));
        vo.put("captureMeta", endpointService.captureMetaOf(e));
        vo.put("docMeta", endpointService.docMetaOf(e));
        return ResponseEntity.ok(vo);
    }

    /**
     * 导入 OpenAPI 3 文档（JSON），<b>仅作参考</b>，不参与自动断言。
     * <p>
     * 写入 doc_meta：summary、description、文档里的字段列表、x-mysql-tables 等扩展。
     * 各开发格式不一，导入后供人查阅，断言仍只认抓包实测和代码对比。
     */
    @PostMapping("/projects/{project}/import-openapi")
    public ResponseEntity<?> importOpenApi(@PathVariable String project,
                                           @RequestBody Map<String, String> body) {
        String content = body == null ? null : body.get("content");
        try {
            var result = openApiImportService.importDocument(project, content);
            return ResponseEntity.ok(Map.of(
                    "imported", result.imported(),
                    "skipped", result.skipped(),
                    "warnings", result.warnings()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", e.getMessage() == null ? "导入失败" : e.getMessage()));
        }
    }

    /**
     * 按本地源码目录全量扫一遍，把 Controller 里的接口录进这个项目。
     * 目录不传时用项目管理里配的仓库路径。
     */
    /**
     * 抓包收进来的接口没有 Controller：到本地仓库里找声明它的方法补上。
     * body: {ids?: [...]}，不给就补项目下所有还没有源文件的。
     */
    /**
     * 接口体检：读源码讲清字段含义、会返回哪些业务码、要不要登录、有哪些可疑写法。
     * body: {endpointId?} 或 {project, method, path}，可带 respSample（抓包响应）用来对字段。
     */
    @PostMapping("/insight")
    public ResponseEntity<?> insight(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(insightService.inspect(body.get("endpointId"), body.get("project"),
                    body.get("method"), body.get("path"), body.get("respSample")));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message",
                    e.getMessage() == null ? "体检失败：" + e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** 按源码生成参数用例的预览：参数表 + 会生成哪些用例。body: {endpointId} */
    @PostMapping("/source-cases/preview")
    public ResponseEntity<?> sourceCasesPreview(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(sourceCaseService.preview(body.get("endpointId")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message",
                    e.getMessage() == null ? "预览失败：" + e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * 按源码生成参数用例：抓包请求当底子，每条只改一个参数；GET 试跑一次，和源码推断一致的启用。
     * body: {ids: [...], tryRun?: true}
     */
    @PostMapping("/source-cases")
    public ResponseEntity<?> sourceCases(@RequestBody Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        if (body.get("ids") instanceof List<?> l) {
            l.forEach(x -> {
                if (x != null) ids.add(String.valueOf(x));
            });
        }
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "先选接口"));
        }
        if (ids.size() > 50) {
            return ResponseEntity.badRequest().body(Map.of("message", "一次最多 50 个接口"));
        }
        boolean tryRun = !Boolean.FALSE.equals(body.get("tryRun"));
        return ResponseEntity.ok(sourceCaseService.generate(ids, tryRun));
    }

    @PostMapping("/projects/{project}/backfill-controllers")
    public ResponseEntity<?> backfillControllers(@PathVariable String project,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        if (body != null && body.get("ids") instanceof List<?> l) {
            l.forEach(x -> {
                if (x != null) ids.add(String.valueOf(x));
            });
        }
        try {
            return ResponseEntity.ok(controllerBackfillService.backfill(project, ids));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message",
                    e.getMessage() == null ? "扫描仓库失败" : e.getMessage()));
        }
    }

    /**
     * 全量扫描一个项目的接口。
     * <p>
     * 默认读<b>基线分支</b>（项目管理里配的 baseBranch，没配是 master）：先 {@code git fetch}，
     * 再把 {@code origin/master} 那个提交导出来扫——不切分支、不碰本地改动。
     * 直接扫仓库目录的话，扫到的是「当前所在分支 + 没提交的改动」，不是「master 上有哪些接口」。
     * <p>
     * body 里给了 {@code dir} 就照旧扫那个目录（不一定是 git 仓库），这时不标「基线里没有」。
     * 给了 {@code ref} 就扫那个分支，同样不标——只有基线说了算。
     */
    @PostMapping("/projects/{project}/scan-source")
    public ResponseEntity<?> scanSource(@PathVariable String project,
                                        @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        try {
            return ResponseEntity.ok(sourceScanService.scanProject(project, b.get("dir"), b.get("ref"),
                    "true".equals(b.get("forceFetch")), b.get("callerDirs")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", e.getMessage() == null ? "扫描失败" : e.getMessage()));
        }
    }

    /** 一键把所有后端项目的基线分支扫一遍；后台跑，返回任务进度 */
    @PostMapping("/scan-all")
    public ResponseEntity<?> scanAll() {
        return ResponseEntity.ok(jobView(sourceScanService.startScanAll()));
    }

    /** jobId 为 latest 时返回最近一个任务；没有任务返回空对象 */
    @GetMapping("/scan-all/{jobId}")
    public ResponseEntity<?> scanAllProgress(@PathVariable String jobId) {
        var job = "latest".equals(jobId) ? sourceScanService.latestJob() : sourceScanService.job(jobId);
        return ResponseEntity.ok(job == null ? Map.of() : jobView(job));
    }

    private static Map<String, Object> jobView(com.mobile.standalone.service.apitest.SourceScanService.Job job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.id);
        out.put("startedAt", job.startedAt);
        out.put("finishedAt", job.finishedAt);
        out.put("done", job.done());
        List<Map<String, Object>> items = new ArrayList<>();
        for (var it : job.items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("project", it.project);
            m.put("name", it.name);
            m.put("state", it.state);
            m.put("error", it.error);
            m.put("result", it.result);
            m.put("levels", it.levels);
            items.add(m);
        }
        out.put("items", items);
        return out;
    }

    /** 每个项目最近一次全量扫描：基于哪个分支、哪个提交、什么时候扫的 */
    @GetMapping("/source-scans")
    public ResponseEntity<?> sourceScans(@RequestParam(required = false) String project) {
        List<String> codes = project == null || project.isBlank() ? null
                : com.mobile.standalone.service.apitest.ApiAppCatalog.matchProjectCodes(project);
        return ResponseEntity.ok(endpointService.lastScans(codes));
    }

    /**
     * 存量重复：同一个接口一条是抓包建的（带网关前缀），一条是源码扫的。只读，合并前先给人看清单。
     */
    @PostMapping("/duplicates/preview")
    public ResponseEntity<?> duplicatesPreview(@RequestBody(required = false) Map<String, Object> body) {
        String project = body == null || body.get("project") == null ? null : String.valueOf(body.get("project"));
        return ResponseEntity.ok(endpointService.duplicates(project));
    }

    @PostMapping("/duplicates/merge")
    public ResponseEntity<?> duplicatesMerge(@RequestBody Map<String, Object> body) {
        String project = body.get("project") == null ? null : String.valueOf(body.get("project"));
        List<String> ids = new ArrayList<>();
        if (body.get("dropIds") instanceof List<?> l) {
            l.forEach(o -> ids.add(String.valueOf(o)));
        }
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "没选要合并的接口"));
        }
        return ResponseEntity.ok(Map.of("merged", endpointService.mergeDuplicates(project, ids)));
    }
}
