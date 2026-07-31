package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 /tmp/screenshots/ 扫描与 taskId 匹配的截图文件。
 * 文件名格式：{场景英文}_{taskId}_{日期}_step{N}.png 或旧格式 {场景}_{taskId}_step{N}_{日期}.png
 * 只要文件名包含 taskId 即视为该任务的截图，供前端展示。
 */
public final class ScreenshotDiscoveryService {

    private static final Path SCREENSHOTS_DIR = Paths.get("/tmp/screenshots").normalize().toAbsolutePath();
    /** 新格式: xxx_taskId_20250221_143052_step0.png */
    private static final Pattern NEW_FORMAT = Pattern.compile("^.+_step(\\d+)\\.png$");
    /** 旧格式: xxx_taskId_step0_20250221.png 或 xxx_taskId_step0_1234567890.png */
    private static final Pattern OLD_FORMAT = Pattern.compile("^.+/step(\\d+)_.+\\.png$");

    private ScreenshotDiscoveryService() {}

    /**
     * 扫描任务对应的步骤截图。匹配规则：文件名包含 taskId（如 _taskId_ 或 _taskId.）即视为该任务截图。
     * 支持 login、search、search_tabs、settings、settings_account 等所有场景。
     */
    public static List<ExecutionLogEntryDto> discoverStepScreenshots(String taskId) {
        if (taskId == null || taskId.isBlank()) return List.of();
        if (!Files.isDirectory(SCREENSHOTS_DIR)) return List.of();

        String id = taskId.trim();
        List<Entry> entries = new ArrayList<>();

        try {
            Files.list(SCREENSHOTS_DIR)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".png") && (name.contains("_" + id + "_") || name.contains("_" + id + "."));
                    })
                    .forEach(p -> {
                        String pathStr = p.toAbsolutePath().normalize().toString();
                        String fileName = p.getFileName().toString();
                        int stepNum = parseStepNumber(pathStr, fileName);
                        entries.add(new Entry(stepNum, pathStr, fileName));
                    });
        } catch (IOException e) {
            return List.of();
        }

        if (entries.isEmpty()) return List.of();

        entries.sort(Comparator
                .comparingInt((Entry e) -> scenarioOrder(e.fileName))
                .thenComparingInt(e -> e.stepNum)
                .thenComparing(e -> e.fileName));
        List<ExecutionLogEntryDto> result = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            ExecutionLogEntryDto dto = new ExecutionLogEntryDto();
            dto.setStep("步骤 " + e.stepNum);
            dto.setLevel("Info");
            dto.setStatus("success");
            dto.setMessage("截图");
            dto.setScreenshotPath(e.path);
            result.add(dto);
        }
        return result;
    }

    private static int parseStepNumber(String pathStr, String fileName) {
        Matcher m = NEW_FORMAT.matcher(pathStr);
        if (m.matches()) return Integer.parseInt(m.group(1));
        m = OLD_FORMAT.matcher(pathStr);
        if (m.matches()) return Integer.parseInt(m.group(1));
        return 0;
    }

    private static int scenarioOrder(String fileName) {
        if (fileName.startsWith("login_")) return 0;
        if (fileName.startsWith("search_tabs_")) return 2;
        if (fileName.startsWith("search_user_follow_")) return 3;
        if (fileName.startsWith("search_")) return 1;
        if (fileName.startsWith("cs_hot_comment_content_jump_")) return 8;
        if (fileName.startsWith("cs_comment_hot_content_")) return 9;
        if (fileName.startsWith("cs_hot_comment_to_profile_")) return 10;
        if (fileName.startsWith("cs_hot_comment_like_")) return 11;
        if (fileName.startsWith("cs_hot_comment_browse_")) return 12;
        if (fileName.startsWith("settings_account_")) return 5;
        if (fileName.startsWith("settings_interests_")) return 6;
        if (fileName.startsWith("settings_school_cert_")) return 7;
        if (fileName.startsWith("settings_")) return 4;
        if (fileName.startsWith("cs_video_browse_")) return 13;
        if (fileName.startsWith("cs_video_play_like_comment_share_")) return 14;
        if (fileName.startsWith("cs_activity_browse_")) return 15;
        if (fileName.startsWith("cs_event_schedule_browse_")) return 16;
        if (fileName.startsWith("cs_ended_match_detail_data_")) return 17;
        if (fileName.startsWith("cs_ended_match_detail_analysis_")) return 18;
        if (fileName.startsWith("launch_")) return -1;
        return 20;
    }

    private static class Entry {
        final int stepNum;
        final String path;
        final String fileName;

        Entry(int stepNum, String path, String fileName) {
            this.stepNum = stepNum;
            this.path = path;
            this.fileName = fileName;
        }
    }
}
