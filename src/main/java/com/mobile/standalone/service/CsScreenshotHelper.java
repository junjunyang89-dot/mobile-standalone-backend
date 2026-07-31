package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.driver.PlatformDriver;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * CS 相关测试的截图辅助：节流 + 重试，避免连续快速截图导致设备过载失败。
 */
@Slf4j
public final class CsScreenshotHelper {

    private static final long MIN_INTERVAL_MS = 800;
    private static final long PRE_SCREENSHOT_MS = 500;
    private static final long RETRY_DELAY_MS = 500;

    private static final ThreadLocal<Long> LAST_SCREENSHOT_TIME = new ThreadLocal<>();

    private CsScreenshotHelper() {}

    /**
     * 为 executionLog 最后一条截屏并写入路径。
     * 节流：距上次截图至少 800ms；截图前等待 500ms；失败时重试一次。
     */
    public static void takeStepScreenshot(PlatformDriver driver, String deviceId, Task task,
            List<ExecutionLogEntryDto> logs, String scenarioName) {
        if (driver == null || logs == null || logs.isEmpty()) return;
        try {
            Long last = LAST_SCREENSHOT_TIME.get();
            if (last != null) {
                long elapsed = System.currentTimeMillis() - last;
                if (elapsed < MIN_INTERVAL_MS) {
                    Thread.sleep(MIN_INTERVAL_MS - elapsed);
                }
            }
            Thread.sleep(PRE_SCREENSHOT_MS);

            int stepIndex = logs.size() - 1;
            String path = ScreenshotNamingHelper.buildStepPath(scenarioName, task != null ? task.getId() : null, stepIndex);
            String saved = driver.takeScreenshot(deviceId, path);
            if (saved == null) {
                Thread.sleep(RETRY_DELAY_MS);
                saved = driver.takeScreenshot(deviceId, path);
            }
            if (saved != null) {
                LAST_SCREENSHOT_TIME.set(System.currentTimeMillis());
                logs.get(stepIndex).setScreenshotPath(saved);
            } else {
                log.warn("步骤截图失败 step={} path={}", stepIndex, path);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("步骤截图失败: {}", e.getMessage());
        }
    }

    /**
     * 为指定索引的日志条目截屏（用于开头、结尾等固定位置）。
     */
    public static void takeScreenshotForEntry(PlatformDriver driver, String deviceId, Task task,
            List<ExecutionLogEntryDto> logs, String scenarioName, int entryIndex) {
        if (driver == null || logs == null || entryIndex < 0 || entryIndex >= logs.size()) return;
        try {
            Long last = LAST_SCREENSHOT_TIME.get();
            if (last != null) {
                long elapsed = System.currentTimeMillis() - last;
                if (elapsed < MIN_INTERVAL_MS) {
                    Thread.sleep(MIN_INTERVAL_MS - elapsed);
                }
            }
            Thread.sleep(PRE_SCREENSHOT_MS);

            String path = ScreenshotNamingHelper.buildStepPath(scenarioName, task != null ? task.getId() : null, entryIndex);
            String saved = driver.takeScreenshot(deviceId, path);
            if (saved == null) {
                Thread.sleep(RETRY_DELAY_MS);
                saved = driver.takeScreenshot(deviceId, path);
            }
            if (saved != null) {
                LAST_SCREENSHOT_TIME.set(System.currentTimeMillis());
                logs.get(entryIndex).setScreenshotPath(saved);
            } else {
                log.warn("步骤截图失败 entryIndex={} path={}", entryIndex, path);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("步骤截图失败: {}", e.getMessage());
        }
    }
}
