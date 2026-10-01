package com.mobile.standalone.service.pool;

import com.mobile.standalone.entity.Device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备池页那一张表：已入库设备 + 还没开机的模拟器目录。
 * <p>
 * 只按 {@code avd.deviceId == device.id} 对齐。不能按名字——真机「iPhone 17」
 * 和模拟器「iPhone 17」会撞上，启停按钮就会出现在真机那一行。
 */
public final class DevicePoolPageAssembler {

    private DevicePoolPageAssembler() {
    }

    public static List<Map<String, Object>> merge(List<Device> devices, List<EmulatorLauncher.AvdView> avds) {
        List<EmulatorLauncher.AvdView> catalog = avds == null ? List.of() : avds;
        Map<String, EmulatorLauncher.AvdView> byDeviceId = new LinkedHashMap<>();
        for (EmulatorLauncher.AvdView avd : catalog) {
            if (avd == null || avd.deviceId() == null || avd.deviceId().isBlank()) continue;
            byDeviceId.putIfAbsent(avd.deviceId(), avd);
        }

        Set<String> claimed = new LinkedHashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (devices != null) {
            for (Device d : devices) {
                if (d == null || d.getId() == null) continue;
                EmulatorLauncher.AvdView avd = byDeviceId.get(d.getId());
                if (avd != null) claimed.add(avd.name());
                // 上次扫进来的 emulator-5554 还在库里写着已连接，进程其实没了。
                // 再画一行「sdk_gphone64 已连接」，人就会以为窗口还在，找不到 Medium_Phone 那行启动。
                if (avd == null && isStaleAvdGhost(d)) {
                    continue;
                }
                rows.add(deviceRow(d, avd));
            }
        }
        for (EmulatorLauncher.AvdView avd : catalog) {
            if (avd == null || claimed.contains(avd.name())) continue;
            rows.add(catalogRow(avd));
        }
        return rows;
    }

    private static Map<String, Object> deviceRow(Device d, EmulatorLauncher.AvdView avd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", displayName(d.getName()));
        m.put("platform", d.getPlatform() == null ? null : d.getPlatform().name());
        m.put("version", firstNonBlank(d.getVersion(), avd == null ? null : avd.version()));
        m.put("status", d.getStatus() == null ? null : d.getStatus().name());
        m.put("lastSeen", d.getLastSeen());
        m.put("environment", d.getEnvironment());
        m.put("isEmulator", Device.isEmulator(d));
        m.put("disabled", Boolean.TRUE.equals(d.getDisabled()));
        m.put("catalogOnly", false);
        m.put("avd", avd == null ? null : EmulatorLauncher.toMap(avd));
        return m;
    }

    private static Map<String, Object> catalogRow(EmulatorLauncher.AvdView avd) {
        Map<String, Object> m = new LinkedHashMap<>();
        String id = avd.deviceId() != null && !avd.deviceId().isBlank()
                ? avd.deviceId()
                : "avd:" + avd.name();
        m.put("id", id);
        m.put("name", displayName(avd.name()));
        m.put("platform", avd.platform() == null ? "ANDROID" : avd.platform());
        m.put("version", avd.version());
        m.put("status", avd.state() == EmulatorLauncher.State.READY ? "CONNECTED" : "DISCONNECTED");
        m.put("lastSeen", null);
        m.put("environment", "");
        m.put("isEmulator", true);
        m.put("disabled", false);
        m.put("catalogOnly", true);
        m.put("avd", EmulatorLauncher.toMap(avd));
        return m;
    }

    /** 上次扫进来的 emulator-5554 还在库里，进程已经没了。 */
    static boolean isStaleAvdGhost(Device d) {
        if (!Device.isEmulator(d)) return false;
        String id = d.getId();
        return id != null && id.startsWith("emulator-");
    }

    /** 名称旁已有「模拟器」标签，后缀再写一遍会让上下两截不像同一张表 */
    private static String displayName(String name) {
        if (name == null || name.isBlank()) return name;
        return name.replaceFirst("（模拟器）$", "").trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
