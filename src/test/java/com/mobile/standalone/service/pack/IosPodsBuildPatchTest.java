package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IosPodsBuildPatchTest {

    @Test
    void patchesSvgKitImport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(IosPodsBuildPatch.SVGKIT_PRIVATE);
        Files.createDirectories(file.getParent());
        String body = """
                #import "SVGKDefine.h"
                @import CocoaLumberjack;
                #define SVGKIT_LOG_CONTEXT 556
                """;
        Files.writeString(file, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));

        List<String> notes = IosPodsBuildPatch.apply(dir);
        assertThat(notes).isNotEmpty();
        String out = Files.readString(file);
        assertThat(out).contains(IosPodsBuildPatch.IMPORT_HEADER);
        assertThat(out).doesNotContain(IosPodsBuildPatch.IMPORT_MODULE);
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertThat(perms).contains(PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void noOpWhenAlreadyPatched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(IosPodsBuildPatch.SVGKIT_PRIVATE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, IosPodsBuildPatch.IMPORT_HEADER + "\n", StandardCharsets.UTF_8);
        assertThat(IosPodsBuildPatch.apply(dir)).isEmpty();
    }
}
