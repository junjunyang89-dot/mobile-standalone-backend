package com.mobile.standalone.controller;

import com.mobile.standalone.service.ScreenshotRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 截图产物的占用查询与清理。清理是删文件的操作，默认走定时任务；手动触发默认只做试运行。
 */
@RestController
@RequestMapping("/screenshot-storage")
@RequiredArgsConstructor
@Slf4j
public class ScreenshotStorageController {

    private final ScreenshotRetentionService retentionService;

    /** 当前占用：总量、任务目录数、最旧多少天、占上限比例 */
    @GetMapping("/usage")
    public ResponseEntity<ScreenshotRetentionService.UsageStat> usage() {
        return ResponseEntity.ok(retentionService.usage());
    }

    /**
     * 触发清理。
     * <p>
     * 默认 {@code dryRun=true} 只统计不删除——删文件不可撤销，先看清楚会删掉什么再决定。
     * 真要删请显式传 {@code dryRun=false}。
     */
    @PostMapping("/cleanup")
    public ResponseEntity<ScreenshotRetentionService.CleanupResult> cleanup(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        if (!dryRun) {
            log.warn("手动触发截图清理（将实际删除文件）");
        }
        return ResponseEntity.ok(retentionService.cleanup(dryRun));
    }
}
