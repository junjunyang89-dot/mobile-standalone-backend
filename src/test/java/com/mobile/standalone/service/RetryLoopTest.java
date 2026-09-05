package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试循环的完整行为。
 * <p>
 * 真机上能验的是「失败后确实又调了一次 Appium」，而「重试成功之后怎么处理日志」
 * 不碰设备也不碰网络，造真机场景既难又不可靠，用单测覆盖更实在。
 */
class RetryLoopTest {

    private UiFlowExecutor executor;
    private List<ExecutionLogEntryDto> logs;
    private KeywordContext.RunState state;
    private UiFlowExecutor.FlowEnv env;
    private KeywordContext ctx;

    @BeforeEach
    void setUp() {
        executor = new UiFlowExecutor(null, null, null, null, null, null,
                new ExecutionCancelRegistry(), null, null, null, null);
        logs = new ArrayList<>();
        state = new KeywordContext.RunState(false, null);
        Task task = new Task();
        task.setId("task-1");

        env = new UiFlowExecutor.FlowEnv(new NoSourceDriver(), null, "device-1", "ANDROID",
                "APP", new HashMap<>(), logs, task, state, false);
        ctx = KeywordContext.builder()
                .rawCode("CLICK")
                .stepParams(Map.of())
                .logs(logs)
                .task(task)
                .pathNo("2")
                .state(state)
                .build();
    }

    /** 第一次失败、第二次成功：这一步最终是过的，之前那条红要降级 */
    @Test
    void 重试成功后前次失败降级且不再抛出() throws Exception {
        FlakyHandler handler = new FlakyHandler(1, true);

        executor.executeWithRetry(handler, ctx, env, Map.of("retryDelayMs", 0));

        assertEquals(2, handler.attempts, "应当重试一次");
        // 第一次尝试写下的红被降级
        ExecutionLogEntryDto firstAttempt = logs.get(0);
        assertEquals("neutral", firstAttempt.getStatus());
        assertEquals("Warn", firstAttempt.getLevel());
        assertTrue(firstAttempt.getMessage().startsWith("[重试后已通过]"));
        // 有一条明确的「本步不稳定」记录，不让它悄悄混过去
        assertTrue(logText().contains("重试后通过"));
        assertTrue(logText().contains("不稳定"));
    }

    @Test
    void 重试期间会记录准备重试的中立日志() throws Exception {
        executor.executeWithRetry(new FlakyHandler(1, true), ctx, env, Map.of("retryDelayMs", 0));

        ExecutionLogEntryDto retryNotice = logs.stream()
                .filter(e -> e.getStep() != null && e.getStep().contains("准备重试"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少「准备重试」日志"));
        assertEquals("neutral", retryNotice.getStatus());
        assertTrue(retryNotice.getMessage().contains("第 1/2 次失败"));
    }

    /** 重试仍失败：原样抛出，并且只保存一次失败现场 */
    @Test
    void 重试仍失败时抛出并只记一次失败现场() {
        FlakyHandler handler = new FlakyHandler(99, false);

        UiFlowExecutionException e = assertThrows(UiFlowExecutionException.class,
                () -> executor.executeWithRetry(handler, ctx, env, Map.of("retryDelayMs", 0)));

        assertEquals(2, handler.attempts, "默认重试 1 次，共 2 次尝试");
        assertTrue(e.getMessage().contains("模拟失败"));
        assertTrue(state.failureDumped, "应当标记已保存失败现场");
        // 失败的日志保持红色：这次确实没通过
        assertEquals("fail", logs.get(0).getStatus());
    }

    @Test
    void 不可重试的关键字只尝试一次() {
        FlakyHandler handler = new FlakyHandler(99, false);
        handler.canRetry = false;

        assertThrows(UiFlowExecutionException.class,
                () -> executor.executeWithRetry(handler, ctx, env, Map.of()));

        assertEquals(1, handler.attempts, "不可重试的关键字不该被重试");
    }

    @Test
    void 步骤可显式加大重试次数() throws Exception {
        FlakyHandler handler = new FlakyHandler(2, true);

        executor.executeWithRetry(handler, ctx, env, Map.of("retryTimes", 3, "retryDelayMs", 0));

        assertEquals(3, handler.attempts, "第 3 次才成功");
    }

    @Test
    void 一次就成功时不产生任何重试痕迹() throws Exception {
        FlakyHandler handler = new FlakyHandler(0, true);

        executor.executeWithRetry(handler, ctx, env, Map.of());

        assertEquals(1, handler.attempts);
        assertTrue(logs.isEmpty(), "成功路径不该多写日志");
    }

    /** 用户点了停止：立刻抛出，不能因为「失败了」就再跑一遍 */
    @Test
    void 取消不触发重试() {
        KeywordHandler cancelling = new FlakyHandler(99, false) {
            @Override
            public void execute(KeywordContext c) throws Exception {
                attempts++;
                throw new UiFlowCancelledException("流程已被用户停止", logs);
            }
        };

        assertThrows(UiFlowCancelledException.class,
                () -> executor.executeWithRetry(cancelling, ctx, env, Map.of("retryDelayMs", 0)));
        assertEquals(1, ((FlakyHandler) cancelling).attempts, "取消不该重试");
    }

    private String logText() {
        return logs.stream()
                .map(e -> (e.getStep() == null ? "" : e.getStep()) + "|" + (e.getMessage() == null ? "" : e.getMessage()))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /** 前 failTimes 次失败，之后按 succeedEventually 决定是否成功 */
    private class FlakyHandler implements KeywordHandler {
        private final int failTimes;
        private final boolean succeedEventually;
        boolean canRetry = true;
        int attempts = 0;

        FlakyHandler(int failTimes, boolean succeedEventually) {
            this.failTimes = failTimes;
            this.succeedEventually = succeedEventually;
        }

        @Override
        public void execute(KeywordContext c) throws Exception {
            attempts++;
            if (attempts <= failTimes || !succeedEventually) {
                // 模拟真实关键字：先写失败日志再抛，这正是需要事后降级的那条
                logs.add(ExecutionLogEntryDto.fail("2 · 点击", "模拟失败 #" + attempts, "xpath://x"));
                throw new UiFlowExecutionException("模拟失败 #" + attempts, logs);
            }
        }

        @Override
        public boolean retryable() {
            return canRetry;
        }

        @Override
        public String code() {
            return "CLICK";
        }

        @Override
        public String nameZh() {
            return "点击";
        }

        @Override
        public String category() {
            return "ACTION";
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public int sortOrder() {
            return 10;
        }

        @Override
        public List<ParamDef> params() {
            return List.of();
        }
    }

    /** 取不到控件树：失败现场落盘退化成空操作，测试不产生文件 */
    private static class NoSourceDriver implements PlatformDriver {
        @Override
        public String getPageSource(String deviceId) {
            return null;
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
        public String takeScreenshot(String deviceId, String savePath) {
            return savePath;
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
}
