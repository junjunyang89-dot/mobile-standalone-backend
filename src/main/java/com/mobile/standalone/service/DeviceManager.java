package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.service.harmony.HdcClient;
import com.mobile.standalone.service.harmony.HmdriverBridge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备管理工具类
 * 支持 Android (ADB)、iOS (libimobiledevice)、纯鸿蒙 NEXT (hdc)
 */
@Component
@Slf4j
public class DeviceManager {

    /**
     * 安装超时。包大（60MB）+ 首次安装要优化 dex，几分钟是正常的；
     * 但必须有上限，否则设备上弹确认框没人点时会永远挂住。
     */
    @org.springframework.beans.factory.annotation.Value("${app.install.adb-install-timeout-seconds:300}")
    private int installTimeoutSeconds = 300;

    @Autowired
    private HdcClient hdcClient;

    @Autowired
    private HmdriverBridge hmdriverBridge;

    /** 每台设备常挂的 WDA xcodebuild / iproxy（不重装，仅 test-without-building） */
    private final ConcurrentHashMap<String, Process> iosWdaXcodeProcs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Process> iosWdaIproxyProcs = new ConcurrentHashMap<>();
    /** 最近一次 WDA 拉起失败原因（供真机点选等接口回显） */
    private final ConcurrentHashMap<String, String> iosWdaLastError = new ConcurrentHashMap<>();
    
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
     * 扫描纯鸿蒙 NEXT 设备（仅 hdc，不走 adb）。
     * 华为/荣耀仍跑 Android 兼容层的机子继续出现在 Android 列表。
     * 未授权调试的设备也会入库（平台 HARMONYOS，状态未连接），方便在设备管理里看见。
     */
    public List<Device> scanHarmonyOSDevices() {
        List<Device> devices = new ArrayList<>();
        if (hdcClient == null) {
            return devices;
        }
        if (!hdcClient.available()) {
            hdcClient.refresh();
        }
        if (!hdcClient.available()) {
            log.debug("hdc 不可用，跳过鸿蒙设备扫描");
            return devices;
        }
        try {
            for (HdcClient.HdcTarget target : hdcClient.listTargetEntries()) {
                if (target == null || target.serial() == null || target.serial().isBlank()) {
                    continue;
                }
                String deviceId = target.serial().trim();
                Device device = new Device();
                device.setId(deviceId);
                device.setPlatform(Device.Platform.HARMONYOS);

                if (target.authorized()) {
                    String name = hdcParam(deviceId, "const.product.name");
                    if (name == null || name.isBlank()) {
                        name = hdcParam(deviceId, "const.product.model");
                    }
                    String version = hdcParam(deviceId, "const.product.software.version");
                    if (version == null || version.isBlank()) {
                        version = hdcParam(deviceId, "const.ohos.apiversion");
                    }
                    device.setName(name != null && !name.isBlank() ? name : "HarmonyOS Device");
                    device.setVersion(version);
                    device.setStatus(Device.DeviceStatus.CONNECTED);
                    log.info("发现鸿蒙设备(hdc): {} ({})", device.getName(), deviceId);
                } else {
                    String reason = target.unauthorized() ? "请点允许USB调试" : "离线";
                    device.setName("鸿蒙设备(" + reason + ")");
                    device.setVersion(null);
                    device.setStatus(Device.DeviceStatus.DISCONNECTED);
                    log.info("发现鸿蒙设备(hdc未就绪): {} ({})", device.getName(), deviceId);
                }
                devices.add(device);
            }
        } catch (Exception e) {
            log.error("扫描鸿蒙设备失败", e);
        }
        return devices;
    }

    private String hdcParam(String deviceId, String key) {
        try {
            String out = hdcClient.shell(deviceId, "param get " + key);
            if (out == null) {
                return null;
            }
            String v = out.trim();
            // 常见失败回显
            if (v.isEmpty() || v.toLowerCase(Locale.ROOT).contains("fail")
                    || v.toLowerCase(Locale.ROOT).contains("error")
                    || v.contains("not exist")) {
                return null;
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取鸿蒙 param，供设备详情等复用 */
    public String getHarmonyParam(String deviceId, String key) {
        return hdcParam(deviceId, key);
    }

    public String runHdcShell(String deviceId, String command) {
        if (hdcClient == null || !hdcClient.available()) {
            return "";
        }
        return hdcClient.shell(deviceId, command);
    }

    /** 鸿蒙已安装三方包名（优先 hmdriver2，其次 hdc bm dump） */
    public List<String> getHarmonyInstalledPackages(String deviceId) {
        List<String> packages = new ArrayList<>();
        if (hmdriverBridge != null && hmdriverBridge.available()) {
            packages.addAll(hmdriverBridge.listApps(deviceId));
            if (!packages.isEmpty()) {
                return packages;
            }
        }
        return listHarmonyPackagesViaHdc(deviceId);
    }

    /**
     * 仅经 hdc 快速列包名（不做 version 富化）。
     */
    public List<String> listHarmonyPackagesViaHdc(String deviceId) {
        List<String> packages = new ArrayList<>();
        if (hdcClient == null || !hdcClient.available()) {
            return packages;
        }
        String labeled = hdcClient.shell(deviceId, "bm dump -a -l");
        if (labeled != null && labeled.contains("bundleName")) {
            Matcher m = Pattern.compile("\"bundleName\"\\s*:\\s*\"([^\"]+)\"").matcher(labeled);
            while (m.find()) {
                String name = m.group(1);
                if (name != null && !name.isBlank() && !packages.contains(name)) {
                    packages.add(name);
                }
            }
            if (!packages.isEmpty()) {
                return packages;
            }
        }
        String dump = hdcClient.shell(deviceId, "bm dump -a");
        if (dump == null || dump.isBlank()) {
            return packages;
        }
        Pattern pkgLine = Pattern.compile("^[\\t ]+([A-Za-z0-9][A-Za-z0-9_.]*\\.[A-Za-z0-9_.]+)\\s*$");
        for (String line : dump.split("\\R")) {
            Matcher ml = pkgLine.matcher(line);
            if (ml.find()) {
                String name = ml.group(1);
                if (!packages.contains(name)) {
                    packages.add(name);
                }
            }
        }
        return packages;
    }

    /**
     * 鸿蒙已装应用：优先 {@code bm dump -a -l} 拿包名+显示名，再并行 {@code bm dump -n} 补 versionName。
     */
    public List<AppInfo> listHarmonyAppsViaHdc(String deviceId) {
        List<AppInfo> apps = new ArrayList<>();
        if (hdcClient == null || !hdcClient.available()) {
            return apps;
        }
        // 1) 包名 + 应用名（快）
        String labeled = hdcClient.shell(deviceId, "bm dump -a -l");
        if (labeled != null && labeled.contains("bundleName")) {
            Pattern pair = Pattern.compile(
                    "\"bundleName\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"label\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                    Pattern.DOTALL);
            Matcher m = pair.matcher(labeled);
            while (m.find()) {
                String pkg = m.group(1);
                String label = m.group(2) == null ? "" : m.group(2).replace("\\\"", "\"");
                if (pkg == null || pkg.isBlank()) continue;
                AppInfo info = new AppInfo();
                info.setPackageName(pkg);
                info.setName(label.isBlank() ? pkg : label);
                info.setVersion("");
                apps.add(info);
            }
        }
        if (apps.isEmpty()) {
            String dump = hdcClient.shell(deviceId, "bm dump -a");
            if (dump != null && !dump.isBlank()) {
                Pattern pkgLine = Pattern.compile("^[\\t ]+([A-Za-z0-9][A-Za-z0-9_.]*\\.[A-Za-z0-9_.]+)\\s*$");
                for (String line : dump.split("\\R")) {
                    Matcher ml = pkgLine.matcher(line);
                    if (ml.find()) {
                        String name = ml.group(1);
                        AppInfo info = new AppInfo();
                        info.setPackageName(name);
                        info.setName(name);
                        info.setVersion("");
                        apps.add(info);
                    }
                }
            }
        }
        if (apps.isEmpty()) {
            return apps;
        }
        if (apps.size() > 200) {
            apps = new ArrayList<>(apps.subList(0, 200));
        }
        // 2) 并行补版本（单包 bm dump -n）；限制并发避免打爆 hdc
        enrichHarmonyAppVersions(deviceId, apps);
        return apps;
    }

    private void enrichHarmonyAppVersions(String deviceId, List<AppInfo> apps) {
        if (apps == null || apps.isEmpty()) return;
        int threads = Math.min(12, Math.max(4, Runtime.getRuntime().availableProcessors()));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (AppInfo app : apps) {
                futures.add(pool.submit(() -> {
                    String ver = readHarmonyAppVersion(deviceId, app.getPackageName());
                    if (ver != null && !ver.isBlank()) {
                        app.setVersion(ver);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get(90, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // 单包失败忽略
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 从 bm dump -n 解析第一个非空 versionName */
    private String readHarmonyAppVersion(String deviceId, String packageName) {
        if (packageName == null || packageName.isBlank()) return null;
        String dump = runHdcShell(deviceId, "bm dump -n " + packageName);
        if (dump == null || dump.isBlank()) return null;
        Matcher m = Pattern.compile("\"versionName\"\\s*:\\s*\"([^\"]*)\"").matcher(dump);
        while (m.find()) {
            String v = m.group(1);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * 鸿蒙电量百分比（0-100）。来源：{@code hidumper -s 3302 -a -i} 的 capacity。
     */
    public Integer getHarmonyBatteryLevel(String deviceId) {
        Map<String, String> info = getHarmonyBatteryInfo(deviceId);
        if (info == null || !info.containsKey("level")) {
            return null;
        }
        try {
            int level = Integer.parseInt(info.get("level").trim());
            return Math.min(100, Math.max(0, level));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 鸿蒙电池字段（hidumper BatteryService） */
    public Map<String, String> getHarmonyBatteryInfo(String deviceId) {
        Map<String, String> batteryInfo = new LinkedHashMap<>();
        String dump = runHdcShell(deviceId, "hidumper -s 3302 -a -i");
        if (dump == null || dump.isBlank()) {
            return batteryInfo;
        }
        for (String line : dump.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("capacity:")) {
                batteryInfo.put("level", t.substring("capacity:".length()).trim());
            } else if (t.startsWith("batteryLevel:")) {
                batteryInfo.put("batteryLevel", t.substring("batteryLevel:".length()).trim());
            } else if (t.startsWith("chargingStatus:")) {
                batteryInfo.put("chargingStatus", t.substring("chargingStatus:".length()).trim());
            } else if (t.startsWith("pluggedType:")) {
                batteryInfo.put("pluggedType", t.substring("pluggedType:".length()).trim());
            } else if (t.startsWith("voltage:")) {
                batteryInfo.put("voltage", t.substring("voltage:".length()).trim());
            } else if (t.startsWith("temperature:")) {
                String raw = t.substring("temperature:".length()).trim();
                batteryInfo.put("temperatureRaw", raw);
                try {
                    double c = Integer.parseInt(raw) / 10.0;
                    batteryInfo.put("temperature", c + "℃");
                } catch (NumberFormatException ignored) {
                    batteryInfo.put("temperature", raw);
                }
            } else if (t.startsWith("technology:")) {
                batteryInfo.put("technology", t.substring("technology:".length()).trim());
            } else if (t.startsWith("healthState:")) {
                batteryInfo.put("health", t.substring("healthState:".length()).trim());
            }
        }
        return batteryInfo;
    }

    public boolean installHarmonyApp(String deviceId, String hapPath) {
        return installHarmonyAppDetailed(deviceId, hapPath) == null;
    }

    /** @return null 表示成功，否则为错误信息 */
    public String installHarmonyAppDetailed(String deviceId, String hapPath) {
        if (hapPath == null || hapPath.isBlank()) {
            return "hap 路径为空";
        }
        if (!Files.isRegularFile(Path.of(hapPath))) {
            return "hap 文件不存在: " + hapPath;
        }
        if (hmdriverBridge != null && hmdriverBridge.available()) {
            if (hmdriverBridge.install(deviceId, hapPath)) {
                return null;
            }
            log.warn("hmdriver install 失败，回退 hdc install");
        }
        if (hdcClient == null || !hdcClient.available()) {
            return "hdc 不可用，无法安装 hap";
        }
        HdcClient.ExecResult r = hdcClient.install(deviceId, hapPath);
        if (r.ok()) {
            return null;
        }
        return "hdc install 失败: " + r.combined();
    }

    public boolean uninstallHarmonyApp(String deviceId, String bundleName) {
        if (hdcClient == null || !hdcClient.available()) {
            return false;
        }
        return hdcClient.uninstall(deviceId, bundleName).ok();
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
     * 批量获取 Android 应用信息（优化版本）。
     * 用 {@code dumpsys package packages} 一次拉全量；勿用无参 {@code pm dump}（会报 no package specified）。
     */
    public List<AppInfo> getAndroidAppsInfoBatch(String deviceId, List<String> packageNames) {
        List<AppInfo> appInfos = new ArrayList<>();

        if (packageNames == null || packageNames.isEmpty()) {
            return appInfos;
        }

        log.info("批量获取 {} 个应用的信息", packageNames.size());

        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "package", "packages")
                    .redirectErrorStream(true)
                    .start();

            java.util.Map<String, AppInfo> appInfoMap = new java.util.HashMap<>();
            String currentPackage = null;
            AppInfo currentAppInfo = null;
            // dumpsys 行通常带缩进："  Package [com.xxx] (hash):"
            Pattern packageHeader = Pattern.compile("^\\s*Package\\s*\\[([^\\]]+)\\]");

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher pkgMatcher = packageHeader.matcher(line);
                    if (pkgMatcher.find()) {
                        if (currentPackage != null && currentAppInfo != null) {
                            appInfoMap.put(currentPackage, currentAppInfo);
                        }
                        currentPackage = pkgMatcher.group(1).trim();
                        currentAppInfo = new AppInfo();
                        currentAppInfo.setPackageName(currentPackage);
                        continue;
                    }
                    if (currentAppInfo == null) {
                        continue;
                    }
                    String trimmed = line.trim();
                    if (trimmed.startsWith("applicationLabel=") && currentAppInfo.getName() == null) {
                        String label = trimmed.substring("applicationLabel=".length()).trim();
                        if (label.startsWith("'") && label.endsWith("'") && label.length() >= 2) {
                            label = label.substring(1, label.length() - 1);
                        }
                        if (!label.isEmpty()) {
                            currentAppInfo.setName(label);
                        }
                    } else if (trimmed.startsWith("versionName=") && currentAppInfo.getVersion() == null) {
                        String version = trimmed.substring("versionName=".length()).trim();
                        version = version.split("\\s")[0];
                        if (!version.isEmpty()) {
                            currentAppInfo.setVersion(version);
                        }
                    }
                }

                if (currentPackage != null && currentAppInfo != null) {
                    appInfoMap.put(currentPackage, currentAppInfo);
                }
            }

            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("dumpsys package packages 超时，回退逐个获取");
                return getAndroidAppsInfoSequential(deviceId, packageNames);
            }

            int withVersion = 0;
            for (String packageName : packageNames) {
                AppInfo appInfo = appInfoMap.get(packageName);
                if (appInfo == null) {
                    appInfo = new AppInfo();
                    appInfo.setPackageName(packageName);
                    appInfo.setName(packageName);
                    appInfo.setVersion("");
                } else {
                    if (appInfo.getName() == null || appInfo.getName().isEmpty()) {
                        appInfo.setName(packageName);
                    }
                    if (appInfo.getVersion() == null) {
                        appInfo.setVersion("");
                    } else {
                        withVersion++;
                    }
                }
                appInfos.add(appInfo);
            }

            log.info("批量获取完成：请求 {} 个，dumpsys 命中 {} 个，解析到 versionName {} 个",
                    packageNames.size(), appInfoMap.size(), withVersion);
            // 几乎全没解析到版本时，命令/机型格式可能不对，回退逐个 dumpsys package <pkg>
            if (withVersion == 0 && !packageNames.isEmpty()) {
                log.warn("批量未解析到任何 versionName，回退逐个获取（较慢）");
                return getAndroidAppsInfoSequential(deviceId, packageNames);
            }

        } catch (IOException | InterruptedException e) {
            log.warn("批量获取应用信息失败，回退到逐个获取: {}", e.getMessage());
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
        
        // 设置默认值（未知版本不填假的 1.0.0，避免误导）
        if (appInfo.getName() == null || appInfo.getName().isEmpty()) {
            appInfo.setName(packageName);
        }
        if (appInfo.getVersion() == null) {
            appInfo.setVersion("");
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
     * 获取 iOS 设备已安装的应用 Bundle ID 列表。
     * 实际解析见 {@link #getIOSInstalledAppsWithInfo(String)}（兼容 ideviceinstaller 1.2 {@code list}）。
     */
    public List<String> getIOSInstalledApps(String deviceId) {
        return getIOSInstalledAppsWithInfo(deviceId).stream()
                .map(AppInfo::getPackageName)
                .filter(p -> p != null && !p.isBlank())
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 获取单个 iOS 应用信息（从完整列表中匹配）。
     */
    public AppInfo getIOSAppInfo(String deviceId, String bundleId) {
        AppInfo appInfo = new AppInfo();
        appInfo.setPackageName(bundleId);
        if (bundleId == null || bundleId.isBlank()) {
            return appInfo;
        }
        for (AppInfo a : getIOSInstalledAppsWithInfo(deviceId)) {
            if (bundleId.equals(a.getPackageName())) {
                return a;
            }
        }
        appInfo.setName(bundleId);
        appInfo.setVersion("");
        return appInfo;
    }

    private String getIOSAppVersion(String deviceId, String bundleId) {
        AppInfo info = getIOSAppInfo(deviceId, bundleId);
        String v = info != null ? info.getVersion() : null;
        return (v == null || v.isBlank()) ? null : v;
    }

    /**
     * 安装 APK 到 Android 设备
     */
    public boolean installAndroidApp(String deviceId, String apkPath) {
        return installAndroidAppDetailed(deviceId, apkPath) == null;
    }

    /**
     * 安装 APK，失败时返回 adb 的原始输出。
     * <p>
     * 只回一个 boolean 的话，{@code INSTALL_FAILED_UPDATE_INCOMPATIBLE}（签名不一致）、
     * {@code INSTALL_PARSE_FAILED_NOT_APK}（下载到的不是安装包）、存储不足这些原因全被吞掉，
     * 上层只能报一句「安装失败」——人和归因都只能并列猜五种可能。实测踩过一次。
     *
     * @return 成功返回 null；失败返回 adb 输出（已裁剪）
     */
    public String installAndroidAppDetailed(String deviceId, String apkPath) {
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
                
                // 必须有超时：设备上弹出安装确认框而没人点时，adb install 会一直等下去，
                // 执行线程和设备锁跟着挂住，整条任务永远不结束。无人值守跑必然踩。
                if (!process.waitFor(installTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    log.error("APK 安装超时({}s): {}", installTimeoutSeconds, apkPath);
                    return "安装超时 " + installTimeoutSeconds + " 秒。设备上可能弹出了安装确认框——"
                            + "请在手机「开发者选项」里打开「USB 安装 / 通过 USB 调试安装应用」，"
                            + "打开后每台机只需设置这一次。";
                }
                int exitCode = process.exitValue();
                
                if (exitCode == 0 || output.toString().contains("Success")) {
                    log.info("APK 安装成功: {}", apkPath);
                    return null;
                }
                String reason = output.toString().trim();
                log.error("APK 安装失败: {}", reason);
                return reason.isEmpty() ? ("adb install 退出码 " + exitCode) : clipAdbOutput(reason);
            }
            
        } catch (IOException | InterruptedException e) {
            log.error("安装 APK 失败", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "安装 APK 异常: " + e.getMessage();
        }
    }

    /**
     * 卸载 Android 应用。未安装也视为成功（便于「先卸再装」）。
     */
    /**
     * 设备是否允许 adb 直接安装（不弹确认框）。
     * <p>
     * 小米/vivo/oppo 这类 ROM 默认要求手动确认每一次 USB 安装，无人值守跑必然卡住。
     * 这是每台机<b>一次性</b>的开发者选项，不是每次安装都要点——但不主动检查的话，
     * 人只会看到任务卡住五分钟然后超时，根本想不到是这个开关。
     *
     * @return null 表示看起来没问题；非 null 是给人看的提示
     */
    public String checkAdbInstallReady(String deviceId) {
        // 各家 ROM 的键名不统一，逐个探；任何一个明确为关闭状态就提示
        for (String[] probe : new String[][]{
                {"secure", "install_non_market_apps"},
                {"global", "install_non_market_apps"},
                {"secure", "adb_install_need_confirm"},
                {"global", "adb_install_need_confirm"}}) {
            String v = readSetting(deviceId, probe[0], probe[1]);
            if (v == null) {
                continue;
            }
            boolean needConfirm = probe[1].contains("need_confirm") ? "1".equals(v) : "0".equals(v);
            if (needConfirm) {
                return "设备 " + deviceId + " 需要手动确认 USB 安装（" + probe[1] + "=" + v + "）。"
                        + "请在手机「开发者选项」里打开「USB 安装 / 通过 USB 调试安装应用」，"
                        + "每台机只需设置这一次。";
            }
        }
        return null;
    }

    /**
     * 安装失败后，按 ROM 给一句能直接照着做的提示。
     * <p>
     * 只在<b>失败时</b>调用：不同于 {@link #checkAdbInstallReady}，这里给的是「这类机型常见原因」，
     * 装成功了就不该拿它去打扰人。
     */
    public String installFailureHint(String deviceId, String failure) {
        String f = failure == null ? "" : failure;
        if (f.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
            return "签名与已装版本不一致，请先卸载再装（流程里可勾「安装前卸载」）。";
        }
        if (f.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            return "版本号低于机上已装的版本，请先卸载再装。";
        }
        if (f.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
            return "设备存储不足。";
        }
        if (isMiui(deviceId)) {
            // MIUI/HyperOS 这道确认是安全中心拦下来的，不落在 install_non_market_apps
            // 这类 settings 键上——所以 checkAdbInstallReady 探不到，只能按机型提示。
            // 弹窗里「拒绝」是默认按钮且带倒计时，没人点就自动拒绝，表现就是安装莫名失败。
            return "小米/红米（MIUI/HyperOS）会在 adb 安装时弹出「USB安装提示」，"
                    + "而且默认按钮是「拒绝」并带倒计时——没人点就自动拒绝。"
                    + "请在该机「开发者选项」里打开「USB安装」（需登录小米账号），"
                    + "并关闭「安装前进行安全检查」之类的安装监控；每台机只需设置这一次。"
                    + "华为/荣耀没有这一层，所以同一条流程在那边不会卡。";
        }
        return null;
    }

    /** 只看厂商标识，不做版本比较：各代 MIUI/HyperOS 的属性名不统一，认准是小米即可 */
    private boolean isMiui(String deviceId) {
        for (String key : new String[]{"ro.miui.ui.version.name", "ro.mi.os.version.name"}) {
            if (readProp(deviceId, key) != null) {
                return true;
            }
        }
        String brand = readProp(deviceId, "ro.product.manufacturer");
        if (brand == null) {
            brand = readProp(deviceId, "ro.product.brand");
        }
        if (brand == null) {
            return false;
        }
        String b = brand.trim().toLowerCase(java.util.Locale.ROOT);
        return b.contains("xiaomi") || b.contains("redmi") || b.contains("poco");
    }

    private String readProp(String deviceId, String key) {
        return runShellLine(deviceId, "getprop", key);
    }

    private String readSetting(String deviceId, String namespace, String key) {
        return runShellLine(deviceId, "settings", "get", namespace, key);
    }

    /** 取 adb shell 一行输出；探测类调用失败一律返回 null，不该影响安装本身 */
    private String runShellLine(String deviceId, String... args) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(
                    java.util.List.of("adb", "-s", deviceId, "shell"));
            cmd.addAll(java.util.Arrays.asList(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                out = r.readLine();
            }
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (out == null) {
                return null;
            }
            out = out.trim();
            return out.isEmpty() || "null".equalsIgnoreCase(out) ? null : out;
        } catch (Exception e) {
            // 探测失败不该影响安装：这只是一层提示，真装不上还有超时兜底
            return null;
        }
    }

    /** adb 有时会把整段 help 一起吐出来，报错文案只留有用的部分 */
    private static String clipAdbOutput(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

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

    /**
     * 通过 uiautomator dump 按可见文案/content-desc 精确匹配并点击（渠道商店「安装」等）。
     * @return 命中并点击成功的文案；未找到或点击失败返回 null
     */
    public String clickAndroidByExactTexts(String deviceId, List<String> texts) {
        if (deviceId == null || texts == null || texts.isEmpty()) {
            return null;
        }
        try {
            String xml = dumpAndroidUiHierarchy(deviceId);
            if (xml == null || xml.isBlank()) {
                return null;
            }
            for (String text : texts) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                int[] center = findNodeCenterByAttr(xml, "text", text.trim());
                if (center == null) {
                    center = findNodeCenterByAttr(xml, "content-desc", text.trim());
                }
                if (center == null) {
                    continue;
                }
                if (adbTap(deviceId, center[0], center[1])) {
                    log.info("按文案点击成功: deviceId={}, text={}, xy=({},{})",
                            deviceId, text, center[0], center[1]);
                    return text.trim();
                }
                log.warn("找到文案但 tap 失败: text={}, xy=({},{})", text, center[0], center[1]);
            }
        } catch (Exception e) {
            log.warn("按文案点击失败: {}", e.getMessage());
        }
        return null;
    }

    private String dumpAndroidUiHierarchy(String deviceId) {
        String remote = "/sdcard/ms_store_uidump.xml";
        try {
            Process dump = new ProcessBuilder("adb", "-s", deviceId, "shell", "uiautomator", "dump", remote)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(dump.getInputStream()))) {
                while (reader.readLine() != null) { /* drain */ }
            }
            dump.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);

            Path local = Files.createTempFile("ms_store_uidump_", ".xml");
            try {
                Process pull = new ProcessBuilder("adb", "-s", deviceId, "pull", remote, local.toString())
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(pull.getInputStream()))) {
                    while (reader.readLine() != null) { /* drain */ }
                }
                pull.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
                if (!Files.isRegularFile(local) || Files.size(local) == 0) {
                    log.warn("商店安装 uiautomator dump 为空: deviceId={}", deviceId);
                    return null;
                }
                return Files.readString(local, java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                try {
                    Files.deleteIfExists(local);
                } catch (Exception ignored) {
                    // ignore
                }
            }
        } catch (Exception e) {
            log.warn("uiautomator dump 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 在 dump XML 中找 attr="exactValue" 节点的 bounds 中心 */
    private static int[] findNodeCenterByAttr(String xml, String attr, String exactValue) {
        if (xml == null || exactValue == null) {
            return null;
        }
        String marker = attr + "=\"" + exactValue + "\"";
        int idx = xml.indexOf(marker);
        if (idx < 0) {
            marker = attr + "='" + exactValue + "'";
            idx = xml.indexOf(marker);
        }
        if (idx < 0) {
            return null;
        }
        int nodeStart = xml.lastIndexOf('<', idx);
        int nodeEnd = xml.indexOf('>', idx);
        if (nodeStart < 0 || nodeEnd < 0 || nodeEnd <= nodeStart) {
            return null;
        }
        String node = xml.substring(nodeStart, nodeEnd + 1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")
                .matcher(node);
        if (!m.find()) {
            return null;
        }
        int l = Integer.parseInt(m.group(1));
        int t = Integer.parseInt(m.group(2));
        int r = Integer.parseInt(m.group(3));
        int b = Integer.parseInt(m.group(4));
        if (r <= l || b <= t) {
            return null;
        }
        return new int[]{(l + r) / 2, (t + b) / 2};
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
    /** 最近一次确保 WDA 失败的可读原因；成功时清空。 */
    public String getLastIosWdaError(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return iosWdaLastError.get(deviceId.trim());
    }

    public synchronized boolean ensureIosWdaRunning(String deviceId, int localPort) {
        if (deviceId == null || deviceId.isBlank() || localPort <= 0) {
            return false;
        }
        if (isWdaStatusOk(localPort)) {
            iosWdaLastError.remove(deviceId);
            log.info("WDA 已就绪: deviceId={}, port={}", deviceId, localPort);
            return true;
        }
        String wdaDir = System.getProperty("user.home")
                + "/.appium/node_modules/appium-xcuitest-driver/node_modules/appium-webdriveragent";
        Path project = Path.of(wdaDir, "WebDriverAgent.xcodeproj");
        if (!Files.isDirectory(project)) {
            String msg = "未找到 WDA 工程: " + project;
            log.warn(msg);
            iosWdaLastError.put(deviceId, msg);
            return false;
        }

        Path logFile = Path.of(System.getProperty("java.io.tmpdir"),
                "wda-" + deviceId.replaceAll("[^A-Za-z0-9_-]", "_") + ".log");

        // 1) xcodebuild test-without-building（复用已装 Runner）
        Process xcode = iosWdaXcodeProcs.get(deviceId);
        if (xcode == null || !xcode.isAlive()) {
            try {
                // 新一轮启动前清空旧日志，便于诊断本次失败
                try {
                    Files.writeString(logFile, "");
                } catch (Exception ignored) {
                }
                List<String> cmd = List.of(
                        "xcodebuild", "test-without-building",
                        "-project", project.toString(),
                        "-scheme", "WebDriverAgentRunner",
                        "-destination", "id=" + deviceId,
                        "-allowProvisioningUpdates",
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
                    String reason = diagnoseWdaLog(logFile);
                    iosWdaLastError.put(deviceId, reason);
                    log.warn("等待 WDA ServerURLHere 超时: deviceId={}, reason={}", deviceId, reason);
                }
            } catch (Exception e) {
                String msg = "启动 WDA xcodebuild 失败: " + e.getMessage();
                log.error(msg);
                iosWdaLastError.put(deviceId, msg);
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
                String msg = "启动 iproxy 失败: " + e.getMessage();
                log.error(msg);
                iosWdaLastError.put(deviceId, msg);
                return false;
            }
        }

        for (int i = 0; i < 20; i++) {
            if (isWdaStatusOk(localPort)) {
                iosWdaLastError.remove(deviceId);
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
        String reason = diagnoseWdaLog(logFile);
        iosWdaLastError.put(deviceId, reason);
        log.warn("WDA status 仍不可达: deviceId={}, port={}, reason={}", deviceId, localPort, reason);
        return false;
    }

    /** 从 xcodebuild 日志提炼可读失败原因（描述文件过期等）。 */
    private static String diagnoseWdaLog(Path logFile) {
        String text = "";
        try {
            if (logFile != null && Files.exists(logFile)) {
                text = Files.readString(logFile);
            }
        } catch (Exception ignored) {
        }
        if (text.contains("0xe8008011") || text.toLowerCase(Locale.ROOT).contains("provisioning profile has expired")) {
            return "WDA 描述文件已过期(0xe8008011)：请用 Xcode 打开 WebDriverAgent，选中真机重新签名并安装 WebDriverAgentRunner（Team=37ZX3X6QJY / Bundle=com.travYang.wda.Runner）";
        }
        if (text.contains("Developer App Certificate is not trusted")
                || text.contains("VPN & Device Management")
                || text.contains("has not been explicitly trusted")) {
            return "手机未信任开发者证书：请到 iPhone「设置 → 通用 → VPN与设备管理」里信任 Apple Development（trav_yang），再点刷新截图";
        }
        if (text.contains("Timed out while enabling automation mode")) {
            return "启用自动化模式超时：请解锁手机，确认「设置 → 隐私与安全性 → 开发者模式」已开启，并允许启用 UI Automation";
        }
        if (text.contains("无法安装") || text.contains("Failed to install")) {
            return "WDA 无法安装到真机：请在 Xcode 重新签名 WebDriverAgentRunner，并在手机上信任开发者证书";
        }
        if (text.contains("Timed out waiting for") || text.contains("not paired")) {
            return "WDA 启动失败：请确认手机已解锁、已信任本机，且 USB 连接正常";
        }
        if (text.isBlank()) {
            return "WDA 未就绪：xcodebuild 未拉起（日志为空）";
        }
        return "WDA 未就绪：请查看日志 " + logFile;
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
     * 获取 iOS 已安装应用列表（含名称、版本、bundleId）。
     * <p>
     * ideviceinstaller ≥1.2 已去掉 {@code -l}，改为子命令 {@code list}；旧版仍回退 {@code -l}。
     * 输出一般为 CSV：CFBundleIdentifier, CFBundleShortVersionString/CFBundleVersion, CFBundleDisplayName
     */
    public List<AppInfo> getIOSInstalledAppsWithInfo(String deviceId) {
        List<AppInfo> result = new ArrayList<>();
        if (deviceId == null || deviceId.isBlank()) {
            return result;
        }
        String output = runIdeviceInstallerList(deviceId);
        if (output == null || output.isBlank()) {
            return result;
        }
        if (looksLikeIdeviceInstallerUsage(output)) {
            log.warn("ideviceinstaller 返回的是帮助/用法文本而非应用列表，设备可能未连接或命令不兼容: {}", deviceId);
            return result;
        }
        boolean isCSVFormat = false;
        boolean headerSeen = false;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.contains("Total:")) continue;
            if (line.startsWith("ERROR") || line.toLowerCase(Locale.ROOT).contains("no device found")) {
                log.warn("ideviceinstaller 列表错误: {}", line);
                continue;
            }
            if (!headerSeen) {
                headerSeen = true;
                if (line.contains("CFBundleIdentifier") || line.contains("CFBundleDisplayName")
                        || line.contains("CFBundleVersion") || line.contains("CFBundleShortVersionString")) {
                    isCSVFormat = true;
                    continue;
                }
            }
            if (isCSVFormat || line.contains(",")) {
                List<String> parts = splitCsvRespectingQuotes(line);
                if (parts.isEmpty()) continue;
                String bundleId = unquote(parts.get(0));
                if (bundleId.isEmpty() || !bundleId.contains(".")) continue;
                // 过滤误把 Usage 行当应用
                if (bundleId.startsWith("-") || bundleId.contains(" ")) continue;
                AppInfo info = new AppInfo();
                info.setPackageName(bundleId);
                if (parts.size() >= 2) {
                    info.setVersion(unquote(parts.get(1)));
                } else {
                    info.setVersion("");
                }
                if (parts.size() >= 3) {
                    String name = unquote(parts.get(2));
                    info.setName(name.isEmpty() ? bundleId : name);
                } else {
                    info.setName(bundleId);
                }
                result.add(info);
            } else if (line.contains(" - ")) {
                String[] parts = line.split(" - ");
                if (parts.length >= 2) {
                    String bundleId = parts[parts.length - 1].trim();
                    if (bundleId.isEmpty() || "Total:".equals(bundleId) || !bundleId.contains(".")) continue;
                    String namePart = parts[0].trim();
                    AppInfo info = new AppInfo();
                    info.setPackageName(bundleId);
                    info.setName(namePart);
                    info.setVersion("");
                    if (namePart.contains("(") && namePart.contains(")")) {
                        int s = namePart.indexOf('('), e = namePart.indexOf(')');
                        if (e > s) {
                            info.setName(namePart.substring(0, s).trim());
                            info.setVersion(namePart.substring(s + 1, e).trim());
                        }
                    }
                    result.add(info);
                }
            }
        }
        log.info("iOS 应用列表: deviceId={}, count={}", deviceId, result.size());
        return result;
    }

    /** 先试新语法 list --user，再回退旧 -l */
    private String runIdeviceInstallerList(String deviceId) {
        String out = runProcessCapture(List.of("ideviceinstaller", "-u", deviceId, "list", "--user"));
        if (out != null && !out.isBlank() && !looksLikeIdeviceInstallerUsage(out)
                && !out.toLowerCase(Locale.ROOT).contains("invalid option")) {
            return out;
        }
        // 兼容旧版 ideviceinstaller
        String legacy = runProcessCapture(List.of("ideviceinstaller", "-u", deviceId, "-l"));
        if (legacy != null && !looksLikeIdeviceInstallerUsage(legacy)) {
            return legacy;
        }
        // 返回较有信息的一份，便于日志
        return (out != null && !out.isBlank()) ? out : legacy;
    }

    private boolean looksLikeIdeviceInstallerUsage(String text) {
        if (text == null) return true;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("usage: ideviceinstaller")
                || t.contains("invalid option")
                || t.contains("manage apps on ios devices")
                || t.contains("commands:");
    }

    private String runProcessCapture(List<String> cmd) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("命令超时: {}", String.join(" ", cmd));
                return "";
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("执行失败 {}: {}", String.join(" ", cmd), e.getMessage());
            return "";
        }
    }

    private static String unquote(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.trim();
    }

    /** 简单 CSV 拆分，支持双引号字段内逗号 */
    private static List<String> splitCsvRespectingQuotes(String line) {
        List<String> parts = new ArrayList<>();
        if (line == null) return parts;
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                cur.append(c);
            } else if (c == ',' && !inQuotes) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }
}
