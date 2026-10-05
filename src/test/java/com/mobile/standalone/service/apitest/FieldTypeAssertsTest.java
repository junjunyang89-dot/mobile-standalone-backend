package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldTypeAssertsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, String> typesByPath(ArrayNode out) {
        Map<String, String> m = new LinkedHashMap<>();
        for (JsonNode a : out) {
            if ("type".equals(a.path("op").asText())) m.put(a.get("path").asText(), a.get("value").asText());
        }
        return m;
    }

    @Test
    void 每个有值的字段都断言类型_不再只挑几个() {
        ArrayNode out = JSON.createArrayNode();
        out.addObject().put("type", "jsonPath").put("path", "$.code").put("equals", 0);
        FieldTypeAsserts.add(out,
                "{\"code\":0,\"message\":null,\"result\":{\"highlight\":584,\"newsReply\":4,\"secretary\":28,"
                        + "\"message\":1,\"notification_neg\":1}}",
                AssertionSource.CAPTURE, "抓包响应里");

        assertThat(typesByPath(out)).containsExactly(
                Map.entry("$.result", "object"),
                Map.entry("$.result.highlight", "number"),
                Map.entry("$.result.newsReply", "number"),
                Map.entry("$.result.secretary", "number"),
                Map.entry("$.result.message", "number"),
                Map.entry("$.result.notification_neg", "number"));
        assertThat(out.toString())
                .contains("\"source\":\"capture\"")
                .contains("抓包响应里是 number（584）");
    }

    @Test
    void 已经断言过的路径和null字段不重复不硬猜() {
        ArrayNode out = JSON.createArrayNode();
        out.addObject().put("type", "jsonPath").put("path", "$.result").put("exists", true);
        FieldTypeAsserts.add(out, "{\"code\":0,\"result\":{\"avatar\":null,\"name\":\"a\"}}",
                AssertionSource.TRYRUN, "试跑响应里");

        assertThat(typesByPath(out)).containsOnlyKeys("$.code", "$.result.name");
    }

    @Test
    void 不进数组元素_列表下次可能是空的() {
        ArrayNode out = JSON.createArrayNode();
        FieldTypeAsserts.add(out, "{\"data\":{\"list\":[{\"id\":1}],\"total\":1}}",
                AssertionSource.CAPTURE, "抓包响应里");

        assertThat(typesByPath(out)).containsOnlyKeys("$.data", "$.data.list", "$.data.total");
        assertThat(out.toString()).doesNotContain("[0]");
    }

    @Test
    void 不是JSON就什么都不加() {
        ArrayNode out = JSON.createArrayNode();
        FieldTypeAsserts.add(out, "<html>", AssertionSource.CAPTURE, "抓包响应里");
        FieldTypeAsserts.add(out, null, AssertionSource.CAPTURE, "抓包响应里");
        assertThat(out).isEmpty();
    }
}
