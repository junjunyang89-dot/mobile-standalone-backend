package com.mobile.standalone.controller;

import com.mobile.standalone.dto.ApiTestDtos.RunRequest;
import com.mobile.standalone.dto.PageResult;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestEnv;
import com.mobile.standalone.entity.ApiTestCollection;
import com.mobile.standalone.entity.ApiTestSchedule;
import com.mobile.standalone.entity.EnvVariable;
import com.mobile.standalone.service.ApiTestCaseService;
import com.mobile.standalone.service.ApiTestRunnerService;
import com.mobile.standalone.service.ApiTestRunService;
import com.mobile.standalone.service.ApiTestStatsService;
import com.mobile.standalone.service.apitest.ApiTestCollectionService;
import com.mobile.standalone.service.apitest.ApiTestScheduleService;
import com.mobile.standalone.service.EnvVariableService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 接口测试：环境（TEST/PROD）+ 用例 CRUD + 执行。
 */
@RestController
@RequestMapping("/api-test")
@RequiredArgsConstructor
public class ApiTestController {

    private final ApiTestCaseService caseService;
    private final ApiTestRunnerService runnerService;
    private final ApiTestStatsService statsService;
    private final EnvVariableService variableService;
    private final ApiTestCollectionService collectionService;
    private final ApiTestRunService runService;
    private final ApiTestScheduleService scheduleService;

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        return ResponseEntity.ok(statsService.getStats());
    }

    @GetMapping("/envs")
    public ResponseEntity<List<ApiTestEnv>> listEnvs() {
        return ResponseEntity.ok(caseService.listEnvs());
    }

    @PutMapping("/envs/{environment}")
    public ResponseEntity<?> saveEnv(@PathVariable String environment, @RequestBody ApiTestEnv body) {
        try {
            body.setEnvironment(environment);
            return ResponseEntity.ok(caseService.saveEnv(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ---------- 定时执行 ----------

    @GetMapping("/schedules")
    public ResponseEntity<List<ApiTestSchedule>> listSchedules() {
        return ResponseEntity.ok(scheduleService.list());
    }

    @PostMapping("/schedules")
    public ResponseEntity<?> saveSchedule(@RequestBody ApiTestSchedule body) {
        try {
            return ResponseEntity.ok(scheduleService.save(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", scheduleService.delete(id)));
    }

    /** 立即跑一次，不影响 cron 的下次触发时间 */
    @PostMapping("/schedules/{id}/run")
    public ResponseEntity<?> runSchedule(@PathVariable String id) {
        try {
            return ResponseEntity.ok(scheduleService.runNow(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ---------- 执行报告 ----------

    @GetMapping("/runs")
    public ResponseEntity<?> listRuns(@RequestParam(required = false) String collectionId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(runService.listRuns(collectionId, limit));
    }

    /** 按天聚合的趋势。一天里手动重跑十几次很正常，逐次画只会看成剧烈波动 */
    @GetMapping("/runs/trend")
    public ResponseEntity<?> trend(@RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(runService.trend(days));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> runDetail(@PathVariable String runId) {
        Map<String, Object> out = runService.runDetail(runId);
        return out == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(out);
    }

    // ---------- 用例集合 ----------

    /** 列表带上用例数与最近一次通过率，前端不用再逐个查 */
    @GetMapping("/collections")
    public ResponseEntity<List<ApiTestCollection>> listCollections(
            @RequestParam(required = false) String project) {
        return ResponseEntity.ok(collectionService.list(project));
    }

    @PostMapping("/collections")
    public ResponseEntity<?> createCollection(@RequestBody ApiTestCollection body) {
        try {
            return ResponseEntity.ok(collectionService.create(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/collections/{id}")
    public ResponseEntity<?> updateCollection(@PathVariable String id, @RequestBody ApiTestCollection body) {
        ApiTestCollection out = collectionService.update(id, body);
        return out == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(out);
    }

    /** 删集合，里面的用例保留（只是移出）——删错一个分组不该赔上几十条用例 */
    @DeleteMapping("/collections/{id}")
    public ResponseEntity<?> deleteCollection(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", collectionService.delete(id)));
    }

    @PostMapping("/collections/{id}/cases")
    public ResponseEntity<?> assignCases(@PathVariable String id, @RequestBody Map<String, List<String>> body) {
        int n = collectionService.assign("__none__".equals(id) ? null : id, body.get("caseIds"));
        return ResponseEntity.ok(Map.of("assigned", n));
    }

    /**
     * 按 Controller 自动归组。几百条用例平铺在一个项目下没法用——
     * 既选不出「这次只跑活动相关的」，也没法给它们排一个有意义的执行顺序。
     * 只处理还没归组的，不会冲掉手工整理过的分组。
     */
    @PostMapping("/projects/{project}/auto-group")
    public ResponseEntity<?> autoGroup(@PathVariable String project) {
        try {
            return ResponseEntity.ok(collectionService.autoGroup(project));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ---------- 环境变量 ----------

    /** secret 的值返回掩码。真值只在执行时从库里取，不下发给浏览器 */
    @GetMapping("/envs/{environment}/variables")
    public ResponseEntity<List<EnvVariable>> listVariables(@PathVariable String environment) {
        return ResponseEntity.ok(variableService.listMasked(environment));
    }

    @PutMapping("/envs/{environment}/variables")
    public ResponseEntity<?> saveVariable(@PathVariable String environment, @RequestBody EnvVariable body) {
        try {
            body.setEnvironment(environment);
            return ResponseEntity.ok(variableService.save(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/envs/{environment}/variables/{name}")
    public ResponseEntity<?> deleteVariable(@PathVariable String environment, @PathVariable String name) {
        variableService.delete(environment, name);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/projects")
    public ResponseEntity<List<String>> listProjects() {
        return ResponseEntity.ok(caseService.listProjects());
    }

    /** 按项目重导种子（仅覆盖 created_by=seed-* 的行）；data-center 用内置 JSON */
    @PostMapping("/projects/{project}/reimport")
    public ResponseEntity<?> reimport(@PathVariable String project) {
        try {
            String p = project == null ? "" : project.trim();
            String json = switch (p) {
                case ApiTestCaseService.PROJECT_DATA_CENTER -> "api-test/seed-data-center.json";
                default -> throw new IllegalArgumentException("暂不支持重导项目: " + p + "（目前仅 data-center）");
            };
            int n = caseService.reimportProjectSeed(p, json);
            return ResponseEntity.ok(Map.of("ok", true, "imported", n, "project", p));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/cases")
    public ResponseEntity<?> listCases(
            @RequestParam(required = false) String project,
            /** 传 __none__ 查还没归组的用例 */
            @RequestParam(required = false) String collectionId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) Boolean enabledOnly,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        if (page != null) {
            int p = page;
            int s = size != null ? size : 20;
            PageResult<ApiTestCase> result = caseService.listCasesPage(project, collectionId, environment, enabledOnly, keyword, p, s);
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(caseService.listCases(project, collectionId, environment, enabledOnly, keyword));
    }

    @GetMapping("/cases/{id}")
    public ResponseEntity<?> getCase(@PathVariable String id) {
        ApiTestCase c = caseService.getCase(id);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
    }

    @PostMapping("/cases")
    public ResponseEntity<?> createCase(@RequestBody ApiTestCase body) {
        try {
            return ResponseEntity.ok(caseService.createCase(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/cases/{id}")
    public ResponseEntity<?> updateCase(@PathVariable String id, @RequestBody ApiTestCase body) {
        try {
            ApiTestCase updated = caseService.updateCase(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/cases/{id}")
    public ResponseEntity<?> deleteCase(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", caseService.deleteCase(id)));
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody RunRequest req) {
        try {
            return ResponseEntity.ok(runnerService.run(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }
}
