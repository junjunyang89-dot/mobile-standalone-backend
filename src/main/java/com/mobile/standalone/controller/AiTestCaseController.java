package com.mobile.standalone.controller;

import com.mobile.standalone.entity.AiTestCase;
import com.mobile.standalone.service.AiTestCaseService;
import com.mobile.standalone.service.TestCaseSheetParser;
import com.mobile.standalone.service.QwenTestCaseGenerator;
import com.mobile.standalone.service.apitest.RequirementCaseGenerator;
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
    private final com.mobile.standalone.service.OpenQuestionService openQuestionService;
    private final com.mobile.standalone.service.apitest.RequirementEndpointService requirementEndpointService;
    private final com.mobile.standalone.service.RequirementCoverageService requirementCoverageService;
    private final com.mobile.standalone.service.apitest.RequirementCaseGenerator caseGenerator;
    private final com.mobile.standalone.service.CaseAskService caseAskService;

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
            /** APP 版本，如 9.3；「APP版本迭代」集合按它分版本看 */
            @RequestParam(required = false) String appVersion,
            /** 需求单号，如 APP-8873 */
            @RequestParam(required = false) String requirementNo,
            /** 涉及端：CLIENT / H5 / SERVER / ADMIN */
            @RequestParam(required = false) String side,
            /** 标题/编号/测试点/模块 模糊搜 */
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size
    ) {
        try {
            Map<String, Object> raw = aiTestCaseService.list(
                    category, pageName, priority, status, platform, batchId,
                    automationAdvice, releaseStage, appVersion, requirementNo, side, keyword, page, size);
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

    @SuppressWarnings("unchecked")
    private int saveOpenQuestions(Map<String, Object> body, Map<String, Object> saved) {
        Object raw = body.get("open_questions");
        if (raw == null) {
            raw = body.get("openQuestions");
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return 0;
        }
        List<com.fasterxml.jackson.databind.JsonNode> nodes = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        for (Object o : list) {
            nodes.add(m.valueToTree(o));
        }
        return openQuestionService.save(
                str(body.get("category")),
                firstNonBlank(str(body.get("app_version")), str(body.get("appVersion"))),
                firstNonBlank(str(body.get("page_name")), str(body.get("pageName"))),
                String.valueOf(saved.get("batchId")),
                nodes);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) {
            if (v != null && !v.isBlank() && !"null".equals(v)) {
                return v;
            }
        }
        return null;
    }

    /**
     * 某个集合 / 某一版的「待确认」项。
     * <p>
     * 这些是生成用例时从需求文档里挑出来的矛盾和缺口，拿去问产品用的，不进用例库。
     */
    @GetMapping("/open-questions")
    public ResponseEntity<?> openQuestions(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String appVersion,
            @RequestParam(required = false, defaultValue = "false") boolean includeResolved) {
        return ResponseEntity.ok(openQuestionService.list(category, appVersion, includeResolved));
    }

    /** 确认完了标掉。不删——问过什么、结论是什么，下个版本还有人会翻 */
    @PostMapping("/open-questions/{id}/resolve")
    public ResponseEntity<?> resolveQuestion(@PathVariable String id,
                                             @RequestParam(required = false, defaultValue = "true") boolean resolved) {
        boolean ok = openQuestionService.resolve(id, resolved);
        return ok ? ResponseEntity.ok(Map.of("success", true)) : ResponseEntity.notFound().build();
    }

    /** 某一版下每个单号的用例数：提测时唯一要回答的问题是「这个单子测完了没有」 */
    @GetMapping("/requirement-counts")
    public ResponseEntity<?> requirementCounts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String appVersion) {
        return ResponseEntity.ok(aiTestCaseService.countByRequirement(category, appVersion));
    }

    /** 删掉一条待确认。和「已确认」不同：这条根本不成立，留着只会下次又判断一遍 */
    @DeleteMapping("/open-questions/{id}")
    public ResponseEntity<?> deleteQuestion(@PathVariable String id) {
        return openQuestionService.delete(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    /** 端的字典，前端多选框直接用，不在前端硬编码这几个中文名 */
    @GetMapping("/sides")
    public ResponseEntity<List<Map<String, Object>>> sides() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] d : com.mobile.standalone.service.RequirementSide.all()) {
            out.add(Map.of("code", d[0], "label", d[1], "hint", d[2]));
        }
        return ResponseEntity.ok(out);
    }

    /** 各需求单关联了几个接口——清单上「涉及接口 · N」用 */
    @GetMapping("/requirement-endpoint-counts")
    public ResponseEntity<?> requirementEndpointCounts() {
        return ResponseEntity.ok(requirementEndpointService.countByRequirements());
    }

    /** 某个需求单涉及的接口（分支改动 + 人工补充） */
    @GetMapping("/requirement-endpoints")
    public ResponseEntity<?> requirementEndpoints(@RequestParam String requirementNo) {
        return ResponseEntity.ok(requirementEndpointService.listByRequirement(requirementNo));
    }

    /**
     * 提测覆盖看板：客户端 / H5 / 后端接口是否齐 P0、接口是否有可跑用例。
     * <p>
     * 2 天快版本靠它防漏端；没就绪可以豁免但必须写原因。
     */
    @GetMapping("/requirement-coverage")
    public ResponseEntity<?> requirementCoverage(
            @RequestParam String requirementNo,
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(requirementCoverageService.coverage(requirementNo, category));
    }

    @PostMapping("/requirement-coverage/waive")
    public ResponseEntity<?> waiveCoverage(@RequestBody Map<String, Object> body) {
        boolean ok = requirementCoverageService.waive(
                str(body.get("requirementNo")),
                str(body.get("dimension")),
                str(body.get("reason")),
                str(body.get("createdBy")));
        return ok
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.badRequest().body(Map.of("message", "单号、端、原因都要填"));
    }

    @DeleteMapping("/requirement-coverage/waive")
    public ResponseEntity<?> clearCoverageWaiver(
            @RequestParam String requirementNo,
            @RequestParam String dimension) {
        return requirementCoverageService.clearWaiver(requirementNo, dimension)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    /**
     * 人工补一个接口到需求单上。
     * <p>
     * 对比分析只认「这个分支改过的」接口，而一个需求往往还会调到一批没改的
     * （「获取更新日志」这种）——那些同样属于这条链路，出问题时一样要看。
     */
    @PostMapping("/requirement-endpoints")
    public ResponseEntity<?> addRequirementEndpoint(@RequestBody Map<String, Object> body) {
        boolean ok = requirementEndpointService.addManual(
                str(body.get("requirementNo")), str(body.get("project")),
                str(body.get("httpMethod")), str(body.get("path")), str(body.get("summary")));
        return ok
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.badRequest().body(Map.of(
                        "message", "单号和路径必填；项目请手动选择，或填可识别前缀的路径（如 /gld/…、/appdatacenter/…）"));
    }

    @PatchMapping("/requirement-endpoints/{id}/project")
    public ResponseEntity<?> assignRequirementEndpointProject(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean ok = requirementEndpointService.assignProject(id, str(body.get("project")));
        return ok
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.badRequest().body(Map.of("message", "指定项目失败，请检查项目编码"));
    }

    /**
     * 从一条涉及接口直接生成用例并归入集合。
     * <p>
     * 抓包抓到的接口原来只是几行字：知道它被调了，但没有任何东西在验它。
     * 这一步把「收进清单 → 生成骨架 → 归集合」一次做完——
     * 中间任何一步断了，「涉及接口 4 个 / 覆盖 0%」就永远是 0。
     * <p>
     * 不传 {@code collectionId} 就按需求单自动找/建一个测试集合，
     * 不用每次从二十几个项目的树里翻。
     */
    @PostMapping("/requirement-endpoints/{id}/generate-case")
    public ResponseEntity<?> generateCaseFromRequirementEndpoint(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            var r = caseGenerator.generate(new RequirementCaseGenerator.Request(
                    id,
                    body == null ? null : str(body.get("collectionId")),
                    body == null ? null : str(body.get("appVersion")),
                    body == null ? null : str(body.get("sourceBranch"))));
            return ResponseEntity.ok(Map.of(
                    "caseId", r.caseId(),
                    "caseName", r.caseName(),
                    "caseEnabled", r.caseEnabled(),
                    "note", r.note() == null ? "" : r.note(),
                    "collectionId", r.collectionId(),
                    "collectionName", r.collectionName(),
                    "collectionCreated", r.collectionCreated()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 给一个需求单下<b>所有</b>涉及接口一次性生成用例。
     * <p>
     * 抓完包挂上去只是「知道它被调了」，还没有任何东西在验它。
     * 逐个点太累——四个接口点四次，第三次就没人点了；
     * 而没生成用例的话，需求单上的覆盖率永远是 0%。
     */
    @PostMapping("/requirements/{requirementNo}/generate-cases")
    public ResponseEntity<?> generateAllCases(@PathVariable String requirementNo,
                                              @RequestBody(required = false) Map<String, Object> body) {
        String appVersion = body == null ? null : str(body.get("appVersion"));
        String collectionId = body == null ? null : str(body.get("collectionId"));
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        int reused = 0;
        List<String> failed = new ArrayList<>();

        for (Map<String, Object> ep : requirementEndpointService.listByRequirement(requirementNo)) {
            String id = str(ep.get("id"));
            try {
                var r = caseGenerator.generate(new RequirementCaseGenerator.Request(
                        id, collectionId, appVersion, str(ep.get("sourceBranch"))));
                boolean isReused = r.note() != null && r.note().contains("已经生成过");
                if (isReused) {
                    reused++;
                } else {
                    ok++;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", ep.get("path"));
                m.put("caseId", r.caseId());
                m.put("caseName", r.caseName());
                m.put("enabled", r.caseEnabled());
                m.put("reused", isReused);
                m.put("collectionName", r.collectionName());
                results.add(m);
            } catch (RuntimeException e) {
                // 一条挂了不该让整批停下：其余接口照样该生成
                failed.add(ep.get("httpMethod") + " " + ep.get("path") + "：" + e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of(
                "created", ok, "reused", reused,
                "failed", failed, "cases", results));
    }

    /** 生成用例时可以选的集合：同项目下还在用的测试集合 */
    @GetMapping("/requirement-endpoints/collections")
    public ResponseEntity<?> pickableCollections(@RequestParam String project,
                                                 @RequestParam(required = false) String requirementNo,
                                                 @RequestParam(required = false) String appVersion) {
        return ResponseEntity.ok(caseGenerator.pickableCollections(project, requirementNo, appVersion));
    }

    /** 解掉一条关联。挂错了要能拿掉，否则错的关联会一直留在链路上 */
    @DeleteMapping("/requirement-endpoints/{id}")
    public ResponseEntity<?> removeRequirementEndpoint(@PathVariable String id) {
        return requirementEndpointService.unlink(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    /** 这个集合下有哪些版本，供版本下拉。新版本排在前面 */
    @GetMapping("/versions")
    public ResponseEntity<?> versions(@RequestParam(required = false) String category) {
        return ResponseEntity.ok(aiTestCaseService.listVersions(category));
    }

    /** id 必须是数字，否则 {@code /ask} 会被当成「查 id=ask」，POST 就变成 405。 */
    @GetMapping("/{id:\\d+}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        AiTestCase row = aiTestCaseService.getById(id);
        if (row == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(aiTestCaseService.toView(row));
    }

    /**
     * 需求页标题旁还没点到某一条 TC 时，按整单问。
     * 不用 {@code POST /ask}：它和 {@code GET /{id}} 抢路径，未重启时就是
     * 「Method POST is not supported」。
     */
    @PostMapping("/requirements/{requirementNo}/ask")
    public ResponseEntity<?> askRequirement(
            @PathVariable String requirementNo,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(caseAskService.ask(
                    null,
                    requirementNo,
                    str(body == null ? null : body.get("question")),
                    com.mobile.standalone.service.CaseAskService.parseHistory(body == null ? null : body.get("history"))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id:\\d+}/ask")
    public ResponseEntity<?> ask(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(caseAskService.ask(
                    id,
                    null,
                    str(body == null ? null : body.get("question")),
                    com.mobile.standalone.service.CaseAskService.parseHistory(body == null ? null : body.get("history"))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 批量保存生成器 JSON：
     * { page_name, platform?, screenshot_url?, test_cases:[{case_id,title,priority,type,precondition,steps}] }
     */
    @PostMapping("/batch")
    public ResponseEntity<?> saveBatch(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> saved = aiTestCaseService.saveBatch(body);
            // 待确认项跟着这一批一起存：生成那一步还不知道要归到哪个版本，
            // 到保存时才有 category / app_version
            int questions = saveOpenQuestions(body, saved);
            @SuppressWarnings("unchecked")
            List<AiTestCase> cases = (List<AiTestCase>) saved.get("cases");
            List<Map<String, Object>> views = cases == null ? List.of()
                    : cases.stream().map(aiTestCaseService::toView).toList();
            Map<String, Object> out = new LinkedHashMap<>(saved);
            out.put("cases", views);
            out.put("openQuestions", questions);
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
            /** PAGE_SHOT（默认）/ REQUIREMENT_DOC —— 两种素材的提示词是相反的 */
            @RequestParam(value = "materialKind", required = false) String materialKind,
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
                    pageName, extra.length() == 0 ? null : extra.toString(), images,
                    com.mobile.standalone.service.MaterialKind.from(materialKind)));
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
    @PutMapping("/{id:\\d+}")
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
    @PatchMapping("/{id:\\d+}/status")
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
    @PostMapping("/{id:\\d+}/promote")
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

    @DeleteMapping("/{id:\\d+}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return aiTestCaseService.delete(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.notFound().build();
    }
}
