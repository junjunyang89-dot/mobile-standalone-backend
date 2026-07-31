package com.mobile.standalone.controller;

import com.mobile.standalone.dto.ReleaseDocDtos.GenerateRequest;
import com.mobile.standalone.dto.ReleaseDocDtos.GenerateResult;
import com.mobile.standalone.dto.ReleaseDocDtos.MailDraftRequest;
import com.mobile.standalone.dto.ReleaseDocDtos.SendMailRequest;
import com.mobile.standalone.service.ReleaseDocService;
import com.mobile.standalone.service.ReleaseMailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 发版文档：按版本从 Wiki 汇总 JIRA → 生成 Confluence 表格页；发版邮件草稿与发送。
 */
@RestController
@RequestMapping("/release-doc")
@RequiredArgsConstructor
public class ReleaseDocController {

    private final ReleaseDocService releaseDocService;
    private final ReleaseMailService releaseMailService;

    @GetMapping("/ping")
    public ResponseEntity<?> ping() {
        try {
            String html = releaseDocService.pingWiki();
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "Wiki 连通正常",
                    "snippetLength", html != null ? html.length() : 0
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    /** 仅预览抽取结果，不创建 Wiki 页 */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody GenerateRequest req) {
        try {
            req.setPreviewOnly(true);
            return ResponseEntity.ok(releaseDocService.generate(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 抽取并创建发版文档 Wiki 子页面 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest req) {
        try {
            req.setPreviewOnly(false);
            GenerateResult result = releaseDocService.generate(req);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 按模版生成发版邮件草稿（HTML） */
    @PostMapping("/mail/draft")
    public ResponseEntity<?> mailDraft(@RequestBody MailDraftRequest req) {
        try {
            return ResponseEntity.ok(releaseMailService.draft(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 发送发版邮件 */
    @PostMapping("/mail/send")
    public ResponseEntity<?> mailSend(@RequestBody SendMailRequest req) {
        try {
            return ResponseEntity.ok(releaseMailService.send(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }
}
