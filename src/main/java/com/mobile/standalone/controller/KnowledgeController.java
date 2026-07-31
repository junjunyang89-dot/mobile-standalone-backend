package com.mobile.standalone.controller;

import com.mobile.standalone.service.AgentStorageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 本地知识库 API（平台自有库 knowledge）。
 * CodeWords Agent 在配置 STORAGE_API_URL=http://本机:3000 后会回调本接口存/搜知识；
 * 前端俊俊页也可直接读本接口展示知识条数。
 */
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final AgentStorageService agentStorageService;

    /** POST {"topic":"...","content":"..."} → {"status":"stored"} */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody KnowledgeRequest body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "请求体不能为空"));
            }
            return ResponseEntity.ok(agentStorageService.saveKnowledge(body.getTopic(), body.getContent()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "保存失败"));
        }
    }

    /** GET ?query= → {"results":[{id,topic,snippet,created_at}],"count":N}，默认 5 条 */
    @GetMapping
    public ResponseEntity<?> search(
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false, defaultValue = "5") int limit) {
        try {
            return ResponseEntity.ok(agentStorageService.searchKnowledge(query, limit));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "搜索失败"));
        }
    }

    @Data
    public static class KnowledgeRequest {
        private String topic;
        private String content;
    }
}
