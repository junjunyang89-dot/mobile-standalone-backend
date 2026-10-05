package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureAiCasePlannerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void verifiedKeepsOnlyAssertionsThatHoldOnCapturedResponse() throws Exception {
        String resp = """
                {"code":0,"message":"ok","data":{"list":[{"id":1,"name":"a"}],"total":1}}
                """;
        ArrayNode proposed = JSON.createArrayNode();
        proposed.addObject().put("type", "jsonPath").put("path", "$.code").put("equals", 0);
        proposed.addObject().put("type", "jsonPath").put("path", "$.data.list").put("exists", true);
        proposed.addObject().put("type", "jsonPath").put("path", "$.data.list[0].name").put("equals", "wrong");
        proposed.addObject().put("type", "jsonPath").put("path", "$.missing").put("exists", true);
        proposed.addObject().put("type", "status").put("equals", 200);

        ArrayNode out = CaptureAiCasePlanner.verified(proposed, resp);
        assertEquals(3, out.size());
        assertEquals("status", out.get(0).path("type").asText());
        assertEquals("default", out.get(0).path("source").asText());
        assertEquals("$.code", out.get(1).path("path").asText());
        assertEquals("capture", out.get(1).path("source").asText());
        assertEquals("抓包响应里就是 0", out.get(1).path("evidence").asText());
        assertEquals("$.data.list", out.get(2).path("path").asText());
        assertEquals("capture", out.get(2).path("source").asText());
    }

    @Test
    void planUsesCapturedRequestAndDisablesWhenResponseIsFailure() throws Exception {
        String resp = """
                {"code":500,"message":"busy","data":null}
                """;
        String raw = """
                {"cases":[{"name":"正常","kind":"NORMAL","expect":"SUCCESS",
                "query":{"page":"1"},"body":null,"basis":"",
                "assertions":[{"type":"jsonPath","path":"$.code","equals":0}]},
                {"name":"缺 page","kind":"BOUNDARY","expect":"ERROR",
                "query":{},"body":null,"basis":"分页参数缺失",
                "assertions":[{"type":"jsonPath","path":"$.code","notEquals":0}]}]}
                """;
        var in = new CaptureAiCasePlanner.Input("GET", "/x", "列表",
                Map.of("page", "2", "token", "secret"), null, resp, "$.code", "0");
        List<CaptureAiCasePlanner.PlannedCase> cases = CaptureAiCasePlanner.plan(raw, in);

        assertEquals(2, cases.size());
        CaptureAiCasePlanner.PlannedCase normal = cases.get(0);
        assertTrue(normal.normal());
        assertEquals("2", normal.query().get("page"));
        assertEquals("{{token}}", normal.query().get("token"));
        assertFalse(normal.enabled());
        assertTrue(normal.note().contains("失败"));

        CaptureAiCasePlanner.PlannedCase boundary = cases.get(1);
        assertFalse(boundary.enabled());
        assertTrue(boundary.note().contains("确认预期"));
        assertEquals("{{token}}", boundary.query().get("token"));
        assertFalse(boundary.query().containsKey("page"));
        assertTrue(boundary.assertions().contains("\"source\":\"ai\""));
    }

    @Test
    void realTokenNeverReachesThePromptOrTheCase() throws Exception {
        var in = new CaptureAiCasePlanner.Input("POST", "/apppush/user/message/msgClassNew", null,
                Map.of("token", "1e143eaf0ed6ec1c", "type", "1"), "{}", "{\"code\":0}", "$.code", "0");
        String prompt = CaptureAiCasePlanner.prompt(in);
        assertFalse(prompt.contains("1e143eaf0ed6ec1c"));
        assertTrue(prompt.contains("\"type\":\"1\""));

        var cases = CaptureAiCasePlanner.plan("{\"cases\":[]}", in);
        assertEquals("{{token}}", cases.get(0).query().get("token"));
        assertEquals("1", cases.get(0).query().get("type"));
    }

    @Test
    void dateParamsWarnAgainstAssertingListItems() {
        var in = new CaptureAiCasePlanner.Input("GET", "/event/getMatchList", null,
                Map.of("matchTime", "2026-10-02 00:00:00", "gameType", "1"), null, "{\"code\":0}", "$.code", "0");
        String prompt = CaptureAiCasePlanner.prompt(in);
        assertTrue(prompt.contains("matchTime 是日期/时间参数"));
        assertFalse(prompt.contains("测试环境抓包"));

        var plain = new CaptureAiCasePlanner.Input("GET", "/x", null,
                Map.of("page", "1"), null, "{\"code\":0}", "$.code", "0");
        assertFalse(CaptureAiCasePlanner.prompt(plain).contains("日期/时间参数"));
    }

    @Test
    void compactJsonKeepsObjectShapeAndTruncatesArrays() {
        String raw = """
                {"code":0,"data":[{"a":1},{"a":2},{"a":3}],"msg":"ok"}
                """;
        String compact = CaptureJsonParser.compactJson(raw, 8000);
        assertTrue(compact.contains("\"code\":0"));
        assertTrue(compact.contains("\"a\":1"));
        assertFalse(compact.contains("\"a\":3"));
    }
}
