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
            EndpointLevel.Verdict auto = EndpointLevel.of(e.getCallerStatus(), e.getCallersJson());
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

    /** 抓包待归类的接口归到项目。body: {ids: [...], project} */
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
    @PostMapping("/projects/{project}/scan-source")
    public ResponseEntity<?> scanSource(@PathVariable String project,
                                        @RequestBody(required = false) Map<String, String> body) {
        var p = projectService.get(project);
        if (p == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "项目不存在: " + project));
        }
        String dir = body == null ? null : body.get("dir");
        if (dir == null || dir.isBlank()) {
            dir = p.getRepoPath();
        }
        if (dir == null || dir.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "没给源码目录，项目管理里也没配仓库路径"));
        }
        try {
            java.nio.file.Path source = java.nio.file.Path.of(dir.trim()).toAbsolutePath().normalize();
            var scan = com.mobile.standalone.service.apitest.SourceEndpointScanner.scan(source);
            var saved = endpointService.upsertFromSource(p.getCode(), scan.endpoints());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dir", dir.trim());
            out.put("javaFiles", scan.javaFiles());
            out.put("controllers", scan.controllers());
            out.put("endpoints", scan.endpoints().size());
            out.put("created", saved.created());
            out.put("updated", saved.updated());
            out.put("authRequired", scan.endpoints().stream().filter(s -> Boolean.TRUE.equals(s.auth())).count());
            out.put("tokenHeaders", scan.tokenHeaders());
            authService.rememberDetectedHeaders(p.getCode(), scan.tokenHeaders());
            // 仓库一般并排放在同一个目录下，默认就搜后端仓库的上一级
            List<java.nio.file.Path> callerRoots = new ArrayList<>();
            String callerDirs = body == null ? null : body.get("callerDirs");
            if (callerDirs != null && !callerDirs.isBlank()) {
                for (String d : callerDirs.split("[\\n,;]")) {
                    if (!d.isBlank()) {
                        callerRoots.add(java.nio.file.Path.of(d.trim()));
                    }
                }
            } else if (source.getParent() != null) {
                callerRoots.add(source.getParent());
            }
            out.put("callerDirs", callerRoots.stream().map(String::valueOf).toList());
            try {
                var callers = endpointService.syncCallers(p.getCode(), callerRoots, source);
                out.put("callerUsed", callers.used());
                out.put("callerDefOnly", callers.defOnly());
                out.put("callerNone", callers.none());
            } catch (Exception e) {
                // 接口已经入库了，搜调用方失败不该让整次扫描算失败
                out.put("callerError", e.getMessage() == null ? "搜调用方失败" : e.getMessage());
            }
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", e.getMessage() == null ? "扫描失败" : e.getMessage()));
        }
    }
}
