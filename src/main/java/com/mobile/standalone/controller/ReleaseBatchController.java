package com.mobile.standalone.controller;

import com.mobile.standalone.service.releasebatch.LogicExplainService;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 接口对比分析2：按版本拉单 → 认项目分支 → 建分支用例 → 逐单分析后端 → 测试评估报告。
 */
@RestController
@RequestMapping("/api-change-batches")
@RequiredArgsConstructor
public class ReleaseBatchController {

    private final ReleaseBatchService service;
    private final LogicExplainService logicExplain;

    @Data
    public static class LogicExplainBody {
        /** 逻辑改动上记的项目名 */
        private String project;
        private String className;
        private String member;
    }

    @Data
    public static class CreateBody {
        private String version;
        /** 为空 = 从 Wiki 拉；有内容 = 手动粘贴 */
        private String text;
        /** JIRA 单地址或单号，有内容时优先按它拉 */
        private String issues;
    }

    @Data
    public static class AiCasesBody {
        private String projectCode;
        /** 「POST /x」；空 = 这个项目除下线外的全部改动接口 */
        private List<String> endpoints;
        private boolean tryRun;
        private boolean runWrites;
    }

    @Data
    public static class LocalBody {
        private String version;
        private String issueKey;
        private String summary;
        /** projectCode + baseDir + headDir；branch、baseBranch 选填，baseBranch 空按 master */
        private List<Target> targets;
    }

    @Data
    public static class AnalyzeBody {
        private String baseRef;
        private boolean force;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(service.list(limit));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateBody body) {
        if (body.getIssues() != null && !body.getIssues().isBlank()) {
            return wrap(() -> service.createFromIssues(body.getVersion(), body.getIssues()));
        }
        return wrap(() -> body.getText() == null || body.getText().isBlank()
                ? service.createFromWiki(body.getVersion())
                : service.createFromText(body.getVersion(), body.getText()));
    }

    @PostMapping("/{id}/tickets/{key}/ai-cases")
    public ResponseEntity<?> aiCases(@PathVariable Long id, @PathVariable String key, @RequestBody AiCasesBody body) {
        return wrap(() -> service.startAiCases(id, key, body.getProjectCode(), body.getEndpoints(),
                body.isTryRun(), body.isRunWrites()));
    }

    @PostMapping("/local")
    public ResponseEntity<?> createLocal(@RequestBody LocalBody body) {
        return wrap(() -> service.createFromLocal(body.getVersion(), body.getIssueKey(), body.getSummary(), body.getTargets()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return wrap(() -> service.get(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/collections")
    public ResponseEntity<?> collections(@PathVariable Long id) {
        return wrap(() -> service.createCollections(id));
    }

    @PostMapping("/{id}/tickets/{key}/refresh")
    public ResponseEntity<?> refresh(@PathVariable Long id, @PathVariable String key) {
        return wrap(() -> service.refreshTicket(id, key));
    }

    @PutMapping("/{id}/tickets/{key}/targets")
    public ResponseEntity<?> targets(@PathVariable Long id, @PathVariable String key, @RequestBody List<Target> body) {
        return wrap(() -> service.saveTargets(id, key, body));
    }

    @PostMapping("/{id}/tickets/{key}/analyze")
    public ResponseEntity<?> analyze(@PathVariable Long id, @PathVariable String key,
                                     @RequestBody(required = false) AnalyzeBody body) {
        AnalyzeBody b = body == null ? new AnalyzeBody() : body;
        return wrap(() -> service.analyzeTicket(id, key, b.getBaseRef(), b.isForce()));
    }

    @PostMapping("/{id}/tickets/{key}/rebuild")
    public ResponseEntity<?> rebuild(@PathVariable Long id, @PathVariable String key) {
        return wrap(() -> service.rebuildTicket(id, key));
    }

    /** AI 把一个方法的逻辑改动翻译成业务规则，存进这一单；要等模型回答，几十秒 */
    @PostMapping("/{id}/tickets/{key}/logic-explain")
    public ResponseEntity<?> logicExplain(@PathVariable Long id, @PathVariable String key, @RequestBody LogicExplainBody body) {
        return wrap(() -> logicExplain.explain(id, key, body.getProject(), body.getClassName(), body.getMember()));
    }

    @GetMapping("/{id}/tickets/{key}/code-dirs")
    public ResponseEntity<?> codeDirs(@PathVariable Long id, @PathVariable String key) {
        return wrap(() -> service.codeDirs(id, key));
    }

    @PutMapping("/{id}/tickets/{key}/manual")
    public ResponseEntity<?> manual(@PathVariable Long id, @PathVariable String key,
                                    @RequestBody Map<String, String> body) {
        return wrap(() -> service.saveManual(id, key, body));
    }

    private static ResponseEntity<?> wrap(Supplier<Object> call) {
        try {
            return ResponseEntity.ok(call.get());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }
}
