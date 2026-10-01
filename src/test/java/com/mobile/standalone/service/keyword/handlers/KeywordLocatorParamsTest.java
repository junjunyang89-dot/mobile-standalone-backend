package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.keyword.KeywordContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordLocatorParamsTest {

    @Test
    void 步骤文案覆盖流程级变量() {
        KeywordContext ctx = KeywordContext.builder()
                .rawCode("CLICK")
                .stepParams(Map.of("tabText", "累计入选次数"))
                .execParams(new HashMap<>(Map.of("tabText", "26年TOP指数", "other", "x")))
                .driver(new FakeDriver())
                .deviceId("d1")
                .platform("ANDROID")
                .logs(new ArrayList<ExecutionLogEntryDto>())
                .task(task())
                .pathNo("1")
                .state(new KeywordContext.RunState(false, null))
                .runtime(new FakeRuntime())
                .build();
        assertEquals("累计入选次数", ctx.locatorParams().get("tabText"));
        assertEquals("x", ctx.locatorParams().get("other"));
    }

    private static Task task() {
        Task t = new Task();
        t.setId("t1");
        return t;
    }
}
