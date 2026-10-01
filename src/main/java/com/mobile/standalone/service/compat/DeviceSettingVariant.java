package com.mobile.standalone.service.compat;

import java.util.Locale;

/**
 * 兼容性测试里的系统设置差异：同一台机切到这个设置后，把核心流程再跑一遍。
 * <p>
 * 变体作业的 jobKey 是 {@code appId__deviceId__DARK_MODE}，基础作业不带后缀。
 * 目前只有 Android 能用 adb 静默切换；iOS / 鸿蒙不生成变体作业。
 */
public enum DeviceSettingVariant {
    DARK_MODE("深色模式"),
    LARGE_FONT("大字体");

    private static final String SEP = "__";

    private final String label;

    DeviceSettingVariant(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String jobKeyOf(String baseJobKey) {
        return baseJobKey + SEP + name();
    }

    public static DeviceSettingVariant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 基础作业返回 null */
    public static DeviceSettingVariant ofJobKey(String jobKey) {
        if (jobKey == null) {
            return null;
        }
        int i = jobKey.lastIndexOf(SEP);
        if (i < 0 || jobKey.indexOf(SEP) == i) {
            return null;
        }
        return parse(jobKey.substring(i + SEP.length()));
    }
}
