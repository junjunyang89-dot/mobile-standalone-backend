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

class ClickEachThenHandlerTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();
    private final FakeDriver driver = new FakeDriver();
    private final FakeRuntime runtime = new FakeRuntime();

    @Test
    void 每点一项就跑同一套步骤() throws Exception {
        List<Map<String, Object>> thenSteps = List.of(
                Map.of("keywordCode", "CLICK"),
                Map.of("keywordCode", "INPUT"));
        KeywordContext ctx = ctx(Map.of(
                "sequence", "26年TOP指数,2025年",
                "waitAfterMs", 0,
                "varName", "tabText",
                "thenSteps", thenSteps));

        new ClickEachThenHandler().execute(ctx);

        assertEquals(2, driver.clicks);
        assertEquals(List.of("CLICK", "INPUT", "CLICK", "INPUT"), runtime.executedCodes);
        assertEquals(List.of("26年TOP指数", "2025年"),
                runtime.locatorParamsSeen.stream().map(m -> m.get("tabText")).toList());
    }

    private KeywordContext ctx(Map<String, Object> params) {
        return KeywordContext.builder()
                .rawCode("CLICK_EACH_THEN")
                .elementKey("tab_by_text")
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
