package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiTestRunServiceDedupeTest {

    private static Map<String, Object> iface(String path, int cases, int failed, int blocked, String error, Integer http) {
        Map<String, Object> m = new HashMap<>();
        m.put("project", "app-data-center");
        m.put("method", "GET");
        m.put("path", path);
        m.put("caseCount", cases);
        m.put("failedCount", failed);
        m.put("blockedCount", blocked);
        m.put("error", error);
        m.put("httpStatus", http);
        return m;
    }

    /** 同一接口被拆成两行（一行用例有归属项目、一行没有），失败只在第二行：合并后不能当成全通过 */
    @Test
    void mergesCountsAndKeepsFailureFromLaterRow() {
        List<Map<String, Object>> out = ApiTestRunService.dedupeInterfaces(List.of(
                iface("/a", 2, 0, 1, null, 200),
                iface("/a", 1, 1, 0, "断言未通过 status", 500),
                iface("/b", 1, 0, 0, null, 200)));

        assertEquals(2, out.size());
        Map<String, Object> a = out.get(0);
        assertEquals(3, a.get("caseCount"));
        assertEquals(1, a.get("failedCount"));
        assertEquals(1, a.get("blockedCount"));
        assertEquals("断言未通过 status", a.get("error"));
        assertEquals(500, a.get("httpStatus"));
    }
}
