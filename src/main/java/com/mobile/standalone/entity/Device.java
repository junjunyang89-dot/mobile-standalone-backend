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
    /**
     * 这台设备跑哪个环境：PROD / TEST，空＝没指定。
     * <p>
     * 绑在设备上而不是每次执行时选：一台手机上装的就是某个环境的包，不会一会儿线上一会儿测试。
     * 四台机器同时跑（两台线上两台测试）时，不用每次去勾一遍——需要人每次重复配的东西，
     * 迟早会配错一次，而配错的现象是「登录失败」，指向的方向完全不对。
     */
    private String environment;
    
    public enum Platform {
        ANDROID, IOS, HARMONYOS,
        /**
         * @deprecated 产品线混进平台的历史值。设备扫描从不写入，回归编排也已改为只写操作系统。
         * 保留仅为让迁移前的存量数据仍能反序列化；跑完 migration_drop_pog_platform.sql 后可删。
         */
        @Deprecated
        POG_ANDROID,
        /**
         * @deprecated 产品线混进平台的历史值。设备扫描从不写入，回归编排也已改为只写操作系统。
         * 保留仅为让迁移前的存量数据仍能反序列化；跑完 migration_drop_pog_platform.sql 后可删。
         */
        @Deprecated
        POG_IOS,
        /**
         * @deprecated 产品线混进平台的历史值。设备扫描从不写入，回归编排也已改为只写操作系统。
         * 保留仅为让迁移前的存量数据仍能反序列化；跑完 migration_drop_pog_platform.sql 后可删。
         */
        @Deprecated
        POGPOG
    }

    /** ADB / Appium Android 技术栈（不含纯鸿蒙 NEXT；Pog-Android 仍走 Android） */
    public static boolean usesAndroidAutomation(Platform p) {
        return p == Platform.ANDROID || p == Platform.POG_ANDROID;
    }

    /** 纯鸿蒙 NEXT：hdc + hmdriver2，不用 adb */
    public static boolean usesHdcAutomation(Platform p) {
        return p == Platform.HARMONYOS;
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
