package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiTestRunnerServiceTest {

    @Test
    void buildUrl_relativeWithQuery() throws Exception {
        String url = ApiTestRunnerService.buildUrl(
                "https://gateway-qa.arca42.com/",
                "/gld/demo",
                "{\"mode\":\"standard\",\"x\":1}"
        );
        assertTrue(url.startsWith("https://gateway-qa.arca42.com/gld/demo?"));
        assertTrue(url.contains("mode=standard"));
        assertTrue(url.contains("x=1"));
    }

    @Test
    void buildUrl_absoluteIgnoresBase() throws Exception {
        String url = ApiTestRunnerService.buildUrl(
                "https://gateway-qa.arca42.com",
                "https://example.com/a",
                "{}"
        );
        assertEquals("https://example.com/a", url);
    }

    @Test
    void buildUrl_queryVariablesResolvedBeforeEncoding() throws Exception {
        var vars = java.util.Map.of("steamId", "76561198000000001", "kw", "a b&c");
        String url = ApiTestRunnerService.buildUrl(
                "https://gwapi.pwesports.cn",
                "/appdatacenter/steamdata/user/steam-summary",
                "{\"steamId\":\"{{steamId}}\",\"q\":\"x-{{kw}}\"}",
                s -> com.mobile.standalone.service.apitest.VariableResolver.resolve(s, java.util.Map.of(), vars));
        assertTrue(url.contains("steamId=76561198000000001"), url);
        // 变量值里的特殊字符也要被编码，不能把 & 当成参数分隔符发出去
        assertTrue(url.contains("q=x-a+b%26c"), url);
        assertFalse(url.contains("%7B%7B"), url);
    }

    @Test
    void jsonPath_nestedAndIndex() throws Exception {
        String body = "{\"code\":0,\"data\":{\"list\":[{\"id\":\"a1\",\"winRate\":0.5}]}}";
        JsonNode code = ApiTestRunnerService.readJsonPath(body, "$.code");
        assertEquals(0, code.asInt());
        JsonNode id = ApiTestRunnerService.readJsonPath(body, "$.data.list[0].id");
        assertEquals("a1", id.asText());
        JsonNode wr = ApiTestRunnerService.readJsonPath(body, "$.data.list[0].winRate");
        assertEquals(0.5, wr.asDouble(), 1e-9);
    }
}
