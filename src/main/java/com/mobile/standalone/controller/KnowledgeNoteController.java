package com.mobile.standalone.controller;

import com.mobile.standalone.auth.AuthFilter;
import com.mobile.standalone.auth.PlatformUser;
import com.mobile.standalone.service.KnowledgeNoteRules;
import com.mobile.standalone.service.KnowledgeNoteService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 测试知识点。写在需求旁边，按模块找回。
 */
@RestController
@RequestMapping("/knowledge-notes")
@RequiredArgsConstructor
public class KnowledgeNoteController {

    private final KnowledgeNoteService service;

    @Value("${app.knowledge-note-image-dir:${user.home}/.mobile-standalone/uploads/knowledge-notes}")
    private String imageDir;

    private static final Set<String> ALLOWED_EXT = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String moduleId,
            @RequestParam(required = false) String requirementNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(Map.of("notes", service.list(moduleId, requirementNo, status, q)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        Map<String, Object> row = service.get(id);
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(row);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(Map.of("id", service.create(body, createdBy(request))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            return service.update(id, body)
                    ? ResponseEntity.ok(Map.of("success", true))
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return service.delete(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "没收到文件"));
        }
        List<String> urls = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                continue;
            }
            String original = f.getOriginalFilename() == null ? "shot.png" : f.getOriginalFilename();
            String ext = "";
            int dot = original.lastIndexOf('.');
            if (dot >= 0) {
                ext = original.substring(dot).toLowerCase(Locale.ROOT);
            }
            if (!ALLOWED_EXT.contains(ext)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", original + "：仅支持 png/jpg/jpeg/gif/webp"));
            }
            if (f.getSize() > 10 * 1024 * 1024L) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", original + "：单张不能超过 10MB"));
            }
            try {
                urls.add(store(f.getBytes(), ext));
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(Map.of(
                        "message", "保存失败：" + e.getMessage()));
            }
        }
        if (urls.size() > KnowledgeNoteRules.MAX_IMAGES) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "一次最多 " + KnowledgeNoteRules.MAX_IMAGES + " 张"));
        }
        return ResponseEntity.ok(Map.of("urls", urls));
    }

    private String store(byte[] bytes, String ext) throws IOException {
        Path root = Paths.get(imageDir).toAbsolutePath().normalize();
        Path day = root.resolve(LocalDate.now().toString());
        Files.createDirectories(day);
        String name = UUID.randomUUID().toString().replace("-", "") + ext;
        Files.write(day.resolve(name), bytes);
        return "/knowledge-note-images/" + LocalDate.now() + "/" + name;
    }

    private static String createdBy(HttpServletRequest request) {
        Object u = request.getAttribute(AuthFilter.ATTR_USER);
        if (u instanceof PlatformUser pu) {
            if (pu.getDisplayName() != null && !pu.getDisplayName().isBlank()) {
                return pu.getDisplayName();
            }
            return pu.getUsername();
        }
        return null;
    }
}
