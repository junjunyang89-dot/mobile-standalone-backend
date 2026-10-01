package com.mobile.standalone.service.apitest;

import com.mobile.standalone.dto.ApiTestDtos.CaseResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCaseGeneratorTest {

    private static final String CONTROLLER = """
            @RestController
            @RequestMapping("/ai")
            public class AiController {
                @Resource
                private AiService aiService;

                @GetMapping("/list/{id}")
                public ResponseResult<List<X>> list(@PathVariable Long id) { return null; }

                /** 获取评论 */
                @GetMapping("/getAiComment")
                @NeedUserLogin
                public ResponseResult<AiCommentVO> getAiComment(@RequestParam("matchId") String matchId, QueryParam q) {
                    if (matchId == null) { throw new X("{bad}"); }
                    return ok(aiService.getAiComment(Long.parseLong(matchId)));
                }
            }
            """;

    @Test
    void methodSnippetIncludesAnnotationsAndBodyOnly() {
        String m = JavaSnippets.method(CONTROLLER, "getAiComment");
        assertNotNull(m);
        assertTrue(m.startsWith("/** 获取评论 */"));
        assertTrue(m.contains("@NeedUserLogin"));
        assertTrue(m.endsWith("}"));
        assertFalse(m.contains("list("));
        assertEquals("AiService", JavaSnippets.fieldType(CONTROLLER, "aiService"));
        assertTrue(JavaSnippets.typeNames(m.substring(0, m.indexOf('{'))).contains("QueryParam"));
        assertEquals("aiService", JavaSnippets.calls(m).get(0)[0]);
    }

    @Test
    void callSiteIsNotMistakenForDeclaration() {
        String src = """
                class S {
                    void a() { int x = getAiComment(1); }
                    AiCommentVO getAiComment(long id) { return dao.find(id); }
                }
                """;
        String m = JavaSnippets.method(src, "getAiComment");
        assertNotNull(m);
        assertTrue(m.startsWith("AiCommentVO getAiComment"));
    }

    @Test
    void fieldsOnlyDropsBodiesAndAccessors() {
        String dto = """
                package a;
                import b.C;
                public class Req extends BaseUserRequest {
                    /** 1=标准 4=天梯 */
                    private Integer mode = 1;
                    private String name;
                    public String getName() { return name; }
                    public void check() { if (mode > 1) { mode = 1; } }
                }
                """;
        String s = JavaSnippets.fieldsOnly(dto, 5000);
        assertTrue(s.contains("1=标准"));
        assertTrue(s.contains("private Integer mode = 1;"));
        assertFalse(s.contains("getName"));
        assertFalse(s.contains("import"));
        assertTrue(s.contains("check() {…}"));
        assertEquals("BaseUserRequest", JavaSnippets.superclass(dto));
    }

    @Test
    void parsePlanStripsProseAndLoginParams() throws Exception {
        var plan = AiCaseGenerator.parsePlan("""
                好的：
                ```json
                {"cases":[{"name":"正常查询","kind":"NORMAL","expect":"SUCCESS","query":{"matchId":"123","token":"x"}},
                          {"name":"缺少 matchId","kind":"BOUNDARY","expect":"ERROR","query":{},"basis":"@RequestParam 必填"}],
                 "dataParams":["matchId"]}
                ```
                """);
        assertEquals(2, plan.cases().size());
        assertEquals(Map.of("matchId", "123"), plan.cases().get(0).query());
        assertEquals("ERROR", plan.cases().get(1).expect());
        assertEquals(List.of("matchId"), plan.dataParams());
    }

    @Test
    void fillDataPrefersVariableThenCaptureThenHarvest() {
        List<String> filled = new ArrayList<>();
        Map<String, String> q = new LinkedHashMap<>(Map.of("matchId", "1", "steamId", "2", "other", "3"));
        AiCaseGenerator.fillData(q, List.of("matchId", "steamId", "other"),
                Map.of("matchId", "999"), Map.of("steamId", "765"), Map.of("other", "h1"), filled);
        assertEquals("{{matchId}}", q.get("matchId"));
        assertEquals("765", q.get("steamId"));
        assertEquals("h1", q.get("other"));
        assertEquals(3, filled.size());
    }

    private static CaseResult result(int http, String body) {
        CaseResult r = new CaseResult();
        r.setStatus("PASSED");
        r.setHttpStatus(http);
        r.setRawBody(body);
        return r;
    }

    private static AiCaseGenerator.AiCase ac(String expect) {
        return new AiCaseGenerator.AiCase("x", "SUCCESS".equals(expect) ? "NORMAL" : "BOUNDARY", expect,
                Map.of(), Map.of(), null, "");
    }

    @Test
    void successAssertsCodeAndExistingFields() {
        var v = AiCaseGenerator.judge(ac("SUCCESS"),
                result(200, "{\"code\":0,\"result\":{\"id\":5,\"title\":\"a\",\"empty\":null}}"),
                "$.code", "0", List.of(), List.of());
        assertTrue(v.enabled());
        assertTrue(v.assertions().contains("\"path\":\"$.code\",\"equals\":0"));
        assertTrue(v.assertions().contains("$.result.id"));
        assertTrue(v.assertions().contains("$.result.title"));
        assertFalse(v.assertions().contains("$.result.empty"));
    }

    @Test
    void successButBizErrorIsPendingWithDataHint() {
        var v = AiCaseGenerator.judge(ac("SUCCESS"),
                result(200, "{\"code\":5001,\"message\":\"比赛不存在\"}"),
                "$.code", "0", List.of("matchId"), List.of());
        assertFalse(v.enabled());
        assertNull(v.assertions());
        assertTrue(v.note().contains("5001"));
        assertTrue(v.note().contains("matchId"));
    }

    @Test
    void expectedErrorPinsActualCode() {
        var v = AiCaseGenerator.judge(ac("ERROR"),
                result(200, "{\"code\":1002,\"message\":\"参数错误\"}"), "$.code", "0", List.of(), List.of());
        assertTrue(v.enabled());
        assertTrue(v.assertions().contains("\"equals\":1002"));

        var missing = AiCaseGenerator.judge(ac("ERROR"), result(400, "{}"), "$.code", "0", List.of(), List.of());
        assertTrue(missing.enabled());
        assertTrue(missing.assertions().contains("\"equals\":400"));
    }

    @Test
    void expectedErrorButSuccessNeedsHuman() {
        var v = AiCaseGenerator.judge(ac("ERROR"), result(200, "{\"code\":0}"), "$.code", "0", List.of(), List.of());
        assertFalse(v.enabled());
        assertTrue(v.note().contains("实际返回成功"));
    }

    @Test
    void harvestCollectsIdsFromNestedLists() {
        Map<String, String> h = new HashMap<>();
        AiCaseGenerator.harvest("{\"code\":0,\"result\":{\"list\":[{\"matchId\":\"88\",\"teamId\":0,\"name\":\"a\"}]}}", h);
        assertEquals("88", h.get("matchid"));
        assertFalse(h.containsKey("teamid"));
        assertFalse(h.containsKey("name"));
    }

    @Test
    void unrunSuccessEnabledOnlyWhenDataKnown() {
        var ok = AiCaseGenerator.unrun(ac("SUCCESS"), "$.code", "0", List.of("matchId"), List.of("matchId=88（抓包）"));
        assertTrue(ok.enabled());
        assertTrue(ok.assertions().contains("\"equals\":200"));
        assertTrue(ok.assertions().contains("\"path\":\"$.code\",\"equals\":0"));

        var guess = AiCaseGenerator.unrun(ac("SUCCESS"), "$.code", "0", List.of("matchId"), List.of());
        assertFalse(guess.enabled());
        assertTrue(guess.note().contains("matchId"));
    }

    @Test
    void unrunErrorAssertsNotSuccessCode() {
        var v = AiCaseGenerator.unrun(ac("ERROR"), "$.code", "0", List.of(), List.of());
        assertTrue(v.enabled());
        assertTrue(v.assertions().contains("\"notEquals\":0"));
        assertFalse(AiCaseGenerator.unrun(ac("ERROR"), null, null, List.of(), List.of()).enabled());
    }
}
