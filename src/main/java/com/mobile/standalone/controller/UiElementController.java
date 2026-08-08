package com.mobile.standalone.controller;

import com.mobile.standalone.dto.PageResult;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.service.UiElementService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 行级元素库 CRUD。默认 catalog=SHARED，测试与线上共用定位。
 */
@RestController
@RequestMapping("/ui-elements")
@RequiredArgsConstructor
public class UiElementController {

    private static final Set<String> ALLOWED_EXT = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    private final UiElementService uiElementService;
    private final com.mobile.standalone.service.InspectorXmlImportService inspectorXmlImportService;

    @Value("${app.ui-element-preview-dir:${user.home}/.mobile-standalone/uploads/ui-elements}")
    private String previewDir;

    /**
     * 元素列表。传 page 时返回分页 {@link PageResult}；不传 page 时返回全量 List（兼容流程下拉等）。
     * 应用 / 平台应作为主维度筛选：同一 nameEn 在 Android / iOS、不同 app 下各一行。
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String catalog,
            /** 逗号分隔，如 AUTOMATION,SHARED — 自动化页同时看到「仅自动化」和「两边共用」 */
            @RequestParam(required = false) String catalogs,
            @RequestParam(required = false) String moduleTag,
            @RequestParam(required = false) String pageId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size
    ) {
        List<String> catalogList = null;
        if (catalogs != null && !catalogs.isBlank()) {
            catalogList = Arrays.stream(catalogs.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        if (page != null) {
            PageResult<UiElement> result = uiElementService.listPage(
                    appId, platform, catalog, catalogList, moduleTag, pageId, source, status, keyword,
                    page, size != null ? size : 20);
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(uiElementService.list(
                appId, platform, catalog, catalogList, moduleTag, pageId, source, status, keyword));
    }

    /**
     * 上传元素预览图（非必传）。返回相对路径，保存元素时写入 previewImage。
     */
    @PostMapping(value = "/preview/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPreview(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请选择图片文件"));
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "preview.png";
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) {
            ext = original.substring(dot).toLowerCase(Locale.ROOT);
        }
        if (!ALLOWED_EXT.contains(ext)) {
            return ResponseEntity.badRequest().body(Map.of("message", "仅支持 png/jpg/jpeg/gif/webp"));
        }
        if (file.getSize() > 5 * 1024 * 1024L) {
            return ResponseEntity.badRequest().body(Map.of("message", "图片不能超过 5MB"));
        }
        try {
            Path dir = Paths.get(previewDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String fileName = UUID.randomUUID() + ext;
            Path target = dir.resolve(fileName);
            Files.copy(file.getInputStream(), target);
            String relative = "ui-element-previews/" + fileName;
            return ResponseEntity.ok(Map.of(
                    "previewImage", relative,
                    "url", "/" + relative
            ));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "上传失败: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiElement> get(@PathVariable String id) {
        UiElement e = uiElementService.get(id);
        return e == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(e);
    }

    /**
     * 解析 Appium Inspector 导出的 XML，返回候选元素（不写库）。
     * body: { platform: IOS|ANDROID|H5, xml: "..." }
     */
    @PostMapping("/inspector-xml/parse")
    public ResponseEntity<?> parseInspectorXml(@RequestBody Map<String, Object> body) {
        try {
            String platform = body.get("platform") != null ? String.valueOf(body.get("platform")) : null;
            String xml = body.get("xml") != null ? String.valueOf(body.get("xml")) : null;
            List<com.mobile.standalone.service.InspectorXmlImportService.ParsedCandidate> list =
                    inspectorXmlImportService.parse(platform, xml);
            return ResponseEntity.ok(Map.of("candidates", list, "count", list.size()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    /**
     * 将勾选的候选写入元素库。source=INSPECTOR_XML，默认 status=CANDIDATE。
     * body: { appId, platform, status?, items: [ParsedCandidate...] }
     */
    @PostMapping("/inspector-xml/import")
    public ResponseEntity<?> importInspectorXml(@RequestBody Map<String, Object> body) {
        try {
            String appId = body.get("appId") != null ? String.valueOf(body.get("appId")) : null;
            String platform = body.get("platform") != null ? String.valueOf(body.get("platform")) : null;
            String status = body.get("status") != null ? String.valueOf(body.get("status")) : "CANDIDATE";
            String pageId = body.get("pageId") != null ? String.valueOf(body.get("pageId")) : null;
            String pageNameZh = body.get("pageNameZh") != null ? String.valueOf(body.get("pageNameZh")) : null;
            String moduleTag = body.get("moduleTag") != null ? String.valueOf(body.get("moduleTag")) : null;
            if (pageId == null || pageId.isBlank() || "null".equalsIgnoreCase(pageId)) {
                return ResponseEntity.badRequest().body(Map.of("message", "请选择或新建归属页面（pageId）"));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawItems = body.get("items") instanceof List
                    ? (List<Map<String, Object>>) body.get("items")
                    : List.of();
            List<com.mobile.standalone.service.InspectorXmlImportService.ParsedCandidate> items = new ArrayList<>();
            for (Map<String, Object> m : rawItems) {
                if (m == null) continue;
                com.mobile.standalone.service.InspectorXmlImportService.ParsedCandidate c =
                        new com.mobile.standalone.service.InspectorXmlImportService.ParsedCandidate();
                c.setNameZh(str(m.get("nameZh")));
                c.setNameEn(str(m.get("nameEn")));
                c.setLocatorType(str(m.get("locatorType")));
                c.setLocatorValue(str(m.get("locatorValue")));
                if (m.get("locatorOptions") instanceof List<?> opts) {
                    List<com.mobile.standalone.service.InspectorXmlImportService.LocatorOption> list = new ArrayList<>();
                    for (Object o : opts) {
                        if (!(o instanceof Map<?, ?> om)) continue;
                        com.mobile.standalone.service.InspectorXmlImportService.LocatorOption lo =
                                new com.mobile.standalone.service.InspectorXmlImportService.LocatorOption();
                        lo.setLocatorType(str(om.get("locatorType")));
                        lo.setLocatorValue(str(om.get("locatorValue")));
                        lo.setLabel(str(om.get("label")));
                        list.add(lo);
                    }
                    c.setLocatorOptions(list);
                }
                if (m.get("locatorFallbacks") instanceof List<?> fbs) {
                    List<com.mobile.standalone.service.InspectorXmlImportService.LocatorOption> list = new ArrayList<>();
                    for (Object o : fbs) {
                        if (!(o instanceof Map<?, ?> om)) continue;
                        com.mobile.standalone.service.InspectorXmlImportService.LocatorOption lo =
                                new com.mobile.standalone.service.InspectorXmlImportService.LocatorOption();
                        lo.setLocatorType(str(om.get("locatorType")));
                        lo.setLocatorValue(str(om.get("locatorValue")));
                        lo.setLabel(str(om.get("label")));
                        list.add(lo);
                    }
                    c.setLocatorFallbacks(list);
                }
                c.setVisualHint(str(m.get("visualHint")));
                c.setModuleTag(str(m.get("moduleTag")));
                c.setPageId(str(m.get("pageId")));
                c.setDescription(str(m.get("description")));
                c.setElementType(str(m.get("elementType")));
                items.add(c);
            }
            var result = inspectorXmlImportService.importSelected(
                    appId, platform, status, pageId, pageNameZh, moduleTag, items);
            return ResponseEntity.ok(Map.of(
                    "created", result.getCreated(),
                    "createdCount", result.getCreated().size(),
                    "skipped", result.getSkipped(),
                    "skippedCount", result.getSkipped().size(),
                    "errors", result.getErrors()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UiElement body) {
        try {
            return ResponseEntity.ok(uiElementService.create(body));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UiElement body) {
        try {
            UiElement updated = uiElementService.update(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return uiElementService.delete(id) ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.notFound().build();
    }
}
