package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightCasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EndpointInsight.Code SUCCESS = new EndpointInsight.Code("SUCCESS", "1", "success", "C.java:63");
    private static final List<EndpointInsight.Field> FIELDS = List.of(
            new EndpointInsight.Field("$.code", "code", "number", "int", null, null, null));

    private static EndpointInsight.Report report(EndpointInsight.Login login, List<EndpointInsight.Risk> risks) {
        return new EndpointInsight.Report("C.java", 62, "m", "ResponseResult<X>", List.of(), List.of(), FIELDS, login,
                SUCCESS, List.of(), List.of(), List.of(), true, risks, List.of());
    }

    private static Map<String, String> query() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("token", "{{token}}");
        q.put("steamId", "1");
        return q;
    }

    @Test
    void 要登录的接口期望登录失败码() throws Exception {
        var login = new EndpointInsight.Login(true, "@NeedUserLogin", false, false, "C.java:51",
                new EndpointInsight.Code("INVALID_TOKEN_ERROR", "4001", "登录失效", "NeedUserLoginAspect.java:51"));
        var p = InsightCases.noToken(report(login, List.of()), query(), Map.of("X-Ver", "1", "token", "abc"),
                List.of("x-app-token"), null, "0");
        assertFalse(p.suspect());
        assertEquals("不带 token · 应提示登录", p.name());
        assertEquals(Map.of("steamId", "1"), p.query());
        assertEquals("", p.headers().get("token"));
        assertEquals("", p.headers().get("Authorization"));
        assertEquals("", p.headers().get("x-app-token"));
        assertEquals("1", p.headers().get("X-Ver"));
        JsonNode a = JSON.readTree(p.assertions()).get(0);
        assertEquals("$.code", a.path("path").asText());
        assertEquals("eq", a.path("op").asText());
        assertEquals("4001", a.path("value").asText());
        assertEquals("code", a.path("source").asText());
    }

    @Test
    void 登录注解被注释的写成疑似问题_断言不是成功码() throws Exception {
        var login = new EndpointInsight.Login(false, "@NeedUserLogin", true, false, "MemberController.java:61", null);
        var risk = new EndpointInsight.Risk("HIGH", "SILENT_NO_LOGIN", "拿不到登录信息也返回成功",
                "if (tokenDetail == null) 时直接 return new VipNumSummaryDTO()", "MemberServiceImpl.java:393");
        var p = InsightCases.noToken(report(login, List.of(risk)), query(), Map.of(), List.of(), "$.code", "0");
        assertTrue(p.suspect());
        assertTrue(p.name().startsWith("疑似问题"));
        assertTrue(p.note().contains("MemberController.java:61"));
        assertTrue(p.note().contains("MemberServiceImpl.java:393"));
        JsonNode a = JSON.readTree(p.assertions()).get(0);
        assertEquals("ne", a.path("op").asText());
        assertEquals("1", a.path("value").asText(), "用接口自己的成功码，不是项目配的 0");
    }

    @Test
    void 看不出登录要求就不生成() {
        var login = new EndpointInsight.Login(null, null, false, false, null, null);
        assertNull(InsightCases.noToken(report(login, List.of()), query(), Map.of(), List.of(), null, "0"));
        assertNull(InsightCases.noToken(null, query(), Map.of(), List.of(), null, "0"));
    }
}
