package com.mobile.standalone.service.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 找本机 DevEco 的 hvigor / node / SDK。
 * <p>
 * IDE 或 launchd 拉起的后端没有人终端里的 PATH，只写 {@code hvigorw} 会直接报找不到命令。
 */
final class HarmonyPackTools {

    private HarmonyPackTools() {
    }

    static String hvigorw(String configured) {
        return firstExisting(
                executable(configured),
                executable("/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw"),
                executable(home() + "/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw"));
    }

    static String nodeHome(String configured) {
        if (dir(configured) != null) {
            return dir(configured);
        }
        Path bundled = Path.of("/Applications/DevEco-Studio.app/Contents/tools/node");
        if (Files.isRegularFile(bundled.resolve("bin").resolve("node"))) {
            return bundled.toString();
        }
        Path homeBundled = Path.of(home(), "Applications/DevEco-Studio.app/Contents/tools/node");
        if (Files.isRegularFile(homeBundled.resolve("bin").resolve("node"))) {
            return homeBundled.toString();
        }
        return null;
    }

    static String sdkHome() {
        return firstExisting(
                dir("/Applications/DevEco-Studio.app/Contents/sdk"),
                dir(home() + "/Applications/DevEco-Studio.app/Contents/sdk"));
    }

    static String ohpm() {
        return firstExisting(
                executable("/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin/ohpm"),
                executable(home() + "/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin/ohpm"));
    }

    static List<String> extraPathEntries(String nodeHome) {
        List<String> extra = new ArrayList<>();
        if (nodeHome != null) {
            extra.add(Path.of(nodeHome, "bin").toString());
        }
        String ohpmBin = ohpm();
        if (ohpmBin != null) {
            extra.add(Path.of(ohpmBin).getParent().toString());
        }
        String sdk = sdkHome();
        if (sdk != null) {
            Path toolchains = Path.of(sdk, "default", "openharmony", "toolchains");
            if (Files.isDirectory(toolchains)) {
                extra.add(toolchains.toString());
            }
        }
        return extra;
    }

    static String joinPath(List<String> extra, String existing) {
        StringBuilder sb = new StringBuilder();
        for (String e : extra) {
            if (e == null || e.isBlank()) continue;
            if (sb.length() > 0) sb.append(java.io.File.pathSeparator);
            sb.append(e);
        }
        if (existing != null && !existing.isBlank()) {
            if (sb.length() > 0) sb.append(java.io.File.pathSeparator);
            sb.append(existing);
        }
        return sb.toString();
    }

    private static String home() {
        return System.getProperty("user.home");
    }

    private static String executable(String path) {
        if (path == null || path.isBlank()) return null;
        Path p = Path.of(path.trim());
        return Files.isRegularFile(p) && Files.isExecutable(p) ? p.toString() : null;
    }

    private static String dir(String path) {
        if (path == null || path.isBlank()) return null;
        Path p = Path.of(path.trim());
        return Files.isDirectory(p) ? p.toString() : null;
    }

    private static String firstExisting(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }
}
