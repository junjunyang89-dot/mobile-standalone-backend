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

class AssertH5UrlHandlerTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();
    private final FakeDriver driver = new FakeDriver();
    private final FakeRuntime runtime = new FakeRuntime();

    @Test
    void 关键字身份() {
        AssertH5UrlHandler h = new AssertH5UrlHandler();
        assertEquals("ASSERT_H5_URL", h.code());
        assertEquals("H5断言页面", h.nameZh());
        assertEquals("ASSERT", h.category());
        assertTrue(h.aliases().contains("WAIT_H5_URL"));
    }

    @Test
    void 缺期望地址直接失败() {
        KeywordContext ctx = KeywordContext.builder()
                .rawCode("ASSERT_H5_URL")
                .stepParams(Map.of())
                .execParams(new HashMap<>())
                .driver(driver)
                .deviceId("dev-1")
                .platform("ANDROID")
                .logs(logs)
                .task(task())
                .pathNo("5")
                .state(new KeywordContext.RunState(false, null))
                .runtime(runtime)
                .build();
        UiFlowExecutionException e = assertThrows(
                UiFlowExecutionException.class, () -> new AssertH5UrlHandler().execute(ctx));
        assertTrue(e.getMessage().contains("期望") || e.getMessage().contains("片段"));
    }

    private static Task task() {
        Task t = new Task();
        t.setId("task-1");
        return t;
    }
}
