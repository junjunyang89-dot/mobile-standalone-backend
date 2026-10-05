package com.mobile.standalone.controller;

import com.mobile.standalone.service.DailyDashboardService;
import com.mobile.standalone.service.WorkbenchStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 工作台看板：领导/执行视角统计卡片。
 */
@RestController
@RequestMapping("/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final WorkbenchStatsService workbenchStatsService;
    private final DailyDashboardService dailyDashboardService;

    /** GET /workbench/stats */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(workbenchStatsService.getStats());
    }

    /** GET /workbench/daily?date=2026-10-04：这一天各类测试跑了多少、加了多少用例，外加所在那一周的概况 */
    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> daily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(dailyDashboardService.day(date));
    }
}
