package com.mobile.standalone.controller;

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

    @Value("${app.ui-element-preview-dir:${user.home}/.mobile-standalone/uploads/ui-elements}")
    private String previewDir;

    @GetMapping
    public ResponseEntity<List<UiElement>> list(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String catalog,
            /** 逗号分隔，如 AUTOMATION,SHARED — 自动化页同时看到「仅自动化」和「两边共用」 */
            @RequestParam(required = false) String catalogs,
            @RequestParam(required = false) String moduleTag,
            @RequestParam(required = false) String keyword
    ) {
        List<String> catalogList = null;
        if (catalogs != null && !catalogs.isBlank()) {
            catalogList = Arrays.stream(catalogs.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return ResponseEntity.ok(uiElementService.list(appId, platform, catalog, catalogList, moduleTag, keyword));
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
