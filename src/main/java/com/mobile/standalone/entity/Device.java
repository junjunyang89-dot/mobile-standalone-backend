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
    /** 哪台执行节点扫到的这台手机；控制面自己不扫设备 */
    private String workerId;

    /**
     * 这台是不是模拟器。
     * <p>
     * 单独存一列，而不是靠序列号前缀猜：本地 AVD 是 {@code emulator-5554} 没错，
     * 但雷电、夜神、云真机都是以 {@code 127.0.0.1:5555} 这种形式连进来的，前缀判不出来。
     * 扫描时读 getprop 定性，一次定死——同一个序列号不会一会儿是模拟器一会儿不是。
     * <p>
     * 存它是为了拦两件事：模拟器不能跑生产环境的流程（会去取生产账号变量），
     * 也不能跑性能测试（帧率、启动耗时在模拟器上没有参考价值）。
     */
    private Boolean emulator;

    /**
     * 写在 getter 上：Jackson 默认走 getEmulator()，只标字段时前端拿到的是 {@code emulator}，
     * 设备列表的「模拟器」标签就永远不亮。
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isEmulator")
    public Boolean getEmulator() {
        return emulator;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("isEmulator")
    public void setEmulator(Boolean emulator) {
        this.emulator = emulator;
    }

    /**
     * 列为空时不当真机拦掉，但 {@code emulator-5554} 这种序列号没有歧义，
     * 加列之前扫进来的 AVD 不能因此在设备池里显示成 0 台模拟器。
     */
    public static boolean isEmulator(Device d) {
        if (d == null) return false;
        if (Boolean.TRUE.equals(d.getEmulator())) return true;
        if (d.getEmulator() != null) return false;
        String id = d.getId() == null ? "" : d.getId().trim();
        return id.startsWith("emulator-");
    }

    /**
     * 上次派活的时间，设备池轮转用。
     * <p>
     * 轮转做成「谁闲得最久谁上」而不是转指针：设备会上线下线、会被人借走，
     * 指针那套一遇到设备增减就乱，而这个不用记状态，天然均衡。
     */
    private LocalDateTime lastUsedAt;

    /**
     * 人工停用：坏了、在维护、被某个专项独占。
     * <p>
     * 与「离线」分开：离线是客观事实，停用是人的决定。混成一个的话，
     * 一台在维护的设备只要还插着就会被继续派活，而拔掉它又会让人以为设备丢了。
     */
    private Boolean disabled;
    
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
