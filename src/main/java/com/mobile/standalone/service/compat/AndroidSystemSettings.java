package com.mobile.standalone.service.compat;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用 adb 切 Android 系统设置，跑完再切回去。
 * <p>
 * 必须还原：设备池是全组共用的，一台机被留在深色模式 + 大字体上，
 * 下一个人跑的普通回归会莫名其妙地挂在「找不到元素」上，而且很难想到是上一批留下的。
 */
@Slf4j
public final class AndroidSystemSettings {

    static final String LARGE_FONT_SCALE = "1.3";
    private static final Pattern NIGHT_MODE = Pattern.compile("Night mode:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);

    private AndroidSystemSettings() {
    }

    /**
     * 切到变体设置。
     *
     * @return 切之前的值，交给 {@link #restore} 还原
     * @throws IllegalStateException 切不过去时——不能装作切了，否则跑出来的是一份标着「深色模式」的普通结果
     */
    public static String apply(String deviceId, DeviceSettingVariant v) {
        switch (v) {
            case DARK_MODE -> {
                String prev = parseNightMode(shell(deviceId, "cmd uimode night"));
                String out = shell(deviceId, "cmd uimode night yes");
                if (!"yes".equals(parseNightMode(out))) {
                    throw new IllegalStateException("切深色模式没生效：" + out.trim());
                }
                return prev == null ? "no" : prev;
            }
            case LARGE_FONT -> {
                String prev = normalizeFontScale(shell(deviceId, "settings get system font_scale"));
                shell(deviceId, "settings put system font_scale " + LARGE_FONT_SCALE);
                String now = normalizeFontScale(shell(deviceId, "settings get system font_scale"));
                if (!LARGE_FONT_SCALE.equals(now)) {
                    throw new IllegalStateException("切大字体没生效，当前 font_scale=" + now);
                }
                return prev;
            }
            default -> throw new IllegalStateException("不支持的设置：" + v);
        }
    }

    public static void restore(String deviceId, DeviceSettingVariant v, String previous) {
        try {
            switch (v) {
                case DARK_MODE -> shell(deviceId, "cmd uimode night " + safeToken(previous, "no"));
                case LARGE_FONT -> shell(deviceId, "settings put system font_scale " + safeToken(previous, "1.0"));
                default -> { }
            }
        } catch (Exception e) {
            log.warn("[compat] 还原{}失败 device={}，请手动改回: {}", v.label(), deviceId, e.getMessage());
        }
    }

    static String parseNightMode(String out) {
        if (out == null) {
            return null;
        }
        Matcher m = NIGHT_MODE.matcher(out);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    static String normalizeFontScale(String out) {
        String s = out == null ? "" : out.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return "1.0";
        }
        try {
            double d = Double.parseDouble(s);
            return d == Math.rint(d) ? String.format(Locale.ROOT, "%.1f", d) : stripZeros(s);
        } catch (NumberFormatException e) {
            return "1.0";
        }
    }

    private static String stripZeros(String s) {
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", ".0") : s;
    }

    /** 只放行数字、字母和点，防止把读回来的脏值拼进 shell */
    private static String safeToken(String v, String fallback) {
        return v != null && v.matches("[A-Za-z0-9.]+") ? v : fallback;
    }

    private static String shell(String deviceId, String cmd) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", cmd)
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("adb 超时：" + cmd);
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("adb 被中断：" + cmd, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("adb 执行失败：" + e.getMessage(), e);
        }
    }
}
