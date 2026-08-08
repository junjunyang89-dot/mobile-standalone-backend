package com.mobile.standalone.service;

import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.UiApp;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 将业务应用字典 id（或自定义包名）解析为 tasks.app_id 所需的 apps 表记录（设备已安装应用）。
 * tasks.app_id 有外键指向 apps.id，不能直接写 ui_apps.id。
 * 包名一律来自调用方 / 应用字典，不做业务包名写死兜底。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskAppResolver {

    private final DeviceMapper deviceMapper;
    private final ApplicationMapper applicationMapper;
    private final UiAppService uiAppService;

    public Application resolveForTask(String uiAppId, String deviceId, Task.Platform platform) {
        return resolveForTask(uiAppId, deviceId, platform, null);
    }

    /**
     * @param uiAppId          应用字典 id；也可直接是已存在的 apps.id；可与 packageOverride 二选一
     * @param deviceId         设备 id
     * @param platform         任务平台
     * @param packageOverride  执行时填写的包名/BundleId，优先于字典包名
     */
    public Application resolveForTask(String uiAppId, String deviceId, Task.Platform platform, String packageOverride) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        String id = uiAppId != null ? uiAppId.trim() : "";
        String pkgOverride = packageOverride != null && !packageOverride.isBlank() ? packageOverride.trim() : null;

        if (!id.isEmpty()) {
            Application existing = applicationMapper.findById(id);
            if (existing != null) {
                return existing;
            }
        }

        Device device = deviceMapper.findById(deviceId.trim());
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }

        Task.Platform plat = platform != null ? platform
                : (device.getPlatform() != null ? Task.Platform.valueOf(device.getPlatform().name()) : Task.Platform.ANDROID);
        boolean ios = plat == Task.Platform.IOS || plat == Task.Platform.POG_IOS;

        String packageName = pkgOverride;
        String displayName = null;
        if (packageName == null && !id.isEmpty()) {
            UiApp dict = uiAppService.get(id);
            if (dict != null) {
                displayName = dict.getNameZh();
                packageName = ios ? dict.getIosBundleId() : dict.getAndroidPackage();
                if (packageName != null) {
                    packageName = packageName.trim();
                }
            }
        }
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException(
                    "无法解析包名：请选择已配置包名的应用字典，或直接填写 packageName / BundleId");
        }

        List<Application> list = applicationMapper.findByDeviceId(device.getId());
        if (list != null) {
            for (Application a : list) {
                String pn = a.getPackageName();
                if (pn != null && packageName.equalsIgnoreCase(pn)) {
                    return a;
                }
            }
            // 宽松匹配：字典 id 提示词（不含写死业务包名）
            String hint = packageHintFromId(id);
            if (hint != null) {
                for (Application a : list) {
                    String pn = a.getPackageName();
                    if (pn != null && pn.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT))) {
                        return a;
                    }
                }
            }
        }

        Application stub = new Application();
        stub.setId("flow-app-" + UUID.randomUUID());
        stub.setName(displayName != null ? displayName : (!id.isEmpty() ? id : packageName));
        stub.setPackageName(packageName);
        stub.setDeviceId(device.getId());
        stub.setPlatform(Application.Platform.valueOf(plat.name()));
        stub.setInstalledAt(LocalDateTime.now());
        applicationMapper.insert(stub);
        log.info("为流程任务创建 apps 占位: device={}, app={}, pkg={}", device.getId(), stub.getName(), stub.getPackageName());
        return stub;
    }

    /** 仅用字典 id 自身做宽松匹配提示，避免写死具体业务包名 */
    private String packageHintFromId(String uiAppId) {
        if (uiAppId == null || uiAppId.isBlank()) return null;
        String id = uiAppId.trim().toLowerCase(Locale.ROOT);
        if (id.contains("pog")) return "pog";
        if (id.contains("esport")) return "esport";
        return null;
    }
}
