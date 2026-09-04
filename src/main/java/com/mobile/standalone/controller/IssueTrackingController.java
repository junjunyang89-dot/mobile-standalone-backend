package com.mobile.standalone.controller;

import com.mobile.standalone.service.IssueTrackingService;
import com.mobile.standalone.service.IssueTrackingWikiService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 线上/测试问题持续跟踪。
 */
@RestController
@RequestMapping("/issue-tracking")
@RequiredArgsConstructor
public class IssueTrackingController {

    private final IssueTrackingService issueTrackingService;
    private final IssueTrackingWikiService issueTrackingWikiService;

    @Value("${app.issue-image-dir:${user.home}/.mobile-standalone/uploads/issues}")
    private String imageDir;

    private static final Set<String> ALLOWED_EXT = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String trackingTester,
            @RequestParam(required = false) String projectType,
            @RequestParam(required = false) String developer,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(issueTrackingService.list(
                environment, status, priority, trackingTester, projectType, developer, q));
    }

    /** 截图。一条问题常常要好几张现场图，所以支持多传 */
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "没收到文件"));
        }
        List<String> urls = new java.util.ArrayList<>();
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
        return ResponseEntity.ok(Map.of("urls", urls));
    }

    /**
     * 追加到 Wiki 表格末尾（不覆盖已有行）。
     * 传 id 只追加这一条；不传则追加尚未出现在 Wiki 上的全部记录。
     */
    @PostMapping("/sync-wiki")
    public ResponseEntity<?> syncWiki(@RequestParam(required = false) String id) {
        try {
            return ResponseEntity.ok(
                    id != null && !id.isBlank()
                            ? issueTrackingWikiService.syncOne(id.trim())
                            : issueTrackingWikiService.sync());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        Map<String, Object> row = issueTrackingService.getById(id);
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(row);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(Map.of("id", issueTrackingService.create(body)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return issueTrackingService.update(id, body)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return issueTrackingService.delete(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    private String store(byte[] bytes, String ext) throws IOException {
        Path root = Paths.get(imageDir).toAbsolutePath().normalize();
        Path day = root.resolve(LocalDate.now().toString());
        Files.createDirectories(day);
        String name = UUID.randomUUID().toString().replace("-", "") + ext;
        Files.write(day.resolve(name), bytes);
        return "/issue-images/" + LocalDate.now() + "/" + name;
    }
}
