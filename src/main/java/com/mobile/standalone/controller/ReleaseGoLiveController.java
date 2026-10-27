package com.mobile.standalone.controller;

import com.mobile.standalone.service.ReleaseGoLiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 发版上线看板：按项目汇总分支，分支级勾选已合并 / 已上线。
 */
@RestController
@RequestMapping("/release-go-live")
@RequiredArgsConstructor
public class ReleaseGoLiveController {

    private final ReleaseGoLiveService releaseGoLiveService;

    @GetMapping("/board")
    public ResponseEntity<?> board(@RequestParam String wikiUrl) {
        try {
            return ResponseEntity.ok(releaseGoLiveService.board(wikiUrl));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @PutMapping("/state")
    public ResponseEntity<?> saveState(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(releaseGoLiveService.saveState(
                    str(body, "wikiPageId"),
                    str(body, "project"),
                    str(body, "branch"),
                    bool(body, "mergedMaster"),
                    bool(body, "released")));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 每日发版校对：日期 + 项目：分支列表 → 待合并 / Jenkins / 待上线 */
    @PostMapping("/daily")
    public ResponseEntity<?> daily(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(releaseGoLiveService.dailyChecklist(
                    str(body, "date"),
                    str(body, "text")));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 再核对一次该分支是否已在 master（结果写回看板） */
    @PostMapping("/verify-master")
    public ResponseEntity<?> verifyMaster(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(releaseGoLiveService.verifyMaster(
                    str(body, "wikiPageId"),
                    str(body, "project"),
                    str(body, "branch")));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @PostMapping("/state/reset")
    public ResponseEntity<?> reset(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(releaseGoLiveService.resetStates(str(body, "wikiPageId")));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        return String.valueOf(body.get(key));
    }

    private static Boolean bool(Map<String, Object> body, String key) {
        if (body == null || !body.containsKey(key) || body.get(key) == null) return null;
        Object v = body.get(key);
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }
}
