package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ApiCodeDir;
import com.mobile.standalone.entity.ApiTableSchema;
import com.mobile.standalone.mapper.ApiCodeDirMapper;
import com.mobile.standalone.mapper.ApiTableSchemaMapper;
import com.mobile.standalone.service.apichange.TableTestHints;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口变更分析的两份配置：表结构、本地代码目录。
 * <p>
 * 都是「录一次、长期用」的东西：项目里没有 .sql 文件，建表语句只能从数据库导出来贴；
 * 远程办公切不了分支，只能一个分支一份目录，路径存下来省得天天手打。
 */
@RestController
@RequestMapping("/api-changes")
@RequiredArgsConstructor
public class ApiTableSchemaController {

    private final ApiTableSchemaMapper schemaMapper;
    private final ApiCodeDirMapper codeDirMapper;

    // ---------- 表结构 ----------

    @GetMapping("/table-schemas")
    public ResponseEntity<?> listSchemas(@RequestParam(required = false) String project) {
        List<ApiTableSchema> rows = schemaMapper.findByProject(project);
        return ResponseEntity.ok(rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("project", r.getProject());
            m.put("tableName", r.getTableName());
            m.put("ddl", r.getDdl());
            m.put("remark", r.getRemark());
            m.put("testHints", TableTestHints.derive(r.getDdl()));
            return m;
        }).toList());
    }

    /**
     * 保存建表语句。表名能从 DDL 里读出来就不用人填——粘完就走，少一次输入少一次填错。
     */
    @PostMapping("/table-schemas")
    public ResponseEntity<?> saveSchema(@RequestBody ApiTableSchema body) {
        if (body == null || body.getDdl() == null || body.getDdl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请粘贴建表语句"));
        }
        String name = body.getTableName() == null || body.getTableName().isBlank()
                ? TableTestHints.tableNameOf(body.getDdl())
                : body.getTableName().trim().toLowerCase();
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "从建表语句里读不出表名，请手动填写表名"));
        }
        body.setTableName(name);
        if (body.getProject() != null && body.getProject().isBlank()) {
            body.setProject(null);
        }
        if (body.getId() != null) {
            schemaMapper.update(body);
        } else {
            schemaMapper.insert(body);
        }
        return ResponseEntity.ok(Map.of(
                "id", body.getId(), "tableName", name,
                "testHints", TableTestHints.derive(body.getDdl())));
    }

    @DeleteMapping("/table-schemas/{id}")
    public ResponseEntity<?> deleteSchema(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("ok", schemaMapper.deleteById(id) > 0));
    }

    /** 只预览推导结果，不落库——粘进去先看看有没有价值 */
    @PostMapping("/table-schemas/preview")
    public ResponseEntity<?> previewHints(@RequestBody Map<String, String> body) {
        String ddl = body == null ? null : body.get("ddl");
        return ResponseEntity.ok(Map.of(
                "tableName", String.valueOf(TableTestHints.tableNameOf(ddl)),
                "testHints", TableTestHints.derive(ddl)));
    }

    // ---------- 代码目录 ----------

    @GetMapping("/code-dirs")
    public ResponseEntity<?> listDirs(@RequestParam(required = false) String project) {
        return ResponseEntity.ok(codeDirMapper.findByProject(project));
    }

    @PostMapping("/code-dirs")
    public ResponseEntity<?> saveDir(@RequestBody ApiCodeDir body) {
        if (body == null || body.getDirPath() == null || body.getDirPath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请填写代码目录路径"));
        }
        if (body.getLabel() == null || body.getLabel().isBlank()) {
            // 没起别名就用目录名，比留空强
            String p = body.getDirPath().replace('\\', '/');
            int slash = p.lastIndexOf('/');
            body.setLabel(slash >= 0 ? p.substring(slash + 1) : p);
        }
        body.setDirPath(body.getDirPath().trim());
        if (body.getIsBaseline() == null) {
            body.setIsBaseline(false);
        }
        if (body.getSortOrder() == null) {
            body.setSortOrder(100);
        }
        if (body.getId() != null) {
            codeDirMapper.update(body);
        } else {
            codeDirMapper.insert(body);
        }
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/code-dirs/{id}")
    public ResponseEntity<?> deleteDir(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("ok", codeDirMapper.deleteById(id) > 0));
    }
}
