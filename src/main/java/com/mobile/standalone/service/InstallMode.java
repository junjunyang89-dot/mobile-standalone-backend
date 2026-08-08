package com.mobile.standalone.service;

/**
 * Android 安装方式（iOS 预留同一枚举，实现后续再接）。
 */
public enum InstallMode {
    /** 蒲公英下载后 adb/idevice 安装 */
    PGYER,
    /** 打开渠道/商店详情页再点安装（默认，线上回归真路径） */
    CHANNEL_PAGE,
    /** 使用已上传的本地 APK/IPA */
    LOCAL_APK,
    /** 展示安装页二维码，人工扫码；后端轮询包名落地 */
    QR_ASSIST;

    public static InstallMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return CHANNEL_PAGE;
        }
        String s = raw.trim().toUpperCase();
        // 旧关键字语义
        if ("STORE".equals(s) || "CHANNEL".equals(s) || "MARKET".equals(s)) {
            return CHANNEL_PAGE;
        }
        try {
            return InstallMode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return CHANNEL_PAGE;
        }
    }
}
