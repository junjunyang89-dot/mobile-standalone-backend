package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebugApiTestNestedJsonTest {

    @Test
    void buildNestedJsonSample_hasDeepPaths() {
        Map<String, Object> root = DebugApiTestService.buildNestedJsonSample("demo");

        assertEquals(0, root.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) data.get("user");
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) user.get("profile");
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) profile.get("address");
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) address.get("detail");
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) data.get("order");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> pay = (Map<String, Object>) order.get("pay");
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) pay.get("receipt");

        assertEquals("u-1001", user.get("id"));
        assertEquals("世纪大道", detail.get("street"));
        assertEquals("SKU-A", items.get(0).get("sku"));
        assertEquals("TXN-7788", receipt.get("txnId"));
        assertEquals(true, receipt.get("success"));
    }

    @Test
    void verifyNestedJson_passesWithMatchingExpect() {
        DebugApiTestService svc = new DebugApiTestService(null, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expect", Map.of(
                "userId", "u-1001",
                "firstSku", "SKU-A",
                "txnId", "TXN-7788",
                "scene", "demo"
        ));

        Map<String, Object> out = svc.verifyNestedJson(body);

        assertEquals(true, out.get("verified"));
        assertEquals("NEST-TXN-7788", out.get("ticket"));
        @SuppressWarnings("unchecked")
        Map<String, Object> echo = (Map<String, Object>) out.get("echo");
        @SuppressWarnings("unchecked")
        Map<String, Object> layer = (Map<String, Object>) echo.get("layer");
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) layer.get("inner");
        assertEquals("SKU-A", inner.get("firstSku"));
    }

    @Test
    void verifyNestedJson_rejectsEmptyBody() {
        DebugApiTestService svc = new DebugApiTestService(null, null);
        assertThrows(IllegalArgumentException.class, () -> svc.verifyNestedJson(null));
    }
}
