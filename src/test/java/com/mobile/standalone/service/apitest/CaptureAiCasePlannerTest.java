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
        assertEquals("$.code", out.get(1).path("path").asText());
        assertEquals("$.data.list", out.get(2).path("path").asText());
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
        assertFalse(normal.query().containsKey("token"));
        assertFalse(normal.enabled());
        assertTrue(normal.note().contains("失败"));

        CaptureAiCasePlanner.PlannedCase boundary = cases.get(1);
        assertFalse(boundary.enabled());
        assertTrue(boundary.note().contains("确认预期"));
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
