package com.mobile.standalone.controller;

import com.mobile.standalone.entity.DebugApiTest;
import com.mobile.standalone.service.DebugApiTestService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebugApiTestEnabledVoTest {

    @Test
    void toEnabledVo_mapsActiveToTrue() {
        DebugApiTest row = new DebugApiTest();
        row.setId(7L);
        row.setName("样例-登录");
        row.setStatus("ACTIVE");

        Map<String, Object> vo = DebugApiTestController.toEnabledVo(row);

        assertEquals(7L, vo.get("id"));
        assertEquals("样例-登录", vo.get("name"));
        assertEquals(true, vo.get("enabled"));
        assertEquals("ACTIVE", vo.get("status"));
        assertTrue(DebugApiTestService.isEnabled(row));
    }

    @Test
    void toEnabledVo_mapsDisabledToFalse() {
        DebugApiTest row = new DebugApiTest();
        row.setId(8L);
        row.setName("样例-下单");
        row.setStatus("DISABLED");

        Map<String, Object> vo = DebugApiTestController.toEnabledVo(row);

        assertEquals(false, vo.get("enabled"));
        assertEquals("DISABLED", vo.get("status"));
        assertFalse(DebugApiTestService.isEnabled(row));
    }
}
