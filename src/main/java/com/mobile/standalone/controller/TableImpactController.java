package com.mobile.standalone.controller;

import com.mobile.standalone.service.apitest.TableImpactService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 表变更影响面：某张表改了，牵动哪些接口和用例。
 * <p>
 * 接口测试要完整，就得能从「表结构变了」反推到「该回归哪些接口」——这是那条线的查询入口。
 */
@RestController
@RequestMapping("/table-impact")
@RequiredArgsConstructor
public class TableImpactController {

    private final TableImpactService service;

    /** 单张表牵动哪些接口 */
    @GetMapping("/{table}")
    public ResponseEntity<?> forTable(@PathVariable String table,
                                      @RequestParam(required = false) String project) {
        try {
            return ResponseEntity.ok(service.forTable(table, project));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 一批变更表的合并影响（对比分析出的 DDL 变更表一把传进来） */
    @PostMapping
    public ResponseEntity<?> forTables(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> tables = body.get("tables") instanceof List
                ? (List<String>) body.get("tables") : List.of();
        String project = body.get("project") == null ? null : String.valueOf(body.get("project"));
        return ResponseEntity.ok(service.forTables(tables, project));
    }
}
