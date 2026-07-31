package com.mobile.standalone.controller;

import com.mobile.standalone.entity.TestCase;
import com.mobile.standalone.service.TestCaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 测试用例管理控制器
 */
@RestController
@RequestMapping("/test-cases")
@RequiredArgsConstructor
public class TestCaseController {
    
    private final TestCaseService testCaseService;
    
    @GetMapping
    public ResponseEntity<List<TestCase>> getAllCases() {
        return ResponseEntity.ok(testCaseService.getAllCases());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<TestCase> getCase(@PathVariable String id) {
        TestCase testCase = testCaseService.getCaseById(id);
        if (testCase != null) {
            return ResponseEntity.ok(testCase);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/platform/{platform}")
    public ResponseEntity<List<TestCase>> getCasesByPlatform(@PathVariable String platform) {
        try {
            TestCase.Platform platformEnum = TestCase.Platform.valueOf(platform.toUpperCase());
            return ResponseEntity.ok(testCaseService.getCasesByPlatform(platformEnum));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    @GetMapping("/app/{appId}")
    public ResponseEntity<List<TestCase>> getCasesByAppId(@PathVariable String appId) {
        return ResponseEntity.ok(testCaseService.getCasesByAppId(appId));
    }
    
    @PostMapping
    public ResponseEntity<TestCase> createCase(@RequestBody TestCase testCase) {
        TestCase created = testCaseService.createCase(testCase);
        return ResponseEntity.ok(created);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<TestCase> updateCase(@PathVariable String id, @RequestBody TestCase testCase) {
        TestCase updated = testCaseService.updateCase(id, testCase);
        if (updated != null) {
            return ResponseEntity.ok(updated);
        }
        return ResponseEntity.notFound().build();
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCase(@PathVariable String id) {
        boolean success = testCaseService.deleteCase(id);
        if (success) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}
