package com.mobile.standalone.controller;

import com.mobile.standalone.service.CursorAskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 主菜单「AI助手」：对话走本机 Cursor SDK，不再走千问。
 */
@RestController
@RequestMapping("/cursor-ask")
@RequiredArgsConstructor
public class CursorAskController {

    private final CursorAskService cursorAskService;

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(cursorAskService.status());
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> body) {
        try {
            String message = str(body.get("message"));
            String sessionId = str(body.get("sessionId"));
            String cwd = str(body.get("cwd"));
            String mode = str(body.get("mode"));
            return ResponseEntity.ok(cursorAskService.chat(message, sessionId, cwd, mode));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() == null ? "调用失败" : e.getMessage()));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset(@RequestBody(required = false) Map<String, Object> body) {
        cursorAskService.reset(
                body == null ? null : str(body.get("sessionId")),
                body == null ? null : str(body.get("mode")));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }
}
