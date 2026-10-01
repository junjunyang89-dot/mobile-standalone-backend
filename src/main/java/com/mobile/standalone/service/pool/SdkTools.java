package com.mobile.standalone.service.pool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 找主控机上的 {@code emulator} / {@code adb}。
 * <p>
 * 人的终端 PATH 里常常有这两条，但 IDE / launchd 拉起的后端进程没有。
 * 只写 {@code new ProcessBuilder("emulator")} 的结果就是设备池永远提示
 * 「找不到 emulator 命令」，而 SDK 其实已经装在 {@code ~/Library/Android/sdk}。
 */
final class SdkTools {

    private SdkTools() {
    }

    static String androidHome() {
        for (String key : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) {
                Path p = Path.of(v.trim());
                if (Files.isDirectory(p)) return p.toString();
            }
        }
        Path fallback = Path.of(System.getProperty("user.home"), "Library", "Android", "sdk");
        return Files.isDirectory(fallback) ? fallback.toString() : null;
    }

    static String emulator() {
        return firstExisting(
                which("emulator"),
                join(androidHome(), "emulator", "emulator"));
    }

    static String adb() {
        return firstExisting(
                which("adb"),
                join(androidHome(), "platform-tools", "adb"));
    }

    static boolean emulatorAvailable() {
        return emulator() != null;
    }

    static boolean simctlAvailable() {
        return which("xcrun") != null;
    }

    /**
     * DevEco 自带的鸿蒙模拟器。不能走 {@code which Emulator}：
     * macOS 上 Android SDK 也有一个同名二进制，找错了启停全废。
     */
    static String harmonyEmulator() {
        String fromEnv = System.getenv("DEVECO_EMULATOR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path p = Path.of(fromEnv.trim());
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p.toString();
            }
        }
        String home = System.getProperty("user.home");
        return firstExisting(
                executable("/Applications/DevEco-Studio.app/Contents/tools/emulator/Emulator"),
                executable(home + "/Applications/DevEco-Studio.app/Contents/tools/emulator/Emulator"));
    }

    static boolean harmonyEmulatorAvailable() {
        return harmonyEmulator() != null;
    }

    private static String join(String home, String... parts) {
        if (home == null) return null;
        Path p = Path.of(home);
        for (String part : parts) {
            p = p.resolve(part);
        }
        return Files.isRegularFile(p) && Files.isExecutable(p) ? p.toString() : null;
    }

    private static String executable(String path) {
        if (path == null || path.isBlank()) return null;
        Path p = Path.of(path);
        return Files.isRegularFile(p) && Files.isExecutable(p) ? p.toString() : null;
    }

    private static String firstExisting(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    private static String which(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd)
                    .redirectErrorStream(true)
                    .start();
            String line = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0 || line.isEmpty()) return null;
            Path path = Path.of(line);
            return Files.isRegularFile(path) ? path.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
