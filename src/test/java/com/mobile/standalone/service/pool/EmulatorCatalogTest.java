package com.mobile.standalone.service.pool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * adb 的输出格式在不同版本、不同连接方式（USB / TCP）下并不一致，
 * 而这里认错一行的后果是模拟器凭空多一台或少一台——设备池里「模拟器 0」这种问题
 * 就是这么来的，且没有任何报错提示。
 */
class EmulatorCatalogTest {

    @Test
    void 只取状态为device的行() {
        List<String> out = EmulatorCatalog.parseAdbDevices(List.of(
                "List of devices attached",
                "emulator-5554\tdevice",
                "PGT_AN00       device product:PGT_AN00 model:PGT_AN00 device:PGT_AN00 transport_id:3",
                "127.0.0.1:5555 device product:ASUS_Z01QD",
                ""));
        assertEquals(List.of("emulator-5554", "PGT_AN00", "127.0.0.1:5555"), out);
    }

    @Test
    void 离线未授权和引导态都不算在线() {
        List<String> out = EmulatorCatalog.parseAdbDevices(List.of(
                "List of devices attached",
                "emulator-5554\toffline",
                "abc123\tunauthorized",
                "def456\tbootloader",
                "ok789\tdevice"));
        assertEquals(List.of("ok789"), out);
    }

    /**
     * 这一条是「包含 device 就算在线」那种写法会踩的坑：
     * adb devices -l 的描述字段里就带着 device:emu64a。
     */
    @Test
    void 描述字段里的device不会被当成状态() {
        List<String> out = EmulatorCatalog.parseAdbDevices(List.of(
                "emulator-5554  offline product:sdk_gphone64_arm64 device:emu64a"));
        assertTrue(out.isEmpty(), "离线的模拟器不该因为描述里有 device:emu64a 就算在线");
    }

    @Test
    void 守护进程启动提示行要跳过() {
        List<String> out = EmulatorCatalog.parseAdbDevices(List.of(
                "* daemon not running; starting now at tcp:5037 *",
                "* daemon started successfully *",
                "List of devices attached",
                "emulator-5556\tdevice"));
        assertEquals(List.of("emulator-5556"), out);
    }

    @Test
    void 空输入不抛异常() {
        assertTrue(EmulatorCatalog.parseAdbDevices(null).isEmpty());
        assertTrue(EmulatorCatalog.parseAdbDevices(List.of()).isEmpty());
        assertTrue(EmulatorCatalog.parseAdbDevices(List.of("List of devices attached", "")).isEmpty());
    }

    // ==================== adb emu avd name ====================

    @Test
    void USB真机不是模拟器序列号() {
        assertTrue(EmulatorCatalog.isEmulatorSerial("emulator-5554"));
        assertTrue(EmulatorCatalog.isEmulatorSerial("127.0.0.1:5555"));
        assertTrue(!EmulatorCatalog.isEmulatorSerial("A4YQ023B06001086"));
        assertTrue(!EmulatorCatalog.isEmulatorSerial("PGT_AN00"));
    }

    @Test
    void 取出avd名并丢掉OK行() {
        assertEquals("Pixel_7_API_34",
                EmulatorCatalog.parseAvdName(List.of("Pixel_7_API_34", "OK")));
    }

    /** 第三方模拟器（雷电/夜神）不认这个命令——返回 null 是预期内的，不是错误 */
    @Test
    void 认不出命令时返回null() {
        assertNull(EmulatorCatalog.parseAvdName(List.of("KO: unknown command")));
        assertNull(EmulatorCatalog.parseAvdName(List.of("error: device not found")));
        assertNull(EmulatorCatalog.parseAvdName(null));
        assertNull(EmulatorCatalog.parseAvdName(List.of()));
        assertNull(EmulatorCatalog.parseAvdName(List.of("", "  ", "OK")));
    }
}
