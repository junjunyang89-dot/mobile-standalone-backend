package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EndpointInsight.Code SUCCESS = new EndpointInsight.Code("SUCCESS", "1", "success", "C.java:88");
    private static final List<EndpointInsight.Field> FIELDS = List.of(
            new EndpointInsight.Field("$.code", "code", "number", "int", null, null, null),
            new EndpointInsight.Field("$.result", "result", "object", "MatchShareVO", null, null, null),
            new EndpointInsight.Field("$.result.title", "title", "string", "String", null, null, null));

    private static ParamInsight.Outcome silent() {
        return new ParamInsight.Outcome(ParamInsight.SILENT, null, null, null, "null",
                "if (matchId == null || gameType == null) → 直接 return null，不报错", "Dota2LiveServiceImpl.java:80", true);
    }

    private static ParamInsight.Outcome paramError() {
        return new ParamInsight.Outcome(ParamInsight.REJECT, "10001", "PARAM_ERROR", "参数错误", null,
                "传 abc 转不成 Integer", "C.java:87", false);
    }

    private static EndpointInsight.Report report(List<ParamInsight.Param> params) {
        return new EndpointInsight.Report("C.java", 87, "getMatchShare", "ResponseResult<MatchShareVO>", List.of(),
                List.of(), FIELDS, null, SUCCESS, List.of(), List.of(), List.of(), true, List.of(), params);
    }

    private static Map<String, String> query() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("matchId", "601514");
        q.put("gameType", "1");
        q.put("token", "{{token}}");
        return q;
    }

    @Test
    void missingParamCaseRemovesOnlyThatParamAndAssertsCurrentSilentBehavior() throws Exception {
        var matchId = new ParamInsight.Param("matchId", "query", "Integer", "number", false, "房间 id", "C.java:87",
                silent(), paramError(), List.of(), List.of(), null, List.of());
        var cases = SourceCases.plan(report(List.of(matchId)), query(), null, null, null, 8);

        var missing = cases.get(0);
        assertEquals("疑似问题 · 缺少 matchId → 返回成功但数据为空", missing.name());
        assertTrue(missing.suspect());
        assertEquals(SourceCases.NEGATIVE, missing.intent());
        assertFalse(missing.query().containsKey("matchId"));
        assertEquals("1", missing.query().get("gameType"));
        assertEquals("{{token}}", missing.query().get("token"));
        JsonNode a = JSON.readTree(missing.assertions());
        assertEquals("$.code", a.get(0).path("path").asText());
        assertEquals("1", a.get(0).path("value").asText());
        assertEquals("code", a.get(0).path("source").asText());
        assertEquals("$.result", a.get(1).path("path").asText());
        assertFalse(a.get(1).path("exists").asBoolean(true));
        assertTrue(missing.expect().contains("Dota2LiveServiceImpl.java:80"), missing.expect());
        assertTrue(missing.expect().contains("疑似问题"), missing.expect());

        var bad = cases.get(1);
        assertEquals("matchId 传 abc → 报 PARAM_ERROR 10001", bad.name());
        assertEquals("abc", bad.query().get("matchId"));
        assertEquals("10001", JSON.readTree(bad.assertions()).get(0).path("value").asText());
    }

    @Test
    void optionalParamNotInCaptureIsSkippedAndBodyBoundsAreNumbers() throws Exception {
        var newsId = new ParamInsight.Param("newsId", "query", "Integer", "number", false, null, "C.java:87",
                new ParamInsight.Outcome(ParamInsight.OPTIONAL, null, null, null, null, "判了非空才用", "S.java:84", false),
                null, List.of(), List.of(), null, List.of());
        var reject = new ParamInsight.Outcome(ParamInsight.REJECT, "10001", "PARAM_ERROR", null, null,
                "抛 AppEngineException(PARAM_ERROR 10001)", "C.java:97", false);
        var pageSize = new ParamInsight.Param("pageSize", "body", "int", "number", false, null, "P.java:13",
                reject, null, List.of(new ParamInsight.Bound("201", "request.getPageSize() > MAX_PAGE_SIZE_LIMIT", reject)),
                List.of(), null, List.of());
        var cases = SourceCases.plan(report(List.of(newsId, pageSize)), Map.of(),
                "{\"pageSize\":20,\"gameType\":1}", "$.code", null, 8);

        assertEquals(2, cases.size(), cases.toString());
        assertEquals("{\"gameType\":1}", cases.get(0).body());
        assertEquals("{\"pageSize\":201,\"gameType\":1}", cases.get(1).body());
    }

    @Test
    void unsupportedValuePicksSomethingTheCodeDoesNotKnow() {
        var gameType = new ParamInsight.Param("gameType", "query", "Integer", "number", false, null, "C.java:87",
                null, null, List.of(), List.of("1", "2"), silent(), List.of());
        var cases = SourceCases.plan(report(List.of(gameType)), query(), null, null, null, 8);
        assertEquals(1, cases.size());
        assertEquals("99", cases.get(0).query().get("gameType"));
        assertTrue(cases.get(0).name().contains("代码只认 1/2"), cases.get(0).name());
    }
}
