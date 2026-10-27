package com.mobile.standalone.service.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台打包时临时改 Podfile，不提交、打完还原。
 * <p>
 * 当前：上游 {@code bahlo/SwiftGif} 已删，裸写 {@code pod 'SwiftGifOrigin'} 会装失败，
 * 临时指到仍带 {@code v1.7.0} 标签的 fork。开发仓库不动。
 */
final class IosPodfilePatch {

    /** 仍带 v1.7.0 的公开 fork；原仓库 github.com/bahlo/SwiftGif 已 404。 */
    static final String SWIFT_GIF_GIT = "https://github.com/Expensify/SwiftGif.git";
    static final String SWIFT_GIF_TAG = "v1.7.0";

    private static final Pattern SWIFT_GIF_LINE = Pattern.compile(
            "^([ \\t]*pod[ \\t]+['\"]SwiftGifOrigin['\"])([^\\n]*)$",
            Pattern.MULTILINE);

    private IosPodfilePatch() {
    }

    /**
     * @return 实际改过的说明（空表示不用补）；调用方务必在 finally 里 {@link #restore()}
     */
    static Applied apply(Path projectDir) throws IOException {
        Path podfile = projectDir.resolve("Podfile");
        if (!Files.isRegularFile(podfile)) {
            return Applied.none(podfile);
        }
        byte[] original = Files.readAllBytes(podfile);
        String text = new String(original, StandardCharsets.UTF_8);
        PatchResult patched = patchText(text);
        if (!patched.changed()) {
            return Applied.none(podfile);
        }
        Files.writeString(podfile, patched.text(), StandardCharsets.UTF_8);
        return new Applied(podfile, original, patched.notes());
    }

    static PatchResult patchText(String text) {
        if (text == null || text.isEmpty()) {
            return new PatchResult(text == null ? "" : text, List.of());
        }
        List<String> notes = new ArrayList<>();
        Matcher m = SWIFT_GIF_LINE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String prefix = m.group(1);
            String rest = m.group(2) == null ? "" : m.group(2);
            if (rest.contains(":git") || rest.contains(":podspec") || rest.contains(":path")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            String replacement = prefix + ", :git => '" + SWIFT_GIF_GIT
                    + "', :tag => '" + SWIFT_GIF_TAG + "'";
            // 保留行尾注释
            int hash = rest.indexOf('#');
            if (hash >= 0) {
                replacement += rest.substring(hash);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            notes.add("SwiftGifOrigin → " + SWIFT_GIF_GIT + " @ " + SWIFT_GIF_TAG
                    + "（上游 bahlo/SwiftGif 已删，平台临时覆盖，不改仓库）");
        }
        m.appendTail(sb);
        return new PatchResult(sb.toString(), List.copyOf(notes));
    }

    record PatchResult(String text, List<String> notes) {
        boolean changed() {
            return notes != null && !notes.isEmpty();
        }
    }

    record Applied(Path podfile, byte[] original, List<String> notes) {
        static Applied none(Path podfile) {
            return new Applied(podfile, null, List.of());
        }

        boolean changed() {
            return original != null && notes != null && !notes.isEmpty();
        }

        void restore() throws IOException {
            if (original == null || podfile == null) {
                return;
            }
            Files.write(podfile, original);
        }
    }
}
