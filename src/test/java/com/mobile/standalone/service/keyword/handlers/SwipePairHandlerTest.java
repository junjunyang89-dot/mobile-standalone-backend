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

class SwipePairHandlerTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();
    private final FakeDriver driver = new FakeDriver();
    private final FakeRuntime runtime = new FakeRuntime();

    @Test
    void 关键字身份() {
        SwipePairHandler h = new SwipePairHandler();
        assertEquals("SWIPE_PAIR", h.code());
        assertEquals("双向滑动", h.nameZh());
        assertEquals(61, h.sortOrder());
        assertTrue(h.params().stream().anyMatch(p -> "upTimes".equals(p.name())));
        assertTrue(h.params().stream().anyMatch(p -> "leftTimes".equals(p.name())));
    }

    @Test
    void 先上后下按次数排队() throws Exception {
        new SwipePairHandler().execute(ctx(Map.of(
                "axis", "上下",
                "orderVertical", "先上后下",
                "upTimes", 2,
                "downTimes", 1,
                "intervalMs", 0
        )));
        assertEquals(List.of("up", "up", "down"), runtime.swipeDirections);
    }

    @Test
    void 先右后左() throws Exception {
        new SwipePairHandler().execute(ctx(Map.of(
                "axis", "左右",
                "orderHorizontal", "先右后左",
                "leftTimes", 1,
                "rightTimes", 2,
                "intervalMs", 0
        )));
        assertEquals(List.of("right", "right", "left"), runtime.swipeDirections);
    }

    @Test
    void 两次都是0则失败() {
        assertThrows(UiFlowExecutionException.class, () ->
                new SwipePairHandler().execute(ctx(Map.of(
                        "axis", "上下",
                        "upTimes", 0,
                        "downTimes", 0
                ))));
    }

    private KeywordContext ctx(Map<String, Object> params) {
        return KeywordContext.builder()
                .rawCode("SWIPE_PAIR")
                .stepParams(new HashMap<>(params))
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
