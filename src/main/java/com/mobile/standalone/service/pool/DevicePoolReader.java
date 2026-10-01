package com.mobile.standalone.service.pool;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.DeviceLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「库里的设备 + 当前占用」拼成挑选规则认识的那份快照。
 *
 * <p>单独一个类，是为了让调度器和自动扩容看到<b>完全一样的池子</b>。
 * 两边各自去查库、各自判断在忙，结论迟早会差一点，
 * 而差的那一点表现出来就是「明明有空设备却在排队」或者「起了模拟器却没人用」。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DevicePoolReader {

    private final DeviceMapper deviceMapper;
    private final DeviceLockService deviceLockService;

    /**
     * @param alsoBusy 本轮已经被算走、但锁还没落下的设备；没有就传 null
     * @return 池子快照；查库失败时返回空列表（调用方按「没有可用设备」处理，而不是崩掉整轮）
     */
    public List<DevicePicker.Candidate> snapshot(Set<String> alsoBusy) {
        List<Device> all;
        try {
            all = deviceMapper.findAll();
        } catch (Exception e) {
            log.warn("[device-pool] 取设备列表失败: {}", e.getMessage());
            return List.of();
        }
        Map<String, DeviceLockService.Holder> holders = deviceLockService.snapshot();
        List<DevicePicker.Candidate> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        for (Device d : all) {
            if (d == null || d.getId() == null) continue;
            boolean busy = holders.containsKey(d.getId())
                    || (alsoBusy != null && alsoBusy.contains(d.getId()));
            out.add(new DevicePicker.Candidate(
                    d.getId(),
                    d.getName(),
                    d.getPlatform() == null ? null : d.getPlatform().name(),
                    d.getEnvironment(),
                    Device.isEmulator(d),
                    d.getStatus() == Device.DeviceStatus.CONNECTED,
                    Boolean.TRUE.equals(d.getDisabled()),
                    busy,
                    d.getLastUsedAt()));
        }
        return out;
    }

    /** 某台设备现在被谁占着；没人占返回 null */
    public DeviceLockService.Holder holderOf(String deviceId) {
        return deviceLockService.current(deviceId);
    }

    /** 这台设备上次派活是什么时候；查不到返回 null */
    public java.time.LocalDateTime lastUsedAt(String deviceId) {
        try {
            Device d = deviceMapper.findById(deviceId);
            return d == null ? null : d.getLastUsedAt();
        } catch (Exception e) {
            return null;
        }
    }
}
