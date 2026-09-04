package com.mobile.standalone.controller;

import com.mobile.standalone.service.apitest.CoverageGapService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 接口覆盖差集：白盒（代码变更分析）与黑盒（前端抓包）两边结论的对照。
 * <p>
 * 回答的是「这个需求还有哪些接口没测到」——改了却没人调的，和调了却没分析过的。
 */
@RestController
@RequestMapping("/coverage-gap")
@RequiredArgsConstructor
public class CoverageGapController {

    private final CoverageGapService service;

    /** 全部需求的概览，按「改了但没抓到」多的排前面 */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> summary(
            @RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(service.summary(includeArchived));
    }

    /** 单个需求的明细，含每个接口落在哪一类差集里 */
    @GetMapping("/{requirementNo}")
    public ResponseEntity<?> forRequirement(
            @PathVariable String requirementNo,
            @RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
        try {
            return ResponseEntity.ok(service.forRequirement(requirementNo, includeArchived));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
