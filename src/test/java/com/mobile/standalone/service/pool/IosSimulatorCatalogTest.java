package com.mobile.standalone.service.pool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IosSimulatorCatalogTest {

    @Test
    void 只收iPhone和iPad并读出状态() {
        List<IosSimulatorCatalog.SimInfo> out = IosSimulatorCatalog.parse(List.of(
                "== Devices ==",
                "-- iOS 26.3 --",
                "    iPhone 17 Pro (5D421DA1-623F-41C7-BEFF-1916B94EF673) (Shutdown)",
                "    iPhone 17 (60AC8557-F87B-4B23-AC75-B65284C5F8E3) (Booted)",
                "    Apple Watch Series 11 (11111111-1111-1111-1111-111111111111) (Shutdown)",
                "    iPad Air 11-inch (M3) (7E94D769-62D5-44D7-B38E-2B3F268D8504) (Booting)"
        ));
        assertEquals(3, out.size());
        assertEquals("iPhone 17 Pro", out.get(0).name());
        assertTrue(out.get(1).booted());
        assertTrue(out.get(2).booting());
        assertEquals("iOS 26.3", out.get(0).runtime());
    }
}
