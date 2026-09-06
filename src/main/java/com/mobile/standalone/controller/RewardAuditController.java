package com.mobile.standalone.controller;

import com.mobile.standalone.service.webtest.RewardOrderAudit;
import com.mobile.standalone.service.webtest.RewardPriorityChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 活动页奖励顺序校验：从抓包读奖励接口，自动抽任务奖品、按优先级校验。
 * 人只需选一场抓包，不用抄任何东西。
 */
@RestController
@RequestMapping("/reward-audit")
@RequiredArgsConstructor
public class RewardAuditController {

    private final RewardOrderAudit audit;

    /**
     * @param body sessionId 抓包会话；ladder 优先级阶梯（可选，二维数组，内层同级）；
     *             aliases 页面名→类目 的人工映射（可选）
     */
    @PostMapping
    public ResponseEntity<?> audit(@RequestBody Map<String, Object> body) {
        String sessionId = str(body.get("sessionId"));
        if (sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请选择一场抓包会话"));
        }
        RewardPriorityChecker.Ladder ladder = parseLadder(body.get("ladder"));
        @SuppressWarnings("unchecked")
        Map<String, String> aliases = body.get("aliases") instanceof Map
                ? (Map<String, String>) body.get("aliases") : Map.of();
        return ResponseEntity.ok(audit.auditFromCapture(sessionId, ladder, aliases));
    }

    @SuppressWarnings("unchecked")
    private RewardPriorityChecker.Ladder parseLadder(Object raw) {
        if (!(raw instanceof List<?> outer) || outer.isEmpty()) {
            return null;
        }
        List<List<String>> tiers = new ArrayList<>();
        for (Object tier : outer) {
            List<String> row = new ArrayList<>();
            if (tier instanceof List<?> l) {
                l.forEach(x -> row.add(String.valueOf(x)));
            } else {
                row.add(String.valueOf(tier));
            }
            if (!row.isEmpty()) tiers.add(row);
        }
        return tiers.isEmpty() ? null : new RewardPriorityChecker.Ladder(tiers);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
