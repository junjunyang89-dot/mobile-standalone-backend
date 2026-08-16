package com.mobile.standalone.service;

import com.mobile.standalone.entity.App;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.AppMapper;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.config.NodeIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@DependsOn("workerNodeService")
@Slf4j
public class DeviceService {
    
    private final DeviceMapper deviceMapper;
    private final AppMapper appMapper;
    private final DeviceManager deviceManager;
    private final PlatformTransactionManager transactionManager;
    private final NodeIdentity nodeIdentity;
    
    /**
     * 返回所有设备，并过滤掉明显无效的记录（如 adb 错误被误入库的）
     */
    public List<Device> getAllDevices() {
        return deviceMapper.findAll().stream()
                .filter(this::isValidDeviceRecord)
                .filter(d -> !nodeIdentity.executesLocally() || belongsToThisNode(d))
                .toList();
    }

    /**
     * 过滤无效设备记录（id/name/version 含 adb 错误信息等）
     */
    private boolean isValidDeviceRecord(Device d) {
        if (d == null || d.getId() == null) return false;
        String id = d.getId().trim();
        if (id.length() < 2) return false;
        String idLower = id.toLowerCase();
        if (idLower.contains("adb") || idLower.contains("not found") || idLower.contains("'list'")) return false;
        if (d.getName() != null) {
            String nameLower = d.getName().toLowerCase();
            if (nameLower.contains("adb:") || nameLower.contains("not found")) return false;
        }
        if (d.getVersion() != null) {
            String verLower = d.getVersion().toLowerCase();
            if (verLower.contains("adb") || verLower.contains("not found") || verLower.contains("device '")) return false;
        }
        return true;
    }
    
    public Device getDeviceById(String id) {
        return deviceMapper.findById(id);
    }
    
    @Transactional
    /** 保存设备上人可编辑的那部分（目前只有环境） */
    public void updateDevice(Device device) {
        deviceMapper.update(device);
    }

    public Device connectDevice(String deviceId) {
        Device device = deviceMapper.findById(deviceId);
        if (device != null) {
            device.setStatus(Device.DeviceStatus.CONNECTED);
            device.setLastSeen(LocalDateTime.now());
            deviceMapper.update(device);
            return device;
        }
        return null;
    }
    
    @Transactional
    public boolean disconnectDevice(String deviceId) {
        Device device = deviceMapper.findById(deviceId);
        if (device != null) {
            device.setStatus(Device.DeviceStatus.DISCONNECTED);
            deviceMapper.update(device);
            return true;
        }
        return false;
    }
    
    /**
     * 首页用：已连接设备统计及列表（总数、各平台数量、设备列表）。
     * 仅包含 status=CONNECTED 且通过 isValidDeviceRecord 的设备。
     */
    public ConnectedDeviceSummary getConnectedDeviceSummary() {
        List<Device> all = getAllDevices();
        List<Device> connected = all.stream()
                .filter(d -> d.getStatus() == Device.DeviceStatus.CONNECTED)
                .toList();
        int android = (int) connected.stream().filter(d -> Device.isAndroidOrPogAndroid(d.getPlatform())).count();
        int ios = (int) connected.stream().filter(d -> Device.usesIosAutomation(d.getPlatform())).count();
        int harmonyos = (int) connected.stream().filter(d -> d.getPlatform() == Device.Platform.HARMONYOS).count();
        return new ConnectedDeviceSummary(
                connected.size(),
                android,
                ios,
                harmonyos,
                new ArrayList<>(connected)
        );
    }

    /**
     * 首页已连接设备统计结果
     */
    public static class ConnectedDeviceSummary {
        private final int totalConnected;
        private final int androidCount;
        private final int iosCount;
        private final int harmonyosCount;
        private final List<Device> devices;

        public ConnectedDeviceSummary(int totalConnected, int androidCount, int iosCount, int harmonyosCount, List<Device> devices) {
            this.totalConnected = totalConnected;
            this.androidCount = androidCount;
            this.iosCount = iosCount;
            this.harmonyosCount = harmonyosCount;
            this.devices = devices != null ? devices : List.of();
        }

        public int getTotalConnected() { return totalConnected; }
        public int getAndroidCount() { return androidCount; }
        public int getIosCount() { return iosCount; }
        public int getHarmonyosCount() { return harmonyosCount; }
        public List<Device> getDevices() { return devices; }
    }

    /** 详情/应用短时缓存，避免连点反复打 adb/hdc */
    private static final long DETAIL_CACHE_TTL_MS = 60_000L;
    private static final long APPS_CACHE_TTL_MS = 60_000L;
    private final ConcurrentHashMap<String, TimedCache<DeviceDetail>> detailCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TimedCache<List<InstalledAppItem>>> appsCache = new ConcurrentHashMap<>();

    private static final class TimedCache<T> {
        final T value;
        final long expireAtMs;

        TimedCache(T value, long ttlMs) {
            this.value = value;
            this.expireAtMs = System.currentTimeMillis() + ttlMs;
        }

        boolean alive() {
            return System.currentTimeMillis() < expireAtMs;
        }
    }

    /**
     * 获取设备详情。默认不含已安装应用、不含 getprop 全文（首屏更快）。
     */
    public DeviceDetail getDeviceDetail(String deviceId) {
        return getDeviceDetail(deviceId, false, false);
    }

    public DeviceDetail getDeviceDetail(String deviceId, boolean includeApps, boolean includeRaw) {
        Device device = deviceMapper.findById(deviceId);
        if (device == null || !isValidDeviceRecord(device)) {
            return null;
        }

        String cacheKey = deviceId + "|apps=" + includeApps + "|raw=" + includeRaw;
        TimedCache<DeviceDetail> cached = detailCache.get(cacheKey);
        if (cached != null && cached.alive()) {
            return cached.value;
        }

        Integer batteryLevel = null;
        List<InstalledAppItem> installedApplications = List.of();
        Map<String, String> iosDetail = new LinkedHashMap<>();
        String iosDetailRaw = "";
        Map<String, Object> androidDetail = new LinkedHashMap<>();
        String androidDetailRaw = "";

        try {
            if (Device.usesAndroidAutomation(device.getPlatform())) {
                batteryLevel = deviceManager.getAndroidBatteryLevel(deviceId);
            } else if (Device.usesIosAutomation(device.getPlatform())) {
                batteryLevel = deviceManager.getIOSBatteryLevel(deviceId);
            } else if (Device.usesHdcAutomation(device.getPlatform())) {
                batteryLevel = deviceManager.getHarmonyBatteryLevel(deviceId);
            }
        } catch (Exception e) {
            log.warn("获取电量失败: deviceId={}", deviceId, e);
        }

        if (includeApps) {
            try {
                List<InstalledAppItem> apps = getInstalledApps(deviceId);
                installedApplications = apps != null ? apps : List.of();
            } catch (Exception e) {
                log.warn("获取安装应用列表失败: deviceId={}", deviceId, e);
            }
        }

        try {
            if (Device.usesIosAutomation(device.getPlatform())) {
                DeviceManager.IOSDetailResult iosResult = deviceManager.getIOSDeviceDetailWithRaw(deviceId);
                iosDetail = iosResult.parsed != null ? iosResult.parsed : new LinkedHashMap<>();
                if (includeRaw) {
                    iosDetailRaw = iosResult.rawOutput != null ? iosResult.rawOutput : "";
                }
            } else if (Device.usesAndroidAutomation(device.getPlatform())) {
                androidDetail = deviceManager.getAndroidDeviceDetail(deviceId);
                if (androidDetail == null) androidDetail = new LinkedHashMap<>();
                if (includeRaw) {
                    androidDetailRaw = buildAndroidDetailRaw(deviceId);
                    if (androidDetailRaw == null || androidDetailRaw.isEmpty()) {
                        androidDetailRaw = "[未获取到 adb 输出] 请确认：1) 设备已通过 USB 连接并开启 USB 调试  2) 本机已安装 adb";
                    }
                }
            } else if (Device.usesHdcAutomation(device.getPlatform())) {
                androidDetail = buildHarmonyDeviceDetail(deviceId);
                if (includeRaw) {
                    androidDetailRaw = buildHarmonyDetailRaw(deviceId);
                }
            }
        } catch (Exception e) {
            log.warn("获取设备详细信息失败: deviceId={}, platform={}", deviceId, device.getPlatform(), e);
            String err = "获取详细数据异常: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            if (Device.usesIosAutomation(device.getPlatform())) {
                iosDetailRaw = err;
            } else if (includeRaw) {
                androidDetailRaw = err;
            }
        }

        DeviceDetail detail = new DeviceDetail(
                device, batteryLevel, installedApplications, iosDetail, iosDetailRaw, androidDetail, androidDetailRaw);
        detailCache.put(cacheKey, new TimedCache<>(detail, DETAIL_CACHE_TTL_MS));
        return detail;
    }

    /**
     * 已安装应用列表（独立接口，带短时缓存）。设备不存在返回 null。
     */
    public List<InstalledAppItem> getInstalledApps(String deviceId) {
        Device device = deviceMapper.findById(deviceId);
        if (device == null || !isValidDeviceRecord(device)) {
            return null;
        }
        TimedCache<List<InstalledAppItem>> cached = appsCache.get(deviceId);
        if (cached != null && cached.alive()) {
            return cached.value;
        }
        List<InstalledAppItem> apps;
        try {
            apps = buildInstalledApps(deviceId, device.getPlatform());
        } catch (Exception e) {
            log.warn("获取安装应用列表失败: deviceId={}", deviceId, e);
            apps = List.of();
        }
        if (apps == null) {
            apps = List.of();
        }
        appsCache.put(deviceId, new TimedCache<>(List.copyOf(apps), APPS_CACHE_TTL_MS));
        return apps;
    }

    /** Android 设备完整原始信息（getprop 全量 + 常用 dumpsys 摘要），供前端完整展示 */
    private String buildAndroidDetailRaw(String deviceId) {
        StringBuilder sb = new StringBuilder();
        String getprop = deviceManager.runAdbShell(deviceId, "getprop");
        if (getprop != null && !getprop.isEmpty()) {
            sb.append("=== getprop ===\n").append(getprop).append("\n");
        }
        String battery = deviceManager.runAdbShell(deviceId, "dumpsys", "battery");
        if (battery != null && !battery.isEmpty()) {
            sb.append("=== dumpsys battery ===\n").append(battery).append("\n");
        }
        return sb.length() > 0 ? sb.toString() : "";
    }

    private String safeHdcParam(String deviceId, String key) {
        try {
            String v = deviceManager.getHarmonyParam(deviceId, key);
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String buildHarmonyDetailRaw(String deviceId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== hdc connector ===\n");
        for (String key : List.of(
                "const.product.name",
                "const.product.model",
                "const.product.software.version",
                "const.ohos.apiversion")) {
            String v = safeHdcParam(deviceId, key);
            sb.append(key).append("=").append(v).append("\n");
        }
        String uname = deviceManager.runHdcShell(deviceId, "uname -a");
        if (uname != null && !uname.isBlank()) {
            sb.append("=== uname ===\n").append(uname).append("\n");
        }
        String battery = deviceManager.runHdcShell(deviceId, "hidumper -s 3302 -a -i");
        if (battery != null && !battery.isBlank()) {
            sb.append("=== hidumper BatteryService ===\n").append(battery).append("\n");
        }
        return sb.toString();
    }

    private List<InstalledAppItem> buildInstalledApps(String deviceId, Device.Platform platform) {
        if (Device.usesAndroidAutomation(platform)) {
            List<String> packages = deviceManager.getAndroidInstalledPackages(deviceId);
            if (packages.isEmpty()) return List.of();
            List<String> limited = packages.size() > 200 ? packages.subList(0, 200) : packages;
            List<DeviceManager.AppInfo> infos = deviceManager.getAndroidAppsInfoBatch(deviceId, limited);
            return infos.stream()
                    .map(a -> new InstalledAppItem(a.getName(), a.getPackageName(), a.getVersion()))
                    .collect(Collectors.toList());
        }
        if (Device.usesIosAutomation(platform)) {
            List<DeviceManager.AppInfo> infos = deviceManager.getIOSInstalledAppsWithInfo(deviceId);
            return infos.stream()
                    .map(a -> new InstalledAppItem(a.getName(), a.getPackageName(), a.getVersion()))
                    .collect(Collectors.toList());
        }
        if (Device.usesHdcAutomation(platform)) {
            // bm dump -a -l 拿名称，并行 bm dump -n 补版本
            List<DeviceManager.AppInfo> infos = deviceManager.listHarmonyAppsViaHdc(deviceId);
            if (infos.isEmpty()) return List.of();
            return infos.stream()
                    .map(a -> new InstalledAppItem(
                            a.getName() != null ? a.getName() : a.getPackageName(),
                            a.getPackageName(),
                            a.getVersion() != null ? a.getVersion() : ""))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    /** 鸿蒙详情：拼成前端 androidDetail 能认的分层结构 */
    private Map<String, Object> buildHarmonyDeviceDetail(String deviceId) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, String> systemInfo = new LinkedHashMap<>();
        systemInfo.put("connector", "hdc");
        systemInfo.put("productName", safeHdcParam(deviceId, "const.product.name"));
        systemInfo.put("model", safeHdcParam(deviceId, "const.product.model"));
        systemInfo.put("softwareVersion", safeHdcParam(deviceId, "const.product.software.version"));
        systemInfo.put("apiVersion", safeHdcParam(deviceId, "const.ohos.apiversion"));
        systemInfo.put("serialNumber", deviceId);
        root.put("systemInfo", systemInfo);

        Map<String, String> hardwareInfo = new LinkedHashMap<>();
        String uname = deviceManager.runHdcShell(deviceId, "uname -a");
        if (uname != null && !uname.isBlank()) {
            hardwareInfo.put("uname", uname.trim());
        }
        if (!hardwareInfo.isEmpty()) {
            root.put("hardwareInfo", hardwareInfo);
        }

        Map<String, String> batteryInfo = deviceManager.getHarmonyBatteryInfo(deviceId);
        if (batteryInfo != null && !batteryInfo.isEmpty()) {
            root.put("batteryInfo", batteryInfo);
        }
        return root;
    }

    /**
     * 设备详情 DTO：基础信息 + 电量 + 安装应用 + iOS 详细字段
     */
    @lombok.Data
    public static class DeviceDetail {
        private String id;
        private String name;
        private String platform;
        private String version;
        private String status;
        private java.time.LocalDateTime lastSeen;
        /** 电量百分比 0-100，无法获取时为 null */
        private Integer batteryLevel;
        /** 已安装应用列表（名称、包名/bundleId、版本） */
        private List<InstalledAppItem> installedApplications;
        /** 仅 iOS：ideviceinfo 解析后的全部键值（DeviceName, ProductVersion, SerialNumber, UniqueDeviceID 等） */
        private Map<String, String> iosDetail;
        /** 仅 iOS：ideviceinfo 完整原始输出，前端可直接在页面完整展示 */
        private String iosDetailRaw;
        /** 仅 Android/HarmonyOS：系统信息、硬件、电池、存储、网络、安全等（systemInfo, hardwareInfo, batteryInfo 等） */
        private Map<String, Object> androidDetail;
        /** 仅 Android/HarmonyOS：getprop + dumpsys battery 等完整原始输出，前端可直接完整展示 */
        private String androidDetailRaw;

        public DeviceDetail(Device device, Integer batteryLevel, List<InstalledAppItem> installedApplications,
                            Map<String, String> iosDetail, String iosDetailRaw,
                            Map<String, Object> androidDetail, String androidDetailRaw) {
            this.id = device.getId();
            this.name = device.getName();
            this.platform = device.getPlatform() != null ? device.getPlatform().name() : null;
            this.version = device.getVersion();
            this.status = device.getStatus() != null ? device.getStatus().name() : null;
            this.lastSeen = device.getLastSeen();
            this.batteryLevel = batteryLevel;
            this.installedApplications = installedApplications != null ? installedApplications : List.of();
            this.iosDetail = iosDetail != null ? iosDetail : new LinkedHashMap<>();
            this.iosDetailRaw = iosDetailRaw != null ? iosDetailRaw : "";
            this.androidDetail = androidDetail != null ? androidDetail : new LinkedHashMap<>();
            this.androidDetailRaw = androidDetailRaw != null ? androidDetailRaw : "";
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class InstalledAppItem {
        private String name;
        private String packageName;
        private String version;
    }

    /**
     * 获取设备已安装的应用列表
     */
    public List<App> getDeviceApps(String deviceId) {
        Device device = deviceMapper.findById(deviceId);
        if (device == null) {
            throw new RuntimeException("设备不存在: " + deviceId);
        }
        return appMapper.findByDeviceId(deviceId);
    }
    
    private volatile long lastDevicePersistMs = 0;

    /**
     * 扫描设备（Android / iOS / HarmonyOS）。
     * <p>
     * 硬件探测（adb / idevice）必须在事务外执行：原先整段 {@code @Transactional}
     * 会在数秒扫描期间独占 {@code devices} 行锁，导致带 device_id 外键的 {@code tasks} 插入
     * 出现 Lock wait timeout（线上回归「启动」步骤常见误报）。
     * DB 落库节流，避免高频 UPDATE devices 与回归步骤抢锁。
     */
    @Scheduled(fixedRateString = "${app.device-scan-interval:5000}")
    public void scanDevices() {
        if (!nodeIdentity.executesLocally()) {
            return;
        }
        try {
            List<Device> scanned = new ArrayList<>();
            scanned.addAll(deviceManager.scanAndroidDevices());
            scanned.addAll(deviceManager.scanIOSDevices());
            scanned.addAll(deviceManager.scanHarmonyOSDevices());

            long now = System.currentTimeMillis();
            // 至少间隔 15s 写库；仅有新设备时立即写入
            boolean hasNew = scanned.stream().anyMatch(d -> deviceMapper.findById(d.getId()) == null);
            if (!hasNew && now - lastDevicePersistMs < 15_000L) {
                return;
            }
            lastDevicePersistMs = now;

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setTimeout(8);
            tx.executeWithoutResult(status -> {
                updateDevicesInDatabase(scanned);
                markDisconnectedDevices();
            });
        } catch (Exception e) {
            log.error("设备扫描失败", e);
        }
    }
    
    /**
     * 更新数据库中的设备信息（仅短事务内调用）
     */
    private void updateDevicesInDatabase(List<Device> scannedDevices) {
        LocalDateTime now = LocalDateTime.now();
        for (Device scannedDevice : scannedDevices) {
            Device existingDevice = deviceMapper.findById(scannedDevice.getId());
            Device.DeviceStatus status = scannedDevice.getStatus() != null
                    ? scannedDevice.getStatus()
                    : Device.DeviceStatus.CONNECTED;

            if (existingDevice != null) {
                existingDevice.setName(scannedDevice.getName());
                existingDevice.setVersion(scannedDevice.getVersion());
                existingDevice.setPlatform(scannedDevice.getPlatform());
                existingDevice.setStatus(status);
                // 未授权/离线也更新 lastSeen，避免被误判成「彻底消失」
                existingDevice.setLastSeen(now);
                existingDevice.setWorkerId(nodeIdentity.id());
                deviceMapper.update(existingDevice);
            } else {
                scannedDevice.setStatus(status);
                scannedDevice.setLastSeen(now);
                scannedDevice.setWorkerId(nodeIdentity.id());
                deviceMapper.insert(scannedDevice);
                log.info("发现新设备: {} ({}) status={}", scannedDevice.getName(), scannedDevice.getId(), status);
            }
        }
    }
    
    /**
     * 标记未连接的设备为断开状态
     */
    private void markDisconnectedDevices() {
        List<Device> allDevices = deviceMapper.findAll();
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(45); // 与落库节流匹配，避免误标断开
        
        for (Device device : allDevices) {
            if (!belongsToThisNode(device)) {
                continue;
            }
            if (device.getStatus() == Device.DeviceStatus.CONNECTED) {
                if (device.getLastSeen() == null || device.getLastSeen().isBefore(threshold)) {
                    device.setStatus(Device.DeviceStatus.DISCONNECTED);
                    deviceMapper.update(device);
                    log.debug("设备已断开: {} ({})", device.getName(), device.getId());
                }
            }
        }
    }

    /** 只动本节点扫到的设备，避免执行节点 A 把 B 的手机标成断开 */
    private boolean belongsToThisNode(Device device) {
        String wid = device.getWorkerId();
        if (wid == null || wid.isBlank()) {
            return true;
        }
        return nodeIdentity.id().equals(wid);
    }
}
