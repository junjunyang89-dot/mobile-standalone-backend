package com.mobile.standalone.service.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 {@code config.gradle} 里的 versionName / versionCode，给打包记录用。
 */
public final class AndroidBuildProfile {

    private static final Pattern VERSION_NAME = Pattern.compile("versionName\\s*:\\s*'([^']+)'");
    private static final Pattern VERSION_CODE = Pattern.compile("versionCode\\s*:\\s*(\\d+)");

    private AndroidBuildProfile() {
    }

    public static String versionName(Path projectDir) {
        return first(VERSION_NAME, readConfig(projectDir));
    }

    public static String versionCode(Path projectDir) {
        return first(VERSION_CODE, readConfig(projectDir));
    }

    static String versionNameFrom(String gradle) {
        return first(VERSION_NAME, gradle);
    }

    static String versionCodeFrom(String gradle) {
        return first(VERSION_CODE, gradle);
    }

    private static String readConfig(Path projectDir) {
        Path file = projectDir.resolve("config.gradle");
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String first(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
