package com.mobile.standalone.service.pack;

import java.util.List;
import java.util.Locale;

/**
 * 鸿蒙 hvigor 命令。
 * <p>
 * 页面上的「包类型」对应工程里两套概念：
 * <ul>
 *   <li>{@code products}：{@code default}=Release 签名目录，{@code debug}=Debug 签名目录</li>
 *   <li>{@code buildModeSet}：{@code release} / {@code debug} / {@code devTest}（测试包）</li>
 * </ul>
 * Testing 包 = {@code product=debug} + {@code buildMode=devTest}，跟 DevEco 里常见的测试编译一致。
 */
public final class HarmonyPackCommand {

    public static final String KIND_RELEASE = "default";
    public static final String KIND_DEBUG = "debug";
    public static final String KIND_TESTING = "devTest";

    private HarmonyPackCommand() {
    }

    /** 页面/任务里存的包类型：default / debug / devTest */
    public static String normalizePackKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return KIND_TESTING;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if ("debug".equals(v)) {
            return KIND_DEBUG;
        }
        if ("devtest".equals(v) || "testing".equals(v) || "dev_test".equals(v)) {
            return KIND_TESTING;
        }
        if ("release".equals(v) || "default".equals(v)) {
            return KIND_RELEASE;
        }
        return KIND_TESTING;
    }

    /** @deprecated 用 {@link #normalizePackKind(String)} */
    public static String normalizeProduct(String raw) {
        return normalizePackKind(raw);
    }

    /** hvigor {@code -p product=}：Testing 走 debug 产品目录 */
    public static String hvigorProduct(String packKind) {
        String kind = normalizePackKind(packKind);
        return KIND_TESTING.equals(kind) ? KIND_DEBUG : kind;
    }

    public static String normalizeOutputType(String raw) {
        if (raw != null && "APP".equalsIgnoreCase(raw.trim())) {
            return "APP";
        }
        return "HAP";
    }

    public static String buildMode(String packKind) {
        String kind = normalizePackKind(packKind);
        if (KIND_DEBUG.equals(kind)) {
            return "debug";
        }
        if (KIND_TESTING.equals(kind)) {
            return "devTest";
        }
        return "release";
    }

    public static String label(String packKind) {
        String kind = normalizePackKind(packKind);
        return switch (kind) {
            case KIND_DEBUG -> "Debug";
            case KIND_TESTING -> "Testing";
            default -> "Release";
        };
    }

    public static List<String> hvigorArgs(String packKind, String outputType, String module) {
        String kind = normalizePackKind(packKind);
        String product = hvigorProduct(kind);
        String mode = buildMode(kind);
        String mod = (module == null || module.isBlank()) ? "entry" : module.trim();
        if ("APP".equals(normalizeOutputType(outputType))) {
            return List.of(
                    "assembleApp",
                    "--mode", "project",
                    "-p", "product=" + product,
                    "-p", "buildMode=" + mode,
                    "--no-daemon");
        }
        return List.of(
                "assembleHap",
                "--mode", "module",
                "-p", "product=" + product,
                "-p", "module=" + mod + "@default",
                "-p", "buildMode=" + mode,
                "--no-daemon");
    }
}
