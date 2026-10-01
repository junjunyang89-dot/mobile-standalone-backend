package com.mobile.standalone.service.pool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarmonyEmulatorCatalogTest {

    @Test
    void 空名单不当成一台() {
        assertTrue(HarmonyEmulatorCatalog.parse("[Empty]").isEmpty());
        assertTrue(HarmonyEmulatorCatalog.parse("Empty").isEmpty());
        assertTrue(HarmonyEmulatorCatalog.parse("[]").isEmpty());
        assertTrue(HarmonyEmulatorCatalog.parse("").isEmpty());
    }

    @Test
    void 读JSON里的名字状态和环回序列号() {
        String json = """
                [
                  {"name":"Phone","status":"Stopped","connectKey":"-","deviceType":"Phone","osVersion":"HarmonyOS 6.1.1(24)"},
                  {"Name":"Phone2","Status":"Running","hdcPort":5555,"deviceType":"Phone","osVersion":"HarmonyOS 6.1.1(24)"}
                ]
                """;
        List<HarmonyEmulatorCatalog.Instance> out = HarmonyEmulatorCatalog.parse(json);
        assertEquals(2, out.size());
        assertEquals("Phone", out.get(0).name());
        assertFalse(out.get(0).running());
        assertNull(out.get(0).serial());
        assertTrue(out.get(1).running());
        assertEquals("127.0.0.1:5555", out.get(1).serial());
        assertEquals("HarmonyOS 6.1.1(24)", out.get(1).osVersion());
    }

    @Test
    void 读表格式并跳过表头() {
        List<HarmonyEmulatorCatalog.Instance> out = HarmonyEmulatorCatalog.parse("""
                Name        Status     ConnectKey        DeviceType  OSVersion
                Phone       Stopped    -                 Phone       HarmonyOS 6.1.1(24)
                Phone2      Running    127.0.0.1:5555    Phone       HarmonyOS 6.1.1(24)
                """);
        assertEquals(2, out.size());
        assertEquals("Phone", out.get(0).name());
        assertNull(out.get(0).serial());
        assertTrue(out.get(1).running());
        assertEquals("127.0.0.1:5555", out.get(1).serial());
    }

    @Test
    void 读DevEco的list_details字段() {
        String json = """
                [{
                  "name": "Phone",
                  "isRunning": "true",
                  "deviceType": "phone",
                  "hw.hdc.port": "5557",
                  "os.osVersion": "HarmonyOS 6.1.1(24)"
                }]
                """;
        List<HarmonyEmulatorCatalog.Instance> out = HarmonyEmulatorCatalog.parse(json);
        assertEquals(1, out.size());
        assertTrue(out.get(0).running());
        assertEquals("127.0.0.1:5557", out.get(0).serial());
        assertEquals("HarmonyOS 6.1.1(24)", out.get(0).osVersion());
    }

    @Test
    void 开机中也要认() {
        List<HarmonyEmulatorCatalog.Instance> out = HarmonyEmulatorCatalog.parse(
                "[{\"name\":\"Phone\",\"status\":\"Starting\"}]");
        assertEquals(1, out.size());
        assertTrue(out.get(0).booting());
        assertFalse(out.get(0).running());
    }
}
