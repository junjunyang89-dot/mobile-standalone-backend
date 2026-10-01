package com.mobile.standalone.service.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * debug APK 默认名来自工程 {@code outputFileName = "app-${variant.name}.apk"}。
 */
public final class AndroidPackArtifacts {

    private AndroidPackArtifacts() {
    }

    public static Optional<Path> findDebugApk(Path projectDir) {
        Path dir = projectDir.resolve("app").resolve("build").resolve("outputs")
                .resolve("apk").resolve("debug");
        Path named = dir.resolve("app-debug.apk");
        if (Files.isRegularFile(named)) {
            return Optional.of(named);
        }
        return newestApk(dir);
    }

    static Optional<Path> newestApk(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".apk"))
                    .max(Comparator.comparingLong(AndroidPackArtifacts::mtime));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
