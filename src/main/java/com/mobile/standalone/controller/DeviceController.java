package com.mobile.standalone.controller;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.service.DeviceService;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * 首页用：已连接设备统计及列表。
     * 返回总数、Android/iOS/HarmonyOS 数量、以及已连接设备列表（设备名称、平台、状态）。
     */
    @GetMapping("/connected-summary")
    public ResponseEntity<DeviceService.ConnectedDeviceSummary> getConnectedDeviceSummary() {
        return ResponseEntity.ok(deviceService.getConnectedDeviceSummary());
    }

    /**
     * 设备详情：设备名称、电量、系统信息；已安装应用默认不含（很慢），见 {@code /devices/{id}/installed-apps}。
     * <p>
     * {@code includeApps=true} 兼容旧调用；{@code includeRaw=true} 才拉 getprop/ideviceinfo 全文（较慢）。
     * 结果短时缓存约 60s，重复点开会快很多。
     */
    @GetMapping("/{id}/detail")
    public ResponseEntity<DeviceService.DeviceDetail> getDeviceDetail(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "false") boolean includeApps,
            @RequestParam(required = false, defaultValue = "false") boolean includeRaw
    ) {
        DeviceService.DeviceDetail detail = deviceService.getDeviceDetail(id, includeApps, includeRaw);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    /** 已安装应用（单独接口，避免拖慢详情首屏） */
    @GetMapping("/{id}/installed-apps")
    public ResponseEntity<?> getInstalledApps(@PathVariable String id) {
        List<DeviceService.InstalledAppItem> apps = deviceService.getInstalledApps(id);
        if (apps == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("installedApplications", apps));
    }

    @GetMapping
    public ResponseEntity<List<Device>> getAllDevices() {
        return ResponseEntity.ok(deviceService.getAllDevices());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Device> getDevice(@PathVariable String id) {
        Device device = deviceService.getDeviceById(id);
        if (device != null) {
            return ResponseEntity.ok(device);
        }
        return ResponseEntity.notFound().build();
    }
    
    @PostMapping("/{id}/connect")
    public ResponseEntity<Device> connectDevice(@PathVariable String id) {
        Device device = deviceService.connectDevice(id);
        if (device != null) {
            return ResponseEntity.ok(device);
        }
        return ResponseEntity.notFound().build();
    }
    
    @PostMapping("/{id}/disconnect")
    public ResponseEntity<Void> disconnectDevice(@PathVariable String id) {
        boolean success = deviceService.disconnectDevice(id);
        if (success) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/platform/{platform}")
    public ResponseEntity<List<Device>> getDevicesByPlatform(@PathVariable String platform) {
        try {
            Device.Platform platformEnum = Device.Platform.valueOf(platform.toUpperCase());
            List<Device> devices = deviceService.getAllDevices();
            // 过滤出匹配平台的设备
            List<Device> filteredDevices = devices.stream()
                .filter(device -> Device.matchesAutomationDeviceListFilter(device.getPlatform(), platformEnum))
                .toList();
            return ResponseEntity.ok(filteredDevices);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 手动触发设备扫描（用于测试）
     */
    /**
     * 设置这台设备跑哪个环境。
     * <p>
     * 绑在设备上而不是每次执行时选：一台手机上装的就是某个环境的包。
     * 四台机器同时跑（两台线上两台测试）时，不用每次去勾一遍。
     */
    @PutMapping("/{id}/environment")
    public ResponseEntity<?> setEnvironment(@PathVariable String id,
                                            @RequestBody Map<String, String> body) {
        Device device = deviceService.getDeviceById(id);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }
        String env = body.get("environment");
        // 空串＝取消绑定，回到「执行时再定」；不能跟「这次不改」混为一谈
        device.setEnvironment(env == null || env.isBlank() ? "" : TestEnvironment.normalize(env));
        deviceService.updateDevice(device);
        return ResponseEntity.ok(Map.of("ok", true, "environment", device.getEnvironment()));
    }

    @PostMapping("/scan")
    public ResponseEntity<String> scanDevices() {
        try {
            deviceService.scanDevices();
            return ResponseEntity.ok("设备扫描完成");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("设备扫描失败: " + e.getMessage());
        }
    }
}
