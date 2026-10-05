package com.mobile.standalone.service.pack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 找本机 Xcode / CocoaPods / 开发证书。launchd 拉起的后端 PATH 很瘦，不能只写 {@code xcodebuild}。
 */
final class IosPackTools {

    private IosPackTools() {
    }

    static Path workspace(Path projectDir) {
        Path w = projectDir.resolve(IosPackCommand.WORKSPACE);
        return Files.isDirectory(w) ? w : null;
    }

    static String xcodebuild() {
        return firstExisting(
                executable("/usr/bin/xcodebuild"),
                executable("/Applications/Xcode.app/Contents/Developer/usr/bin/xcodebuild"));
    }

    static String pod() {
        return firstExisting(
                executable("/opt/homebrew/bin/pod"),
                executable("/usr/local/bin/pod"),
                executable(home() + "/.rbenv/shims/pod"));
    }

    static boolean xcodeReady() {
        String bin = xcodebuild();
        if (bin == null) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(bin, "-version").redirectErrorStream(true).start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 钥匙串里有 Apple Development，自动签名才打得动 development IPA */
    static boolean developmentIdentityPresent() {
        try {
            Process p = new ProcessBuilder("security", "find-identity", "-v", "-p", "codesigning")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            String lower = out.toLowerCase();
            return lower.contains("apple development") || lower.contains("iphone developer");
        } catch (Exception e) {
            return false;
        }
    }

    static List<String> extraPathEntries() {
        List<String> extra = new ArrayList<>();
        extra.add("/usr/bin");
        extra.add("/bin");
        extra.add("/usr/sbin");
        extra.add("/opt/homebrew/bin");
        extra.add("/usr/local/bin");
        extra.add(home() + "/.rbenv/shims");
        Path xcodeDev = Path.of("/Applications/Xcode.app/Contents/Developer/usr/bin");
        if (Files.isDirectory(xcodeDev)) {
            extra.add(xcodeDev.toString());
        }
        return extra;
    }

    private static String home() {
        return System.getProperty("user.home");
    }

    private static String executable(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path p = Path.of(path.trim());
        return Files.isRegularFile(p) && Files.isExecutable(p) ? p.toString() : null;
    }

    private static String firstExisting(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }
}
