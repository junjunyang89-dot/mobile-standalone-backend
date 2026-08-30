package com.mobile.standalone.controller;

import com.mobile.standalone.service.RequirementBugService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 测出来的 Bug。
 * <p>
 * 字段照着 wiki 那张表定的，导出时一键粘过去——
 * 列对不上的话搬一次要逐条重填，人就不会在平台记了。
 */
@Slf4j
@RestController
@RequestMapping("/requirement-bugs")
@RequiredArgsConstructor
public class RequirementBugController {

    private final RequirementBugService bugService;

    @Value("${app.bug-image-dir:${user.home}/.mobile-standalone/uploads/bugs}")
    private String imageDir;

    private static final Set<String> ALLOWED_EXT = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String requirementNo) {
        return ResponseEntity.ok(bugService.listByRequirement(requirementNo));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam String requirementNo) {
        return ResponseEntity.ok(bugService.summaryOf(requirementNo));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(Map.of("id", bugService.create(body)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return bugService.update(id, body)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return bugService.delete(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    /**
     * 导成 wiki 能粘的 HTML 表格。
     * <p>
     * 省掉的是「一条条重敲九列」。<b>截图粘不过去</b>——图走的是本机地址，
     * wiki 服务器访问不到，所以表里只列文件名，图还得手动拖。
     * 这一点在返回里说清楚，比让人贴完才发现全是裂图强。
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestParam String requirementNo) {
        return ResponseEntity.ok(Map.of(
                "html", bugService.toWikiHtml(requirementNo),
                "note", "粘到 wiki 后表格结构会保留。截图不会跟着过去（图在本机，wiki 访问不到），"
                        + "需要手动拖一次"));
    }

    /** 截图。一个 bug 常常要好几张（问题现场 + 原型对照），所以支持多传 */
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

    /** 按日期分目录：一个版本几十张图，全堆一层的话备份和清理都没法做 */
    private String store(byte[] bytes, String ext) throws IOException {
        Path root = Paths.get(imageDir).toAbsolutePath().normalize();
        Path day = root.resolve(LocalDate.now().toString());
        Files.createDirectories(day);
        String name = UUID.randomUUID().toString().replace("-", "") + ext;
        Files.write(day.resolve(name), bytes);
        return "/bug-images/" + LocalDate.now() + "/" + name;
    }
}
