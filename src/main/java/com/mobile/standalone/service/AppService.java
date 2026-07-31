package com.mobile.standalone.service;

import com.mobile.standalone.entity.App;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.AppMapper;
import com.mobile.standalone.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppService {
    
    private final AppMapper appMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceManager deviceManager;
    private final String uploadDir = System.getProperty("user.home") + "/.mobile-standalone/uploads";
    
    /**
     * 获取设备已安装的应用列表
     */
    public List<App> getInstalledApps(String deviceId) {
        // 检查设备是否存在
        Device device = deviceMapper.findById(deviceId);
        if (device == null) {
            throw new RuntimeException("设备不存在: " + deviceId);
        }
        
        // 根据平台获取应用列表
        List<App> apps = appMapper.findByDeviceId(deviceId);
        
        // 如果数据库中没有，尝试从设备获取
        if (apps.isEmpty()) {
            apps = scanInstalledApps(deviceId, device.getPlatform() != null ? device.getPlatform().name() : null);
            // 保存到数据库
            for (App app : apps) {
                appMapper.insert(app);
            }
        }
        
        return apps;
    }
    
    /**
     * 上传并安装应用
     */
    public App uploadAndInstallApp(MultipartFile file, String deviceId) {
        // 检查设备是否存在
        Device device = deviceMapper.findById(deviceId);
        if (device == null) {
            throw new RuntimeException("设备不存在: " + deviceId);
        }
        
        // 创建上传目录
        try {
            Files.createDirectories(Paths.get(uploadDir));
        } catch (IOException e) {
            throw new RuntimeException("创建上传目录失败", e);
        }
        
        // 保存文件
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String filename = UUID.randomUUID().toString() + extension;
        Path filePath = Paths.get(uploadDir, filename);
        
        try {
            file.transferTo(filePath.toFile());
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败", e);
        }
        
        // 安装应用到设备
        App app = installAppToDevice(filePath.toFile(), deviceId, device.getPlatform() != null ? device.getPlatform().name() : null, originalFilename);
        
        // 保存到数据库
        appMapper.insert(app);
        
        return app;
    }
    
    /**
     * 扫描设备已安装的应用
     */
    private List<App> scanInstalledApps(String deviceId, String platform) {
        log.info("扫描设备 {} 的已安装应用，平台: {}", deviceId, platform);
        
        List<App> apps = new ArrayList<>();
        
        if ("ANDROID".equalsIgnoreCase(platform)) {
            // 扫描 Android 应用
            List<String> packages = deviceManager.getAndroidInstalledPackages(deviceId);
            for (String packageName : packages) {
                App app = new App();
                app.setId(UUID.randomUUID().toString());
                app.setName(deviceManager.getAndroidAppName(deviceId, packageName));
                app.setPackageName(packageName);
                app.setDeviceId(deviceId);
                app.setPlatform("ANDROID");
                app.setInstallTime(System.currentTimeMillis());
                apps.add(app);
            }
        } else if ("IOS".equalsIgnoreCase(platform)) {
            // 扫描 iOS 应用
            List<String> packageNames = deviceManager.getIOSInstalledApps(deviceId);
            for (String packageName : packageNames) {
                App app = new App();
                app.setId(UUID.randomUUID().toString());
                app.setName(packageName); // iOS 包名通常包含应用名称
                app.setPackageName(packageName);
                app.setDeviceId(deviceId);
                app.setPlatform("IOS");
                app.setInstallTime(System.currentTimeMillis());
                apps.add(app);
            }
        }
        
        log.info("扫描到 {} 个应用", apps.size());
        return apps;
    }
    
    /**
     * 安装应用到设备
     */
    private App installAppToDevice(File appFile, String deviceId, String platform, String originalFilename) {
        log.info("安装应用到设备 {}，平台: {}，文件: {}", deviceId, platform, appFile.getName());
        
        boolean installSuccess = false;
        
        if ("ANDROID".equalsIgnoreCase(platform)) {
            // 安装 Android APK
            installSuccess = deviceManager.installAndroidApp(deviceId, appFile.getAbsolutePath());
        } else if ("IOS".equalsIgnoreCase(platform)) {
            // 安装 iOS IPA
            installSuccess = deviceManager.installIOSApp(deviceId, appFile.getAbsolutePath());
        } else {
            log.warn("不支持的平台: {}", platform);
            throw new RuntimeException("不支持的平台: " + platform);
        }
        
        if (!installSuccess) {
            throw new RuntimeException("应用安装失败");
        }
        
        // 创建应用对象
        App app = new App();
        app.setId(UUID.randomUUID().toString());
        app.setName(extractAppName(originalFilename));
        app.setPackageName(extractPackageName(appFile, platform));
        app.setDeviceId(deviceId);
        app.setPlatform(platform);
        app.setInstallTime(System.currentTimeMillis());
        app.setFileSize(appFile.length());
        
        log.info("应用安装成功: {}", app.getName());
        return app;
    }
    
    /**
     * 从文件名提取应用名称
     */
    private String extractAppName(String filename) {
        if (filename == null) return "未知应用";
        int lastDot = filename.lastIndexOf(".");
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }
    
    /**
     * 从应用文件提取包名
     */
    private String extractPackageName(File appFile, String platform) {
        // TODO: 实现实际的包名提取逻辑
        // Android: 解析 APK 的 AndroidManifest.xml
        // iOS: 解析 IPA 的 Info.plist
        // HarmonyOS: 解析 HAP 的 config.json
        
        return "com.example.app";
    }
}
