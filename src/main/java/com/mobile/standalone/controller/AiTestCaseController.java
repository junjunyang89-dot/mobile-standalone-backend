package com.mobile.standalone.controller;

import com.mobile.standalone.entity.AiTestCase;
import com.mobile.standalone.service.AiTestCaseService;
import com.mobile.standalone.service.TestCaseSheetParser;
import com.mobile.standalone.service.QwenTestCaseGenerator;
import com.mobile.standalone.service.AgentDocumentParseService;
import com.mobile.standalone.service.ai.QwenChatClient;
import java.util.ArrayList;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 生成用例文档 CRUD（表 ai_test_cases）。
 */
@RestController
@RequestMapping("/ai-test-cases")
@RequiredArgsConstructor
public class AiTestCaseController {

    private final AiTestCaseService aiTestCaseService;
    private final QwenTestCaseGenerator qwenTestCaseGenerator;
    private final AgentDocumentParseService documentParseService;

    /** 分页列表：GET /ai-test-cases?category=&pageName=&priority=&status=&batchId=&page=1&size=20 */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String pageName,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String automationAdvice,
            @RequestParam(required = false) String releaseStage,
            /** 标题/编号/测试点/模块 模糊搜 */
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size
    ) {
        try {
            Map<String, Object> raw = aiTestCaseService.list(
                    category, pageName, priority, status, platform, batchId,
                    automationAdvice, releaseStage, keyword, page, size);
            @SuppressWarnings("unchecked")
            List<AiTestCase> content = (List<AiTestCase>) raw.get("content");
            List<Map<String, Object>> views = content == null ? List.of()
                    : content.stream().map(aiTestCaseService::toView).toList();
            Map<String, Object> out = new LinkedHashMap<>(raw);
            out.put("content", views);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        AiTestCase row = aiTestCaseService.getById(id);
        if (row == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(aiTestCaseService.toView(row));
    }

    /**
     * 批量保存生成器 JSON：
     * { page_name, platform?, screenshot_url?, test_cases:[{case_id,title,priority,type,precondition,steps}] }
     */
    @PostMapping("/batch")
    public ResponseEntity<?> saveBatch(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> saved = aiTestCaseService.saveBatch(body);
            @SuppressWarnings("unchecked")
            List<AiTestCase> cases = (List<AiTestCase>) saved.get("cases");
            List<Map<String, Object>> views = cases == null ? List.of()
                    : cases.stream().map(aiTestCaseService::toView).toList();
            Map<String, Object> out = new LinkedHashMap<>(saved);
            out.put("cases", views);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "保存失败"));
        }
    }

    /**
     * 用千问生成用例（替代 CodeWords 那条按次计费的工作流）。
     * <p>
     * 只生成<b>不落库</b>：生成完人要先看一眼再决定存不存，直接入库错了还得一条条删。
     * 落库仍走 /batch，格式与原生成器一致，下游不用改。
     */
    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> generate(
            @RequestParam(value = "pageName", required = false) String pageName,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "files", required = false) MultipartFile[] files
    ) {
        try {
            List<QwenChatClient.Image> images = new ArrayList<>();
            StringBuilder extra = new StringBuilder(text == null ? "" : text.trim());
            if (files != null) {
                for (MultipartFile f : files) {
                    if (f == null || f.isEmpty()) {
                        continue;
                    }
                    String ct = f.getContentType();
                    boolean isImage = (ct != null && ct.startsWith("image/"))
                            || AgentDocumentParseService.isImage(f);
                    if (isImage) {
                        images.add(new QwenChatClient.Image(f.getBytes(), ct));
                    } else if (AgentDocumentParseService.isSupported(
                            f.getOriginalFilename() == null ? "" : f.getOriginalFilename())) {
                        // 需求文档也能直接丢进来，抽出文字当素材
                        String parsed = documentParseService.extractText(f);
                        if (parsed != null && !parsed.isBlank()) {
                            if (extra.length() > 0) extra.append("\n\n");
                            extra.append(parsed);
                        }
                    }
                }
            }
            return ResponseEntity.ok(qwenTestCaseGenerator.generate(
                    pageName, extra.length() == 0 ? null : extra.toString(), images));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "生成失败"));
        }
    }

    /** 手工新建一条用例（source=MANUAL）。生成器批量落库仍走 /batch */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody AiTestCase body) {
        try {
            return ResponseEntity.ok(aiTestCaseService.toView(aiTestCaseService.createManual(body)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /** 编辑：只覆盖请求里给了的字段。AI 生成的用例也能在这里改，改完仍保留 source=AI */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody AiTestCase body) {
        try {
            AiTestCase updated = aiTestCaseService.updateManual(id, body);
            if (updated == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(aiTestCaseService.toView(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 表格解析预览：只解析<b>不落库</b>。
     * <p>
     * 一份几十行的表直接插库，错了要一条条删。先给人看一眼解析成什么样、忽略了哪些列，
     * 确认无误再走 /import 落库。
     */
    @PostMapping(value = "/import/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseSheet(@RequestPart("file") MultipartFile file) {
        try {
            TestCaseSheetParser.Result r =
                    TestCaseSheetParser.parse(file.getBytes(), file.getOriginalFilename());
            return ResponseEntity.ok(Map.of(
                    "rows", r.rows().stream().map(aiTestCaseService::toView).toList(),
                    "raw", r.rows(),
                    "warnings", r.warnings()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "文件读取失败：" + e.getMessage()));
        }
    }

    /** 从表格软件直接粘贴的内容（行换行、列 Tab），同样只解析不落库 */
    @PostMapping("/import/parse-text")
    public ResponseEntity<?> parseText(@RequestBody Map<String, String> body) {
        try {
            TestCaseSheetParser.Result r =
                    TestCaseSheetParser.parseText(body == null ? null : body.get("text"));
            return ResponseEntity.ok(Map.of(
                    "rows", r.rows().stream().map(aiTestCaseService::toView).toList(),
                    "raw", r.rows(),
                    "warnings", r.warnings()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /** 确认导入：body { category, rows: [...] }，rows 用预览返回的 raw */
    @PostMapping("/import")
    public ResponseEntity<?> importRows(@RequestBody ImportRequest body) {
        try {
            return ResponseEntity.ok(aiTestCaseService.importRows(
                    body == null ? null : body.getCategory(),
                    body == null ? null : body.getRows()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    @lombok.Data
    public static class ImportRequest {
        private String category;
        private List<AiTestCase> rows;
    }

    /** 复核：PATCH /ai-test-cases/{id}/status  body: { "status": "REVIEWED" } */
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String status = body == null ? null : body.get("status");
            AiTestCase updated = aiTestCaseService.updateStatus(id, status);
            if (updated == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(aiTestCaseService.toView(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 单条提升为线上稳定 */
    @PostMapping("/{id}/promote")
    public ResponseEntity<?> promote(@PathVariable Long id) {
        try {
            AiTestCase updated = aiTestCaseService.promote(id);
            if (updated == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(aiTestCaseService.toView(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 批量提升：body { ids: [1,2,3] } */
    @PostMapping("/promote-batch")
    public ResponseEntity<?> promoteBatch(@RequestBody Map<String, Object> body) {
        try {
            Object raw = body == null ? null : body.get("ids");
            List<Long> ids = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o == null) continue;
                    if (o instanceof Number n) ids.add(n.longValue());
                    else ids.add(Long.parseLong(String.valueOf(o)));
                }
            }
            return ResponseEntity.ok(aiTestCaseService.promoteBatch(ids));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage() != null ? e.getMessage() : "提升失败"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return aiTestCaseService.delete(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.notFound().build();
    }
}
