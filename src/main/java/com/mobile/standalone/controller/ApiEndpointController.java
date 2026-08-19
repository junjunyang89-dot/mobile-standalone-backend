package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.service.apitest.ApiCaseSkeleton;
import com.mobile.standalone.service.apitest.ApiEndpointService;
import com.mobile.standalone.service.apitest.ApiEndpointStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 状态字典，前端筛选片直接用，不在前端硬编码这几个中文名 */
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
        ApiCaseSkeleton.Draft draft = ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                e.getHttpMethod(), e.getPath(), e.getSummary(),
                project == null ? null : project.getSuccessCodePath(),
                project == null ? null : project.getSuccessCodeValue(),
                endpointService.responseFieldsOf(e)));

        com.mobile.standalone.entity.ApiTestCase c = new com.mobile.standalone.entity.ApiTestCase();
        c.setProject(e.getProject());
        c.setName(draft.name());
        c.setDescription("由接口清单生成 · " + e.getHttpMethod() + " " + e.getPath());
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
        return ResponseEntity.ok(vo);
    }
}
