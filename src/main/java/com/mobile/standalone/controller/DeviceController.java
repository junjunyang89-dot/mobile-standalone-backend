package com.mobile.standalone.controller;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
     * 设备详情：设备名称、电量、已安装应用列表；iOS 返回 iosDetail/iosDetailRaw，Android 返回 androidDetail/androidDetailRaw。
     * 前端务必展示 iosDetailRaw 或 androidDetailRaw（如用 &lt;pre&gt;），有数据时会完整显示，失败时会显示错误提示。
     */
    @GetMapping("/{id}/detail")
    public ResponseEntity<DeviceService.DeviceDetail> getDeviceDetail(@PathVariable String id) {
        DeviceService.DeviceDetail detail = deviceService.getDeviceDetail(id);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
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
