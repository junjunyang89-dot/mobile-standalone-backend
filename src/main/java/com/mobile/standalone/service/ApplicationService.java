package com.mobile.standalone.service;

import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationService {
    
    private final ApplicationMapper applicationMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceManager deviceManager;
    
    public List<Application> getAllApplications() {
        return applicationMapper.findAll();
    }
    
    public Application getApplicationById(String id) {
        return applicationMapper.findById(id);
    }
    
    public List<Application> getApplicationsByPlatform(Application.Platform platform) {
        if (platform == null) {
            return applicationMapper.findByPlatform(null);
        }
        // Android ↔ Pog-Android、iOS ↔ Pog-iOS 共用应用列表（库中可能存 ANDROID/POG_ANDROID 等）
        if (platform == Application.Platform.ANDROID || platform == Application.Platform.POG_ANDROID) {
            return mergeApplicationsById(
                    applicationMapper.findByPlatform(Application.Platform.ANDROID.name()),
                    applicationMapper.findByPlatform(Application.Platform.POG_ANDROID.name()));
        }
        if (platform == Application.Platform.IOS || platform == Application.Platform.POG_IOS) {
            return mergeApplicationsById(
                    applicationMapper.findByPlatform(Application.Platform.IOS.name()),
                    applicationMapper.findByPlatform(Application.Platform.POG_IOS.name()));
        }
        return applicationMapper.findByPlatform(platform.name());
    }

    /** 合并两路应用列表，按 id 去重，先 ANDROID/IOS 再 Pog，顺序稳定 */
    private static List<Application> mergeApplicationsById(List<Application> primary, List<Application> secondary) {
        Map<String, Application> byId = new LinkedHashMap<>();
        if (primary != null) {
            for (Application a : primary) {
                if (a != null && a.getId() != null) {
                    byId.putIfAbsent(a.getId(), a);
                }
            }
        }
        if (secondary != null) {
            for (Application a : secondary) {
                if (a != null && a.getId() != null) {
                    byId.putIfAbsent(a.getId(), a);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }
    
    /**
     * 获取设备已安装的应用列表
     * 默认先从数据库返回（缓存）；若数据库为空或 refresh=true 则从设备实时扫描并更新缓存。
     * @param deviceId 设备 ID
     * @param refresh 是否强制从设备重新扫描（true 时忽略缓存，可拿到新安装的应用如 com.arca.egames.intl）
     */
    public List<Application> getApplicationsByDeviceId(String deviceId, boolean refresh) {
        log.info("开始获取设备 {} 的应用列表, refresh={}", deviceId, refresh);

        Device device = deviceMapper.findById(deviceId);
        if (device == null) {
            log.warn("设备不存在: {}", deviceId);
            throw new RuntimeException("设备不存在: " + deviceId);
        }

        log.info("设备信息: id={}, name={}, platform={}", deviceId, device.getName(), device.getPlatform());

        List<Application> applications = applicationMapper.findByDeviceId(deviceId);
        log.info("数据库中找到 {} 个应用", applications.size());

        if (refresh) {
            int deleted = applicationMapper.deleteByDeviceId(deviceId);
            log.info("强制刷新：已清除该设备缓存 {} 条", deleted);
            applications = new ArrayList<>();
        }

        if (applications.isEmpty()) {
            log.info("从设备实时扫描应用...");
            try {
                if (device.getPlatform() == null) {
                    throw new RuntimeException("设备平台类型未设置: " + deviceId);
                }
                applications = scanInstalledApplicationsFromDevice(deviceId, device.getPlatform());
                log.info("从设备扫描到 {} 个应用", applications.size());

                if (!applications.isEmpty()) {
                    log.info("开始保存应用到数据库...");
                    for (Application app : applications) {
                        try {
                            applicationMapper.insert(app);
                        } catch (Exception e) {
                            log.warn("保存应用失败: {}, 错误: {}", app.getPackageName(), e.getMessage());
                        }
                    }
                    log.info("应用保存完成");
                }
            } catch (Exception e) {
                log.error("从设备扫描应用失败: {}", e.getMessage(), e);
                return new ArrayList<>();
            }
        }

        log.info("返回 {} 个应用", applications.size());
        return applications;
    }
    
    /**
     * 从设备实时扫描已安装的应用
     */
    private List<Application> scanInstalledApplicationsFromDevice(String deviceId, Device.Platform platform) {
        log.info("开始扫描设备 {} 的已安装应用，平台: {}", deviceId, platform);
        
        List<Application> applications = new ArrayList<>();
        
        try {
            if (platform == Device.Platform.ANDROID || platform == Device.Platform.POG_ANDROID) {
                // 扫描 Android 应用（Pog-Android 与 Android 相同 ADB 流程）
                boolean pog = platform == Device.Platform.POG_ANDROID;
                log.info("使用 ADB 扫描{}应用...", pog ? " Pog-Android " : " Android ");
                List<String> packages = deviceManager.getAndroidInstalledPackages(deviceId);
                log.info("获取到 {} 个包名", packages.size());
                
                // 使用批量方法获取应用信息（优化性能）
                long startTime = System.currentTimeMillis();
                List<DeviceManager.AppInfo> appInfos = deviceManager.getAndroidAppsInfoBatch(deviceId, packages);
                long endTime = System.currentTimeMillis();
                log.info("批量获取应用信息耗时: {} ms", (endTime - startTime));
                
                // 转换为 Application 对象
                for (DeviceManager.AppInfo appInfo : appInfos) {
                    try {
                        Application app = new Application();
                        app.setId(UUID.randomUUID().toString());
                        app.setName(appInfo.getName());
                        app.setPackageName(appInfo.getPackageName());
                        app.setVersion(appInfo.getVersion());
                        app.setDeviceId(deviceId);
                        app.setPlatform(pog ? Application.Platform.POG_ANDROID : Application.Platform.ANDROID);
                        app.setInstalledAt(LocalDateTime.now());
                        applications.add(app);
                    } catch (Exception e) {
                        log.warn("处理应用 {} 时出错: {}", appInfo.getPackageName(), e.getMessage());
                    }
                }
        } else if (platform == Device.Platform.IOS || platform == Device.Platform.POG_IOS) {
            boolean pog = platform == Device.Platform.POG_IOS;
            // 扫描 iOS 应用
            log.info("使用 ideviceinstaller 扫描{}应用...", pog ? " Pog-iOS " : " iOS ");
            List<String> packageNames = deviceManager.getIOSInstalledApps(deviceId);
            log.info("获取到 {} 个应用包名", packageNames.size());
            
            if (packageNames.isEmpty()) {
                log.warn("iOS 设备 {} 未获取到任何应用，可能原因：", deviceId);
                log.warn("1. 设备未信任此电脑");
                log.warn("2. ideviceinstaller 未正确安装");
                log.warn("3. 设备未连接或未授权");
                log.warn("4. 设备上没有安装应用");
            } else {
                for (String packageName : packageNames) {
                    try {
                        // 获取应用详细信息（名称和版本）
                        DeviceManager.AppInfo appInfo = deviceManager.getIOSAppInfo(deviceId, packageName);
                        
                        Application app = new Application();
                        app.setId(UUID.randomUUID().toString());
                        app.setName(appInfo.getName());
                        app.setPackageName(packageName);
                        app.setVersion(appInfo.getVersion());
                        app.setDeviceId(deviceId);
                        app.setPlatform(pog ? Application.Platform.POG_IOS : Application.Platform.IOS);
                        app.setInstalledAt(LocalDateTime.now());
                        applications.add(app);
                        log.debug("添加应用: {} ({})", appInfo.getName(), packageName);
                    } catch (Exception e) {
                        log.warn("处理应用 {} 时出错: {}", packageName, e.getMessage(), e);
                    }
                }
            }
            } else if (platform == Device.Platform.HARMONYOS) {
                // HarmonyOS 也使用 ADB，类似 Android
                log.info("使用 ADB 扫描 HarmonyOS 应用...");
                List<String> packages = deviceManager.getAndroidInstalledPackages(deviceId);
                log.info("获取到 {} 个包名", packages.size());
                
                // 使用批量方法获取应用信息（优化性能）
                long startTime = System.currentTimeMillis();
                List<DeviceManager.AppInfo> appInfos = deviceManager.getAndroidAppsInfoBatch(deviceId, packages);
                long endTime = System.currentTimeMillis();
                log.info("批量获取应用信息耗时: {} ms", (endTime - startTime));
                
                // 转换为 Application 对象
                for (DeviceManager.AppInfo appInfo : appInfos) {
                    try {
                        Application app = new Application();
                        app.setId(UUID.randomUUID().toString());
                        app.setName(appInfo.getName());
                        app.setPackageName(appInfo.getPackageName());
                        app.setVersion(appInfo.getVersion());
                        app.setDeviceId(deviceId);
                        app.setPlatform(Application.Platform.HARMONYOS);
                        app.setInstalledAt(LocalDateTime.now());
                        applications.add(app);
                    } catch (Exception e) {
                        log.warn("处理应用 {} 时出错: {}", appInfo.getPackageName(), e.getMessage());
                    }
                }
            } else {
                log.warn("不支持的平台类型: {}", platform);
                throw new RuntimeException("不支持的平台类型: " + platform);
            }
        } catch (Exception e) {
            log.error("扫描应用时发生异常: {}", e.getMessage(), e);
            throw new RuntimeException("扫描应用失败: " + e.getMessage(), e);
        }
        
        log.info("扫描完成，共找到 {} 个应用", applications.size());
        return applications;
    }
    
    @Transactional
    public Application createApplication(Application application) {
        if (application.getId() == null) {
            application.setId(UUID.randomUUID().toString());
        }
        applicationMapper.insert(application);
        return application;
    }
    
    @Transactional
    public Application updateApplication(String id, Application applicationData) {
        Application application = applicationMapper.findById(id);
        if (application != null) {
            if (applicationData.getName() != null) application.setName(applicationData.getName());
            if (applicationData.getPackageName() != null) application.setPackageName(applicationData.getPackageName());
            if (applicationData.getVersion() != null) application.setVersion(applicationData.getVersion());
            if (applicationData.getConfig() != null) application.setConfig(applicationData.getConfig());
            applicationMapper.update(application);
            return application;
        }
        return null;
    }
    
    @Transactional
    public boolean deleteApplication(String id) {
        Application application = applicationMapper.findById(id);
        if (application != null) {
            applicationMapper.deleteById(id);
            return true;
        }
        return false;
    }
}
