package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IosPodfilePatchTest {

    @Test
    void patchesBareSwiftGifOrigin() {
        String in = """
                platform :ios, '13.0'
                  pod 'SnapKit', '5.7.1'
                  pod 'SwiftGifOrigin'
                  pod 'Masonry'
                """;
        IosPodfilePatch.PatchResult out = IosPodfilePatch.patchText(in);
        assertThat(out.changed()).isTrue();
        assertThat(out.text()).contains(
                "pod 'SwiftGifOrigin', :git => '" + IosPodfilePatch.SWIFT_GIF_GIT
                        + "', :tag => '" + IosPodfilePatch.SWIFT_GIF_TAG + "'");
        assertThat(out.text()).contains("pod 'SnapKit'");
        assertThat(out.text()).doesNotContain("pod 'SwiftGifOrigin'\n");
    }

    @Test
    void keepsExistingGitOverride() {
        String in = "  pod 'SwiftGifOrigin', :git => 'https://example.com/x.git', :tag => 'v1'\n";
        IosPodfilePatch.PatchResult out = IosPodfilePatch.patchText(in);
        assertThat(out.changed()).isFalse();
        assertThat(out.text()).isEqualTo(in);
    }

    @Test
    void applyAndRestore(@TempDir Path dir) throws Exception {
        Path podfile = dir.resolve("Podfile");
        String original = "  pod 'SwiftGifOrigin'\n";
        Files.writeString(podfile, original, StandardCharsets.UTF_8);

        IosPodfilePatch.Applied applied = IosPodfilePatch.apply(dir);
        assertThat(applied.changed()).isTrue();
        assertThat(Files.readString(podfile)).contains(":git =>");

        applied.restore();
        assertThat(Files.readString(podfile)).isEqualTo(original);
    }
}
