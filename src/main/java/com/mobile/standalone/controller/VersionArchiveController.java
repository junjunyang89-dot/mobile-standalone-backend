package com.mobile.standalone.controller;

import com.mobile.standalone.dto.VersionArchiveDtos.CloseRequest;
import com.mobile.standalone.dto.VersionArchiveDtos.SyncRequest;
import com.mobile.standalone.service.VersionArchiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/version-archives")
@RequiredArgsConstructor
public class VersionArchiveController {

    private final VersionArchiveService versionArchiveService;

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(versionArchiveService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        var d = versionArchiveService.get(id);
        if (d == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(d);
    }

    /** 从 Wiki 汇总页同步某版本的 JIRA 单到本地归档 */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(@RequestBody SyncRequest req) {
        try {
            return ResponseEntity.ok(versionArchiveService.sync(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /**
     * 批量关单。默认 dryRun；真正关闭需 confirm=true 且 dryRun 不为 true。
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<?> close(@PathVariable String id, @RequestBody(required = false) CloseRequest req) {
        try {
            if (req == null) req = new CloseRequest();
            return ResponseEntity.ok(versionArchiveService.closeReleased(id, req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        boolean ok = versionArchiveService.delete(id);
        return ok ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.notFound().build();
    }

    /** 重新打开 JIRA 单到 Testing，并分配给指定 QA */
    @PostMapping("/jira/{issueKey}/reopen-assign")
    public ResponseEntity<?> reopenAssign(
            @PathVariable String issueKey,
            @RequestBody Map<String, String> body) {
        try {
            String username = body != null ? body.get("username") : null;
            String comment = body != null ? body.get("comment") : null;
            String result = versionArchiveService.reopenAndAssign(issueKey, username, comment);
            return ResponseEntity.ok(Map.of("ok", true, "message", result));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 批量：重开到 Testing 并分配 QA */
    @PostMapping("/jira/reopen-assign-batch")
    public ResponseEntity<?> reopenAssignBatch(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> keys = body != null && body.get("issueKeys") instanceof List<?>
                    ? ((List<?>) body.get("issueKeys")).stream().map(String::valueOf).toList()
                    : List.of();
            String username = body != null && body.get("username") != null
                    ? String.valueOf(body.get("username")) : null;
            String comment = body != null && body.get("comment") != null
                    ? String.valueOf(body.get("comment")) : null;
            List<String> logs = versionArchiveService.reopenAndAssignBatch(keys, username, comment);
            return ResponseEntity.ok(Map.of("ok", true, "logs", logs));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }
}
