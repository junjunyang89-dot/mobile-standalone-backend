package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IosBuildProfileTest {

    @Test
    void readsMarketingAndCurrentFromPbxproj(@TempDir Path dir) throws Exception {
        Path pbx = dir.resolve("esport-app").resolve("esport-app.xcodeproj").resolve("project.pbxproj");
        Files.createDirectories(pbx.getParent());
        Files.writeString(pbx, """
                MARKETING_VERSION = 4.1.8;
                CURRENT_PROJECT_VERSION = 4180;
                """);
        assertThat(IosBuildProfile.versionName(dir)).isEqualTo("4.1.8");
        assertThat(IosBuildProfile.versionCode(dir)).isEqualTo("4180");
    }
}
