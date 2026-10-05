package com.mobile.standalone.controller;

import com.mobile.standalone.service.apitest.AiCaseGenerator;
import com.mobile.standalone.service.apitest.EndpointLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** AI 生成接口用例：后台批量跑，页面轮询进度 */
@RestController
@RequestMapping("/api-endpoints/ai-cases")
@RequiredArgsConstructor
public class ApiAiCaseController {

    private final AiCaseGenerator generator;

    /** body: { project, endpointIds?, onlyUncovered?, tryRun?, runWrites?, dir?, preferCapture?, levels? } */
    @PostMapping
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        if (body.get("endpointIds") instanceof List<?> l) {
            l.forEach(x -> {
                if (x != null) ids.add(String.valueOf(x));
            });
        }
        try {
            var job = generator.start(new AiCaseGenerator.Request(
                    str(body.get("project")), ids,
                    !Boolean.FALSE.equals(body.get("onlyUncovered")),
                    Boolean.TRUE.equals(body.get("tryRun")),
                    Boolean.TRUE.equals(body.get("runWrites")),
                    str(body.get("dir")),
                    Boolean.TRUE.equals(body.get("preferCapture")),
                    levels(body.get("levels"))));
            return ResponseEntity.ok(job);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /** 全部后端项目按级别各起一批，只生成没用例的。body: { levels: [CORE, IMPORTANT], tryRun?, runWrites? } */
    @PostMapping("/all")
    public ResponseEntity<?> startAll(@RequestBody Map<String, Object> body) {
        try {
            var batch = generator.startAll(levels(body.get("levels")),
                    Boolean.TRUE.equals(body.get("tryRun")), Boolean.TRUE.equals(body.get("runWrites")));
            return ResponseEntity.ok(batchView(batch));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /** 最近一次批量的进度；没有返回 {} */
    @GetMapping("/all/latest")
    public ResponseEntity<?> latestAll() {
        var batch = generator.lastBatch();
        return ResponseEntity.ok(batch == null ? Map.of() : batchView(batch));
    }

    private Map<String, Object> batchView(AiCaseGenerator.Batch batch) {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean running = false;
        for (var en : batch.entries()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("project", en.project());
            r.put("name", en.name());
            r.put("skipped", en.skipped());
            var job = en.jobId() == null ? null : generator.get(en.jobId());
            if (job != null) {
                r.put("jobId", job.getId());
                r.put("total", job.getTotal());
                r.put("done", job.getDone());
                r.put("created", job.getCreated());
                r.put("enabledCases", job.getEnabledCases());
                r.put("failed", job.getFailed());
                r.put("running", job.isRunning());
                r.put("message", job.getMessage());
                r.put("sourceRef", job.getSourceRef());
                running |= job.isRunning();
            }
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", batch.id());
        out.put("startedAt", batch.startedAt());
        out.put("levels", batch.levels());
        out.put("running", running);
        out.put("items", rows);
        return out;
    }

    private static Set<EndpointLevel> levels(Object raw) {
        Set<EndpointLevel> out = new LinkedHashSet<>();
        if (raw instanceof List<?> l) {
            for (Object x : l) {
                EndpointLevel lv = x == null ? null : EndpointLevel.parse(String.valueOf(x));
                if (lv != null) out.add(lv);
            }
        }
        return out;
    }

    /** 页面重新打开时接上：有在跑的给在跑的，没有给最近一批 */
    @GetMapping("/latest")
    public ResponseEntity<?> latest(@RequestParam String project) {
        var job = generator.active(project);
        if (job == null) {
            job = generator.latest(project);
        }
        return ResponseEntity.ok(job == null ? Map.of() : job);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> get(@PathVariable String jobId) {
        var job = generator.get(jobId);
        return job == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(job);
    }

    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String jobId) {
        return generator.cancel(jobId) ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.notFound().build();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
