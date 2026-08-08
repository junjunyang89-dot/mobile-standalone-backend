package com.mobile.standalone.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.AgentDocumentParseService;
import com.mobile.standalone.service.AgentStorageService;
import com.mobile.standalone.service.CodeWordsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 俊俊 AI：平台前端 → 本后端代理 → CodeWords Agent（外部智能）。
 * 知识/对话仍存本地库；不替代平台其它业务能力。
 */
@RestController
@RequestMapping("/codewords")
@RequiredArgsConstructor
public class CodeWordsController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CodeWordsService codeWordsService;
    private final AgentStorageService agentStorageService;
    private final AgentDocumentParseService documentParseService;

    /** 配置状态（不返回密钥） */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(codeWordsService.status());
        m.put("knowledgeCount", agentStorageService.knowledgeCount());
        m.put("storageReady", true);
        return ResponseEntity.ok(m);
    }

    /**
     * 平台前端读知识库（不走 STORAGE_API_KEY，避免列表空白）。
     * CodeWords 云端仍用 /api/knowledge 并带密钥。
     */
    @GetMapping("/knowledge")
    public ResponseEntity<?> listKnowledge(
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            return ResponseEntity.ok(agentStorageService.searchKnowledge(query, limit));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "加载失败"));
        }
    }

    /** 平台前端写入知识（对话里「新增知识条目」） */
    @PostMapping("/knowledge")
    public ResponseEntity<?> saveKnowledge(@RequestBody Map<String, String> body) {
        try {
            String topic = body == null ? null : body.get("topic");
            String content = body == null ? null : body.get("content");
            return ResponseEntity.ok(agentStorageService.saveKnowledge(topic, content));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "保存失败"));
        }
    }

    /** 平台前端读会话 */
    @GetMapping("/conversation")
    public ResponseEntity<?> loadConversation(@RequestParam(name = "session_id") String sessionId) {
        try {
            return ResponseEntity.ok(agentStorageService.loadConversation(sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "加载失败"));
        }
    }

    /** 平台前端存会话 */
    @PostMapping("/conversation")
    public ResponseEntity<?> saveConversation(@RequestBody Map<String, Object> body) {
        try {
            String sessionId = body == null ? null : String.valueOf(body.getOrDefault("session_id", ""));
            Object messages = body == null ? null : body.get("messages");
            return ResponseEntity.ok(agentStorageService.saveConversation(sessionId, messages));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "保存失败"));
        }
    }

    /**
     * 调用 AI Agent（workflow_skeleton_85e9599e）
     * body: { "message": "...", "sessionId": "user_yang" }
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "请求体不能为空"));
            }
            JsonNode result = codeWordsService.chat(body.getMessage(), body.getSessionId());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "调用失败"));
        }
    }

    /**
     * 直接调用元素监控工作流（csgo_element_monitor_b69770d5）
     * body: { "url": "https://...", "schedule": true }
     */
    @PostMapping("/monitor")
    public ResponseEntity<?> monitor(@RequestBody MonitorRequest body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "请求体不能为空"));
            }
            JsonNode result = codeWordsService.monitor(body.getUrl(), body.getSchedule());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "调用失败"));
        }
    }

    /**
     * 测试用例生成器 v2（JSON）：
     * images 可为 data URI，或 /agent-uploads/xxx（服务端读盘转 base64，避免 ngrok/公网拉不到图）。
     */
    @PostMapping("/test-cases/generate")
    public ResponseEntity<?> generateTestCases(@RequestBody Map<String, Object> body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "请求体不能为空"));
            }
            Object pageObj = body.get("page_name") != null ? body.get("page_name") : body.get("pageName");
            String pageName = pageObj == null ? null : String.valueOf(pageObj);
            Object elementsObj = body.get("elements");
            JsonNode elements = elementsObj == null ? null : JSON.valueToTree(elementsObj);
            Object imagesObj = body.get("images");
            JsonNode images = imagesObj == null ? null : JSON.valueToTree(imagesObj);
            String screenshotUrl = body.get("screenshot_url") != null
                    ? String.valueOf(body.get("screenshot_url"))
                    : (body.get("screenshotUrl") != null ? String.valueOf(body.get("screenshotUrl")) : null);
            String requirements = body.get("requirements") != null ? String.valueOf(body.get("requirements")) : null;
            Object textObj = body.get("text_content") != null ? body.get("text_content") : body.get("textContent");
            String textContent = textObj == null ? null : String.valueOf(textObj);
            JsonNode result = codeWordsService.generateTestCases(
                    pageName, elements, screenshotUrl, requirements, textContent, images);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "生成失败"));
        }
    }

    /**
     * 测试用例生成器：multipart 上传。
     * form: page_name, text_content?, requirements?, files（图片→base64；文档→抽文字）
     */
    @PostMapping(value = "/test-cases/generate-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> generateTestCasesUpload(
            @RequestParam(value = "page_name", required = false) String pageName,
            @RequestParam(value = "pageName", required = false) String pageNameCamel,
            @RequestParam(value = "text_content", required = false) String textContent,
            @RequestParam(value = "requirements", required = false) String requirements,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        try {
            String page = pageName != null && !pageName.isBlank() ? pageName : pageNameCamel;
            List<MultipartFile> all = new ArrayList<>();
            if (files != null) {
                for (MultipartFile f : files) {
                    if (f != null && !f.isEmpty()) all.add(f);
                }
            }
            if (file != null && !file.isEmpty()) all.add(file);

            StringBuilder text = new StringBuilder();
            if (textContent != null && !textContent.isBlank()) {
                text.append(textContent.trim());
            }
            List<MultipartFile> imageFiles = new ArrayList<>();
            for (MultipartFile f : all) {
                String name = f.getOriginalFilename() == null ? "file" : f.getOriginalFilename();
                if (AgentDocumentParseService.isImage(f)
                        || AgentDocumentParseService.isImageContentType(f.getContentType())) {
                    imageFiles.add(f);
                } else if (AgentDocumentParseService.isSupported(name)) {
                    String extracted = documentParseService.extractText(f);
                    if (extracted != null && !extracted.isBlank()) {
                        if (text.length() > 0) text.append("\n\n");
                        text.append(extracted);
                    }
                } else {
                    imageFiles.add(f);
                }
            }

            JsonNode result = codeWordsService.generateTestCasesFromUpload(
                    page,
                    text.length() == 0 ? null : text.toString(),
                    requirements,
                    imageFiles.toArray(new MultipartFile[0])
            );
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "生成失败"));
        }
    }

    @Data
    public static class ChatRequest {
        private String message;
        /** 会话 ID，同会话可保持上下文；默认 user_yang */
        private String sessionId;
    }

    @Data
    public static class MonitorRequest {
        private String url;
        /** 是否开启定时监控，默认 true */
        private Boolean schedule;
    }
}
