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

class InputH5HandlerTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();
    private final FakeDriver driver = new FakeDriver();
    private final FakeRuntime runtime = new FakeRuntime();

    @Test
    void 关键字身份() {
        InputH5Handler h = new InputH5Handler();
        assertEquals("INPUT_H5", h.code());
        assertEquals("H5输入", h.nameZh());
        assertTrue(h.params().stream().anyMatch(p -> "value".equals(p.name()) && p.required()));
    }

    @Test
    void 只有找框的字没有输入内容则失败() {
        KeywordContext ctx = ctx(Map.of("text", "请输入搜索关键词"));
        UiFlowExecutionException e = assertThrows(
                UiFlowExecutionException.class, () -> new InputH5Handler().execute(ctx));
        assertTrue(e.getMessage().contains("输入内容"));
    }

    private KeywordContext ctx(Map<String, Object> params) {
        return KeywordContext.builder()
                .rawCode("INPUT_H5")
                .stepParams(params)
                .execParams(new HashMap<>())
                .driver(driver)
                .deviceId("dev-1")
                .platform("ANDROID")
                .logs(logs)
                .task(task())
                .pathNo("4")
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
