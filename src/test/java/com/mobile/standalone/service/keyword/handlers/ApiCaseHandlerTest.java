package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ApiTestDtos.CaseResult;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.ApiTestRunnerService;
import com.mobile.standalone.service.ApiTestRunnerService.FlowCaseResult;
import com.mobile.standalone.service.UiFlowExecutionException;
import com.mobile.standalone.service.keyword.KeywordContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiCaseHandlerTest {

    @Mock
    private ApiTestRunnerService runner;

    private ApiCaseHandler handler;
    private Map<String, String> vars;
    private List<ExecutionLogEntryDto> logs;

    @BeforeEach
    void setUp() {
        handler = new ApiCaseHandler(runner);
        vars = new HashMap<>();
        vars.put("environment", "TEST");
        vars.put("matchId", "2398948");
        logs = new ArrayList<>();
    }

    @Test
    void 取值回填写入流程变量() throws Exception {
        CaseResult cr = passed("比赛详情");
        when(runner.runCaseForFlow(eq("case-1"), eq("TEST"), anyMap(), any()))
                .thenReturn(new FlowCaseResult(cr, Map.of("teamA", "Johnny Speeds", "teamB", "MTX"), "TEST"));

        handler.execute(ctx(Map.of(
                "caseId", "case-1",
                "mode", "EXTRACT",
                "runtimeVars", "{\"matchId\":\"${matchId}\"}",
                "retryTimes", 0
        )));

        assertEquals("Johnny Speeds", vars.get("teamA"));
        assertEquals("MTX", vars.get("teamB"));
        ArgumentCaptor<Map<String, String>> seed = ArgumentCaptor.forClass(Map.class);
        verify(runner).runCaseForFlow(eq("case-1"), eq("TEST"), seed.capture(), isNull());
        assertEquals("2398948", seed.getValue().get("matchId"));
    }

    @Test
    void 断言失败会按间隔重试() throws Exception {
        CaseResult fail = new CaseResult();
        fail.setId("case-1");
        fail.setName("领奖校验");
        fail.setStatus("FAILED");
        fail.setError("jsonPath $.code != 0");
        CaseResult ok = passed("领奖校验");
        when(runner.runCaseForFlow(anyString(), anyString(), anyMap(), any()))
                .thenReturn(new FlowCaseResult(fail, Map.of(), "TEST"))
                .thenReturn(new FlowCaseResult(ok, Map.of(), "TEST"));

        handler.execute(ctx(Map.of(
                "caseId", "case-1",
                "mode", "ASSERT",
                "retryTimes", 2,
                "retryDelayMs", 1
        )));

        verify(runner, times(2)).runCaseForFlow(anyString(), anyString(), anyMap(), any());
        assertTrue(logs.stream().anyMatch(l -> "success".equals(l.getStatus())));
    }

    @Test
    void 取值模式没抽到变量就失败() {
        CaseResult cr = passed("空提取");
        when(runner.runCaseForFlow(anyString(), anyString(), anyMap(), any()))
                .thenReturn(new FlowCaseResult(cr, Map.of(), "TEST"));

        assertThrows(UiFlowExecutionException.class, () -> handler.execute(ctx(Map.of(
                "caseId", "case-1",
                "mode", "EXTRACT",
                "retryTimes", 0
        ))));
    }

    @Test
    void 不走外层自动重试() {
        assertFalse(handler.retryable());
    }

    private static CaseResult passed(String name) {
        CaseResult cr = new CaseResult();
        cr.setId("case-1");
        cr.setName(name);
        cr.setStatus("PASSED");
        cr.setMethod("GET");
        cr.setUrl("https://example/test");
        cr.setHttpStatus(200);
        cr.setDurationMs(12L);
        return cr;
    }

    private KeywordContext ctx(Map<String, Object> stepParams) {
        return KeywordContext.builder()
                .rawCode("API_CASE")
                .stepParams(stepParams)
                .driver(new FakeDriver())
                .deviceId("dev-1")
                .platform("ANDROID")
                .execParams(vars)
                .logs(logs)
                .task(new Task())
                .depth(0)
                .pathNo("2")
                .state(new KeywordContext.RunState(false, null))
                .runtime(new FakeRuntime())
                .build();
    }
}
