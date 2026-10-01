package com.mobile.standalone.service.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 找本机 Android SDK / JDK / gradlew。
 * <p>
 * IDE 或 launchd 拉起的后端没有人终端里的 PATH，只写 {@code gradle} 会直接报找不到。
 */
final class AndroidPackTools {

    private AndroidPackTools() {
    }

    static Path gradlew(Path projectDir) {
        Path w = projectDir.resolve("gradlew");
        return Files.isRegularFile(w) ? w : null;
    }

    static String git() {
        Path p = Path.of("/usr/bin/git");
        return Files.isRegularFile(p) && Files.isExecutable(p) ? p.toString() : "git";
    }

    static Path keystore(Path projectDir) {
        Path p = projectDir.resolve("app").resolve("sign_esports_app.keystore");
        return Files.isRegularFile(p) ? p : null;
    }

    static String androidHome() {
        for (String key : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) {
                Path p = Path.of(v.trim());
                if (Files.isDirectory(p)) {
                    return p.toString();
                }
            }
        }
        Path fallback = Path.of(System.getProperty("user.home"), "Library", "Android", "sdk");
        return Files.isDirectory(fallback) ? fallback.toString() : null;
    }

    static String javaHome() {
        String env = System.getenv("JAVA_HOME");
        if (dirWithJava(env) != null) {
            return dirWithJava(env);
        }
        String home = System.getProperty("user.home");
        return firstExisting(
                dirWithJava("/Applications/Android Studio.app/Contents/jbr"),
                dirWithJava("/Applications/Android Studio.app/Contents/jre"),
                dirWithJava(home + "/Applications/Android Studio.app/Contents/jbr"),
                dirWithJava("/opt/homebrew/opt/openjdk@17"),
                dirWithJava("/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"));
    }

    static List<String> extraPathEntries() {
        List<String> extra = new ArrayList<>();
        String java = javaHome();
        if (java != null) {
            extra.add(Path.of(java, "bin").toString());
        }
        String sdk = androidHome();
        if (sdk != null) {
            Path platformTools = Path.of(sdk, "platform-tools");
            if (Files.isDirectory(platformTools)) {
                extra.add(platformTools.toString());
            }
            Path cmdline = Path.of(sdk, "cmdline-tools", "latest", "bin");
            if (Files.isDirectory(cmdline)) {
                extra.add(cmdline.toString());
            }
        }
        extra.add("/usr/bin");
        extra.add("/bin");
        extra.add("/opt/homebrew/bin");
        extra.add("/usr/local/bin");
        return extra;
    }

    private static String dirWithJava(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path home = Path.of(path.trim());
        Path java = home.resolve("bin").resolve("java");
        return Files.isRegularFile(java) ? home.toString() : null;
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
