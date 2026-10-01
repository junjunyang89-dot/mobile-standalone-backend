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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickH5HandlerTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();
    private final FakeDriver driver = new FakeDriver();
    private final FakeRuntime runtime = new FakeRuntime();

    @Test
    void 关键字身份() {
        ClickH5Handler h = new ClickH5Handler();
        assertEquals("CLICK_H5", h.code());
        assertEquals("H5点文案", h.nameZh());
        assertEquals(12, h.sortOrder());
        assertTrue(h.aliases().contains("H5_CLICK"));
    }

    @Test
    void 缺文案直接失败() {
        KeywordContext ctx = ctx(Map.of("by", ClickH5Handler.BY_TEXT));
        UiFlowExecutionException e = assertThrows(
                UiFlowExecutionException.class, () -> new ClickH5Handler().execute(ctx));
        assertTrue(e.getMessage().contains("找控件") || e.getMessage().contains("页面上"));
    }

    @Test
    void 缺选择器直接失败() {
        KeywordContext ctx = ctx(Map.of("by", ClickH5Handler.BY_CSS));
        UiFlowExecutionException e = assertThrows(
                UiFlowExecutionException.class, () -> new ClickH5Handler().execute(ctx));
        assertTrue(e.getMessage().contains("选择器"));
    }

    @Test
    void 没有Appium适配器时失败() {
        KeywordContext ctx = ctx(Map.of("by", ClickH5Handler.BY_TEXT, "text", "搜索"));
        UiFlowExecutionException e = assertThrows(
                UiFlowExecutionException.class, () -> new ClickH5Handler().execute(ctx));
        assertTrue(e.getMessage().contains("Appium"));
    }

    private KeywordContext ctx(Map<String, Object> params) {
        return KeywordContext.builder()
                .rawCode("CLICK_H5")
                .stepParams(params)
                .execParams(new HashMap<>())
                .driver(driver)
                .deviceId("dev-1")
                .platform("ANDROID")
                .logs(logs)
                .task(task())
                .pathNo("3")
                .state(new KeywordContext.RunState(false, null))
                .runtime(runtime)
                .build();
    }

    private static Task task() {
        Task t = new Task();
        t.setId("task-1");
        return t;
    }
}
