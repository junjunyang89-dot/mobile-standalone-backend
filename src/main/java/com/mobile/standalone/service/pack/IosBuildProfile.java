package com.mobile.standalone.service.pack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 只读 Xcode 工程里的 MARKETING_VERSION / CURRENT_PROJECT_VERSION，给打包记录用。
 */
final class IosBuildProfile {

    private static final Pattern MARKETING = Pattern.compile("MARKETING_VERSION\\s*=\\s*([^;]+);");
    private static final Pattern CURRENT = Pattern.compile("CURRENT_PROJECT_VERSION\\s*=\\s*([^;]+);");

    private IosBuildProfile() {
    }

    static String versionName(Path projectDir) {
        return firstGroup(readPbxproj(projectDir), MARKETING);
    }

    static String versionCode(Path projectDir) {
        return firstGroup(readPbxproj(projectDir), CURRENT);
    }

    private static String firstGroup(String text, Pattern p) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1).trim();
        return v.isBlank() ? null : v;
    }

    private static String readPbxproj(Path projectDir) {
        Path pbx = projectDir.resolve("esport-app").resolve("esport-app.xcodeproj").resolve("project.pbxproj");
        if (!Files.isRegularFile(pbx)) {
            try (Stream<Path> walk = Files.walk(projectDir, 4)) {
                pbx = walk.filter(p -> p.getFileName().toString().equals("project.pbxproj"))
                        .findFirst()
                        .orElse(null);
            } catch (Exception e) {
                return null;
            }
        }
        if (pbx == null || !Files.isRegularFile(pbx)) {
            return null;
        }
        try {
            return Files.readString(pbx, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
