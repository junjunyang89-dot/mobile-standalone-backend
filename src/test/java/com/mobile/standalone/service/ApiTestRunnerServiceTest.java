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
