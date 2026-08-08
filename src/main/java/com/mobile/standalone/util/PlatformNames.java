package com.mobile.standalone.util;

import java.util.List;
import java.util.Locale;

/**
 * 平台名归一。
 * <p>
 * {@code POG_ANDROID} / {@code POG_IOS} 把「操作系统」和「产品线」混在了一个维度里：
 * POG 指的是海外 PogPog 这个 APP，本该由 {@code appId} 表达。混进平台之后，
 * 元素查找键 {@code (appId, platform, nameEn)} 里 appId 已经能区分国内/海外了，
 * 平台再分一次纯属冗余——同一个元素要按平台各存一份，元素库直接翻倍。
 * <p>
 * 设备扫描从来只写 ANDROID / IOS / HARMONYOS，POG 值是回归编排按 appId 算出来塞进任务的。
 * 这里统一归一到操作系统维度；旧值仍可解析，便于存量数据平滑过渡。
 */
public final class PlatformNames {

    public static final String ANDROID = "ANDROID";
    public static final String IOS = "IOS";
    public static final String HARMONYOS = "HARMONYOS";

    /** 历史遗留的产品线平台名，读到时归一，不再写入 */
    private static final List<String> LEGACY_ANDROID = List.of("POG_ANDROID", "POGPOG");
    private static final List<String> LEGACY_IOS = List.of("POG_IOS");

    private PlatformNames() {
    }

    /** 归一到操作系统维度；未知值原样返回，避免把没见过的平台吞掉 */
    public static String normalize(String platform) {
        if (platform == null || platform.isBlank()) {
            return null;
        }
        String upper = platform.trim().toUpperCase(Locale.ROOT);
        if (LEGACY_ANDROID.contains(upper)) {
            return ANDROID;
        }
        if (LEGACY_IOS.contains(upper)) {
            return IOS;
        }
        return upper;
    }

    public static boolean isLegacy(String platform) {
        if (platform == null) {
            return false;
        }
        String upper = platform.trim().toUpperCase(Locale.ROOT);
        return LEGACY_ANDROID.contains(upper) || LEGACY_IOS.contains(upper);
    }

    /**
     * 归一后的平台对应的历史别名，用于查不到时回退。
     * <p>
     * 存量元素还存在 {@code POG_ANDROID} 下，代码改成按 {@code ANDROID} 查之后就找不到了；
     * 数据迁移前后都要能跑，所以主查询落空时再按别名找一次。
     */
    public static List<String> legacyAliasesOf(String normalizedPlatform) {
        if (normalizedPlatform == null) {
            return List.of();
        }
        return switch (normalizedPlatform.trim().toUpperCase(Locale.ROOT)) {
            case ANDROID -> LEGACY_ANDROID;
            case IOS -> LEGACY_IOS;
            default -> List.of();
        };
    }

    public static boolean isIos(String platform) {
        return IOS.equals(normalize(platform));
    }

    /** Android 应用生态（不含鸿蒙） */
    public static boolean isAndroid(String platform) {
        return ANDROID.equals(normalize(platform));
    }
}
