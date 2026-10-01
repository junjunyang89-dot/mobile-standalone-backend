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

class OpenH5HandlerTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();
    private final FakeDriver driver = new FakeDriver();
    private final FakeRuntime runtime = new FakeRuntime();

    @Test
    void 关键字身份() {
        OpenH5Handler h = new OpenH5Handler();
        assertEquals("OPEN_H5", h.code());
        assertEquals("H5打开页面", h.nameZh());
        assertEquals("ACTION", h.category());
        assertEquals(8, h.sortOrder());
        assertTrue(h.params().stream().anyMatch(p -> "url".equals(p.name()) && p.required()));
    }

    @Test
    void 完美电竞走openWebView_scheme() {
        String s = OpenH5Handler.inAppOpenScheme(
                OpenH5Handler.ESPORTS_PKG, null,
                "https://news.wmpvp.com/gameMall.html#/index?tagId=0&assistCode=");
        assertTrue(s.startsWith("pwesports://global/openWebView?url="));
        assertTrue(s.contains("%23%2Findex"));
    }

    @Test
    void hash地址必须被单引号包住以免被shell当注释() {
        String q = OpenH5Handler.shSingleQuote("https://news.wmpvp.com/gameMall.html#/index?tagId=1");
        assertEquals("'https://news.wmpvp.com/gameMall.html#/index?tagId=1'", q);
    }

    @Test
    void 缺URL直接失败() {
        KeywordContext ctx = KeywordContext.builder()
                .rawCode("OPEN_H5")
                .stepParams(Map.of())
                .execParams(new HashMap<>())
                .driver(driver)
                .deviceId("dev-1")
                .platform("ANDROID")
                .logs(logs)
                .task(task())
                .pathNo("2")
                .state(new KeywordContext.RunState(false, null))
                .runtime(runtime)
                .build();
        assertThrows(UiFlowExecutionException.class, () -> new OpenH5Handler().execute(ctx));
    }

    private static Task task() {
        Task t = new Task();
        t.setId("task-1");
        return t;
    }
}
