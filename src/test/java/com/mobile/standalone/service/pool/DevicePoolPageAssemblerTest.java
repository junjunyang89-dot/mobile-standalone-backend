package com.mobile.standalone.service.pool;

import com.mobile.standalone.entity.Device;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DevicePoolPageAssemblerTest {

    @Test
    void 已开机的模拟器只出现一行() {
        Device d = device("udid-1", "iPhone 17（模拟器）", true);
        EmulatorLauncher.AvdView ready = avd("iPhone 17", EmulatorLauncher.State.READY, "udid-1");
        EmulatorLauncher.AvdView offline = avd("iPhone Air", EmulatorLauncher.State.NOT_RUNNING, "udid-2");

        List<Map<String, Object>> rows = DevicePoolPageAssembler.merge(List.of(d), List.of(ready, offline));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("catalogOnly")).isEqualTo(false);
        assertThat(rows.get(0).get("id")).isEqualTo("udid-1");
        assertThat(avdName(rows.get(0))).isEqualTo("iPhone 17");
        assertThat(rows.get(1).get("catalogOnly")).isEqualTo(true);
        assertThat(rows.get(1).get("name")).isEqualTo("iPhone Air");
        assertThat(rows.get(1).get("version")).isEqualTo("iOS 26.3");
    }

    @Test
    void 不按名字把真机和模拟器对上() {
        Device phone = device("real-udid", "iPhone 17", false);
        EmulatorLauncher.AvdView sim = avd("iPhone 17", EmulatorLauncher.State.NOT_RUNNING, "sim-udid");

        List<Map<String, Object>> rows = DevicePoolPageAssembler.merge(List.of(phone), List.of(sim));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("avd")).isNull();
        assertThat(rows.get(0).get("isEmulator")).isEqualTo(false);
        assertThat(rows.get(1).get("catalogOnly")).isEqualTo(true);
        assertThat(rows.get(1).get("id")).isEqualTo("sim-udid");
    }

    @Test
    void 已开机的安卓AVD和adb行合成一行() {
        Device d = new Device();
        d.setId("emulator-5554");
        d.setName("sdk_gphone64_arm64");
        d.setPlatform(Device.Platform.ANDROID);
        d.setStatus(Device.DeviceStatus.CONNECTED);
        d.setEmulator(true);
        EmulatorLauncher.AvdView ready = new EmulatorLauncher.AvdView(
                "Medium_Phone_API_36.1", EmulatorLauncher.State.READY,
                "emulator-5554", null, null, null, false, null, "ANDROID", "16");

        List<Map<String, Object>> rows = DevicePoolPageAssembler.merge(List.of(d), List.of(ready));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id")).isEqualTo("emulator-5554");
        assertThat(rows.get(0).get("catalogOnly")).isEqualTo(false);
        assertThat(avdName(rows.get(0))).isEqualTo("Medium_Phone_API_36.1");
    }

    @Test
    void 已关机的安卓AVD不留下幽灵已连接行() {
        Device ghost = new Device();
        ghost.setId("emulator-5554");
        ghost.setName("sdk_gphone64_arm64");
        ghost.setPlatform(Device.Platform.ANDROID);
        ghost.setStatus(Device.DeviceStatus.CONNECTED);
        ghost.setEmulator(true);
        EmulatorLauncher.AvdView catalog = new EmulatorLauncher.AvdView(
                "Medium_Phone_API_36.1", EmulatorLauncher.State.NOT_RUNNING,
                null, null, null, null, false, null, "ANDROID", null);

        List<Map<String, Object>> rows = DevicePoolPageAssembler.merge(List.of(ghost), List.of(catalog));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo("Medium_Phone_API_36.1");
        assertThat(rows.get(0).get("catalogOnly")).isEqualTo(true);
        assertThat(rows.get(0).get("status")).isEqualTo("DISCONNECTED");
    }

    @Test
    void 没开机的安卓AVD没有deviceId也要进表() {
        EmulatorLauncher.AvdView avd = new EmulatorLauncher.AvdView(
                "Medium_Phone_API_36.1", EmulatorLauncher.State.NOT_RUNNING,
                null, null, null, null, false, null, "ANDROID", null);

        List<Map<String, Object>> rows = DevicePoolPageAssembler.merge(List.of(), List.of(avd));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id")).isEqualTo("avd:Medium_Phone_API_36.1");
        assertThat(rows.get(0).get("catalogOnly")).isEqualTo(true);
        assertThat(rows.get(0).get("status")).isEqualTo("DISCONNECTED");
    }

    private static Device device(String id, String name, boolean emulator) {
        Device d = new Device();
        d.setId(id);
        d.setName(name);
        d.setPlatform(Device.Platform.IOS);
        d.setStatus(Device.DeviceStatus.CONNECTED);
        d.setEmulator(emulator);
        return d;
    }

    private static EmulatorLauncher.AvdView avd(String name, EmulatorLauncher.State state, String deviceId) {
        return new EmulatorLauncher.AvdView(name, state, deviceId, null, null, null, false, null, "IOS", "iOS 26.3");
    }

    @SuppressWarnings("unchecked")
    private static String avdName(Map<String, Object> row) {
        Map<String, Object> avd = (Map<String, Object>) row.get("avd");
        return avd == null ? null : String.valueOf(avd.get("name"));
    }
}
