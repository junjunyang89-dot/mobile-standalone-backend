package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IosPackArtifactsTest {

    @Test
    void findsNewestIpa(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("old.ipa");
        Path neu = dir.resolve("esport-app.ipa");
        Files.writeString(old, "a");
        Thread.sleep(5);
        Files.writeString(neu, "b");
        assertThat(IosPackArtifacts.findIpa(dir).orElseThrow().getFileName().toString())
                .isEqualTo("esport-app.ipa");
    }
}
