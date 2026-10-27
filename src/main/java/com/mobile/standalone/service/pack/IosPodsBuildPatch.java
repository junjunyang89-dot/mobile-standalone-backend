package com.mobile.standalone.service.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@code pod install} 之后、对本机 {@code Pods/} 做的编译补丁（不改仓库源码）。
 * <p>
 * SVGKit 私有头用 {@code @import CocoaLumberjack;}，静态库集成时模块找不到会 archive 失败。
 */
final class IosPodsBuildPatch {

    static final String SVGKIT_PRIVATE = "Pods/SVGKit/Source/SVGKDefine_Private.h";
    static final String IMPORT_MODULE = "@import CocoaLumberjack;";
    static final String IMPORT_HEADER = "#import <CocoaLumberjack/CocoaLumberjack.h>";

    private IosPodsBuildPatch() {
    }

    static List<String> apply(Path projectDir) throws IOException {
        List<String> notes = new ArrayList<>();
        if (patchSvgKitCocoaLumberjack(projectDir)) {
            notes.add(SVGKIT_PRIVATE + "：@import CocoaLumberjack → #import <CocoaLumberjack/…>"
                    + "（静态 Pod 下模块找不到，平台临时改 Pods，不改仓库）");
        }
        return List.copyOf(notes);
    }

    static boolean patchSvgKitCocoaLumberjack(Path projectDir) throws IOException {
        Path file = projectDir.resolve(SVGKIT_PRIVATE).normalize();
        if (!Files.isRegularFile(file)) {
            return false;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.contains(IMPORT_MODULE)) {
            return false;
        }
        ensureWritable(file);
        String next = text.replace(IMPORT_MODULE, IMPORT_HEADER);
        Files.writeString(file, next, StandardCharsets.UTF_8);
        return true;
    }

    private static void ensureWritable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            if (!perms.contains(PosixFilePermission.OWNER_WRITE)) {
                EnumSet<PosixFilePermission> next = EnumSet.copyOf(perms);
                next.add(PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(file, next);
            }
        } catch (UnsupportedOperationException ignored) {
            file.toFile().setWritable(true);
        }
    }
}
