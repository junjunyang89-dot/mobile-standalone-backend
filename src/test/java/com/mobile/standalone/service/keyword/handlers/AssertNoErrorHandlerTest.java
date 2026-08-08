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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 负面断言：无人值守时的兜底。
 * <p>
 * 「按钮点得到但接口返回 500、页面弹了服务器开小差」这类问题，元素断言抓不到
 * （元素都在，只是内容不对），截图要等人复核——这一步能让它当场变红。
 */
class AssertNoErrorHandlerTest {

    private AssertNoErrorHandler handler;
    private SourceDriver driver;
    private List<ExecutionLogEntryDto> logs;

    @BeforeEach
    void setUp() {
        handler = new AssertNoErrorHandler();
        driver = new SourceDriver();
        logs = new ArrayList<>();
    }

    @Test
    void 页面干净时通过() throws Exception {
        driver.source = "<hierarchy><node text=\"首页\"/><node text=\"我的\"/></hierarchy>";

        handler.execute(ctx(Map.of("errorTexts", "服务器开小差\n网络异常")));

        assertTrue(logText().contains("未发现已知错误提示"));
    }

    @Test
    void 出现错误提示时判失败并说明命中了哪条() {
        driver.source = "<hierarchy><node text=\"服务器开小差，请稍后重试\"/></hierarchy>";

        UiFlowExecutionException e = assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(Map.of("errorTexts", "服务器开小差\n网络异常"))));

        assertTrue(e.getMessage().contains("服务器开小差"));
    }

    @Test
    void 命中多条时全部列出() {
        driver.source = "<node text=\"网络异常\"/><node text=\"加载失败\"/>";

        UiFlowExecutionException e = assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(Map.of("errorTexts", "网络异常\n加载失败\n服务器开小差"))));

        assertTrue(e.getMessage().contains("网络异常"));
        assertTrue(e.getMessage().contains("加载失败"));
        assertFalse(e.getMessage().contains("服务器开小差"));
    }

    @Test
    void 关掉判失败开关后只告警不中断() throws Exception {
        driver.source = "<node text=\"网络异常\"/>";

        handler.execute(ctx(Map.of("errorTexts", "网络异常", "failOnMatch", false)));

        assertTrue(logText().contains("只告警不中断"));
    }

    @Test
    void 逗号分隔也能解析() {
        driver.source = "<node text=\"系统繁忙\"/>";

        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(Map.of("errorTexts", "网络异常，系统繁忙,加载失败"))));
    }

    @Test
    void 未配置文案时报错() {
        driver.source = "<node text=\"首页\"/>";

        assertThrows(UiFlowExecutionException.class,
                () -> handler.execute(ctx(Map.of("errorTexts", "   "))));
    }

    /** 拿不到控件树就如实说明，不能假装通过——那会把真问题盖掉 */
    @Test
    void 控件树取不到时跳过检查而不是判通过() throws Exception {
        driver.source = null;

        handler.execute(ctx(Map.of("errorTexts", "网络异常")));

        assertTrue(logText().contains("跳过检查"));
        assertFalse(logText().contains("未发现已知错误提示"));
    }

    @Test
    void 控件树读取异常时跳过检查() throws Exception {
        driver.throwOnRead = true;

        handler.execute(ctx(Map.of("errorTexts", "网络异常")));

        assertTrue(logText().contains("无法获取控件树"));
    }

    /** 错误提示出现本身就是结论，重试会因为 toast 消失而把真问题盖掉 */
    @Test
    void 不允许自动重试() {
        assertFalse(handler.retryable());
    }

    @Test
    void 默认文案已预置且走通用参数渲染() {
        assertFalse(handler.customUi(), "纯参数表单，不该需要专用编辑器");
        var errorTexts = handler.params().stream()
                .filter(p -> p.name().equals("errorTexts"))
                .findFirst()
                .orElseThrow();
        String defaults = String.valueOf(errorTexts.defaultValue());
        assertTrue(defaults.contains("服务器开小差"));
        assertTrue(defaults.contains("网络异常"));
    }

    // ==================== 辅助 ====================

    private KeywordContext ctx(Map<String, Object> stepParams) {
        return KeywordContext.builder()
                .rawCode("ASSERT_NO_ERROR")
                .stepParams(stepParams)
                .driver(driver)
                .deviceId("device-1")
                .platform("ANDROID")
                .uiAppId("APP")
                .execParams(new HashMap<>())
                .logs(logs)
                .task(task())
                .pathNo("5")
                .state(new KeywordContext.RunState(false, null))
                .runtime(new FakeRuntime())
                .build();
    }

    private static Task task() {
        Task t = new Task();
        t.setId("task-1");
        return t;
    }

    private String logText() {
        return logs.stream()
                .map(e -> (e.getStep() == null ? "" : e.getStep()) + "|" + (e.getMessage() == null ? "" : e.getMessage()))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /** 可设定控件树内容，或模拟读取异常 */
    private static class SourceDriver extends FakeDriver {
        String source = "";
        boolean throwOnRead = false;

        @Override
        public String getPageSource(String deviceId) {
            if (throwOnRead) {
                throw new IllegalStateException("会话已失效");
            }
            return source;
        }
    }
}
