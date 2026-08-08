package com.mobile.standalone.controller;

import com.mobile.standalone.dto.ChangeReviewDtos.DiffRequest;
import com.mobile.standalone.dto.ChangeReviewDtos.ImportBranchesRequest;
import com.mobile.standalone.service.ChangeReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 周改动漏测看板：本地多仓 git diff（只读）+ 必测清单对照 + 发版分支/SQL 备注解析。
 */
@RestController
@RequestMapping("/change-review")
@RequiredArgsConstructor
public class ChangeReviewController {

    private final ChangeReviewService changeReviewService;

    @GetMapping("/ping")
    public ResponseEntity<?> ping() {
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "message", "change-review ready（本地 git diff，需本机已 clone 仓库）"
        ));
    }

    /** 多项目 base...head diff，并可选对照必测清单 / 发版 DB 备注 */
    @PostMapping("/diff")
    public ResponseEntity<?> diff(@RequestBody DiffRequest req) {
        try {
            return ResponseEntity.ok(changeReviewService.analyze(req));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** 从发版文档校对行解析「仓库: 分支」与 DB 备注汇总 */
    @PostMapping("/import-branches")
    public ResponseEntity<?> importBranches(@RequestBody ImportBranchesRequest req) {
        try {
            return ResponseEntity.ok(changeReviewService.parseReleaseBranches(req));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }
}
