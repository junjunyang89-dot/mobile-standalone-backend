package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.driver.PlatformDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「等页面稳定后拍一张」的行为。
 * <p>
 * 原先是动作后每秒 1 张连拍 3 张：页面 4 秒才加载完就 3 张全是 loading，
 * 0.3 秒就好了则 3 张一样还白等 3 秒。交给 AI 做结果比对时这些噪声图是误判来源。
 */
class SettledScreenshotTest {

    private UiFlowExecutor executor;
    private ScriptedDriver driver;
    private List<ExecutionLogEntryDto> logs;
    private Task task;
    private Path screenshotDir;

    @BeforeEach
    void setUp() throws Exception {
        executor = new UiFlowExecutor(null, null, null, null, null, null,
                new ExecutionCancelRegistry(), null, null, null, null);
        screenshotDir = Files.createTempDirectory("settle-shot-test");
        ReflectionTestUtils.setField(executor, "screenshotDir", screenshotDir.toString());
        driver = new ScriptedDriver();
        logs = new ArrayList<>();
        task = new Task();
        task.setId("task-1");
    }

    /** 页面立刻稳定：只拍一张，并标记为最终态 */
    @Test
    void 页面稳定后只拍一张并打上标记() {
        driver.pageSources("home", "home", "home");

        executor.captureSettledScreenshot(driver, "device-1", task, logs, Map.of(), "1 · 点击");

        List<ExecutionLogEntryDto> shots = screenshots();
        assertEquals(1, shots.size(), "稳定后应只拍一张，实际 " + shots.size() + " 张");
        assertTrue(shots.get(0).getScreenshotSettled());
        assertTrue(shots.get(0).getMessage().contains("稳定"));
    }

    /** 快页面不该再白等 3 秒 */
    @Test
    void 快页面不付固定等待代价() {
        driver.pageSources("home", "home");

        long t0 = System.currentTimeMillis();
        executor.captureSettledScreenshot(driver, "device-1", task, logs, Map.of(), "1 · 点击");
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(elapsed < 2000, "页面已稳定不该久等，实际 " + elapsed + "ms");
    }

    /** 慢页面：前几次控件树还在变，要等到不变了才拍 */
    @Test
    void 页面还在变化时会继续等待() {
        driver.pageSources("loading1", "loading2", "loading3", "home", "home");

        executor.captureSettledScreenshot(driver, "device-1", task, logs,
                Map.of("settlePollMs", 100), "1 · 点击");

        List<ExecutionLogEntryDto> shots = screenshots();
        assertEquals(1, shots.size());
        assertTrue(shots.get(0).getScreenshotSettled());
        // 最后一次读到的应是稳定后的 home，而不是 loading 中间态
        assertEquals("home", driver.lastReturned);
    }

    /** 一直不稳定（如轮播动画）：超时后仍拍一张，但明确标记为「未稳定」 */
    @Test
    void 始终不稳定时超时拍照并标记未稳定() {
        driver.alwaysChanging = true;

        executor.captureSettledScreenshot(driver, "device-1", task, logs,
                Map.of("settleTimeoutMs", 600, "settlePollMs", 100), "1 · 点击");

        List<ExecutionLogEntryDto> shots = screenshots();
        assertEquals(1, shots.size());
        assertFalse(shots.get(0).getScreenshotSettled());
        assertTrue(shots.get(0).getMessage().contains("超时仍在变化"));
    }

    /** 驱动拿不到控件树时退化成固定短等待，不能因此不拍照 */
    @Test
    void 拿不到控件树时仍会拍照并标记未稳定() {
        driver.pageSourceUnavailable = true;

        executor.captureSettledScreenshot(driver, "device-1", task, logs, Map.of(), "1 · 点击");

        List<ExecutionLogEntryDto> shots = screenshots();
        assertEquals(1, shots.size());
        assertFalse(shots.get(0).getScreenshotSettled());
        assertTrue(shots.get(0).getMessage().contains("无控件树"));
    }

    /** 显式配了 screenshotRounds 的老流程仍走连拍，行为不变 */
    @Test
    void 显式配置连拍时保持原有行为() {
        driver.pageSources("home", "home");

        executor.captureSettledScreenshot(driver, "device-1", task, logs,
                Map.of("screenshotRounds", 3, "screenshotIntervalMs", 200), "1 · 点击");

        assertEquals(3, screenshots().size(), "显式连拍应拍满配置的张数");
        assertTrue(screenshots().stream().allMatch(e -> e.getScreenshotSettled() == null),
                "连拍出来的不是确定最终态，不应打稳定标记");
    }

    /** 收到停止请求时不再空等满超时 */
    @Test
    void 已请求停止时立即收尾() {
        ExecutionCancelRegistry registry = new ExecutionCancelRegistry();
        registry.register("task-1");
        registry.cancel("task-1");
        UiFlowExecutor cancelled = new UiFlowExecutor(null, null, null, null, null, null, registry, null, null, null, null);
        ReflectionTestUtils.setField(cancelled, "screenshotDir", screenshotDir.toString());
        driver.alwaysChanging = true;

        long t0 = System.currentTimeMillis();
        cancelled.captureSettledScreenshot(driver, "device-1", task, logs,
                Map.of("settleTimeoutMs", 30_000), "1 · 点击");
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(elapsed < 3000, "已请求停止不该等满超时，实际 " + elapsed + "ms");
    }

    private List<ExecutionLogEntryDto> screenshots() {
        return logs.stream().filter(e -> e.getScreenshotPath() != null).toList();
    }

    /** 按脚本依次返回控件树，用来模拟页面从加载中到稳定的过程 */
    private static class ScriptedDriver implements PlatformDriver {
        private final List<String> scripted = new ArrayList<>();
        private int idx = 0;
        private int changing = 0;
        boolean alwaysChanging = false;
        boolean pageSourceUnavailable = false;
        String lastReturned;

        void pageSources(String... sources) {
            Collections.addAll(scripted, sources);
        }

        @Override
        public String getPageSource(String deviceId) {
            if (pageSourceUnavailable) {
                return null;
            }
            if (alwaysChanging) {
                lastReturned = "changing-" + (changing++);
                return lastReturned;
            }
            String src = scripted.isEmpty()
                    ? "empty"
                    : scripted.get(Math.min(idx++, scripted.size() - 1));
            lastReturned = src;
            return src;
        }

        @Override
        public String takeScreenshot(String deviceId, String savePath) {
            return savePath;
        }

        @Override
        public boolean launchApp(String deviceId, String packageName) {
            return true;
        }

        @Override
        public boolean click(String deviceId, int x, int y) {
            return true;
        }

        @Override
        public boolean clickElement(String deviceId, String selector) {
            return true;
        }

        @Override
        public boolean swipe(String deviceId, int x1, int y1, int x2, int y2, int duration) {
            return true;
        }

        @Override
        public boolean inputText(String deviceId, String text) {
            return true;
        }

        @Override
        public boolean inputTextToElement(String deviceId, String selector, String text) {
            return true;
        }

        @Override
        public boolean waitForElement(String deviceId, String selector, int timeoutSeconds) {
            return true;
        }

        @Override
        public boolean isElementPresent(String deviceId, String selector) {
            return true;
        }

        @Override
        public String getElementText(String deviceId, String selector) {
            return "";
        }

        @Override
        public String getElementAttribute(String deviceId, String selector, String attribute) {
            return "";
        }
    }

    /** 保证 Map.of() 传进来的空参数也能走通 */
    @Test
    void 空参数使用默认设置() {
        driver.pageSources("home", "home");
        Map<String, Object> empty = new HashMap<>();

        executor.captureSettledScreenshot(driver, "device-1", task, logs, empty, "1 · 点击");

        assertNotNull(screenshots().get(0).getScreenshotPath());
    }
}
