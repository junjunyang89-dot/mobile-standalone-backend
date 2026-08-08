package com.mobile.standalone.service;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 步骤级重试次数的决策规则。
 * <p>
 * 不重试的话偶发失败会变成「偶现红」，而偶现红最终会让人不再相信红色——
 * 每次先怀疑是自动化抽风，复核成本超过收益，整套东西就没人看了。
 * 但带嵌套步骤/有副作用的关键字必须关掉重试，否则会重复执行分支里已经跑过的步骤。
 */
class StepRetryTest {

    /** {@code resolveRetryTimes} 是私有静态方法，这里直接反射验规则，避免为测试放宽可见性 */
    private static int resolveRetryTimes(KeywordHandler handler, Map<String, Object> params) throws Exception {
        Method m = UiFlowExecutor.class.getDeclaredMethod(
                "resolveRetryTimes", KeywordHandler.class, Map.class);
        m.setAccessible(true);
        return (int) m.invoke(null, handler, params);
    }

    @Test
    void 可重试关键字默认重试一次() throws Exception {
        assertEquals(1, resolveRetryTimes(new StubHandler(true), Map.of()));
        assertEquals(1, resolveRetryTimes(new StubHandler(true), null));
    }

    @Test
    void 不可重试关键字默认不重试() throws Exception {
        assertEquals(0, resolveRetryTimes(new StubHandler(false), Map.of()));
    }

    @Test
    void 步骤显式配置优先于关键字默认() throws Exception {
        assertEquals(3, resolveRetryTimes(new StubHandler(true), Map.of("retryTimes", 3)));
        // 显式关掉可重试关键字的重试
        assertEquals(0, resolveRetryTimes(new StubHandler(true), Map.of("retryTimes", 0)));
        // 明知有副作用仍要重试，也允许——用户自己清楚在做什么
        assertEquals(2, resolveRetryTimes(new StubHandler(false), Map.of("retryTimes", 2)));
    }

    @Test
    void 重试次数有上限且不接受负数() throws Exception {
        assertEquals(5, resolveRetryTimes(new StubHandler(true), Map.of("retryTimes", 99)));
        assertEquals(0, resolveRetryTimes(new StubHandler(true), Map.of("retryTimes", -3)));
    }

    @Test
    void 字符串形式的配置也能解析() throws Exception {
        assertEquals(2, resolveRetryTimes(new StubHandler(true), Map.of("retryTimes", "2")));
    }

    /** 带嵌套步骤或副作用的真实关键字必须是不可重试的，避免重复执行 */
    @Test
    void 有副作用的关键字已关闭重试() {
        List<KeywordHandler> mustNotRetry = List.of(
                new com.mobile.standalone.service.keyword.handlers.IfElementExistsHandler(),
                new com.mobile.standalone.service.keyword.handlers.WaitAnyHandler(),
                new com.mobile.standalone.service.keyword.handlers.DismissPopupsHandler(),
                new com.mobile.standalone.service.keyword.handlers.InstallAppHandler(),
                new com.mobile.standalone.service.keyword.handlers.UninstallAppHandler(),
                new com.mobile.standalone.service.keyword.handlers.AssertElementStateHandler());

        for (KeywordHandler h : mustNotRetry) {
            assertTrue(!h.retryable(), h.code() + " 不该允许自动重试：会重复执行已完成的动作");
        }
    }

    /** 纯交互类关键字重试是安全且有价值的 */
    @Test
    void 交互类关键字允许重试() {
        List<KeywordHandler> shouldRetry = List.of(
                new com.mobile.standalone.service.keyword.handlers.ClickHandler(),
                new com.mobile.standalone.service.keyword.handlers.InputHandler(),
                new com.mobile.standalone.service.keyword.handlers.WaitElementHandler(),
                new com.mobile.standalone.service.keyword.handlers.ScrollUntilHandler(),
                new com.mobile.standalone.service.keyword.handlers.AssertTextHandler());

        for (KeywordHandler h : shouldRetry) {
            assertTrue(h.retryable(), h.code() + " 应允许重试：失败多为偶发，重试一次即可消除大量偶现红");
        }
    }

    @Test
    void 断言状态开启纠正点击时禁止重试即使显式配了次数() throws Exception {
        var handler = new com.mobile.standalone.service.keyword.handlers.AssertElementStateHandler();
        assertEquals(0, resolveRetryTimes(handler, Map.of("fixByClick", true, "retryTimes", 3)));
        assertEquals(3, resolveRetryTimes(handler, Map.of("fixByClick", false, "retryTimes", 3)));
    }

    private record StubHandler(boolean canRetry) implements KeywordHandler {
        @Override
        public String code() {
            return "STUB";
        }

        @Override
        public String nameZh() {
            return "测试桩";
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
            return 500;
        }

        @Override
        public boolean retryable() {
            return canRetry;
        }

        @Override
        public List<ParamDef> params() {
            return List.of();
        }

        @Override
        public void execute(KeywordContext ctx) {
            // 测试桩
        }
    }

}
