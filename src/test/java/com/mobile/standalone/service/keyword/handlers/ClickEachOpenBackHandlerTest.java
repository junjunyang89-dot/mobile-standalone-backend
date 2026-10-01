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

class ClickEachOpenBackHandlerTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();
    private final FakeDriver driver = new FakeDriver();
    private final FakeRuntime runtime = new FakeRuntime();

    @Test
    void 每年进入后跳转再返回() throws Exception {
        KeywordContext ctx = ctx(Map.of(
                "sequence", "2025-2024",
                "waitAfterMs", 0,
                "waitAfterJumpMs", 0,
                "waitAfterBackMs", 0,
                "jumpElementKey", "detail_entry",
                "backElementKey", "nav_back"));

        new ClickEachOpenBackHandler().execute(ctx);

        assertEquals(6, driver.clicks);
        assertEquals(List.of(
                "year_sort_btn", "detail_entry", "nav_back",
                "year_sort_btn", "detail_entry", "nav_back"), runtime.elementKeysSeen);
        assertEquals(List.of("2025", "2025", "2025", "2024", "2024", "2024"),
                runtime.locatorParamsSeen.stream().map(m -> m.get("year")).toList());
    }

    private KeywordContext ctx(Map<String, Object> params) {
        return KeywordContext.builder()
                .rawCode("CLICK_EACH_OPEN_BACK")
                .elementKey("year_sort_btn")
                .stepParams(new HashMap<>(params))
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
    }

    private static Task task() {
        Task t = new Task();
        t.setId("task-1");
        return t;
    }
}
