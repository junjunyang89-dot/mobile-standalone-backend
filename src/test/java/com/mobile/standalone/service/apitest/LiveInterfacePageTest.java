package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveInterfacePageTest {

    private static Map<String, Object> row(String id, String method, String path, String name,
                                           String controller, String level, String kind) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("method", method);
        m.put("path", path);
        m.put("name", name);
        m.put("controller", controller);
        m.put("level", level);
        m.put("caseKind", kind);
        m.put("enabled", true);
        return m;
    }

    @Test
    void groupsCasesByInterfaceAndPutsCoreFirst() {
        List<Map<String, Object>> rows = List.of(
                row("1", "get", "/a", "查A · 正常", "C", null, null),
                row("2", "POST", "/b", "写B · 正常", "C", "CORE", null),
                row("3", "GET", "/a", "查A · 缺参", "C", null, null));

        List<Map<String, Object>> groups = ApiTestCollectionService.groupByInterface(rows);

        assertThat(groups).extracting(g -> g.get("path")).containsExactly("/b", "/a");
        assertThat(groups.get(1).get("method")).isEqualTo("GET");
        assertThat(groups.get(1).get("title")).isEqualTo("查A");
        assertThat((List<?>) groups.get(1).get("cases")).hasSize(2);
    }

    @Test
    void filtersByControllerKeywordAndKind() {
        List<Map<String, Object>> rows = List.of(
                row("1", "GET", "/user/info", "用户信息 · 正常", "UserController", null, "REGRESSION"),
                row("2", "GET", "/user/list", "用户列表 · 正常", "UserController", null, null),
                row("3", "GET", "/order/list", "订单列表 · 正常", "OrderController", null, "REGRESSION"));

        assertThat(ApiTestCollectionService.filterLiveRows(rows, "UserController", null, null)).hasSize(2);
        assertThat(ApiTestCollectionService.filterLiveRows(rows, null, "LIST", null))
                .extracting(r -> r.get("id")).containsExactly("2", "3");
        assertThat(ApiTestCollectionService.filterLiveRows(rows, "UserController", null, "UNCLASSIFIED"))
                .extracting(r -> r.get("id")).containsExactly("2");
    }
}
