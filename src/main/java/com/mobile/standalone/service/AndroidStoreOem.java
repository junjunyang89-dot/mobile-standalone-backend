package com.mobile.standalone.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android 国内渠道商店策略：按厂商打开对应应用市场，并用该商店常见「安装」文案点击。
 */
public enum AndroidStoreOem {
    XIAOMI,
    HUAWEI,
    HONOR,
    OPPO,
    VIVO,
    SAMSUNG,
    PLAY,
    DEFAULT;

    public static AndroidStoreOem fromManufacturer(String manufacturer) {
        String m = manufacturer == null ? "" : manufacturer.trim().toLowerCase(Locale.ROOT);
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            return XIAOMI;
        }
        if (m.contains("huawei")) {
            return HUAWEI;
        }
        if (m.contains("honor")) {
            return HONOR;
        }
        if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) {
            return OPPO;
        }
        if (m.contains("vivo") || m.contains("iqoo")) {
            return VIVO;
        }
        if (m.contains("samsung")) {
            return SAMSUNG;
        }
        return DEFAULT;
    }

    /** 配置键：app.install.vendor-tap.xiaomi 等 */
    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 详情页「安装」类按钮文案（精确匹配 text / content-desc）。
     * 顺序：优先「安装」，再「下载/升级」等。
     */
    public List<String> installButtonTexts() {
        return switch (this) {
            case XIAOMI -> List.of("安装", "升级", "下载", "继续安装", "立即安装");
            case HUAWEI, HONOR -> List.of("安装", "升级", "下载", "继续", "打开");
            case OPPO -> List.of("安装", "升级", "下载", "继续安装");
            case VIVO -> List.of("安装", "升级", "下载", "继续安装", "立即下载");
            case SAMSUNG -> List.of("安装", "升级", "下载", "Install", "Update");
            case PLAY -> List.of("安装", "Install", "更新", "Update");
            case DEFAULT -> List.of("安装", "下载", "升级", "获取", "继续安装", "Install");
        };
    }

    /** 商店包名提示（日志用；打开仍靠 URI） */
    public String storePackageHint() {
        return switch (this) {
            case XIAOMI -> "com.xiaomi.market";
            case HUAWEI -> "com.huawei.appmarket";
            case HONOR -> "com.hihonor.appmarket";
            case OPPO -> "com.heytap.market";
            case VIVO -> "com.bbk.appstore";
            case SAMSUNG -> "com.sec.android.app.samsungapps";
            case PLAY -> "com.android.vending";
            case DEFAULT -> "market";
        };
    }

    public List<String> openUris(String packageName, String keyword) {
        String pkg = packageName != null ? packageName.trim() : "";
        String enc = encode(keyword);
        List<String> uris = new ArrayList<>();
        switch (this) {
            case PLAY -> {
                uris.add("market://details?id=" + pkg);
                uris.add("https://play.google.com/store/apps/details?id=" + pkg);
            }
            case HUAWEI, HONOR -> {
                uris.add("appmarket://details?id=" + pkg);
                uris.add("market://details?id=" + pkg);
                if (!enc.isBlank()) {
                    uris.add("https://appgallery.huawei.com/#/search/" + enc);
                }
            }
            case XIAOMI -> {
                uris.add("mimarket://details?id=" + pkg);
                uris.add("market://details?id=" + pkg);
            }
            case OPPO -> {
                uris.add("oppomarket://details?packagename=" + pkg);
                uris.add("market://details?id=" + pkg);
            }
            case VIVO -> {
                uris.add("vivomarket://details?id=" + pkg);
                uris.add("market://details?id=" + pkg);
            }
            case SAMSUNG -> {
                uris.add("samsungapps://ProductDetail/" + pkg);
                uris.add("market://details?id=" + pkg);
            }
            case DEFAULT -> {
                uris.add("market://details?id=" + pkg);
                if (!enc.isBlank()) {
                    uris.add("market://search?q=" + enc);
                }
            }
        }
        return uris;
    }

    private static String encode(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        try {
            return URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return keyword.trim();
        }
    }
}
