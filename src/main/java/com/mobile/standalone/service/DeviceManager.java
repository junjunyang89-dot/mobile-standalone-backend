package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备管理工具类
 * 支持 Android (ADB) 和 iOS (libimobiledevice) 设备管理
 */
@Component
@Slf4j
public class DeviceManager {

    /** 每台设备常挂的 WDA xcodebuild / iproxy（不重装，仅 test-without-building） */
    private final ConcurrentHashMap<String, Process> iosWdaXcodeProcs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Process> iosWdaIproxyProcs = new ConcurrentHashMap<>();
    
    /**
     * 扫描 Android 设备（使用 ADB）
     */
    public List<Device> scanAndroidDevices() {
        List<Device> devices = new ArrayList<>();
        
        try {
            // 执行 adb devices 命令
            Process process = new ProcessBuilder("adb", "devices", "-l")
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                boolean firstLine = true;
                
                while ((line = reader.readLine()) != null) {
                    // 跳过第一行 "List of devices attached"
                    if (firstLine) {
                        firstLine = false;
                        continue;
                    }
                    
                    // 跳过空行
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    
                    // 解析设备信息：格式为 SERIAL\tdevice 或 SERIAL  device model:XXX
                    // 排除 adb 错误行（如 "adb: device 'List' not found"）
                    if (line.contains("device") && !line.contains("offline") && !line.contains("unauthorized")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length > 0) {
                            String deviceId = parts[0].trim();
                            if (!isValidAdbDeviceId(deviceId)) {
                                log.debug("跳过无效设备行: {}", line);
                                continue;
                            }
                            String deviceName = extractDeviceName(line);
                            String version = getAndroidVersion(deviceId);
                            if (isErrorLikeVersion(version)) {
                                version = null;
                            }
                            Device device = new Device();
                            device.setId(deviceId);
                            device.setName(deviceName != null ? deviceName : "Android Device");
                            device.setPlatform(Device.Platform.ANDROID);
                            device.setVersion(version);
                            device.setStatus(Device.DeviceStatus.CONNECTED);
                            devices.add(device);
                            log.info("发现 Android 设备: {} ({})", device.getName(), deviceId);
                        }
                    }
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("ADB 命令执行失败，退出码: {}", exitCode);
            }
            
        } catch (IOException | InterruptedException e) {
            log.error("扫描 Android 设备失败", e);
        }
        
        return devices;
    }
    
    /**
     * 扫描 iOS 设备（使用 libimobiledevice）
     */
    public List<Device> scanIOSDevices() {
        List<Device> devices = new ArrayList<>();
        
        try {
            // 执行 idevice_id -l 命令获取设备列表
            Process process = new ProcessBuilder("idevice_id", "-l")
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String deviceId;
                while ((deviceId = reader.readLine()) != null) {
                    deviceId = deviceId.trim();
                    if (!deviceId.isEmpty()) {
                        // 获取设备名称和版本
                        String deviceName = getIOSDeviceName(deviceId);
                        String version = getIOSVersion(deviceId);
                        
                        Device device = new Device();
                        device.setId(deviceId);
                        device.setName(deviceName != null ? deviceName : "iOS Device");
                        device.setPlatform(Device.Platform.IOS);
                        device.setVersion(version);
                        device.setStatus(Device.DeviceStatus.CONNECTED);
                        
                        devices.add(device);
                        log.info("发现 iOS 设备: {} ({})", deviceName, deviceId);
                    }
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.debug("idevice_id 命令执行失败，退出码: {}。可能未安装 libimobiledevice 或没有 iOS 设备连接", exitCode);
            }
            
        } catch (IOException | InterruptedException e) {
            log.debug("扫描 iOS 设备失败，可能未安装 libimobiledevice 或没有 iOS 设备连接: {}", e.getMessage());
        }
        
        return devices;
    }
    
    /**
     * 扫描 HarmonyOS 设备（使用 ADB，通过设备特征识别）
     */
    public List<Device> scanHarmonyOSDevices() {
        List<Device> devices = new ArrayList<>();
        
        try {
            // HarmonyOS 设备也通过 ADB 连接，但需要特殊识别
            Process process = new ProcessBuilder("adb", "devices", "-l")
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                boolean firstLine = true;
                
                while ((line = reader.readLine()) != null) {
                    if (firstLine) {
                        firstLine = false;
                        continue;
                    }
                    
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    
                    // 检查是否是 HarmonyOS 设备（通过设备信息判断）
                    if (line.contains("device") && !line.contains("offline") && !line.contains("unauthorized")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length > 0) {
                            String deviceId = parts[0].trim();
                            if (!isValidAdbDeviceId(deviceId)) {
                                log.debug("跳过无效设备行: {}", line);
                                continue;
                            }
                            // 检查设备品牌，判断是否为 HarmonyOS
                            String deviceInfo = getDeviceProperty(deviceId, "ro.product.brand");
                            
                            // 简单判断：华为设备可能是 HarmonyOS
                            // 实际应该通过更精确的方式判断
                            boolean isHarmonyOS = deviceInfo != null && 
                                (deviceInfo.toLowerCase().contains("huawei") || 
                                 deviceInfo.toLowerCase().contains("honor") ||
                                 checkHarmonyOSVersion(deviceId));
                            
                            if (isHarmonyOS) {
                                String deviceName = extractDeviceName(line);
                                String version = getHarmonyOSVersion(deviceId);
                                
                                Device device = new Device();
                                device.setId(deviceId);
                                device.setName(deviceName != null ? deviceName : "HarmonyOS Device");
                                device.setPlatform(Device.Platform.HARMONYOS);
                                device.setVersion(version);
                                device.setStatus(Device.DeviceStatus.CONNECTED);
                                
                                devices.add(device);
                                log.info("发现 HarmonyOS 设备: {} ({})", deviceName, deviceId);
                            }
                        }
                    }
                }
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            log.error("扫描 HarmonyOS 设备失败", e);
        }
        
        return devices;
    }
    
    /**
     * 获取设备属性
     */
    private String getDeviceProperty(String deviceId, String property) {
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "getprop", property)
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String value = reader.readLine();
                if (value != null) {
                    return value.trim();
                }
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            // 忽略错误
        }
        
        return null;
    }
    
    /**
     * 检查是否为 HarmonyOS 系统
     */
    private boolean checkHarmonyOSVersion(String deviceId) {
        String version = getDeviceProperty(deviceId, "ro.build.version.harmony");
        return version != null && !version.isEmpty();
    }
    
    /**
     * 获取 HarmonyOS 版本
     */
    private String getHarmonyOSVersion(String deviceId) {
        String version = getDeviceProperty(deviceId, "ro.build.version.harmony");
        if (version == null || version.isEmpty()) {
            version = getDeviceProperty(deviceId, "ro.build.version.release");
        }
        return version;
    }
    
    /**
     * 获取 Android 设备已安装的应用列表
     */
    public List<String> getAndroidInstalledPackages(String deviceId) {
        List<String> packages = new ArrayList<>();
        
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "pm", "list", "packages")
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // 格式: package:com.example.app
                    if (line.startsWith("package:")) {
                        String packageName = line.substring(8).trim();
                        packages.add(packageName);
                    }
                }
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            log.error("获取 Android 应用列表失败: {}", e.getMessage());
        }
        
        return packages;
    }
    
    /**
     * 获取 Android 应用名称
     */
    public String getAndroidAppName(String deviceId, String packageName) {
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "pm", "dump", packageName)
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // 查找 applicationLabel
                    if (line.contains("applicationLabel=")) {
                        String[] parts = line.split("=");
                        if (parts.length > 1) {
                            String label = parts[1].trim();
                            // 移除可能的引号
                            if (label.startsWith("'") && label.endsWith("'")) {
                                label = label.substring(1, label.length() - 1);
                            }
                            if (!label.isEmpty()) {
                                return label;
                            }
                        }
                    }
                }
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            log.warn("获取应用名称失败: {}", e.getMessage());
        }
        
        // 如果无法获取，返回包名
        return packageName;
    }
    
    /**
     * 获取 Android 应用版本号
     */
    public String getAndroidAppVersion(String deviceId, String packageName) {
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "package", packageName)
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // 查找 versionName
                    if (line.contains("versionName=")) {
                        String[] parts = line.split("=");
                        if (parts.length > 1) {
                            String version = parts[1].trim();
                            // 移除可能的空格和特殊字符
                            version = version.split("\\s")[0];
                            if (!version.isEmpty()) {
                                return version;
                            }
                        }
                    }
                }
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            log.warn("获取应用版本失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 批量获取 Android 应用信息（优化版本）
     * 使用单个命令获取所有应用信息，然后解析
     */
    public List<AppInfo> getAndroidAppsInfoBatch(String deviceId, List<String> packageNames) {
        List<AppInfo> appInfos = new ArrayList<>();
        
        if (packageNames == null || packageNames.isEmpty()) {
            return appInfos;
        }
        
        log.info("批量获取 {} 个应用的信息", packageNames.size());
        
        try {
            // 方法1: 使用 pm dump 批量获取（更快）
            // 注意：pm dump 可能输出很大，但比逐个调用快得多
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "pm", "dump")
                    .redirectErrorStream(true)
                    .start();
            
            // 解析输出，提取所有应用信息
            java.util.Map<String, AppInfo> appInfoMap = new java.util.HashMap<>();
            String currentPackage = null;
            AppInfo currentAppInfo = null;
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // 查找包名行: Package [com.example.app]
                    if (line.startsWith("Package [")) {
                        // 保存上一个应用
                        if (currentPackage != null && currentAppInfo != null) {
                            appInfoMap.put(currentPackage, currentAppInfo);
                        }
                        
                        // 提取新包名
                        String packageLine = line.substring(9); // 移除 "Package ["
                        int endIndex = packageLine.indexOf(']');
                        if (endIndex > 0) {
                            currentPackage = packageLine.substring(0, endIndex);
                            currentAppInfo = new AppInfo();
                            currentAppInfo.setPackageName(currentPackage);
                        }
                    } else if (currentAppInfo != null) {
                        // 查找应用名称
                        if (line.contains("applicationLabel=") && currentAppInfo.getName() == null) {
                            String[] parts = line.split("=");
                            if (parts.length > 1) {
                                String label = parts[1].trim();
                                if (label.startsWith("'") && label.endsWith("'")) {
                                    label = label.substring(1, label.length() - 1);
                                }
                                if (!label.isEmpty()) {
                                    currentAppInfo.setName(label);
                                }
                            }
                        }
                        
                        // 查找版本号
                        if (line.contains("versionName=") && currentAppInfo.getVersion() == null) {
                            String[] parts = line.split("=");
                            if (parts.length > 1) {
                                String version = parts[1].trim();
                                version = version.split("\\s")[0];
                                if (!version.isEmpty()) {
                                    currentAppInfo.setVersion(version);
                                }
                            }
                        }
                    }
                }
                
                // 保存最后一个应用
                if (currentPackage != null && currentAppInfo != null) {
                    appInfoMap.put(currentPackage, currentAppInfo);
                }
            }
            
            process.waitFor();
            
            // 为所有包名创建 AppInfo（包括未在dump中找到的）
            for (String packageName : packageNames) {
                AppInfo appInfo = appInfoMap.get(packageName);
                if (appInfo == null) {
                    appInfo = new AppInfo();
                    appInfo.setPackageName(packageName);
                    appInfo.setName(packageName);
                    appInfo.setVersion("1.0.0");
                } else {
                    // 设置默认值
                    if (appInfo.getName() == null || appInfo.getName().isEmpty()) {
                        appInfo.setName(packageName);
                    }
                    if (appInfo.getVersion() == null || appInfo.getVersion().isEmpty()) {
                        appInfo.setVersion("1.0.0");
                    }
                }
                appInfos.add(appInfo);
            }
            
            log.info("批量获取完成，成功获取 {} 个应用信息", appInfos.size());
            
        } catch (IOException | InterruptedException e) {
            log.warn("批量获取应用信息失败，回退到逐个获取: {}", e.getMessage());
            // 回退到逐个获取
            return getAndroidAppsInfoSequential(deviceId, packageNames);
        }
        
        return appInfos;
    }
    
    /**
     * 逐个获取 Android 应用信息（备用方法）
     */
    private List<AppInfo> getAndroidAppsInfoSequential(String deviceId, List<String> packageNames) {
        List<AppInfo> appInfos = new ArrayList<>();
        for (String packageName : packageNames) {
            appInfos.add(getAndroidAppInfo(deviceId, packageName));
        }
        return appInfos;
    }
    
    /**
     * 获取 Android 应用的详细信息（名称和版本）
     * 返回格式：AppInfo{name='应用名称', version='1.0.0'}
     * 注意：此方法较慢，建议使用批量方法 getAndroidAppsInfoBatch
     */
    public AppInfo getAndroidAppInfo(String deviceId, String packageName) {
        AppInfo appInfo = new AppInfo();
        appInfo.setPackageName(packageName);
        
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "package", packageName)
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // 查找 applicationLabel
                    if (line.contains("applicationLabel=") && appInfo.getName() == null) {
                        String[] parts = line.split("=");
                        if (parts.length > 1) {
                            String label = parts[1].trim();
                            if (label.startsWith("'") && label.endsWith("'")) {
                                label = label.substring(1, label.length() - 1);
                            }
                            if (!label.isEmpty()) {
                                appInfo.setName(label);
                            }
                        }
                    }
                    
                    // 查找 versionName
                    if (line.contains("versionName=") && appInfo.getVersion() == null) {
                        String[] parts = line.split("=");
                        if (parts.length > 1) {
                            String version = parts[1].trim();
                            version = version.split("\\s")[0];
                            if (!version.isEmpty()) {
                                appInfo.setVersion(version);
                            }
                        }
                    }
                    
                    // 如果已经获取到名称和版本，可以提前退出
                    if (appInfo.getName() != null && appInfo.getVersion() != null) {
                        break;
                    }
                }
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            log.warn("获取应用信息失败: {}", e.getMessage());
        }
        
        // 设置默认值
        if (appInfo.getName() == null || appInfo.getName().isEmpty()) {
            appInfo.setName(packageName);
        }
        if (appInfo.getVersion() == null || appInfo.getVersion().isEmpty()) {
            appInfo.setVersion("1.0.0");
        }
        
        return appInfo;
    }
    
    /**
     * 应用信息类
     */
    public static class AppInfo {
        private String name;
        private String version;
        private String packageName;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getVersion() {
            return version;
        }
        
        public void setVersion(String version) {
            this.version = version;
        }
        
        public String getPackageName() {
            return packageName;
        }
        
        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }
    }
    
    /**
     * 获取 iOS 设备已安装的应用列表
     * 支持两种输出格式：
     * 1. CSV格式: CFBundleIdentifier, CFBundleVersion, CFBundleDisplayName
     * 2. 旧格式: AppName - com.example.app
     */
    public List<String> getIOSInstalledApps(String deviceId) {
        List<String> apps = new ArrayList<>();
        
        log.info("开始获取 iOS 设备 {} 的应用列表", deviceId);
        
        try {
            Process process = new ProcessBuilder("ideviceinstaller", "-u", deviceId, "-l")
                    .redirectErrorStream(true)
                    .start();
            
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();
            boolean isCSVFormat = false;
            boolean isFirstLine = true;
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    
                    // 检查是否是CSV格式（第一行包含CFBundleIdentifier）
                    if (isFirstLine) {
                        isFirstLine = false;
                        if (line.contains("CFBundleIdentifier") || line.contains("CFBundleVersion") || line.contains("CFBundleDisplayName")) {
                            isCSVFormat = true;
                            log.debug("检测到CSV格式输出");
                            continue; // 跳过标题行
                        }
                    }
                    
                    // 跳过空行
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    
                    // 跳过标题行（旧格式）
                    if (line.contains("Total:") || line.trim().isEmpty()) {
                        log.debug("跳过标题行: {}", line);
                        continue;
                    }
                    
                    // 检查错误信息
                    if (line.contains("ERROR") || line.contains("error") || line.contains("failed")) {
                        errorOutput.append(line).append("\n");
                        log.warn("检测到错误信息: {}", line);
                        continue;
                    }
                    
                    if (isCSVFormat) {
                        // CSV格式: com.tencent.xin, "8.0.67.35", "微信"
                        // 或者: com.tencent.xin, 8.0.67.35, 微信
                        String[] parts = line.split(",");
                        if (parts.length >= 1) {
                            String bundleId = parts[0].trim();
                            // 移除可能的引号
                            if (bundleId.startsWith("\"") && bundleId.endsWith("\"")) {
                                bundleId = bundleId.substring(1, bundleId.length() - 1);
                            }
                            if (!bundleId.isEmpty()) {
                                apps.add(bundleId);
                                log.debug("找到应用 (CSV格式): {}", bundleId);
                            }
                        }
                    } else {
                        // 旧格式: AppName - com.example.app
                        // 也可能格式为: AppName (Version) - com.example.app
                        if (line.contains(" - ")) {
                            String[] parts = line.split(" - ");
                            if (parts.length >= 2) {
                                String bundleId = parts[parts.length - 1].trim();
                                if (!bundleId.isEmpty() && !bundleId.equals("Total:")) {
                                    apps.add(bundleId);
                                    log.debug("找到应用 (旧格式): {}", bundleId);
                                }
                            }
                        }
                    }
                }
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                log.error("ideviceinstaller 命令执行失败，退出码: {}, 设备ID: {}", exitCode, deviceId);
                log.error("命令输出: {}", output.toString());
                if (errorOutput.length() > 0) {
                    log.error("错误输出: {}", errorOutput.toString());
                }
            } else {
                log.info("成功获取 {} 个 iOS 应用", apps.size());
                if (apps.isEmpty() && output.length() > 0) {
                    log.warn("未找到应用，但命令执行成功。输出内容: {}", output.toString());
                }
            }
            
        } catch (IOException e) {
            log.error("执行 ideviceinstaller 命令失败，设备ID: {}, 错误: {}", deviceId, e.getMessage(), e);
        } catch (InterruptedException e) {
            log.error("等待 ideviceinstaller 命令完成时被中断，设备ID: {}", deviceId, e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("获取 iOS 应用列表时发生未知错误，设备ID: {}", deviceId, e);
        }
        
        return apps;
    }
    
    /**
     * 获取 iOS 应用信息（名称和版本）
     * 支持CSV格式和旧格式的输出
     */
    public AppInfo getIOSAppInfo(String deviceId, String bundleId) {
        AppInfo appInfo = new AppInfo();
        appInfo.setPackageName(bundleId);
        
        log.debug("获取 iOS 应用信息: deviceId={}, bundleId={}", deviceId, bundleId);
        
        try {
            Process process = new ProcessBuilder("ideviceinstaller", "-u", deviceId, "-l")
                    .redirectErrorStream(true)
                    .start();
            
            boolean isCSVFormat = false;
            boolean isFirstLine = true;
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // 检查是否是CSV格式
                    if (isFirstLine) {
                        isFirstLine = false;
                        if (line.contains("CFBundleIdentifier") || line.contains("CFBundleVersion") || line.contains("CFBundleDisplayName")) {
                            isCSVFormat = true;
                            continue; // 跳过标题行
                        }
                    }
                    
                    if (isCSVFormat) {
                        // CSV格式: com.tencent.xin, "8.0.67.35", "微信"
                        // 解析CSV格式
                        if (line.contains(bundleId)) {
                            String[] parts = line.split(",");
                            if (parts.length >= 3) {
                                String id = parts[0].trim();
                                // 移除引号
                                if (id.startsWith("\"") && id.endsWith("\"")) {
                                    id = id.substring(1, id.length() - 1);
                                }
                                
                                if (id.equals(bundleId)) {
                                    // 提取版本号（第2列）
                                    String version = parts[1].trim();
                                    if (version.startsWith("\"") && version.endsWith("\"")) {
                                        version = version.substring(1, version.length() - 1);
                                    }
                                    appInfo.setVersion(version);
                                    
                                    // 提取应用名称（第3列）
                                    String name = parts[2].trim();
                                    if (name.startsWith("\"") && name.endsWith("\"")) {
                                        name = name.substring(1, name.length() - 1);
                                    }
                                    appInfo.setName(name);
                                    
                                    log.debug("从CSV格式提取: name={}, version={}, bundleId={}", name, version, bundleId);
                                    break;
                                }
                            }
                        }
                    } else {
                        // 旧格式: AppName - com.example.app
                        // 也可能格式为: AppName (Version) - com.example.app
                        if (line.contains(" - ") && line.contains(bundleId)) {
                            String[] parts = line.split(" - ");
                            if (parts.length >= 2 && parts[parts.length - 1].trim().equals(bundleId)) {
                                String appNamePart = parts[0].trim();
                                // 如果包含版本号，格式为: AppName (Version)
                                if (appNamePart.contains("(") && appNamePart.contains(")")) {
                                    int startIndex = appNamePart.indexOf("(");
                                    int endIndex = appNamePart.indexOf(")");
                                    if (startIndex > 0 && endIndex > startIndex) {
                                        String appName = appNamePart.substring(0, startIndex).trim();
                                        String version = appNamePart.substring(startIndex + 1, endIndex).trim();
                                        appInfo.setName(appName);
                                        appInfo.setVersion(version);
                                        log.debug("从旧格式提取: name={}, version={}", appName, version);
                                        break;
                                    }
                                } else {
                                    appInfo.setName(appNamePart);
                                    log.debug("从旧格式提取: name={}", appNamePart);
                                }
                            }
                        }
                    }
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("获取 iOS 应用信息时命令执行失败，退出码: {}", exitCode);
            }
            
            // 如果未获取到版本号，尝试其他方法
            if (appInfo.getVersion() == null || appInfo.getVersion().isEmpty()) {
                String version = getIOSAppVersion(deviceId, bundleId);
                if (version != null && !version.isEmpty()) {
                    appInfo.setVersion(version);
                }
            }
            
        } catch (IOException | InterruptedException e) {
            log.warn("获取 iOS 应用信息失败: {}", e.getMessage(), e);
        }
        
        // 设置默认值
        if (appInfo.getName() == null || appInfo.getName().isEmpty()) {
            appInfo.setName(bundleId);
            log.debug("使用 bundleId 作为应用名称: {}", bundleId);
        }
        if (appInfo.getVersion() == null || appInfo.getVersion().isEmpty()) {
            log.warn("无法获取 iOS 应用 {} 的版本号，使用默认值", bundleId);
            appInfo.setVersion("1.0.0");
        }
        
        log.debug("最终应用信息: name={}, version={}, bundleId={}", 
                appInfo.getName(), appInfo.getVersion(), appInfo.getPackageName());
        
        return appInfo;
    }
    
    /**
     * 获取 iOS 应用版本号
     * 尝试多种方法获取版本信息
     * 注意：iOS 应用版本获取受限于系统权限，可能需要越狱设备或特殊配置
     */
    private String getIOSAppVersion(String deviceId, String bundleId) {
        // 方法1: 尝试使用 ideviceinstaller 的详细信息
        // 注意：标准 ideviceinstaller 可能不支持直接获取版本，需要其他工具
        try {
            // 尝试使用 ideviceinstaller 获取应用列表的详细信息
            // 某些版本的 ideviceinstaller 可能支持 -o xml 或 -o list 格式
            ProcessBuilder pb = new ProcessBuilder("ideviceinstaller", "-u", deviceId, "-l");
            Process process = pb.redirectErrorStream(true).start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // 标准输出格式: AppName - com.example.app
                    // 某些版本可能包含版本信息，格式可能为: AppName (1.0.0) - com.example.app
                    if (line.contains(bundleId)) {
                        // 尝试从括号中提取版本号
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\(([\\d\\.]+)\\)");
                        java.util.regex.Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            return matcher.group(1);
                        }
                    }
                }
            }
            
            process.waitFor();
        } catch (Exception e) {
            log.debug("方法1获取iOS版本失败: {}", e.getMessage());
        }
        
        // 方法2: 尝试使用 plistutil 或 plutil 解析应用包信息
        // 这需要访问应用包的 Info.plist 文件，通常需要越狱或特殊权限
        // 暂时不实现，因为需要文件系统访问权限
        
        // 方法3: 如果以上方法都失败，返回 null，让调用者使用默认值
        // 在实际使用中，可以考虑：
        // 1. 使用第三方工具如 libimobiledevice 的其他命令
        // 2. 从已安装的 IPA 文件中提取版本信息（如果有本地缓存）
        // 3. 使用 iTunes/App Store API 查询版本（需要网络）
        
        log.debug("无法从设备获取 iOS 应用 {} 的版本号", bundleId);
        return null;
    }
    
    /**
     * 安装 APK 到 Android 设备
     */
    public boolean installAndroidApp(String deviceId, String apkPath) {
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "install", "-r", apkPath)
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                StringBuilder output = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("ADB install: {}", line);
                }
                
                int exitCode = process.waitFor();
                
                if (exitCode == 0 || output.toString().contains("Success")) {
                    log.info("APK 安装成功: {}", apkPath);
                    return true;
                } else {
                    log.error("APK 安装失败: {}", output.toString());
                    return false;
                }
            }
            
        } catch (IOException | InterruptedException e) {
            log.error("安装 APK 失败", e);
            return false;
        }
    }

    /**
     * 卸载 Android 应用。未安装也视为成功（便于「先卸再装」）。
     */
    public boolean uninstallAndroidApp(String deviceId, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "uninstall", packageName.trim())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            String text = output.toString();
            if (exitCode == 0 || text.contains("Success") || text.toLowerCase().contains("not installed")) {
                log.info("Android 卸载完成: deviceId={}, package={}", deviceId, packageName);
                return true;
            }
            log.error("Android 卸载失败: {}", text);
            return false;
        } catch (IOException | InterruptedException e) {
            log.error("卸载 Android 应用失败: {}", e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** Android 是否已安装指定包名 */
    public boolean isAndroidAppInstalled(String deviceId, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "pm", "path", packageName.trim())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            process.waitFor();
            return output.toString().contains("package:");
        } catch (Exception e) {
            log.warn("检查 Android 安装状态失败: {}", e.getMessage());
            return false;
        }
    }

    /** 读取 Android 厂商（小写），用于选择应用市场深链 */
    public String getAndroidManufacturer(String deviceId) {
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "getprop", "ro.product.manufacturer")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line != null ? line.trim().toLowerCase() : "";
            }
        } catch (Exception e) {
            log.warn("读取厂商失败: {}", e.getMessage());
            return "";
        }
    }

    /** 用 VIEW Intent 打开 URI（应用市场 / Play / 网页） */
    public boolean openAndroidUri(String deviceId, String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(
                    "adb", "-s", deviceId, "shell", "am", "start",
                    "-a", "android.intent.action.VIEW",
                    "-d", uri.trim()
            ).redirectErrorStream(true).start();
            int code = process.waitFor();
            log.info("打开 Android URI: deviceId={}, uri={}, exit={}", deviceId, uri, code);
            return code == 0;
        } catch (Exception e) {
            log.warn("打开 Android URI 失败: {}", e.getMessage());
            return false;
        }
    }

    /** 返回 [width, height]；失败返回 null */
    public int[] getAndroidScreenSize(String deviceId) {
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "wm", "size")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            process.waitFor();
            // Physical size: 1080x2400 或 Override size: ...
            String text = out.toString();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)x(\\d+)")
                    .matcher(text);
            String last = null;
            while (m.find()) {
                last = m.group(0);
            }
            if (last != null) {
                String[] parts = last.split("x");
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            }
        } catch (Exception e) {
            log.warn("读取屏幕尺寸失败: {}", e.getMessage());
        }
        return null;
    }

    public boolean adbTap(String deviceId, int x, int y) {
        try {
            Process process = new ProcessBuilder(
                    "adb", "-s", deviceId, "shell", "input", "tap", String.valueOf(x), String.valueOf(y)
            ).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            log.warn("adb tap 失败: {}", e.getMessage());
            return false;
        }
    }

    /** 回桌面（KEYCODE_HOME=3） */
    public boolean pressAndroidHome(String deviceId) {
        try {
            Process process = new ProcessBuilder(
                    "adb", "-s", deviceId, "shell", "input", "keyevent", "KEYCODE_HOME"
            ).redirectErrorStream(true).start();
            boolean ok = process.waitFor() == 0;
            log.info("Android 回桌面: deviceId={}, ok={}", deviceId, ok);
            return ok;
        } catch (Exception e) {
            log.warn("Android 回桌面失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 启动 Android 应用：优先已知电竞/Pog 主入口，否则用 monkey LAUNCHER。
     */
    public boolean launchAndroidApp(String deviceId, String packageName) {
        if (deviceId == null || packageName == null || packageName.isBlank()) {
            return false;
        }
        String pkg = packageName.trim();
        String component = null;
        if ("com.pwrd.steam.esports".equals(pkg)) {
            component = "com.pwrd.steam.esports/.ui.activity.launcher.LauncherActivity";
        } else if ("com.arca.egames.intl".equals(pkg)) {
            component = "com.arca.egames.intl/.LauncherActivity";
        }
        if (component != null) {
            try {
                Process p = new ProcessBuilder(
                        "adb", "-s", deviceId, "shell", "am", "start", "-n", component
                ).redirectErrorStream(true).start();
                boolean ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
                log.info("Android am start: deviceId={}, component={}, ok={}", deviceId, component, ok);
                if (ok) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("am start 失败: {}", e.getMessage());
            }
        }
        try {
            Process p = new ProcessBuilder(
                    "adb", "-s", deviceId, "shell", "monkey",
                    "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"
            ).redirectErrorStream(true).start();
            boolean ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            log.info("Android monkey 启动: deviceId={}, pkg={}, ok={}", deviceId, pkg, ok);
            return ok;
        } catch (Exception e) {
            log.warn("monkey 启动失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 不经 Appium / WDA：用 devicectl 的 payload-url 打开 App Store 详情；失败则 idevicedebug 拉起 App Store。
     */
    public boolean openIosAppStoreUrl(String deviceId, String url) {
        if (deviceId == null || url == null || url.isBlank()) {
            return false;
        }
        String u = url.trim();
        if (launchIosWithDevicectl(deviceId, "com.apple.AppStore", u)) {
            return true;
        }
        if (u.startsWith("https://")) {
            String itms = u.replace("https://", "itms-apps://");
            if (launchIosWithDevicectl(deviceId, "com.apple.AppStore", itms)) {
                return true;
            }
        }
        // Safari 打开 HTTPS 也可跳进 App Store
        if (u.startsWith("https://") && launchIosWithDevicectl(deviceId, "com.apple.mobilesafari", u)) {
            return true;
        }
        return launchIosAppCli(deviceId, "com.apple.AppStore");
    }

    /** 启动已安装的 iOS App（devicectl → idevicedebug），不触发 WDA 重装 */
    public boolean launchIosAppCli(String deviceId, String bundleId) {
        if (deviceId == null || bundleId == null || bundleId.isBlank()) {
            return false;
        }
        String bundle = bundleId.trim();
        if (launchIosWithDevicectl(deviceId, bundle, null)) {
            return true;
        }
        try {
            Process p = new ProcessBuilder(
                    "idevicedebug", "-u", deviceId, "run", bundle, "--detach"
            ).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            int code = finished ? p.exitValue() : -1;
            // --detach 时进程可能非 0 仍已拉起
            boolean ok = finished && (code == 0 || out.toString().toLowerCase().contains("running"));
            log.info("idevicedebug 启动: deviceId={}, bundle={}, exit={}, ok={}", deviceId, bundle, code, ok);
            return ok || finished;
        } catch (Exception e) {
            log.warn("idevicedebug 启动失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean launchIosWithDevicectl(String deviceId, String bundleId, String payloadUrl) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("xcrun");
            cmd.add("devicectl");
            cmd.add("device");
            cmd.add("process");
            cmd.add("launch");
            cmd.add("--device");
            cmd.add(deviceId);
            if (payloadUrl != null && !payloadUrl.isBlank()) {
                cmd.add("--payload-url");
                cmd.add(payloadUrl.trim());
            }
            cmd.add(bundleId);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = p.waitFor(25, TimeUnit.SECONDS);
            int code = finished ? p.exitValue() : -1;
            boolean ok = finished && code == 0;
            log.info("devicectl launch: deviceId={}, bundle={}, payload={}, exit={}, ok={}, out={}",
                    deviceId, bundleId, payloadUrl, code, ok,
                    out.length() > 200 ? out.substring(0, 200) : out);
            return ok;
        } catch (Exception e) {
            log.debug("devicectl launch 不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 确保本机可访问的 WDA 已就绪（{@code http://127.0.0.1:localPort/status}）。
     * <p>
     * 只跑 {@code xcodebuild test-without-building} + {@code iproxy}，不重装手机上的 WDA，避免反复信任。
     */
    public synchronized boolean ensureIosWdaRunning(String deviceId, int localPort) {
        if (deviceId == null || deviceId.isBlank() || localPort <= 0) {
            return false;
        }
        if (isWdaStatusOk(localPort)) {
            log.info("WDA 已就绪: deviceId={}, port={}", deviceId, localPort);
            return true;
        }
        String wdaDir = System.getProperty("user.home")
                + "/.appium/node_modules/appium-xcuitest-driver/node_modules/appium-webdriveragent";
        Path project = Path.of(wdaDir, "WebDriverAgent.xcodeproj");
        if (!Files.isDirectory(project)) {
            log.warn("未找到 WDA 工程: {}", project);
            return false;
        }

        // 1) xcodebuild test-without-building（复用已装 Runner）
        Process xcode = iosWdaXcodeProcs.get(deviceId);
        if (xcode == null || !xcode.isAlive()) {
            try {
                Path logFile = Path.of(System.getProperty("java.io.tmpdir"),
                        "wda-" + deviceId.replaceAll("[^A-Za-z0-9_-]", "_") + ".log");
                List<String> cmd = List.of(
                        "xcodebuild", "test-without-building",
                        "-project", project.toString(),
                        "-scheme", "WebDriverAgentRunner",
                        "-destination", "id=" + deviceId,
                        "IPHONEOS_DEPLOYMENT_TARGET=17.6",
                        "DEVELOPMENT_TEAM=37ZX3X6QJY",
                        "CODE_SIGN_IDENTITY=Apple Development",
                        "PRODUCT_BUNDLE_IDENTIFIER=com.travYang.wda.Runner",
                        "GCC_TREAT_WARNINGS_AS_ERRORS=0",
                        "COMPILER_INDEX_STORE_ENABLE=NO"
                );
                ProcessBuilder pb = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                xcode = pb.start();
                iosWdaXcodeProcs.put(deviceId, xcode);
                log.info("已启动 WDA xcodebuild(test-without-building): deviceId={}, log={}", deviceId, logFile);

                boolean ready = false;
                for (int i = 0; i < 90; i++) {
                    if (!xcode.isAlive()) {
                        log.warn("WDA xcodebuild 提前退出: deviceId={}", deviceId);
                        break;
                    }
                    String logText = Files.exists(logFile) ? Files.readString(logFile) : "";
                    if (logText.contains("ServerURLHere")) {
                        ready = true;
                        break;
                    }
                    Thread.sleep(1000);
                }
                if (!ready) {
                    log.warn("等待 WDA ServerURLHere 超时: deviceId={}", deviceId);
                }
            } catch (Exception e) {
                log.error("启动 WDA xcodebuild 失败: {}", e.getMessage());
                return false;
            }
        }

        // 2) iproxy localPort -> device 8100
        Process iproxy = iosWdaIproxyProcs.get(deviceId);
        if (iproxy == null || !iproxy.isAlive() || !isWdaStatusOk(localPort)) {
            destroyQuietly(iosWdaIproxyProcs.remove(deviceId));
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "iproxy", localPort + ":8100", "-u", deviceId
                ).redirectErrorStream(true);
                iproxy = pb.start();
                iosWdaIproxyProcs.put(deviceId, iproxy);
                log.info("已启动 iproxy: {} -> device:8100, deviceId={}", localPort, deviceId);
                Thread.sleep(1500);
            } catch (Exception e) {
                log.error("启动 iproxy 失败: {}", e.getMessage());
                return false;
            }
        }

        for (int i = 0; i < 20; i++) {
            if (isWdaStatusOk(localPort)) {
                log.info("WDA 就绪: deviceId={}, http://127.0.0.1:{}/status", deviceId, localPort);
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("WDA status 仍不可达: deviceId={}, port={}", deviceId, localPort);
        return false;
    }

    private static boolean isWdaStatusOk(int localPort) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + localPort + "/status").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1200);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private static void destroyQuietly(Process p) {
        if (p == null) {
            return;
        }
        try {
            p.destroyForcibly();
        } catch (Exception ignored) {
            // ignore
        }
    }
    
    /**
     * 安装 IPA 到 iOS 设备
     */
    public boolean installIOSApp(String deviceId, String ipaPath) {
        try {
            Process process = new ProcessBuilder("ideviceinstaller", "-u", deviceId, "-i", ipaPath)
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String line;
                StringBuilder output = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("ideviceinstaller: {}", line);
                }
                
                int exitCode = process.waitFor();
                
                if (exitCode == 0 || output.toString().contains("Complete")) {
                    log.info("IPA 安装成功: {}", ipaPath);
                    return true;
                } else {
                    log.error("IPA 安装失败: {}", output.toString());
                    return false;
                }
            }
            
        } catch (IOException | InterruptedException e) {
            log.error("安装 IPA 失败", e);
            return false;
        }
    }

    /**
     * 卸载 iOS 应用。未安装也视为成功。
     * 新版 ideviceinstaller 语法：ideviceinstaller -u UDID uninstall BUNDLEID（不再支持 -U）
     */
    public boolean uninstallIOSApp(String deviceId, String bundleId) {
        if (bundleId == null || bundleId.isBlank()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(
                    "ideviceinstaller", "-u", deviceId, "uninstall", bundleId.trim())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            String text = output.toString();
            if (exitCode == 0
                    || text.toLowerCase().contains("complete")
                    || text.toLowerCase().contains("not found")
                    || text.toLowerCase().contains("no such")
                    || text.toLowerCase().contains("not installed")) {
                log.info("iOS 卸载完成: deviceId={}, bundleId={}, out={}", deviceId, bundleId, text.trim());
                return true;
            }
            log.error("iOS 卸载失败: exit={}, out={}", exitCode, text);
            return false;
        } catch (IOException | InterruptedException e) {
            log.error("卸载 iOS 应用失败: {}", e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** iOS 是否已安装指定 Bundle ID（ideviceinstaller list） */
    public boolean isIOSAppInstalled(String deviceId, String bundleId) {
        if (bundleId == null || bundleId.isBlank()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("ideviceinstaller", "-u", deviceId, "list")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            process.waitFor();
            String needle = bundleId.trim();
            for (String line : output.toString().split("\n")) {
                if (line.contains(needle)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("检查 iOS 安装状态失败: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 校验是否为合法的 ADB 设备 ID（序列号），排除 adb 错误行被误解析
     */
    private boolean isValidAdbDeviceId(String deviceId) {
        if (deviceId == null || deviceId.length() < 2) return false;
        String lower = deviceId.toLowerCase();
        if (lower.contains("adb") || lower.contains("not found") || lower.contains("list")
                || lower.contains("error") || deviceId.contains("'") || deviceId.contains("\"")) {
            return false;
        }
        // 设备 ID 通常为字母数字、点、横线、下划线
        return deviceId.matches("^[a-zA-Z0-9._-]+$");
    }

    /**
     * 判断 version 是否像错误信息（getprop 失败时可能返回 adb 错误）
     */
    private boolean isErrorLikeVersion(String version) {
        if (version == null || version.isEmpty()) return false;
        String lower = version.toLowerCase();
        return lower.contains("adb") || lower.contains("not found") || lower.contains("error")
                || lower.contains("list") || lower.contains("device '");
    }

    /**
     * 从 adb devices -l 输出中提取设备名称
     */
    private String extractDeviceName(String line) {
        // 格式: device_id device model:MODEL_NAME transport_id:TRANSPORT_ID
        Pattern pattern = Pattern.compile("model:([^\\s]+)");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 获取 Android 设备版本
     */
    private String getAndroidVersion(String deviceId) {
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "getprop", "ro.build.version.release")
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String version = reader.readLine();
                if (version != null) {
                    version = version.trim();
                    if (!isErrorLikeVersion(version)) {
                        return version;
                    }
                }
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            log.warn("获取 Android 版本失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 获取 iOS 设备名称
     */
    private String getIOSDeviceName(String deviceId) {
        try {
            Process process = new ProcessBuilder("ideviceinfo", "-u", deviceId, "-k", "DeviceName")
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String name = reader.readLine();
                if (name != null) {
                    return name.trim();
                }
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            log.warn("获取 iOS 设备名称失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 获取 iOS 设备版本
     */
    private String getIOSVersion(String deviceId) {
        try {
            Process process = new ProcessBuilder("ideviceinfo", "-u", deviceId, "-k", "ProductVersion")
                    .redirectErrorStream(true)
                    .start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                
                String version = reader.readLine();
                if (version != null) {
                    return version.trim();
                }
            }
            
            process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            log.warn("获取 iOS 版本失败: {}", e.getMessage());
        }
        
        return null;
    }

    // ---------- 设备详情：电量、iOS 详细信息 ----------

    /**
     * 获取 Android 设备电量百分比（0-100），获取失败返回 null。
     */
    public Integer getAndroidBatteryLevel(String deviceId) {
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "battery")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("level:")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 2) {
                            int level = Integer.parseInt(parts[1].trim());
                            return Math.min(100, Math.max(0, level));
                        }
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.debug("获取 Android 电量失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 执行 adb shell 命令并返回标准输出（整段文本），失败或超时返回 null。
     * 包内可见，供 DeviceService 等拼装 Android 完整原始信息用。
     */
    String runAdbShell(String deviceId, String... shellArgs) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("adb");
            cmd.add("-s");
            cmd.add(deviceId);
            cmd.add("shell");
            for (String a : shellArgs) {
                cmd.add(a);
            }
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() > 0) out.append("\n");
                    out.append(line);
                }
            }
            process.waitFor();
            return out.toString();
        } catch (Exception e) {
            log.debug("adb shell 执行失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取 Android 设备详细信息（通过 ADB getprop / dumpsys / settings / wm 等）。
     * 返回分层结构：systemInfo、hardwareInfo、batteryInfo、storageInfo、networkInfo、securityInfo 等，供前端展示。
     */
    public Map<String, Object> getAndroidDeviceDetail(String deviceId) {
        Map<String, Object> root = new LinkedHashMap<>();
        // 一、系统基础信息
        Map<String, String> systemInfo = new LinkedHashMap<>();
        systemInfo.put("serialNumber", getDeviceProperty(deviceId, "ro.serialno"));
        systemInfo.put("androidId", runAdbShell(deviceId, "settings", "get", "secure", "android_id"));
        systemInfo.put("versionRelease", getDeviceProperty(deviceId, "ro.build.version.release"));
        systemInfo.put("sdkInt", getDeviceProperty(deviceId, "ro.build.version.sdk"));
        systemInfo.put("securityPatch", getDeviceProperty(deviceId, "ro.build.version.security_patch"));
        systemInfo.put("buildType", getDeviceProperty(deviceId, "ro.build.type"));
        systemInfo.put("brand", getDeviceProperty(deviceId, "ro.product.brand"));
        systemInfo.put("model", getDeviceProperty(deviceId, "ro.product.model"));
        systemInfo.put("manufacturer", getDeviceProperty(deviceId, "ro.product.manufacturer"));
        systemInfo.put("board", getDeviceProperty(deviceId, "ro.product.board"));
        systemInfo.put("hardware", getDeviceProperty(deviceId, "ro.hardware"));
        systemInfo.put("deviceName", getDeviceProperty(deviceId, "ro.product.name"));
        systemInfo.put("productName", getDeviceProperty(deviceId, "ro.product.product.name"));
        String btName = runAdbShell(deviceId, "settings", "get", "secure", "bluetooth_name");
        if (btName != null && !btName.equals("null")) systemInfo.put("bluetoothDeviceName", btName.trim());
        root.put("systemInfo", systemInfo);

        // 二、硬件：屏幕、CPU、内存
        Map<String, String> hardwareInfo = new LinkedHashMap<>();
        String wmSize = runAdbShell(deviceId, "wm", "size");
        if (wmSize != null) {
            String parsed = wmSize.replace("Physical size:", "").trim();
            if (parsed.contains("\n")) parsed = parsed.substring(0, parsed.indexOf("\n")).trim();
            hardwareInfo.put("screenSize", parsed);
        }
        String wmDensity = runAdbShell(deviceId, "wm", "density");
        if (wmDensity != null) {
            String parsed = wmDensity.replace("Physical density:", "").trim();
            if (parsed.contains("\n")) parsed = parsed.substring(0, parsed.indexOf("\n")).trim();
            hardwareInfo.put("screenDensity", parsed);
        }
        hardwareInfo.put("cpuAbi", getDeviceProperty(deviceId, "ro.product.cpu.abi"));
        String nproc = runAdbShell(deviceId, "nproc");
        if (nproc != null) hardwareInfo.put("cpuCores", nproc.trim());
        String meminfo = runAdbShell(deviceId, "cat", "/proc/meminfo");
        if (meminfo != null) {
            for (String line : meminfo.split("\n")) {
                if (line.startsWith("MemTotal:"))
                    hardwareInfo.put("memTotal", line.replace("MemTotal:", "").trim());
                else if (line.startsWith("MemAvailable:"))
                    hardwareInfo.put("memAvailable", line.replace("MemAvailable:", "").trim());
            }
        }
        root.put("hardwareInfo", hardwareInfo);

        // 三、电池
        Map<String, String> batteryInfo = new LinkedHashMap<>();
        String battery = runAdbShell(deviceId, "dumpsys", "battery");
        if (battery != null) {
            for (String line : battery.split("\n")) {
                line = line.trim();
                if (line.startsWith("level:")) batteryInfo.put("level", line.replace("level:", "").trim());
                else if (line.startsWith("status:")) batteryInfo.put("status", line.replace("status:", "").trim());
                else if (line.startsWith("temperature:")) batteryInfo.put("temperature", line.replace("temperature:", "").trim());
                else if (line.startsWith("voltage:")) batteryInfo.put("voltage", line.replace("voltage:", "").trim());
                else if (line.startsWith("health:")) batteryInfo.put("health", line.replace("health:", "").trim());
                else if (line.startsWith("AC powered:") || line.startsWith("USB powered:")) {
                    if (line.contains("true")) batteryInfo.put("charging", "true");
                }
            }
            if (!batteryInfo.containsKey("charging")) batteryInfo.put("charging", "false");
        }
        root.put("batteryInfo", batteryInfo);

        // 四、存储
        Map<String, String> storageInfo = new LinkedHashMap<>();
        String df = runAdbShell(deviceId, "df", "/data");
        if (df != null) {
            String[] lines = df.split("\n");
            if (lines.length >= 2) {
                String[] cols = lines[1].split("\\s+");
                if (cols.length >= 4) {
                    storageInfo.put("totalBlocks", cols[1]);
                    storageInfo.put("usedBlocks", cols[2]);
                    storageInfo.put("availableBlocks", cols[3]);
                }
            }
        }
        root.put("storageInfo", storageInfo);

        // 五、网络
        Map<String, String> networkInfo = new LinkedHashMap<>();
        String wifi = runAdbShell(deviceId, "dumpsys", "wifi");
        if (wifi != null) {
            if (wifi.contains("mWifiInfo")) {
                int ssidStart = wifi.indexOf("SSID: ");
                if (ssidStart >= 0) {
                    int end = wifi.indexOf("\n", ssidStart);
                    String ssidLine = end > 0 ? wifi.substring(ssidStart, end) : wifi.substring(ssidStart);
                    networkInfo.put("wifiSsid", ssidLine.replace("SSID: ", "").trim());
                }
                int ipStart = wifi.indexOf("ip=");
                if (ipStart >= 0) {
                    int end = wifi.indexOf(",", ipStart);
                    if (end < 0) end = wifi.indexOf("\n", ipStart);
                    String ipLine = end > 0 ? wifi.substring(ipStart, end) : wifi.substring(ipStart);
                    networkInfo.put("wifiIp", ipLine.replace("ip=", "").trim());
                }
            }
        }
        String conn = runAdbShell(deviceId, "dumpsys", "connectivity");
        if (conn != null && conn.contains("Active default network")) {
            networkInfo.put("networkType", conn.contains("WIFI") ? "WIFI" : (conn.contains("MOBILE") ? "MOBILE" : "UNKNOWN"));
        }
        String nfc = runAdbShell(deviceId, "dumpsys", "nfc");
        if (nfc != null) {
            networkInfo.put("nfcEnabled", nfc.contains("mState=STATE_ON") || nfc.contains("mState=STATE_TURNING_ON") ? "true" : "false");
        }
        String loc = runAdbShell(deviceId, "dumpsys", "location");
        if (loc != null) {
            networkInfo.put("locationEnabled", loc.contains("mLocationEnabled=true") ? "true" : "false");
        }
        root.put("networkInfo", networkInfo);

        // 六、安全与权限
        Map<String, String> securityInfo = new LinkedHashMap<>();
        securityInfo.put("adbEnabled", runAdbShell(deviceId, "settings", "get", "global", "adb_enabled"));
        String tags = getDeviceProperty(deviceId, "ro.build.tags");
        securityInfo.put("buildTags", tags);
        securityInfo.put("unlockedBootloader", tags != null && tags.contains("test-keys") ? "true" : "false");
        String su = runAdbShell(deviceId, "which", "su");
        securityInfo.put("rooted", (su != null && !su.trim().isEmpty()) ? "true" : "false");
        root.put("securityInfo", securityInfo);

        // 七、传感器（简要）
        String sensors = runAdbShell(deviceId, "dumpsys", "sensorservice");
        if (sensors != null) {
            Map<String, String> sensorInfo = new LinkedHashMap<>();
            sensorInfo.put("hasAccelerometer", sensors.contains("Accelerometer") ? "true" : "false");
            sensorInfo.put("hasGyroscope", sensors.contains("Gyroscope") ? "true" : "false");
            sensorInfo.put("hasFingerprint", (sensors.contains("Fingerprint") || sensors.contains("fingerprint")) ? "true" : "false");
            root.put("sensorInfo", sensorInfo);
        }

        return root;
    }

    /**
     * 获取 iOS 设备电量百分比（0-100），需 libimobiledevice 支持 battery 域，失败返回 null。
     */
    public Integer getIOSBatteryLevel(String deviceId) {
        try {
            Process process = new ProcessBuilder("ideviceinfo", "-u", deviceId,
                            "-q", "com.apple.mobile.battery", "-k", "BatteryCurrentCapacity")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    int level = Integer.parseInt(line.trim());
                    return Math.min(100, Math.max(0, level));
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.debug("获取 iOS 电量失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取 iOS 设备详细信息（类似 iMazing 概览）。
     * 返回 Pair：左为解析后的键值对 Map，右为 ideviceinfo 的完整原始输出（便于前端完整展示）。
     */
    public static class IOSDetailResult {
        public final Map<String, String> parsed;
        public final String rawOutput;

        public IOSDetailResult(Map<String, String> parsed, String rawOutput) {
            this.parsed = parsed != null ? parsed : new LinkedHashMap<>();
            this.rawOutput = rawOutput != null ? rawOutput : "";
        }
    }

    public IOSDetailResult getIOSDeviceDetailWithRaw(String deviceId) {
        Map<String, String> detail = new LinkedHashMap<>();
        StringBuilder raw = new StringBuilder();
        try {
            Process process = new ProcessBuilder("ideviceinfo", "-u", deviceId)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    raw.append(line).append("\n");
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).trim();
                        String value = line.substring(colon + 1).trim();
                        if (!key.isEmpty()) {
                            detail.put(key, value);
                        }
                    }
                }
            }
            int exitCode = process.waitFor();
            if (raw.length() == 0) {
                raw.append("[ideviceinfo 未返回数据] 退出码: ").append(exitCode)
                        .append("\n请确认：1) 设备已通过 USB 连接  2) 设备已信任此电脑  3) 已安装 libimobiledevice（macOS: brew install libimobiledevice）");
            } else if (exitCode != 0) {
                raw.insert(0, "[ideviceinfo 退出码 " + exitCode + "]\n");
            }
        } catch (Exception e) {
            log.warn("获取 iOS 设备详情失败: deviceId={}, error={}", deviceId, e.getMessage());
            if (raw.length() == 0) {
                raw.append("获取失败: ").append(e.getMessage())
                        .append("\n请确认：1) 已安装 libimobiledevice（ideviceinfo 命令）  2) 设备已连接并信任此电脑");
            }
        }
        return new IOSDetailResult(detail, raw.toString());
    }

    /** 兼容旧调用：仅返回解析后的 Map */
    public Map<String, String> getIOSDeviceDetail(String deviceId) {
        return getIOSDeviceDetailWithRaw(deviceId).parsed;
    }

    /**
     * 获取 iOS 已安装应用列表（含名称、版本、bundleId），一次 ideviceinstaller -l 解析。
     */
    public List<AppInfo> getIOSInstalledAppsWithInfo(String deviceId) {
        List<AppInfo> result = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("ideviceinstaller", "-u", deviceId, "-l")
                    .redirectErrorStream(true)
                    .start();
            boolean isCSVFormat = false;
            boolean isFirstLine = true;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isFirstLine) {
                        isFirstLine = false;
                        if (line.contains("CFBundleIdentifier") || line.contains("CFBundleDisplayName")) {
                            isCSVFormat = true;
                            continue;
                        }
                    }
                    if (line.trim().isEmpty() || line.contains("Total:")) continue;
                    if (isCSVFormat) {
                        String[] parts = line.split(",");
                        if (parts.length >= 1) {
                            String bundleId = parts[0].trim();
                            if (bundleId.startsWith("\"")) bundleId = bundleId.substring(1);
                            if (bundleId.endsWith("\"")) bundleId = bundleId.substring(0, bundleId.length() - 1);
                            if (bundleId.isEmpty()) continue;
                            AppInfo info = new AppInfo();
                            info.setPackageName(bundleId);
                            if (parts.length >= 2) {
                                String ver = parts[1].trim();
                                if (ver.startsWith("\"")) ver = ver.substring(1);
                                if (ver.endsWith("\"")) ver = ver.substring(0, ver.length() - 1);
                                info.setVersion(ver);
                            } else {
                                info.setVersion("1.0.0");
                            }
                            if (parts.length >= 3) {
                                String name = parts[2].trim();
                                if (name.startsWith("\"")) name = name.substring(1);
                                if (name.endsWith("\"")) name = name.substring(0, name.length() - 1);
                                info.setName(name);
                            } else {
                                info.setName(bundleId);
                            }
                            result.add(info);
                        }
                    } else {
                        if (line.contains(" - ")) {
                            String[] parts = line.split(" - ");
                            if (parts.length >= 2) {
                                String bundleId = parts[parts.length - 1].trim();
                                if (bundleId.isEmpty() || "Total:".equals(bundleId)) continue;
                                String namePart = parts[0].trim();
                                AppInfo info = new AppInfo();
                                info.setPackageName(bundleId);
                                info.setName(namePart);
                                info.setVersion("1.0.0");
                                if (namePart.contains("(") && namePart.contains(")")) {
                                    int s = namePart.indexOf("("), e = namePart.indexOf(")");
                                    if (e > s) {
                                        info.setName(namePart.substring(0, s).trim());
                                        info.setVersion(namePart.substring(s + 1, e).trim());
                                    }
                                }
                                result.add(info);
                            }
                        }
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.warn("获取 iOS 应用列表(含信息)失败: {}", e.getMessage());
        }
        return result;
    }
}
