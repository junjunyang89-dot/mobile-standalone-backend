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
@lombok.extern.slf4j.Slf4j
@RestController
@RequestMapping("/ui-elements")
@RequiredArgsConstructor
public class UiElementController {

    private final com.mobile.standalone.service.InspectorShotReader inspectorShotReader;
    private final com.mobile.standalone.service.UiElementHealthService elementHealthService;

    /**
     * 元素健康报表。
     * <p>
     * 四张表回答四个不同的问题，刻意不合成一个「健康分」——分数看着好看，
     * 但拿到 78 分之后没人知道该干什么，而「这 6 个元素定位已失效」是能直接派活的。
     *
     * @param staleDays 多少天没被任何用例验证过算「陈旧」，默认 14
     */
    @GetMapping("/health")
    public ResponseEntity<?> health(@RequestParam(required = false) String appId,
                                    @RequestParam(required = false) String platform,
                                    @RequestParam(required = false, defaultValue = "14") int staleDays) {
        try {
            return ResponseEntity.ok(elementHealthService.report(appId, platform, staleDays));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 离线体检：拿已经存下来的失败现场控件树，扫一遍元素库。
     *
     * <p>不需要设备、不需要人走到哪一页、不用往流程里挂关键字——原料是执行失败时
     * 顺手存下的控件树，本来就在那儿。
     *
     * <p><b>它只报两件能证明的事</b>：哪些定位<b>确认还活着</b>（某个现场里命中恰好 1 个），
     * 哪些<b>不唯一</b>（命中多个，回放只点第 1 个，是「找到了、点了、没反应」的来源）。
     * 一次都没命中的归到「没覆盖到」，<b>不判失效</b>——这一层不知道每份现场是哪一页，
     * 分不出「定位废了」和「这些现场里根本没有这一页」。判失效看报表里的「可疑」，那里有 fail_count 做证据。
     *
     * @param maxScenes 最多读几份现场（从新到旧），默认 30
     */
    @PostMapping("/health/probe-saved")
    public ResponseEntity<?> probeSaved(@RequestParam(required = false) String appId,
                                        @RequestParam(required = false) String platform,
                                        @RequestParam(required = false, defaultValue = "30") int maxScenes) {
        try {
            return ResponseEntity.ok(elementHealthService.probeSavedScenes(appId, platform, maxScenes));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "离线体检失败"));
        }
    }

    /**
     * 对着一台真机的当前屏做体检：手动走到某一页，点一下就知道这页的元素还活着几个。
     * <p>
     * 平时不该靠这个——真正省事的是把「页面元素体检」关键字插进已有流程搭车跑，
     * 跑用例的时候顺手就把沿途页面全体检了。这个接口是给「临时想看一眼」和排查用的。
     */
    @PostMapping("/health/probe")
    public ResponseEntity<?> probe(@RequestBody Map<String, Object> body) {
        try {
            String deviceId = body.get("deviceId") == null ? null : String.valueOf(body.get("deviceId"));
            String appId = body.get("appId") == null ? null : String.valueOf(body.get("appId"));
            String platform = body.get("platform") == null ? null : String.valueOf(body.get("platform"));
            String pageId = body.get("pageId") == null ? null : String.valueOf(body.get("pageId"));
            return ResponseEntity.ok(elementHealthService.probeDevice(deviceId, appId, platform, pageId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "体检失败"));
        }
    }

    /**
     * 从 Appium Inspector 截图里认元素，<b>只出草稿，不入库</b>。
     * <p>
     * 模型是在认字，{@code btn_login} 认成 {@code btn_1ogin} 完全可能，
     * 而错一个字符的定位器不会报错，只会在跑流程时超时找不到——
     * 那时候离录入已经很远，没人会想到是当初录错了。所以必须让人核一眼再存。
     */
    @org.springframework.web.bind.annotation.PostMapping(
            value = "/read-inspector-shot",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> readInspectorShot(
            @org.springframework.web.bind.annotation.RequestParam("files")
            org.springframework.web.multipart.MultipartFile[] files) {
        try {
            java.util.List<com.mobile.standalone.service.ai.QwenChatClient.Image> images =
                    new java.util.ArrayList<>();
            for (org.springframework.web.multipart.MultipartFile f : files == null
                    ? new org.springframework.web.multipart.MultipartFile[0] : files) {
                if (f != null && !f.isEmpty()) {
                    images.add(new com.mobile.standalone.service.ai.QwenChatClient.Image(
                            f.getBytes(), f.getContentType()));
                }
            }
            return ResponseEntity.ok(java.util.Map.of("drafts", inspectorShotReader.read(images)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("message", "识别失败：" + e.getMessage()));
        }
    }

    private static final Set<String> ALLOWED_EXT = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    private final UiElementService uiElementService;
    private final com.mobile.standalone.service.InspectorXmlImportService inspectorXmlImportService;
    private final com.mobile.standalone.service.ElementKeySuggester elementKeySuggester;

    @Value("${app.ui-element-preview-dir:${user.home}/.mobile-standalone/uploads/ui-elements}")
    private String previewDir;

    /**
     * 中文元素名 → 建议的英文 nameEn（小写下划线）。
     * 翻不出来返回空串：别为了一个建议把新建元素卡住。
     */
    @GetMapping("/suggest-key")
    public ResponseEntity<Map<String, String>> suggestKey(@RequestParam String nameZh) {
        String key = elementKeySuggester.suggest(nameZh);
        return ResponseEntity.ok(Map.of("key", key == null ? "" : key));
    }

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

    /**
     * 从另一个平台复制元素，定位留空待补。
     * body: { fromAppId, fromPlatform, toAppId?, toPlatform, pageId?, dryRun? }
     */
    @PostMapping("/copy-platform")
    public ResponseEntity<?> copyPlatform(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(uiElementService.copyPlatform(
                    str(body.get("fromAppId")), str(body.get("fromPlatform")),
                    str(body.get("toAppId")), str(body.get("toPlatform")),
                    str(body.get("pageId")), Boolean.TRUE.equals(body.get("dryRun"))));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UiElement body) {
        UiElement saved;
        try {
            saved = uiElementService.create(body);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
        try {
            saved.setMirroredTo(uiElementService.mirrorToSiblingPlatforms(saved));
        } catch (RuntimeException ex) {
            log.warn("[ui-elements] {} 已建，同步到另一端失败: {}", saved.getNameEn(), ex.getMessage());
        }
        return ResponseEntity.ok(saved);
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
