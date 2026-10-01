package com.mobile.standalone.service.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 打包产物位置跟 DevEco 约定走：优先带 {@code -signed} 的那份。
 */
public final class HarmonyPackArtifacts {

    private HarmonyPackArtifacts() {
    }

    public static Optional<Path> find(Path projectDir, String product, String outputType) {
        String p = HarmonyPackCommand.hvigorProduct(product);
        if ("APP".equals(HarmonyPackCommand.normalizeOutputType(outputType))) {
            return findApp(projectDir, p);
        }
        return findHap(projectDir, p);
    }

    static Optional<Path> findHap(Path projectDir, String product) {
        String p = HarmonyPackCommand.hvigorProduct(product);
        Path dir = projectDir.resolve("entry").resolve("build").resolve(p)
                .resolve("outputs").resolve("default");
        Path signed = dir.resolve("entry-default-signed.hap");
        if (Files.isRegularFile(signed)) {
            return Optional.of(signed);
        }
        Path unsigned = dir.resolve("entry-default-unsigned.hap");
        if (Files.isRegularFile(unsigned)) {
            return Optional.of(unsigned);
        }
        return newestWithSuffix(dir, ".hap");
    }

    /** 打包前清掉旧 hap，避免增量构建 UP-TO-DATE 时把旧包当成新包发出去 */
    public static void clearHapOutputs(Path projectDir, String product) {
        String p = HarmonyPackCommand.hvigorProduct(product);
        Path dir = projectDir.resolve("entry").resolve("build").resolve(p)
                .resolve("outputs").resolve("default");
        deleteQuietly(dir.resolve("entry-default-signed.hap"));
        deleteQuietly(dir.resolve("entry-default-unsigned.hap"));
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 删不掉就交给后面的时间戳校验兜住
        }
    }

    static Optional<Path> findApp(Path projectDir, String product) {
        Path dir = projectDir.resolve("build").resolve("outputs").resolve(product);
        Path signedPreferred = newestWithSuffix(dir, "-signed.app").orElse(null);
        if (signedPreferred != null) {
            return Optional.of(signedPreferred);
        }
        return newestWithSuffix(dir, ".app");
    }

    private static Optional<Path> newestWithSuffix(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .max(Comparator.comparingLong(HarmonyPackArtifacts::mtime));
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
