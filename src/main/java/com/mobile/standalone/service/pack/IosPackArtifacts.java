package com.mobile.standalone.service.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** 找 xcodebuild -exportArchive 吐出的 ipa。 */
final class IosPackArtifacts {

    private IosPackArtifacts() {
    }

    static Optional<Path> findIpa(Path exportDir) {
        if (exportDir == null || !Files.isDirectory(exportDir)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(exportDir, 2)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".ipa"))
                    .max(Comparator.comparingLong(IosPackArtifacts::mtime));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }
}
