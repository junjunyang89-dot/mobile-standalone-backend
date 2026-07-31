package com.mobile.standalone.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一截图命名：{场景英文}_{taskId}_{日期}_step{N}.png
 * 例如：login_040e3fd3-a064-423a-ac82-174e353d17ea_20250221_143052_step0.png
 * 前端按 taskId 匹配展示。
 */
public final class ScreenshotNamingHelper {

    private static final String SCREENSHOTS_DIR = "/tmp/screenshots";
    private static final DateTimeFormatter DATE_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ScreenshotNamingHelper() {}

    /**
     * 构建步骤截图路径。格式：{scenario}_{taskId}_{yyyyMMdd_HHmmss}_step{N}.png
     *
     * @param scenarioName 场景英文名，如 login、search、search_tabs、settings
     * @param taskId       任务 ID
     * @param stepIndex    步骤序号（从 0 起）
     * @return 完整文件路径
     */
    public static String buildStepPath(String scenarioName, String taskId, int stepIndex) {
        String id = (taskId != null && !taskId.isBlank()) ? taskId : "unknown";
        String dateSuffix = LocalDateTime.now().format(DATE_SUFFIX);
        return SCREENSHOTS_DIR + "/" + scenarioName + "_" + id + "_" + dateSuffix + "_step" + stepIndex + ".png";
    }
}
