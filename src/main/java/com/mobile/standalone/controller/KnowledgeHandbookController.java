package com.mobile.standalone.controller;

import com.mobile.standalone.entity.KnowledgeHandbook;
import com.mobile.standalone.service.HandbookAiSummarizer;
import com.mobile.standalone.service.KnowledgeHandbookService;
import com.mobile.standalone.service.ai.QwenChatClient;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 知识手册 API：分类短记（现象 / 原因 / 处理）。
 */
@RestController
@RequestMapping("/handbook")
@RequiredArgsConstructor
public class KnowledgeHandbookController {

    private static final Set<String> ALLOWED_EXT = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");
    private static final int MAX_AI_IMAGES = 3;

    private final KnowledgeHandbookService knowledgeHandbookService;
    private final HandbookAiSummarizer handbookAiSummarizer;

    @Value("${app.handbook-image-dir:${user.home}/.mobile-standalone/uploads/handbook}")
    private String imageDir;

    @GetMapping("/meta")
    public ResponseEntity<Map<String, Object>> meta() {
        return ResponseEntity.ok(knowledgeHandbookService.meta());
    }

    /**
     * AI 汇总：粘贴文字 / 贴图 → 短记草稿（不落库）。
     * 有图时第一张落盘并返回 imageUrl，便于直接当配图保存。
     */
    @PostMapping(value = "/ai-summarize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> aiSummarize(
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "hintCategory", required = false) String hintCategory,
            @RequestParam(value = "hintProject", required = false) String hintProject,
            @RequestParam(value = "files", required = false) MultipartFile[] files
    ) {
        try {
            List<QwenChatClient.Image> images = new ArrayList<>();
            String firstImageUrl = null;
            if (files != null) {
                for (MultipartFile f : files) {
                    if (f == null || f.isEmpty()) {
                        continue;
                    }
                    if (images.size() >= MAX_AI_IMAGES) {
                        break;
                    }
                    String ct = f.getContentType();
                    boolean isImage = ct != null && ct.startsWith("image/");
                    String original = f.getOriginalFilename() != null ? f.getOriginalFilename() : "";
                    String ext = "";
                    int dot = original.lastIndexOf('.');
                    if (dot >= 0) {
                        ext = original.substring(dot).toLowerCase(Locale.ROOT);
                    }
                    if (!isImage && !ALLOWED_EXT.contains(ext)) {
                        continue;
                    }
                    if (ext.isBlank()) {
                        ext = ".png";
                    }
                    if (!ALLOWED_EXT.contains(ext)) {
                        return ResponseEntity.badRequest().body(Map.of("message", "仅支持 png/jpg/jpeg/gif/webp"));
                    }
                    if (f.getSize() > 5 * 1024 * 1024L) {
                        return ResponseEntity.badRequest().body(Map.of("message", "单张图片不能超过 5MB"));
                    }
                    byte[] bytes = f.getBytes();
                    String mime = (ct != null && ct.startsWith("image/")) ? ct : mimeOf(ext);
                    images.add(new QwenChatClient.Image(bytes, mime));
                    if (firstImageUrl == null) {
                        firstImageUrl = storeImage(bytes, ext);
                    }
                }
            }

            Map<String, Object> draft = handbookAiSummarizer.summarize(text, images, hintCategory, hintProject);
            Map<String, Object> body = new LinkedHashMap<>(draft);
            if (firstImageUrl != null) {
                body.put("imageUrl", firstImageUrl);
            }
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "AI 汇总失败"));
        }
    }

    /**
     * 上传短记配图（可选）。返回相对路径，保存短记时写入 imageUrl。
     */
    @PostMapping(value = "/image/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请选择图片文件"));
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "image.png";
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
            String relative = storeImage(file.getBytes(), ext);
            return ResponseEntity.ok(Map.of(
                    "imageUrl", relative,
                    "url", "/" + relative
            ));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "上传失败: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeHandbook>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "100") int limit
    ) {
        return ResponseEntity.ok(knowledgeHandbookService.list(category, project, q, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        KnowledgeHandbook m = knowledgeHandbookService.get(id);
        return m == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(m);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody KnowledgeHandbook body) {
        try {
            return ResponseEntity.ok(knowledgeHandbookService.create(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "保存失败"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody KnowledgeHandbook body) {
        try {
            return ResponseEntity.ok(knowledgeHandbookService.update(id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "更新失败"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            knowledgeHandbookService.delete(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "删除失败"));
        }
    }

    private String storeImage(byte[] bytes, String ext) throws IOException {
        Path dir = Paths.get(imageDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String fileName = UUID.randomUUID() + ext;
        Files.write(dir.resolve(fileName), bytes);
        return "handbook-images/" + fileName;
    }

    private static String mimeOf(String ext) {
        return switch (ext) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            default -> "image/png";
        };
    }
}
