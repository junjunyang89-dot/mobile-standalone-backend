package com.mobile.standalone.controller;

import com.mobile.standalone.entity.Requirement;
import com.mobile.standalone.service.RequirementService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 需求登记。
 * <p>
 * 单号来自 JIRA，此处手工录入、不做同步。建这层是为了回答原先答不了的问题：
 * <b>这个需求属于哪个版本、覆盖哪些模块、测到什么程度</b>。
 */
@RestController
@RequestMapping("/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final RequirementService service;

    @GetMapping
    public ResponseEntity<List<Requirement>> list(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String versionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(service.list(projectCode, versionId, status, keyword));
    }

    @GetMapping("/{requirementNo}")
    public ResponseEntity<?> get(@PathVariable String requirementNo) {
        Requirement r = service.get(requirementNo);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }

    /**
     * 新增或更新。
     * <p>
     * {@code moduleIds} 传的是<b>当前覆盖的完整模块集合</b>，服务端先清空再写入；
     * 不传（null）则保持原有关联不动。
     */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody SaveRequest body) {
        try {
            return ResponseEntity.ok(service.save(body.getRequirement(), body.getModuleIds()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 单独改模块覆盖范围，不动需求本身的其它字段 */
    @PutMapping("/{requirementNo}/modules")
    public ResponseEntity<?> replaceModules(@PathVariable String requirementNo,
                                            @RequestBody Map<String, List<String>> body) {
        try {
            service.replaceModules(requirementNo, body.get("moduleIds"));
            return ResponseEntity.ok(service.get(requirementNo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{requirementNo}")
    public ResponseEntity<?> delete(@PathVariable String requirementNo) {
        return ResponseEntity.ok(Map.of("ok", service.delete(requirementNo)));
    }

    @Data
    public static class SaveRequest {
        private Requirement requirement;
        /** 完整的模块集合；null 表示不改动模块关联 */
        private List<String> moduleIds;
    }
}
