package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.service.apitest.ApiCaseSkeleton;
import com.mobile.standalone.service.apitest.ApiEndpointService;
import com.mobile.standalone.service.apitest.ApiEndpointStatus;
import com.mobile.standalone.service.apitest.CaseReadiness;
import com.mobile.standalone.service.apitest.EndpointAssertionContext;
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
        for (ApiEndpoint e : rows) {
            boolean hasBizCode = bizCodeByProject.computeIfAbsent(
                    e.getProject() == null ? "" : e.getProject(), this::projectHasBizCode);
            ApiEndpointStatus st = endpointService.statusOf(e, hasBizCode);
            counts.merge(st.name(), 1, Integer::sum);
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", e.getId());
            vo.put("project", e.getProject());
            vo.put("httpMethod", e.getHttpMethod());
            vo.put("path", e.getPath());
            vo.put("handler", e.getHandler());
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
            items.add(vo);
        }
        return ResponseEntity.ok(Map.of(
                "total", items.size(),
                "counts", counts,
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

    /** 接口清单左侧树：APP / PVP / WEB catalog + 接口数统计（与项目管理同一套） */
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
    private List<Map<String, Object>> readinessOf(String endpointId) {
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
            var r = CaseReadiness.check(c, vars);
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
        vo.put("cases", readinessOf(e.getId()));
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
}
