package com.mobile.standalone.controller;

import com.mobile.standalone.service.apitest.CaseReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 变更影响的用例复查清单：接口/表变了，哪些用例可能失效需复查。
 * 上线同步那一刻调它，把受影响用例标出来，而不是笼统一句"重新调整用例"。
 */
@RestController
@RequestMapping("/case-review")
@RequiredArgsConstructor
public class CaseReviewController {

    private final CaseReviewService service;

    /**
     * @param body project；changedFields 变更字段名；changedTables 变更表名
     *             （都可从对比分析报告里取）
     */
    @PostMapping
    public ResponseEntity<?> forChanges(@RequestBody Map<String, Object> body) {
        String project = body.get("project") == null ? null : String.valueOf(body.get("project"));
        return ResponseEntity.ok(service.forChanges(project, strList(body.get("changedFields")),
                strList(body.get("changedTables"))));
    }

    @SuppressWarnings("unchecked")
    private List<String> strList(Object o) {
        return o instanceof List ? (List<String>) o : List.of();
    }
}
