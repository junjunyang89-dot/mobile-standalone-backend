package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.UiFlowExecutionException;
import com.mobile.standalone.service.keyword.FlowOutcome;
import com.mobile.standalone.service.keyword.KeywordContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 竞速等待的时序行为。这是 WAIT_ANY 存在的全部理由——
 * 「等哪个先出现」和「等某一个超时后再猜」是两回事，后者在慢设备上会误判分支。
 */
class WaitAnyHandlerTest {

    private WaitAnyHandler handler;
    private FakeRuntime runtime;
    private FakeDriver driver;
    private List<ExecutionLogEntryDto> logs;
    private KeywordContext.RunState state;

    @BeforeEach
    void setUp() {
        handler = new WaitAnyHandler();
        runtime = new FakeRuntime();
        driver = new FakeDriver();
        logs = new ArrayList<>();
        state = new KeywordContext.RunState(false, null);
    }

    // ==================== 命中分支 ====================

    @Test
    void 第一个候选出现时走它的分支() throws Exception {
        driver.presentAfter("xpath:warm_tip", 0);

        handler.execute(ctx(params(Map.of(
                "candidates", List.of(
                        candidate("warm_tip", "温馨提示", step("CLICK")),
                        candidate("home_entry", "首页", step("BACK")))))));

        assertEquals(List.of("CLICK"), runtime.executedCodes);
        assertTrue(logText().contains("温馨提示"));
    }

    /** 关键场景：二次启动没有弹窗，直接命中首页，不该为温馨提示付任何超时代价 */
    @Test
    void 第二个候选出现时走它的分支且不等满超时() throws Exception {
        driver.presentAfter("xpath:home_entry", 0);

        long t0 = System.currentTimeMillis();
        handler.execute(ctx(params(Map.of(
                "timeoutSec", 20,
                "candidates", List.of(
                        candidate("warm_tip", "温馨提示", step("CLICK")),
                        candidate("home_entry", "首页", step("BACK")))))));
        long elapsed = System.currentTimeMillis() - t0;

        assertEquals(List.of("BACK"), runtime.executedCodes);
        assertTrue(elapsed < 3000, "命中后应立刻继续，实际耗时 " + elapsed + "ms");
    }

    /**
     * 慢设备场景：弹窗第 3 轮探测才出现。
     * 换成「IF 等 3 秒」的写法这里就会误判成二次启动，这条测试锁住不再回退。
     */
    @Test
    void 候选延迟出现仍能正确命中() throws Exception {
        driver.presentAfter("xpath:warm_tip", 3);

        handler.execute(ctx(params(Map.of(
                "timeoutSec", 20,
                "pollIntervalMs", 100,
                "candidates", List.of(
                        candidate("warm_tip", "温馨提示", step("CLICK")),
                        candidate("home_entry", "首页", step("BACK")))))));

        assertEquals(List.of("CLICK"), runtime.executedCodes);
    }

    @Test
    void 同一轮多个都在屏上时按填写顺序优先() throws Exception {
        driver.presentAfter("xpath:warm_tip", 0);
        driver.presentAfter("xpath:home_entry", 0);

        handler.execute(ctx(params(Map.of(
                "candidates", List.of(
                        candidate("home_entry", "首页", step("BACK")),
                        candidate("warm_tip", "温馨提示", step("CLICK")))))));

        assertEquals(List.of("BACK"), runtime.executedCodes);
    }

    @Test
    void 命中的候选没有子步骤时不报错() throws Exception {
        driver.presentAfter("xpath:home_entry", 0);

        handler.execute(ctx(params(Map.of(
                "candidates", List.of(candidate("home_entry", "首页"))))));

        assertTrue(runtime.executedCodes.isEmpty());
        assertFalse(state.earlyReturn);
    }

    // ==================== 都没出现 ====================

    @Test
    void 都没出现时优先跑兜底步骤() throws Exception {
        handler.execute(ctx(params(Map.of(
                "timeoutSec", 1,
                "pollIntervalMs", 100,
                "candidates", List.of(candidate("warm_tip", "温馨提示", step("CLICK"))),
                "noneSteps", List.of(step("SCREENSHOT"))))));

        assertEquals(List.of("SCREENSHOT"), runtime.executedCodes);
        assertFalse(state.earlyReturn);
    }

    @Test
    void 都没出现且只有提示时按判定提前返回() throws Exception {
        handler.execute(ctx(params(Map.of(
                "timeoutSec", 1,
                "pollIntervalMs", 100,
                "candidates", List.of(candidate("warm_tip", "温馨提示")),
                "noneMessage", "启动后既无弹窗也无首页",
                "endStatus", "SKIP"))));

        assertTrue(state.earlyReturn);
        assertEquals("启动后既无弹窗也无首页", state.returnMessage);
        assertEquals(FlowOutcome.SKIP, state.returnOutcome);
    }

    @Test
    void 提前返回未写判定时按失败处理() throws Exception {
        handler.execute(ctx(params(Map.of(
                "timeoutSec", 1,
                "pollIntervalMs", 100,
                "candidates", List.of(candidate("warm_tip", "温馨提示")),
                "noneMessage", "都没出现"))));

        assertEquals(FlowOutcome.FAIL, state.returnOutcome);
    }

    @Test
    void 都没出现且无兜底无提示时判失败() {
        UiFlowExecutionException e = assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(params(Map.of(
                        "timeoutSec", 1,
                        "pollIntervalMs", 100,
                        "candidates", List.of(candidate("warm_tip", "温馨提示")))))));

        assertTrue(e.getMessage().contains("超时"));
    }

    // ==================== 配置错误 ====================

    @Test
    void 没有候选时立即报错() {
        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(params(Map.of("candidates", List.of())))));
    }

    @Test
    void 候选缺元素时立即报错而不是白等超时() {
        long t0 = System.currentTimeMillis();

        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(params(Map.of(
                        "timeoutSec", 30,
                        "candidates", List.of(Map.of("label", "没填元素的候选")))))));

        assertTrue(System.currentTimeMillis() - t0 < 3000, "配置错误应立刻报错，不该先等满超时");
    }

    @Test
    void 元素库里没有该元素时立即报错() {
        runtime.unknownElements.add("not_in_library");

        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(params(Map.of(
                        "candidates", List.of(candidate("not_in_library", "库里没有")))))));
    }

    // ==================== 取消 ====================

    @Test
    void 等待期间收到停止会中断() {
        runtime.cancelAfterChecks = 2;

        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(params(Map.of(
                        "timeoutSec", 30,
                        "pollIntervalMs", 100,
                        "candidates", List.of(candidate("warm_tip", "温馨提示")))))));
    }

    // ==================== 辅助 ====================

    private KeywordContext ctx(Map<String, Object> stepParams) {
        return KeywordContext.builder()
                .rawCode("WAIT_ANY")
                .stepParams(stepParams)
                .driver(driver)
                .deviceId("device-1")
                .platform("ANDROID")
                .uiAppId("DOMESTIC_ESPORTS")
                .execParams(new HashMap<>())
                .logs(logs)
                .task(task())
                .depth(0)
                .pathNo("1")
                .performanceMode(false)
                .state(state)
                .runtime(runtime)
                .build();
    }

    private static Task task() {
        Task t = new Task();
        t.setId("task-1");
        return t;
    }

    private static Map<String, Object> params(Map<String, Object> raw) {
        return new HashMap<>(raw);
    }

    private static Map<String, Object> candidate(String elementKey, String label, Map<String, Object>... steps) {
        Map<String, Object> c = new HashMap<>();
        c.put("elementKey", elementKey);
        c.put("label", label);
        c.put("steps", List.of(steps));
        return c;
    }

    private static Map<String, Object> step(String code) {
        return Map.of("keywordCode", code, "params", Map.of());
    }

    private String logText() {
        return logs.stream().map(ExecutionLogEntryDto::getStep).reduce("", (a, b) -> a + "|" + b)
                + logs.stream().map(ExecutionLogEntryDto::getMessage).reduce("", (a, b) -> a + "|" + b);
    }
}
