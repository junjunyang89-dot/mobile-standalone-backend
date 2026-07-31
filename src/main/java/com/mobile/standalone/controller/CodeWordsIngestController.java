package com.mobile.standalone.controller;

import com.mobile.standalone.service.AgentDocumentParseService;
import com.mobile.standalone.service.AgentStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 俊俊页文档/图片上传：解析文本或落盘图片，可选写入 knowledge。
 */
@RestController
@RequestMapping("/codewords")
@RequiredArgsConstructor
@Slf4j
public class CodeWordsIngestController {

    private final AgentDocumentParseService documentParseService;
    private final AgentStorageService agentStorageService;

    @Value("${app.agent-upload-dir:${user.home}/.mobile-standalone/uploads/agent-files}")
    private String uploadDir;

    @PostMapping(value = "/ingest-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> ingest(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "saveKnowledge", defaultValue = "true") boolean saveKnowledge) {
        try {
            if (files == null || files.length == 0) {
                return ResponseEntity.badRequest().body(Map.of("message", "请至少上传一个文件"));
            }
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(root);

            List<Map<String, Object>> items = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                String filename = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
                boolean asImage = AgentDocumentParseService.isImage(file);
                if (!asImage && !AgentDocumentParseService.isSupported(filename)
                        && !AgentDocumentParseService.isImageContentType(file.getContentType())) {
                    items.add(Map.of(
                            "filename", filename,
                            "ok", false,
                            "message", "不支持的格式"
                    ));
                    continue;
                }
                try {
                    if (asImage) {
                        items.add(handleImage(file, filename, root, saveKnowledge));
                    } else {
                        items.add(handleDocument(file, filename, saveKnowledge));
                    }
                } catch (Exception e) {
                    log.warn("[agent-ingest] 处理失败 {}: {}", filename, e.getMessage());
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("filename", filename);
                    one.put("ok", false);
                    one.put("message", e.getMessage() != null ? e.getMessage() : "解析失败");
                    items.add(one);
                }
            }
            long ok = items.stream().filter(m -> Boolean.TRUE.equals(m.get("ok"))).count();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", items.size());
            out.put("successCount", ok);
            out.put("items", items);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "上传失败"));
        }
    }

    private Map<String, Object> handleDocument(MultipartFile file, String filename, boolean saveKnowledge) {
        String text = documentParseService.extractText(file);
        String topic = AgentDocumentParseService.topicFromFilename(filename);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("filename", filename);
        one.put("kind", "document");
        one.put("topic", topic);
        one.put("size", file.getSize());
        one.put("chars", text.length());
        one.put("preview", text.length() > 300 ? text.substring(0, 300) + "…" : text);
        one.put("content", text);
        one.put("ok", true);
        if (saveKnowledge) {
            agentStorageService.saveKnowledge(topic, text);
            one.put("knowledgeStatus", "stored");
        }
        return one;
    }

    private Map<String, Object> handleImage(
            MultipartFile file, String filename, Path root, boolean saveKnowledge) throws Exception {
        String ext = AgentDocumentParseService.extension(filename);
        if (ext.isEmpty()) {
            String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
            if (ct.contains("png")) ext = "png";
            else if (ct.contains("jpeg") || ct.contains("jpg")) ext = "jpg";
            else if (ct.contains("gif")) ext = "gif";
            else if (ct.contains("webp")) ext = "webp";
            else ext = "png";
            filename = AgentDocumentParseService.topicFromFilename(filename) + "." + ext;
        }
        String stored = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                + "." + ext;
        Path target = root.resolve(stored).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        Files.copy(file.getInputStream(), target);

        String url = "/agent-uploads/" + stored;
        String topic = AgentDocumentParseService.topicFromFilename(filename);
        String content = "[图片附件] " + filename + "\n本地访问路径: " + url
                + "\n说明: 用户从俊俊对话框粘贴/上传的截图，请结合对话理解画面内容。";

        Map<String, Object> one = new LinkedHashMap<>();
        one.put("filename", filename);
        one.put("kind", "image");
        one.put("topic", topic);
        one.put("size", file.getSize());
        one.put("chars", content.length());
        one.put("url", url);
        one.put("preview", "图片已保存: " + filename);
        one.put("content", content);
        one.put("ok", true);
        if (saveKnowledge) {
            agentStorageService.saveKnowledge(topic, content);
            one.put("knowledgeStatus", "stored");
        }
        return one;
    }
}
