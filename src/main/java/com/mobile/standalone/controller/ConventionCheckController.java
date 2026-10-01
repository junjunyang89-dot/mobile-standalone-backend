package com.mobile.standalone.controller;

import com.mobile.standalone.service.convention.ConventionCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 业务仓库约定检查：对着本机源码跑，不要设备。产物是风险，不进用例通过率。
 */
@RestController
@RequestMapping("/convention-check")
@RequiredArgsConstructor
public class ConventionCheckController {

    private final ConventionCheckService service;

    @GetMapping("/rules")
    public List<Map<String, Object>> rules() {
        return service.rules();
    }

    /** 返回: { kind, verdict: RISK|INCOMPLETE|CLEAN, summary, results: [...] } */
    @GetMapping("/run")
    public Map<String, Object> run() {
        return service.run();
    }

    /**
     * body: { "ruleIds": [...]（空=全部）, "force": false, "reason": "..." }
     * 计数类只许往下锁；往上锁要 force + reason。
     */
    @PostMapping("/baseline")
    public ResponseEntity<?> lockBaseline(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        List<String> ids = b.get("ruleIds") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        boolean force = Boolean.TRUE.equals(b.get("force"));
        String reason = b.get("reason") == null ? null : String.valueOf(b.get("reason"));
        return ResponseEntity.ok(service.lockBaseline(ids, force, reason));
    }
}
