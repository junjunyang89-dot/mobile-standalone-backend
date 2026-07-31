package com.mobile.standalone.controller;

import com.mobile.standalone.entity.PageObject;
import com.mobile.standalone.service.PageObjectService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 页面对象（元素）管理接口，供前端增删改查元素。
 * 基路径：/api/page-objects（context-path 为 /api 时）
 */
@RestController
@RequestMapping("/page-objects")
@RequiredArgsConstructor
public class PageObjectController {

    private final PageObjectService pageObjectService;

    /**
     * 新增页面对象（含元素）
     * POST /api/page-objects
     */
    @PostMapping
    public ResponseEntity<PageObject> create(@RequestBody CreatePageObjectRequest request) {
        if (request.getPlatform() == null || request.getAppId() == null || request.getAppId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getPagePath() == null || request.getPagePath().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        PageObject po = new PageObject();
        po.setName(request.getName() != null ? request.getName() : request.getPagePath());
        po.setDescription(request.getDescription());
        po.setPlatform(parsePlatform(request.getPlatform()));
        po.setAppId(request.getAppId());
        po.setPagePath(request.getPagePath().trim());
        po.setCatalog(parseCatalogOrDefault(request.getCatalog()));
        po.setElements(request.getElements());
        po.setCreatedBy(request.getCreatedBy());
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(pageObjectService.createPageObject(po));
    }

    /**
     * 列表：按目录、应用、平台筛选（不传则返回全部；catalog 默认由前端传 AUTOMATION）
     * GET /api/page-objects?catalog=AUTOMATION&appId=xxx&platform=ANDROID
     */
    @GetMapping
    public ResponseEntity<List<PageObject>> list(
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String platform
    ) {
        PageObject.Catalog cat = parseCatalogOrNull(catalog);
        PageObject.Platform p = parsePlatformOrNull(platform);
        if (cat != null || (appId != null && !appId.isBlank()) || p != null) {
            return ResponseEntity.ok(pageObjectService.listByFilters(cat, appId, p));
        }
        return ResponseEntity.ok(pageObjectService.getAllPageObjects());
    }

    /**
     * 详情
     * GET /api/page-objects/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<PageObject> get(@PathVariable String id) {
        PageObject po = pageObjectService.getPageObjectById(id);
        if (po != null) {
            return ResponseEntity.ok(po);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 分类展示：返回页面对象及其元素按「定位方式」分组，便于前端按 id / xpath / text 等分类展示。
     * GET /api/page-objects/{id}/categorized
     */
    @GetMapping("/{id}/categorized")
    public ResponseEntity<PageObjectCategorizedResponse> getCategorized(@PathVariable String id) {
        PageObject po = pageObjectService.getPageObjectById(id);
        if (po == null) {
            return ResponseEntity.notFound().build();
        }
        PageObjectCategorizedResponse resp = buildCategorizedResponse(po);
        return ResponseEntity.ok(resp);
    }

    /**
     * 按应用+平台+页面路径查一条（便于前端编辑某一页元素）
     * GET /api/page-objects/by-page?platform=ANDROID&appId=xxx&pagePath=Login
     */
    @GetMapping("/by-page")
    public ResponseEntity<PageObject> getByPage(
            @RequestParam String platform,
            @RequestParam String appId,
            @RequestParam String pagePath
    ) {
        if (platform == null || appId == null || pagePath == null) {
            return ResponseEntity.badRequest().build();
        }
        PageObject po = pageObjectService.findByPagePath(platform, appId, pagePath);
        if (po != null) {
            return ResponseEntity.ok(po);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 更新页面对象（含元素）
     * PUT /api/page-objects/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<PageObject> update(@PathVariable String id, @RequestBody UpdatePageObjectRequest request) {
        PageObject po = new PageObject();
        po.setName(request.getName());
        po.setDescription(request.getDescription());
        po.setPagePath(request.getPagePath());
        if (request.getCatalog() != null && !request.getCatalog().isBlank()) {
            po.setCatalog(parseCatalogOrDefault(request.getCatalog()));
        }
        po.setElements(request.getElements());
        po.setUpdatedAt(LocalDateTime.now());
        PageObject updated = pageObjectService.updatePageObject(id, po);
        if (updated != null) {
            return ResponseEntity.ok(updated);
        }
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage() != null ? e.getMessage() : "参数错误"));
    }

    /**
     * 按页面删除：删除整个页面对象（含其下所有元素）。
     * DELETE /api/page-objects/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePage(@PathVariable String id) {
        if (pageObjectService.deletePageObject(id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 按元素删除：从指定页面中移除一个元素，不删页面本身。
     * DELETE /api/page-objects/{id}/elements/{elementKey}
     * @param id 页面对象 ID
     * @param elementKey 元素标识，如 SIDE_MENU_BUTTON、CLICK_LOGIN
     */
    @DeleteMapping("/{id}/elements/{elementKey}")
    public ResponseEntity<PageObject> deleteElement(
            @PathVariable String id,
            @PathVariable String elementKey
    ) {
        PageObject updated = pageObjectService.removeElement(id, elementKey);
        if (updated != null) {
            return ResponseEntity.ok(updated);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 批量导入页面对象（含元素），便于从文档/脚本一次性落库。
     * POST /api/page-objects/import
     * Body: { "appId": "xxx", "platform": "ANDROID", "pageObjects": [ { "pagePath": "Settings_SideMenu", "name": "设置-侧边栏", "elements": { ... } }, ... ] }
     */
    @PostMapping("/import")
    public ResponseEntity<ImportResult> importPageObjects(@RequestBody ImportRequest request) {
        if (request.getAppId() == null || request.getAppId().isBlank()
                || request.getPlatform() == null || request.getPlatform().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getPageObjects() == null || request.getPageObjects().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        PageObject.Platform platform = parsePlatform(request.getPlatform());
        List<String> createdIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ImportPageItem item : request.getPageObjects()) {
            if (item.getPagePath() == null || item.getPagePath().isBlank()) {
                errors.add("pagePath 为空，跳过");
                continue;
            }
            try {
                PageObject po = new PageObject();
                po.setName(item.getName() != null ? item.getName() : item.getPagePath());
                po.setDescription(item.getDescription());
                po.setPlatform(platform);
                po.setAppId(request.getAppId());
                po.setPagePath(item.getPagePath().trim());
                po.setCatalog(parseCatalogOrDefault(request.getCatalog()));
                po.setElements(item.getElements() != null ? item.getElements() : Map.of());
                po.setCreatedAt(LocalDateTime.now());
                po.setUpdatedAt(LocalDateTime.now());
                PageObject created = pageObjectService.createPageObject(po);
                createdIds.add(created.getId());
            } catch (Exception e) {
                errors.add(item.getPagePath() + ": " + e.getMessage());
            }
        }
        ImportResult result = new ImportResult();
        result.setCreated(createdIds.size());
        result.setIds(createdIds);
        result.setErrors(errors.isEmpty() ? null : errors);
        return ResponseEntity.ok(result);
    }

    private PageObjectCategorizedResponse buildCategorizedResponse(PageObject po) {
        PageObjectCategorizedResponse resp = new PageObjectCategorizedResponse();
        resp.setId(po.getId());
        resp.setName(po.getName());
        resp.setDescription(po.getDescription());
        resp.setPlatform(po.getPlatform());
        resp.setAppId(po.getAppId());
        resp.setPagePath(po.getPagePath());
        resp.setCatalog(po.getCatalog());
        resp.setCreatedAt(po.getCreatedAt());
        resp.setUpdatedAt(po.getUpdatedAt());
        resp.setElements(po.getElements() != null ? po.getElements() : Map.of());
        resp.setElementCount(po.getElements() != null ? po.getElements().size() : 0);

        Map<String, List<ElementWithKey>> bySelectorType = new LinkedHashMap<>();
        if (po.getElements() != null) {
            for (Map.Entry<String, PageObject.PageElement> e : po.getElements().entrySet()) {
                String key = e.getKey();
                PageObject.PageElement el = e.getValue();
                String category = (el.getSelectorType() != null && !el.getSelectorType().isBlank())
                        ? el.getSelectorType().toLowerCase()
                        : "other";
                ElementWithKey entry = new ElementWithKey();
                entry.setKey(key);
                entry.setName(el.getName());
                entry.setSelector(el.getSelector());
                entry.setSelectorType(el.getSelectorType());
                entry.setDescription(el.getDescription());
                entry.setTimeout(el.getTimeout());
                bySelectorType.computeIfAbsent(category, k -> new ArrayList<>()).add(entry);
            }
        }
        resp.setElementsBySelectorType(bySelectorType);
        return resp;
    }

    private static PageObject.Platform parsePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        return PageObject.Platform.valueOf(platform.trim().toUpperCase());
    }

    private static PageObject.Platform parsePlatformOrNull(String platform) {
        if (platform == null || platform.isBlank()) return null;
        try {
            return PageObject.Platform.valueOf(platform.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PageObject.Catalog parseCatalogOrDefault(String catalog) {
        PageObject.Catalog c = parseCatalogOrNull(catalog);
        return c != null ? c : PageObject.Catalog.AUTOMATION;
    }

    private static PageObject.Catalog parseCatalogOrNull(String catalog) {
        if (catalog == null || catalog.isBlank()) return null;
        try {
            return PageObject.Catalog.valueOf(catalog.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Data
    public static class CreatePageObjectRequest {
        private String name;           // 可选，不传则用 pagePath
        private String description;
        private String platform;       // 必填：ANDROID | IOS | HARMONYOS
        private String appId;          // 必填
        private String pagePath;       // 必填，页面标识如 Login
        /** AUTOMATION | ONLINE | SHARED，默认 AUTOMATION */
        private String catalog;
        private Map<String, PageObject.PageElement> elements;  // 元素定义，可为空 {} 后续再补
        private String createdBy;
    }

    @Data
    public static class UpdatePageObjectRequest {
        private String name;
        private String description;
        private String pagePath;
        private String catalog;
        private Map<String, PageObject.PageElement> elements;
    }

    @Data
    public static class ImportRequest {
        private String appId;
        private String platform;   // ANDROID | IOS | HARMONYOS
        /** 导入目标目录，默认 AUTOMATION */
        private String catalog;
        private List<ImportPageItem> pageObjects;
    }

    @Data
    public static class ImportPageItem {
        private String pagePath;
        private String name;
        private String description;
        private Map<String, PageObject.PageElement> elements;
    }

    @Data
    public static class ImportResult {
        private int created;
        private List<String> ids;
        private List<String> errors;
    }

    /** 分类展示：页面对象 + 元素按定位方式分组，供前端按 id/xpath/text 等分类展示 */
    @Data
    public static class PageObjectCategorizedResponse {
        private String id;
        private String name;
        private String description;
        private PageObject.Platform platform;
        private String appId;
        private String pagePath;
        private PageObject.Catalog catalog;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        /** 元素总数 */
        private int elementCount;
        /** 原始元素 Map，key 为元素标识 */
        private Map<String, PageObject.PageElement> elements;
        /** 按 selectorType 分组：id / xpath / text / accessibility-id / other，每组为元素列表（含 key） */
        private Map<String, List<ElementWithKey>> elementsBySelectorType;
    }

    @Data
    public static class ElementWithKey {
        private String key;       // 元素标识，如 SIDE_MENU_BUTTON
        private String name;
        private String selector;
        private String selectorType;
        private String description;
        private Integer timeout;
    }
}
