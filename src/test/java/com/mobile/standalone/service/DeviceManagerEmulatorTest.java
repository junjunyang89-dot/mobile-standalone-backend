package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟器定性宁可漏判、不能误伤真机。
 * 整份 getprop 里搜 emulator、characteristics 没右括号就往下吃，都会把真机判成模拟器。
 */
class DeviceManagerEmulatorTest {

    @Test
    void 本地AVD序列号在读不到属性时也能认() {
        assertTrue(DeviceManager.looksLikeEmulator("emulator-5554", null));
        assertTrue(DeviceManager.looksLikeEmulator("emulator-5554", "   "));
    }

    @Test
    void 真机序列号读不到属性不当模拟器() {
        assertFalse(DeviceManager.looksLikeEmulator("R58M30ABCDE", null));
        assertFalse(DeviceManager.looksLikeEmulator("127.0.0.1:5555", null));
    }

    @Test
    void qemu标志认模拟器() {
        assertTrue(DeviceManager.looksLikeEmulator("R58M30ABCDE", line("ro.kernel.qemu", "1")));
        assertTrue(DeviceManager.looksLikeEmulator("R58M30ABCDE", line("ro.boot.qemu", "1")));
    }

    @Test
    void characteristics带emulator才认() {
        assertTrue(DeviceManager.looksLikeEmulator(
                "R58M30ABCDE", line("ro.build.characteristics", "emulator,nosdcard")));
    }

    @Test
    void characteristics没有右括号不往下吃() {
        // 陷阱：没右括号时若吃到文件结尾，后面任何含 emulator 的行都会误伤
        String props = "[ro.build.characteristics]: [default\n"
                + "[ro.product.name]: [something_emulator_helper]\n";
        assertFalse(DeviceManager.looksLikeEmulator("R58M30ABCDE", props));
    }

    @Test
    void 别的属性值里带emulator不认() {
        String props = line("ro.product.model", "Pixel 8")
                + line("persist.sys.app", "com.example.emulator_helper")
                + line("ro.build.characteristics", "nosdcard");
        assertFalse(DeviceManager.looksLikeEmulator("R58M30ABCDE", props));
    }

    @Test
    void 已知模拟器硬件() {
        for (String hw : new String[]{"goldfish", "ranchu", "vbox86", "android_x86", "ttvm"}) {
            assertTrue(DeviceManager.looksLikeEmulator("127.0.0.1:5555", line("ro.hardware", hw)), hw);
        }
    }

    @Test
    void 真机常见getprop不当模拟器() {
        String props = line("ro.kernel.qemu", "0")
                + line("ro.hardware", "qcom")
                + line("ro.build.characteristics", "nosdcard")
                + line("ro.product.model", "SM-S9180");
        assertFalse(DeviceManager.looksLikeEmulator("R58M30ABCDE", props));
    }

    @Test
    void 列为空时emulator序列号仍算模拟器() {
        Device avd = new Device();
        avd.setId("emulator-5554");
        assertTrue(Device.isEmulator(avd));
        Device phone = new Device();
        phone.setId("R58M30ABCDE");
        assertFalse(Device.isEmulator(phone));
        Device ld = new Device();
        ld.setId("127.0.0.1:5555");
        assertFalse(Device.isEmulator(ld));
    }

    @Test
    void 鸿蒙环回地址才算模拟器() {
        assertTrue(DeviceManager.looksLikeHarmonyEmulator("127.0.0.1:5555", "Huawei Phone"));
        assertTrue(DeviceManager.looksLikeHarmonyEmulator("localhost:5555", null));
        assertTrue(DeviceManager.looksLikeHarmonyEmulator("6UNBB26604017338", "Phone（模拟器）"));
        assertFalse(DeviceManager.looksLikeHarmonyEmulator("6UNBB26604017338", "华为畅享 90 Pro Max"));
        assertFalse(DeviceManager.looksLikeHarmonyEmulator("192.168.1.20:5555", "CHZ-AL00"));
    }

    @Test
    void 收下雷电夜神这种hostPort序列号() {
        assertTrue(DeviceManager.isValidAdbDeviceId("127.0.0.1:5555"));
        assertTrue(DeviceManager.isValidAdbDeviceId("localhost:62001"));
        assertTrue(DeviceManager.isValidAdbDeviceId("emulator-5554"));
        assertFalse(DeviceManager.isValidAdbDeviceId("adb: device 'List' not found"));
        assertFalse(DeviceManager.isValidAdbDeviceId("List"));
    }

    private static String line(String key, String value) {
        return "[" + key + "]: [" + value + "]\n";
    }
}
