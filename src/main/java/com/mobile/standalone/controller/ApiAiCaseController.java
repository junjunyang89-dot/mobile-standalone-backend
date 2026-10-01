package com.mobile.standalone.controller;

import com.mobile.standalone.service.apitest.AiCaseGenerator;
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
import java.util.List;
import java.util.Map;

/** AI 生成接口用例：后台批量跑，页面轮询进度 */
@RestController
@RequestMapping("/api-endpoints/ai-cases")
@RequiredArgsConstructor
public class ApiAiCaseController {

    private final AiCaseGenerator generator;

    /** body: { project, endpointIds?, onlyUncovered?, tryRun?, runWrites?, dir? } */
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
                    str(body.get("dir"))));
            return ResponseEntity.ok(job);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
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
