package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.UiFlowExecutionException;
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
 * 弹窗清理。要点是：没有弹窗是正常情况不能算失败，
 * 以及弹窗会串（关掉一个才弹下一个）——单趟扫描处理不了，必须循环。
 */
class DismissPopupsHandlerTest {

    private DismissPopupsHandler handler;
    private FakeRuntime runtime;
    private FakeDriver driver;
    private List<ExecutionLogEntryDto> logs;

    @BeforeEach
    void setUp() {
        handler = new DismissPopupsHandler();
        runtime = new FakeRuntime();
        driver = new FakeDriver();
        logs = new ArrayList<>();
    }

    /** 二次启动、屏幕本来就干净——这是最常见的情况，绝不能判失败 */
    @Test
    void 没有任何弹窗时正常通过() throws Exception {
        handler.execute(ctx(popups(popup("warm_tip", "温馨提示"), popup("upgrade", "升级提示"))));

        assertTrue(logText().contains("无已知弹窗需要处理"));
        assertTrue(driver.clickedSelectors.isEmpty());
    }

    @Test
    void 出现的弹窗会被关掉() throws Exception {
        driver.popup("xpath:warm_tip");

        handler.execute(ctx(popups(popup("warm_tip", "温馨提示"), popup("upgrade", "升级提示"))));

        assertEquals(List.of("xpath:warm_tip"), driver.clickedSelectors);
        assertTrue(logText().contains("温馨提示"));
    }

    @Test
    void 多个弹窗同时在屏上会一并关掉() throws Exception {
        driver.popup("xpath:warm_tip");
        driver.popup("xpath:upgrade");

        handler.execute(ctx(popups(popup("warm_tip", "温馨提示"), popup("upgrade", "升级提示"))));

        assertTrue(driver.clickedSelectors.contains("xpath:warm_tip"));
        assertTrue(driver.clickedSelectors.contains("xpath:upgrade"));
    }

    /**
     * 串弹窗：关掉隐私协议之后才冒出升级提示。
     * 顺序 IF 只有在配置顺序恰好匹配时才行，循环则与顺序无关——这正是要循环的理由。
     */
    @Test
    void 关掉一个后才冒出来的弹窗也能清掉() throws Exception {
        driver.popup("xpath:privacy");
        driver.popupAfterDismissing("xpath:privacy", "xpath:upgrade");

        // 故意把 upgrade 配在 privacy 前面，验证不依赖配置顺序
        handler.execute(ctx(popups(popup("upgrade", "升级提示"), popup("privacy", "隐私协议"))));

        assertTrue(driver.clickedSelectors.contains("xpath:privacy"));
        assertTrue(driver.clickedSelectors.contains("xpath:upgrade"));
    }

    @Test
    void 某轮没关掉任何东西就提前结束不空转() throws Exception {
        driver.popup("xpath:warm_tip");

        handler.execute(ctx(popupsWith(Map.of("maxRounds", 10), popup("warm_tip", "温馨提示"))));

        // 第一轮关掉，第二轮发现没得关就停：温馨提示只会被点一次
        assertEquals(1, driver.clickedSelectors.stream().filter("xpath:warm_tip"::equals).count());
    }

    @Test
    void 关不掉的弹窗默认只告警不中断() throws Exception {
        driver.popup("xpath:warm_tip");
        driver.stubbornPopup("xpath:stuck");

        handler.execute(ctx(popups(popup("warm_tip", "温馨提示"), popup("stuck", "关不掉的弹窗"))));

        assertTrue(logText().contains("仍存在"));
    }

    @Test
    void 开启严格模式后关不掉就判失败() {
        driver.popup("xpath:warm_tip");
        driver.stubbornPopup("xpath:stuck");

        UiFlowExecutionException e = assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(popupsWith(
                        Map.of("failIfStillPresent", true),
                        popup("warm_tip", "温馨提示"), popup("stuck", "关不掉的弹窗")))));

        assertTrue(e.getMessage().contains("未能全部关闭"));
    }

    @Test
    void 未配置弹窗时报错() {
        assertThrows(UiFlowExecutionException.class, () -> handler.execute(ctx(popups())));
    }

    @Test
    void 弹窗项缺元素时报错() {
        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(popups(Map.of("label", "没填元素")))));
    }

    @Test
    void 元素库里没有该元素时报错() {
        runtime.unknownElements.add("not_in_library");

        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(popups(popup("not_in_library", "库里没有")))));
    }

    @Test
    void 清理期间收到停止会中断() {
        runtime.cancelAfterChecks = 0;

        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(popups(popup("warm_tip", "温馨提示")))));
    }

    @Test
    void 清理弹窗不会触发提前返回() throws Exception {
        driver.popup("xpath:warm_tip");
        KeywordContext ctx = ctx(popups(popup("warm_tip", "温馨提示")));

        handler.execute(ctx);

        assertFalse(ctx.getState().earlyReturn);
    }

    // ==================== 锚点模式 ====================

    /** 二次启动：首页一开始就在屏上，应立刻返回，一个弹窗都不用探 */
    @Test
    void 锚点已在屏上时立即返回() throws Exception {
        driver.presentAfter("xpath:home_flag", 0);

        long t0 = System.currentTimeMillis();
        handler.execute(ctx(popupsWith(
                Map.of("untilElementKey", "home_flag", "untilTimeoutSec", 20),
                popup("warm_tip", "温馨提示"))));
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(driver.clickedSelectors.isEmpty());
        assertTrue(logText().contains("无弹窗"));
        assertTrue(elapsed < 2000, "锚点已在屏上应立即返回，实际 " + elapsed + "ms");
    }

    /**
     * 这条是加锚点的全部理由：首次安装时弹窗要过几轮才渲染出来。
     * 没有锚点的话第一轮扫空就走人，弹窗随后弹出来把后续步骤全挡掉。
     */
    @Test
    void 弹窗延迟出现时锚点模式仍能清掉() throws Exception {
        driver.delayedPopup("xpath:warm_tip", 3);
        driver.presentAfter("xpath:home_flag", 6);

        handler.execute(ctx(popupsWith(
                Map.of("untilElementKey", "home_flag", "untilTimeoutSec", 20, "pollIntervalMs", 100),
                popup("warm_tip", "温馨提示"))));

        assertTrue(driver.clickedSelectors.contains("xpath:warm_tip"),
                "延迟出现的弹窗应被关掉，实际点击: " + driver.clickedSelectors);
        assertTrue(logText().contains("温馨提示"));
    }

    @Test
    void 不配锚点时扫空就走这是已知取舍() throws Exception {
        driver.delayedPopup("xpath:warm_tip", 3);

        handler.execute(ctx(popups(popup("warm_tip", "温馨提示"))));

        // 没有锚点就没有等待窗口，此时弹窗还没出现——所以才建议配锚点
        assertTrue(driver.clickedSelectors.isEmpty());
        assertTrue(logText().contains("无已知弹窗需要处理"));
    }

    @Test
    void 锚点始终不出现时判失败() {
        UiFlowExecutionException e = assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(popupsWith(
                        Map.of("untilElementKey", "home_flag", "untilTimeoutSec", 1, "pollIntervalMs", 100),
                        popup("warm_tip", "温馨提示")))));

        assertTrue(e.getMessage().contains("仍未到达"));
    }

    @Test
    void 锚点元素不在元素库时立即报错() {
        runtime.unknownElements.add("home_flag");

        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(popupsWith(
                        Map.of("untilElementKey", "home_flag"),
                        popup("warm_tip", "温馨提示")))));
    }

    @Test
    void 锚点模式下收到停止会中断() {
        runtime.cancelAfterChecks = 0;

        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(popupsWith(
                        Map.of("untilElementKey", "home_flag", "untilTimeoutSec", 30),
                        popup("warm_tip", "温馨提示")))));
    }

    // ==================== 辅助 ====================

    private KeywordContext ctx(Map<String, Object> stepParams) {
        return KeywordContext.builder()
                .rawCode("DISMISS_POPUPS")
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
                .state(new KeywordContext.RunState(false, null))
                .runtime(runtime)
                .build();
    }

    private static Task task() {
        Task t = new Task();
        t.setId("task-1");
        return t;
    }

    @SafeVarargs
    private static Map<String, Object> popups(Map<String, Object>... items) {
        Map<String, Object> p = new HashMap<>();
        p.put("popups", List.of(items));
        return p;
    }

    @SafeVarargs
    private static Map<String, Object> popupsWith(Map<String, Object> extra, Map<String, Object>... items) {
        Map<String, Object> p = popups(items);
        p.putAll(extra);
        return p;
    }

    private static Map<String, Object> popup(String elementKey, String label) {
        return Map.of("elementKey", elementKey, "label", label);
    }

    private String logText() {
        return logs.stream()
                .map(e -> (e.getStep() == null ? "" : e.getStep()) + "|" + (e.getMessage() == null ? "" : e.getMessage()))
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
