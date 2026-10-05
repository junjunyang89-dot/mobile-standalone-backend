package com.mobile.standalone.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.auth.CurrentUser;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.service.ApiTestCaseService;
import com.mobile.standalone.service.apichange.FieldAssertBuilder;
import com.mobile.standalone.service.apichange.FieldMarkService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口对比报告里字段级的操作：打业务标签、标记已确认、把字段变化转成用例断言。
 * <p>
 * 报告只负责摆出「变了什么」，要人做的事都落在这里，而不是在报告里写一句「确认有值、空值……」。
 */
@Slf4j
@RestController
@RequestMapping("/api-changes")
@RequiredArgsConstructor
public class ApiFieldReviewController {

    private final FieldMarkService marks;
    private final ApiTestCaseService caseService;
    private final JdbcTemplate jdbc;

    @Data
    public static class MarkRequest {
        private String project;
        private String tag;
        private Boolean confirmed;
        private List<FieldMarkService.Item> items;
    }

    @Data
    public static class AssertPreviewRequest {
        private String caseId;
        private List<FieldAssertBuilder.Field> fields;
    }

    @Data
    public static class AssertApplyRequest {
        private String caseId;
        /** [{path, check: type|exists|absent, jsonType}] */
        private List<Map<String, String>> items;
    }

    @GetMapping("/field-marks")
    public ResponseEntity<?> listMarks(@RequestParam(required = false) String project,
                                       @RequestParam(required = false) String refs) {
        return ResponseEntity.ok(marks.list(project, refs));
    }

    @PutMapping("/field-marks/tags")
    public ResponseEntity<?> setTag(@RequestBody MarkRequest body, HttpServletRequest request) {
        try {
            int n = marks.setTag(body.getProject(), body.getItems(), body.getTag(), CurrentUser.nameOrNull(request));
            return ResponseEntity.ok(Map.of("updated", n));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/field-marks/confirms")
    public ResponseEntity<?> setConfirmed(@RequestBody MarkRequest body, HttpServletRequest request) {
        try {
            int n = marks.setConfirmed(body.getProject(), body.getItems(),
                    !Boolean.FALSE.equals(body.getConfirmed()), CurrentUser.nameOrNull(request));
            return ResponseEntity.ok(Map.of("updated", n));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 只算不写：给人看一眼路径对不对再写进用例 */
    @PostMapping("/field-asserts/preview")
    public ResponseEntity<?> previewAsserts(@RequestBody AssertPreviewRequest body) {
        ApiTestCase c = body.getCaseId() == null ? null : caseService.getCase(body.getCaseId());
        if (c == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "用例不存在"));
        }
        String caseBody = latestCaseBody(c.getId());
        String projectBody = caseBody == null ? latestProjectBody(c.getProject()) : null;
        FieldAssertBuilder.Prefix prefix = FieldAssertBuilder.prefix(caseBody, projectBody);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", c.getId());
        out.put("caseName", c.getName());
        out.put("prefix", prefix.path());
        out.put("prefixFrom", prefix.from());
        out.put("guessed", prefix.guessed());
        out.put("items", FieldAssertBuilder.propose(body.getFields(), caseBody, projectBody, c.getAssertions()));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/field-asserts")
    public ResponseEntity<?> applyAsserts(@RequestBody AssertApplyRequest body) {
        ApiTestCase c = body.getCaseId() == null ? null : caseService.getCase(body.getCaseId());
        if (c == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "用例不存在"));
        }
        List<ObjectNode> adds = new ArrayList<>();
        for (Map<String, String> it : body.getItems() == null ? List.<Map<String, String>>of() : body.getItems()) {
            String path = it == null ? null : it.get("path");
            if (path == null || !path.trim().startsWith("$")) {
                return ResponseEntity.badRequest().body(Map.of("message", "断言路径要以 $ 开头：" + path));
            }
            adds.add(FieldAssertBuilder.toAssertion(path.trim(), it.get("check"), it.get("jsonType")));
        }
        if (adds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "没有要写入的断言"));
        }
        FieldAssertBuilder.Merge m = FieldAssertBuilder.append(c.getAssertions(), adds);
        if (m.added() > 0) {
            c.setAssertions(m.json());
            caseService.updateCase(c.getId(), c);
        }
        return ResponseEntity.ok(Map.of("caseId", c.getId(), "caseName", c.getName(),
                "added", m.added(), "skipped", m.skipped()));
    }

    private String latestCaseBody(String caseId) {
        return first("""
                SELECT body_preview FROM api_test_run_cases
                WHERE case_id = ? AND body_preview IS NOT NULL AND body_preview <> ''
                ORDER BY created_at DESC LIMIT 1
                """, caseId);
    }

    private String latestProjectBody(String project) {
        if (project == null || project.isBlank()) {
            return null;
        }
        return first("""
                SELECT rc.body_preview FROM api_test_run_cases rc JOIN api_test_cases c ON c.id = rc.case_id
                WHERE c.project = ? AND rc.body_preview LIKE '{%' AND rc.created_at > NOW() - INTERVAL 30 DAY
                ORDER BY rc.created_at DESC LIMIT 1
                """, project);
    }

    private String first(String sql, Object arg) {
        try {
            List<String> rows = jdbc.queryForList(sql, String.class, arg);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[field-asserts] 取响应样本失败: {}", e.getMessage());
            return null;
        }
    }
}
