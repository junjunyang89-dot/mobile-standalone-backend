package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ApiChangeReport;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.ApiChangeReportService;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer;
import lombok.Data;
import com.mobile.standalone.service.apitest.EndpointChangeFlag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口变更分析：分支 vs 基线，看本次提测动了哪些接口、返回体字段、表。
 * <p>
 * 只读地跑 git，不会碰你正在开发的工作区。分析成功后自动落库快照，便于复看。
 */
@Slf4j
@RestController
@RequestMapping("/api-changes")
@RequiredArgsConstructor
public class ApiChangeController {

    private final ApiChangeAnalyzer analyzer;
    private final ApiProjectMapper apiProjectMapper;
    private final com.mobile.standalone.mapper.ApiTestCaseMapper apiTestCaseMapper;
    private final com.mobile.standalone.mapper.ApiTableSchemaMapper apiTableSchemaMapper;
    private final ApiChangeReportService apiChangeReportService;
    private final com.mobile.standalone.service.apitest.ApiEndpointService apiEndpointService;

    /**
     * 把这份报告同步进接口清单。
     * <p>
     * 跟着分析自动做，不做成一个要人记得点的按钮：你的流程是「拉分支 → 录入接口清单」，
     * 中间多一次点击，迟早会有一版忘了点，而忘了的表现是清单看着干净、实际是上一版的。
     * <p>
     * 同步失败不影响报告本身——分析结果是主产物，清单是派生的。
     */
    private Map<String, Object> syncToEndpointCatalog(ApiChangeAnalyzer.Report report, String projectCode) {
        if (report == null || projectCode == null || projectCode.isBlank()) {
            return null;
        }
        try {
            List<com.mobile.standalone.service.apitest.ApiEndpointService.EndpointChange> changes =
                    new java.util.ArrayList<>();
            for (ApiChangeAnalyzer.EndpointChange e : report.getEndpoints()) {
                EndpointChangeFlag.Input in = new EndpointChangeFlag.Input(
                        e.getChangeType(), e.getRisk(),
                        e.getParams() == null ? 0 : e.getParams().size(),
                        e.getFields() == null ? 0 : e.getFields().size(),
                        e.getBodyFields() == null ? 0 : e.getBodyFields().size(),
                        e.getChangedImpls() == null ? 0 : e.getChangedImpls().size());
                changes.add(new com.mobile.standalone.service.apitest.ApiEndpointService.EndpointChange(
                        e.getHttpMethod(), e.getPath(), e.getHandler(), e.getSummary(), e.getFile(),
                        e.getChangeType(),
                        EndpointChangeFlag.of(in),
                        EndpointChangeFlag.note(in, e.getRisk()),
                        topLevelFieldNames(e)));
            }
            var r = apiEndpointService.syncFromChangeReport(projectCode, report.getHeadRef(), changes);
            return Map.of("created", r.created(), "updated", r.updated(), "deprecated", r.deprecated());
        } catch (Exception ex) {
            log.warn("[api-changes] 同步接口清单失败（不影响本次报告）: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 给报告里的表挂上建表语句与测试注意事项。
     * <p>
     * 只报「涉及了 xxx 表」对测试没帮助——人还得自己翻 DDL、自己想边界。
     * 而建表语句里其实已经写着大半测试点（唯一索引、可空、大整数精度）。
     */
    private void attachTableSchemas(ApiChangeAnalyzer.Report report, String projectCode) {
        if (report == null || report.getTables().isEmpty()) {
            return;
        }
        java.util.List<com.mobile.standalone.entity.ApiTableSchema> all;
        try {
            all = apiTableSchemaMapper.findByProject(projectCode);
        } catch (Exception e) {
            return;
        }
        java.util.Map<String, com.mobile.standalone.entity.ApiTableSchema> byName = new java.util.HashMap<>();
        for (com.mobile.standalone.entity.ApiTableSchema x : all) {
            if (x.getTableName() != null) {
                byName.put(x.getTableName().toLowerCase(), x);
            }
        }
        for (ApiChangeAnalyzer.TableChange t : report.getTables()) {
            com.mobile.standalone.entity.ApiTableSchema sc =
                    byName.get(t.getTable() == null ? "" : t.getTable().toLowerCase());
            if (sc == null) {
                continue;
            }
            t.setDdlText(sc.getDdl());
            t.setRemark(sc.getRemark());
            t.setTestHints(com.mobile.standalone.service.apichange.TableTestHints.derive(sc.getDdl()));
        }
    }

    /**
     * 给报告里的每个接口标上已有几条接口用例。
     * <p>
     * 不标的话，报告看完人还得自己回忆「这接口我有用例吗」——那正是它该替人做的事。
     * 标了之后「未覆盖」那几条就是本次要补的用例清单。
     */
    private void markCoverage(ApiChangeAnalyzer.Report report, String projectCode) {
        if (report == null || report.getEndpoints().isEmpty()) {
            return;
        }
        List<com.mobile.standalone.entity.ApiTestCase> cases;
        try {
            cases = apiTestCaseMapper.findByFilters(projectCode, null, null, null, null, null, null);
        } catch (Exception e) {
            // 用例表没建或查不到不该让整份报告失败，覆盖率只是锦上添花
            return;
        }
        if (cases == null || cases.isEmpty()) {
            return;
        }
        for (ApiChangeAnalyzer.EndpointChange e : report.getEndpoints()) {
            String path = e.getPath() == null ? "" : e.getPath();
            int n = 0;
            for (com.mobile.standalone.entity.ApiTestCase c : cases) {
                String cp = c.getPath() == null ? "" : c.getPath();
                // 用例里的 path 可能带 baseUrl 前缀，也可能只写相对段，两头都兜一下
                if (!cp.isBlank() && (cp.equals(path) || cp.endsWith(path) || path.endsWith(cp))) {
                    n++;
                }
            }
            e.setTestCases(n);
        }
    }

    /** 返回体的顶层字段名，「生成用例」按它出断言 */
    private List<String> topLevelFieldNames(ApiChangeAnalyzer.EndpointChange e) {
        List<String> out = new java.util.ArrayList<>();
        if (e.getResponseFields() == null) {
            return out;
        }
        for (var node : e.getResponseFields()) {
            if (node != null && node.getName() != null && !node.getName().isBlank()) {
                out.add(node.getName().trim());
            }
        }
        return out;
    }

    /** 列出配了仓库路径的项目，以及各自的分支，供页面下拉 */
    @GetMapping("/projects")
    public ResponseEntity<?> projects() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiProject p : apiProjectMapper.findAll(null)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("repoPath", p.getRepoPath());
            m.put("baseBranch", p.getBaseBranch());
            m.put("ready", p.getRepoPath() != null && !p.getRepoPath().isBlank());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** 某个项目本地仓库的分支列表（含远程），省得人手打分支名 */
    @GetMapping("/branches")
    public ResponseEntity<?> branches(@RequestParam String project) {
        ApiProject p = apiProjectMapper.findByCode(project);
        if (p == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "项目不存在: " + project));
        }
        try {
            return ResponseEntity.ok(Map.of(
                    "repoPath", String.valueOf(p.getRepoPath()),
                    "branches", analyzer.branches(p.getRepoPath()),
                    "current", analyzer.currentBranchOf(p.getRepoPath())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /** 历史报告列表（不含大 JSON） */
    @GetMapping("/reports")
    public ResponseEntity<List<ApiChangeReport>> listReports(
            @RequestParam(required = false) String project,
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(apiChangeReportService.list(project, limit));
    }

    /** 打开一份历史报告（含完整 report） */
    @GetMapping("/reports/{id}")
    public ResponseEntity<?> getReport(@PathVariable Long id) {
        try {
            Map<String, Object> detail = apiChangeReportService.getDetail(id);
            return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/reports/{id}")
    public ResponseEntity<?> deleteReport(@PathVariable Long id) {
        try {
            apiChangeReportService.delete(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody AnalyzeRequest body) {
        if (body == null || body.getProject() == null || body.getProject().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请选择项目"));
        }
        ApiProject p = apiProjectMapper.findByCode(body.getProject());
        if (p == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "项目不存在: " + body.getProject()));
        }
        String base = body.getBaseRef() != null && !body.getBaseRef().isBlank()
                ? body.getBaseRef()
                : p.getBaseBranch();
        try {
            ApiChangeAnalyzer.Report report = analyzer.analyze(
                    p.getName() == null ? p.getCode() : p.getName(),
                    p.getRepoPath(), base, body.getHeadRef());
            markCoverage(report, p.getCode());
            attachTableSchemas(report, p.getCode());
            apiChangeReportService.saveSnapshot(p.getCode(), report);
            Map<String, Object> sync = syncToEndpointCatalog(report, p.getCode());
            if (sync != null) {
                report.setCatalogSync(sync);
            }
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() == null ? "分析失败" : e.getMessage()));
        }
    }

    /**
     * 目录对目录：拿不到内网、只能把两个分支各拷一份到本地时用。
     * 不需要 git，两个目录里的源码直接比。
     */
    @PostMapping("/analyze-dirs")
    public ResponseEntity<?> analyzeDirs(@RequestBody DirRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "请求体为空"));
        }
        try {
            String name = body.getProject() == null || body.getProject().isBlank()
                    ? "本地目录对比" : body.getProject();
            ApiChangeAnalyzer.Report report =
                    analyzer.analyzeDirs(name, body.getBaseDir(), body.getHeadDir());
            markCoverage(report, body.getProject());
            attachTableSchemas(report, body.getProject());
            apiChangeReportService.saveSnapshot(body.getProject(), report);
            Map<String, Object> sync = syncToEndpointCatalog(report, body.getProject());
            if (sync != null) {
                report.setCatalogSync(sync);
            }
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() == null ? "分析失败" : e.getMessage()));
        }
    }

    @Data
    public static class DirRequest {
        private String project;
        /** 基线目录，如 master 那份 */
        private String baseDir;
        /** 待测目录，如提测分支那份 */
        private String headDir;
    }

    @Data
    public static class AnalyzeRequest {
        private String project;
        /** 基线，留空用项目配置里的 baseBranch，再留空按 master */
        private String baseRef;
        /** 待测分支，留空用该仓库当前分支 */
        private String headRef;
    }
}
