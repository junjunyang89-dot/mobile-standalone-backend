package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.UiFlowExecutionException;
import com.mobile.standalone.service.keyword.KeywordContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputEditTextHandlerTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();
    private final FakeDriver driver = new FakeDriver();
    private final FakeRuntime runtime = new FakeRuntime();

    @Test
    void 关键字身份() {
        InputEditTextHandler h = new InputEditTextHandler();
        assertEquals("INPUT_EDITTEXT", h.code());
        assertEquals(List.of("EDITTEXT"), h.aliases());
        assertEquals("EditText输入", h.nameZh());
        assertEquals("ACTION", h.category());
        assertTrue(h.needsElement());
        assertEquals(21, h.sortOrder());
    }

    @Test
    void 先点所选元素再向焦点框输入() throws Exception {
        new InputEditTextHandler().execute(ctx("community_comment_hint", "自动化试评"));

        assertEquals("xpath:community_comment_hint", driver.lastFocusedSelector);
        assertEquals("自动化试评", driver.lastFocusedText);
        assertEquals("success", lastLog().getStatus());
    }

    @Test
    void 主定位失败时再试不带元素的焦点输入() throws Exception {
        // 第一次带 selector 失败，第二次 selector=null 成功
        FakeDriver flaky = new FakeDriver() {
            int calls;

            @Override
            public boolean inputTextToFocusedEditText(String deviceId, String selector, String text) {
                lastFocusedSelector = selector;
                lastFocusedText = text;
                return ++calls > 1;
            }
        };
        KeywordContext ctx = KeywordContext.builder()
                .rawCode("INPUT_EDITTEXT")
                .elementKey("community_comment_hint")
                .stepParams(Map.of("text", "hello"))
                .execParams(new HashMap<>())
                .driver(flaky)
                .deviceId("dev-1")
                .logs(logs)
                .task(task())
                .pathNo("6")
                .state(new KeywordContext.RunState(false, null))
                .runtime(runtime)
                .build();

        new InputEditTextHandler().execute(ctx);

        assertNull(flaky.lastFocusedSelector);
        assertEquals("hello", flaky.lastFocusedText);
    }

    @Test
    void 焦点输入也失败则中断() {
        driver.focusedInputOk = false;

        assertThrows(UiFlowExecutionException.class,
                () -> new InputEditTextHandler().execute(ctx("community_comment_hint", "x")));
        assertEquals("fail", lastLog().getStatus());
    }

    @Test
    void 输入内容走变量替换() throws Exception {
        KeywordContext ctx = KeywordContext.builder()
                .rawCode("INPUT_EDITTEXT")
                .elementKey("et_comment")
                .stepParams(Map.of("text", "${comment}"))
                .execParams(new HashMap<>(Map.of("comment", "赛季真好看")))
                .driver(driver)
                .deviceId("dev-1")
                .logs(logs)
                .task(task())
                .pathNo("6")
                .state(new KeywordContext.RunState(false, null))
                .runtime(runtime)
                .build();

        new InputEditTextHandler().execute(ctx);

        assertEquals("赛季真好看", driver.lastFocusedText);
    }

    private KeywordContext ctx(String elementKey, String text) {
        return KeywordContext.builder()
                .rawCode("INPUT_EDITTEXT")
                .elementKey(elementKey)
                .stepParams(Map.of("text", text))
                .execParams(new HashMap<>())
                .driver(driver)
                .deviceId("dev-1")
                .logs(logs)
                .task(task())
                .pathNo("6")
                .state(new KeywordContext.RunState(false, null))
                .runtime(runtime)
                .build();
    }

    private static Task task() {
        Task t = new Task();
        t.setId("task-1");
        return t;
    }

    private ExecutionLogEntryDto lastLog() {
        assertFalse(logs.isEmpty(), "应当写入一条执行日志");
        return logs.get(logs.size() - 1);
    }
}
