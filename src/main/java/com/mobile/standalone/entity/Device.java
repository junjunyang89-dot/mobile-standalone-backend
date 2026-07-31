package com.mobile.standalone.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Device {
    private String id;
    private String name;
    private Platform platform;
    private String version;
    private DeviceStatus status = DeviceStatus.DISCONNECTED;
    private LocalDateTime lastSeen;
    private String config;
    
    public enum Platform {
        ANDROID, IOS, HARMONYOS, POG_ANDROID, POG_IOS, POGPOG
    }

    /** ADB / Appium Android 技术栈（含鸿蒙、Pog-Android） */
    public static boolean usesAndroidAutomation(Platform p) {
        return p == Platform.ANDROID || p == Platform.HARMONYOS || p == Platform.POG_ANDROID;
    }

    /** Appium / idevice iOS 技术栈（含 Pog-iOS） */
    public static boolean usesIosAutomation(Platform p) {
        return p == Platform.IOS || p == Platform.POG_IOS;
    }

    /** 与标准 Android 应用生态一致（不含鸿蒙），用于电竞等 Android 专属自动化 */
    public static boolean isAndroidOrPogAndroid(Platform p) {
        return p == Platform.ANDROID || p == Platform.POG_ANDROID;
    }

    /**
     * 自动化「按平台拉设备列表」：Android 与 Pog-Android 共用设备池（库中 ANDROID 或 POG_ANDROID）；
     * iOS 与 Pog-iOS 共用（IOS 或 POG_IOS）；鸿蒙及其它按精确匹配。
     */
    public static boolean matchesAutomationDeviceListFilter(Platform devicePlatform, Platform requestedFilter) {
        if (devicePlatform == null || requestedFilter == null) {
            return false;
        }
        if (requestedFilter == Platform.ANDROID || requestedFilter == Platform.POG_ANDROID) {
            return devicePlatform == Platform.ANDROID || devicePlatform == Platform.POG_ANDROID;
        }
        if (requestedFilter == Platform.IOS || requestedFilter == Platform.POG_IOS) {
            return devicePlatform == Platform.IOS || devicePlatform == Platform.POG_IOS;
        }
        return devicePlatform == requestedFilter;
    }

    public enum DeviceStatus {
        CONNECTED, DISCONNECTED
    }
}
