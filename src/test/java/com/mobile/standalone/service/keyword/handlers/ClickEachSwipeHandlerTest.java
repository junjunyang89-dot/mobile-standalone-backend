package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.keyword.KeywordContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClickEachSwipeHandlerTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();
    private final FakeDriver driver = new FakeDriver();
    private final FakeRuntime runtime = new FakeRuntime();

    @Test
    void 每点完一年就滑一下() throws Exception {
        KeywordContext ctx = ctx(Map.of(
                "sequence", "2025-2023",
                "waitAfterMs", 0,
                "afterSwipe", "up",
                "afterSwipeTimes", 1));

        new ClickEachSwipeHandler().execute(ctx);

        assertEquals(3, driver.clicks);
        assertEquals(List.of("2025", "2024", "2023"),
                runtime.locatorParamsSeen.stream().map(m -> m.get("year")).toList());
        assertEquals(List.of("up", "up", "up"), runtime.swipeDirections);
    }

    private KeywordContext ctx(Map<String, Object> params) {
        return KeywordContext.builder()
                .rawCode("CLICK_EACH_SWIPE")
                .elementKey("year_sort_btn")
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
