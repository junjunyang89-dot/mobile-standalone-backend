package com.mobile.standalone.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mobile.standalone.service.AgentStorageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 本地对话存储（平台自有库 agent_conversations）。
 * CodeWords 配置 STORAGE_API_URL 后可回调；俊俊页也会读写会话。
 */
@RestController
@RequestMapping("/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final AgentStorageService agentStorageService;

    /** POST {"session_id":"...","messages":[...]} */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody ConversationRequest body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "请求体不能为空"));
            }
            return ResponseEntity.ok(agentStorageService.saveConversation(body.getSessionId(), body.getMessages()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "保存失败"));
        }
    }

    /** GET ?session_id=xxx → {"messages":[...]} */
    @GetMapping
    public ResponseEntity<?> load(@RequestParam(name = "session_id") String sessionId) {
        try {
            return ResponseEntity.ok(agentStorageService.loadConversation(sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "加载失败"));
        }
    }

    @Data
    public static class ConversationRequest {
        @JsonProperty("session_id")
        private String sessionId;
        private Object messages;
    }
}
