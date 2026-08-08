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
import java.util.stream.Stream;

/**
 * 扫描任务截图：
 * 1) 流程执行：{@code ~/.mobile-standalone/screenshots/{taskId}/step-*.png}
 * 2) 旧功能体验：{@code /tmp/screenshots/*_{taskId}_*_stepN.png}
 */
public final class ScreenshotDiscoveryService {

    private static final Path TMP_SCREENSHOTS_DIR = Paths.get("/tmp/screenshots").normalize().toAbsolutePath();
    private static final Path FLOW_SCREENSHOTS_DIR = Paths.get(System.getProperty("user.home"), ".mobile-standalone", "screenshots")
            .normalize().toAbsolutePath();
    /** 新格式: xxx_taskId_20250221_143052_step0.png */
    private static final Pattern NEW_FORMAT = Pattern.compile("^.+_step(\\d+)\\.png$");
    /** 旧格式: xxx_taskId_step0_20250221.png */
    private static final Pattern OLD_FORMAT = Pattern.compile("^.+_step(\\d+)_.+\\.png$");
    /** 流程: step-1785596987763.png（时间戳，按文件名排序） */
    private static final Pattern FLOW_STEP = Pattern.compile("^step-(\\d+)\\.png$");

    private ScreenshotDiscoveryService() {}

    public static List<ExecutionLogEntryDto> discoverStepScreenshots(String taskId) {
        if (taskId == null || taskId.isBlank()) return List.of();
        String id = taskId.trim();
        List<Entry> entries = new ArrayList<>();
        entries.addAll(discoverFlowDir(id));
        entries.addAll(discoverTmpDir(id));
        if (entries.isEmpty()) return List.of();

        entries.sort(Comparator
                .comparingInt((Entry e) -> e.scenarioOrder)
                .thenComparingLong(e -> e.sortKey)
                .thenComparing(e -> e.fileName));
        List<ExecutionLogEntryDto> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            ExecutionLogEntryDto dto = new ExecutionLogEntryDto();
            dto.setStep(e.label != null ? e.label : ("截图 " + (i + 1)));
            dto.setLevel("Info");
            dto.setStatus("success");
            dto.setMessage(e.fileName);
            dto.setScreenshotPath(e.path);
            result.add(dto);
        }
        return result;
    }

    /** 流程执行器写入：~/.mobile-standalone/screenshots/{taskId}/step-*.png */
    private static List<Entry> discoverFlowDir(String taskId) {
        Path dir = FLOW_SCREENSHOTS_DIR.resolve(taskId);
        if (!Files.isDirectory(dir)) return List.of();
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        Matcher m = FLOW_STEP.matcher(name);
                        long sortKey = m.matches() ? Long.parseLong(m.group(1)) : p.toFile().lastModified();
                        out.add(new Entry(sortKey, 0, p.toAbsolutePath().normalize().toString(), name, "流程截图"));
                    });
        } catch (IOException ignored) {
            return List.of();
        }
        return out;
    }

    private static List<Entry> discoverTmpDir(String taskId) {
        if (!Files.isDirectory(TMP_SCREENSHOTS_DIR)) return List.of();
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(TMP_SCREENSHOTS_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".png") && (name.contains("_" + taskId + "_") || name.contains("_" + taskId + "."));
                    })
                    .forEach(p -> {
                        String pathStr = p.toAbsolutePath().normalize().toString();
                        String fileName = p.getFileName().toString();
                        int stepNum = parseStepNumber(pathStr, fileName);
                        out.add(new Entry(stepNum, scenarioOrder(fileName), pathStr, fileName, "步骤 " + stepNum));
                    });
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    private static int parseStepNumber(String pathStr, String fileName) {
        Matcher m = NEW_FORMAT.matcher(fileName);
        if (m.matches()) return Integer.parseInt(m.group(1));
        m = OLD_FORMAT.matcher(fileName);
        if (m.matches()) return Integer.parseInt(m.group(1));
        m = NEW_FORMAT.matcher(pathStr);
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
        final long sortKey;
        final int scenarioOrder;
        final String path;
        final String fileName;
        final String label;

        Entry(long sortKey, int scenarioOrder, String path, String fileName, String label) {
            this.sortKey = sortKey;
            this.scenarioOrder = scenarioOrder;
            this.path = path;
            this.fileName = fileName;
            this.label = label;
        }
    }
}
