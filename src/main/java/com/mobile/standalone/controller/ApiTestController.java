package com.mobile.standalone.controller;

import com.mobile.standalone.dto.ApiTestDtos.RunRequest;
import com.mobile.standalone.dto.PageResult;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestEnv;
import com.mobile.standalone.service.ApiTestCaseService;
import com.mobile.standalone.service.ApiTestRunnerService;
import com.mobile.standalone.service.ApiTestStatsService;
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
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) Boolean enabledOnly,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        if (page != null) {
            int p = page;
            int s = size != null ? size : 20;
            PageResult<ApiTestCase> result = caseService.listCasesPage(project, environment, enabledOnly, keyword, p, s);
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(caseService.listCases(project, environment, enabledOnly, keyword));
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
