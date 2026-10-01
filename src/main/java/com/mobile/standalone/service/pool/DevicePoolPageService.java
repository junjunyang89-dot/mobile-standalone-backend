package com.mobile.standalone.service.pool;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备池页专用快照。选设备 / 排班仍走 {@code GET /devices}，不要读这个。
 */
@Service
@RequiredArgsConstructor
public class DevicePoolPageService {

    private final DeviceService deviceService;
    private final EmulatorLauncher emulatorLauncher;

    public Map<String, Object> snapshot() {
        EmulatorLauncher.Overview overview = emulatorLauncher.overview();
        List<Device> devices = deviceService.getAllDevices();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", DevicePoolPageAssembler.merge(devices, overview.avds()));
        out.put("emulatorCliAvailable", overview.emulatorCliAvailable());
        out.put("runningUnmatched", overview.runningUnmatched());
        out.put("runningCount", overview.runningCount());
        out.put("maxRunning", overview.maxRunning());
        out.put("hint", overview.hint());
        return out;
    }
}
