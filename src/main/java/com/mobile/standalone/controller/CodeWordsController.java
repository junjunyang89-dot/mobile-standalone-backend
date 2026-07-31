package com.mobile.standalone.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mobile.standalone.service.AgentStorageService;
import com.mobile.standalone.service.CodeWordsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 俊俊 AI：平台前端 → 本后端代理 → CodeWords Agent（外部智能）。
 * 知识/对话仍存本地库；不替代平台其它业务能力。
 */
@RestController
@RequestMapping("/codewords")
@RequiredArgsConstructor
public class CodeWordsController {

    private final CodeWordsService codeWordsService;
    private final AgentStorageService agentStorageService;

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
