package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ApiChangeReport;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.ApiChangeReportService;
import com.mobile.standalone.service.ApiChangeRunService;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer;
import lombok.Data;
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
 * 分析流水线本身在 {@link ApiChangeRunService}，按版本批量分析也走那一条。
 */
@Slf4j
@RestController
@RequestMapping("/api-changes")
@RequiredArgsConstructor
public class ApiChangeController {

    private final ApiChangeAnalyzer analyzer;
    private final ApiProjectMapper apiProjectMapper;
    private final ApiChangeReportService apiChangeReportService;
    private final ApiChangeRunService runService;
    private final com.mobile.standalone.service.ApiProjectService apiProjectService;
    private final com.mobile.standalone.service.ProjectRepoSync repoSync;

    /**
     * 项目下拉：和项目管理同一棵树，分类 → 项目，默认只列要扫接口的后端。
     * 前端、客户端、网关在单项目接口对比里分析不出接口，列出来只会让人选了白跑；
     * 按版本批量对比要看跨项目调用关系，传 {@code all=true} 把前端、客户端也列上。
     */
    @GetMapping("/projects")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> projects(@RequestParam(defaultValue = "false") boolean all) {
        Map<String, Object> tree = apiProjectService.buildTree(null);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> cat : (List<Map<String, Object>>) tree.getOrDefault("categories", List.of())) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> p : (List<Map<String, Object>>) cat.getOrDefault("projects", List.of())) {
                String kind = p.get("kind") == null ? null : String.valueOf(p.get("kind"));
                boolean scanned = com.mobile.standalone.service.apitest.ApiProjectKind.isBackend(kind)
                        && Boolean.TRUE.equals(p.get("scanEndpoints"));
                if (!scanned && !all) {
                    continue;
                }
                Map<String, Object> opt = projectOption(p);
                opt.put("kind", com.mobile.standalone.service.apitest.ApiProjectKind.normalize(kind));
                items.add(opt);
            }
            if (items.isEmpty()) {
                continue;
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("key", cat.get("key"));
            g.put("label", cat.get("label"));
            g.put("projects", items);
            groups.add(g);
        }
        return ResponseEntity.ok(Map.of("groups", groups));
    }

    private static Map<String, Object> projectOption(Map<String, Object> p) {
        String repoPath = p.get("repoPath") == null ? null : String.valueOf(p.get("repoPath"));
        String gitUrl = p.get("gitUrl") == null ? null : String.valueOf(p.get("gitUrl"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.get("code"));
        m.put("name", p.get("name"));
        m.put("repoPath", repoPath);
        m.put("gitUrl", gitUrl);
        m.put("baseBranch", p.get("baseBranch"));
        // 有 GitLab 地址就算能用：选中时会自动 clone
        m.put("ready", (repoPath != null && !repoPath.isBlank()) || (gitUrl != null && !gitUrl.isBlank()));
        return m;
    }

    /**
     * 某个项目的分支列表，省得人手打分支名。
     * {@code fetch=true} 时先按项目管理里的 GitLab 地址拉一次（没有本地仓库就 clone），
     * 分支名去掉 origin/ 前缀合并展示——分析时会自动用远程那份。
     */
    @GetMapping("/branches")
    public ResponseEntity<?> branches(@RequestParam String project,
                                      @RequestParam(required = false, defaultValue = "false") boolean fetch) {
        ApiProject p = apiProjectMapper.findByCode(project);
        if (p == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "项目不存在: " + project));
        }
        Map<String, Object> sync = null;
        if (fetch) {
            sync = repoSync.syncQuietly(p.getCode(), ApiChangeRunService.SYNC_FRESH_MS);
            p = apiProjectMapper.findByCode(project);
        }
        try {
            List<String> raw = analyzer.branches(p.getRepoPath());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("repoPath", String.valueOf(p.getRepoPath()));
            out.put("branches", fetch ? ApiChangeAnalyzer.displayBranches(raw) : raw);
            out.put("current", analyzer.currentBranchOf(p.getRepoPath()));
            if (sync != null) {
                out.put("sync", sync);
            }
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            String msg = String.valueOf(e.getMessage());
            if (sync != null && Boolean.FALSE.equals(sync.get("ok"))) {
                msg = msg + "（" + sync.get("message") + "）";
            }
            return ResponseEntity.badRequest().body(Map.of("message", msg));
        }
    }

    /** 分支是否已合进基线：分支用例页据此提示「可以点上线了」 */
    @GetMapping("/branch-merge-status")
    public ResponseEntity<?> branchMergeStatus(@RequestParam String project, @RequestParam String branch) {
        ApiProject p = apiProjectMapper.findByCode(project);
        if (p == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "项目不存在: " + project));
        }
        repoSync.syncQuietly(p.getCode(), ApiChangeRunService.SYNC_FRESH_MS);
        p = apiProjectMapper.findByCode(project);
        String base = p.getBaseBranch() == null || p.getBaseBranch().isBlank() ? "master" : p.getBaseBranch().trim();
        try {
            ApiChangeAnalyzer.MergeStatus st = analyzer.mergeStatus(p.getRepoPath(), branch, base);
            String msg = switch (st) {
                case MERGED -> "「" + branch + "」已经合进 " + base + "，确认上线后可以点「分支已上线」";
                case GONE -> "远程已经没有「" + branch + "」，通常是合并后删掉了，确认上线后可以点「分支已上线」";
                case OPEN -> "「" + branch + "」还没合进 " + base;
            };
            return ResponseEntity.ok(Map.of("status", st.name(), "base", base, "message", msg));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("status", "UNKNOWN", "base", base, "message", String.valueOf(e.getMessage())));
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
        try {
            return ResponseEntity.ok(runService.analyzeGit(body.getProject(), body.getBaseRef(), body.getHeadRef(), null));
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
            return ResponseEntity.ok(runService.analyzeDirs(
                    body.getProject(), body.getBaseDir(), body.getHeadDir(), null, null));
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
