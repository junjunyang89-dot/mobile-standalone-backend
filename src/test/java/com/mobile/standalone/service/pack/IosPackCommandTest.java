package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IosPackCommandTest {

    @Test
    void archiveArgs_pointAtEsportAppDebug() {
        List<String> args = IosPackCommand.archiveArgs(
                "ESModulizeCloud.xcworkspace", "/tmp/a.xcarchive", "Z3U4KP2R7K");
        assertThat(args).contains(
                "-workspace", "ESModulizeCloud.xcworkspace",
                "-scheme", "esport-app",
                "-configuration", "Debug",
                "DEVELOPMENT_TEAM=Z3U4KP2R7K",
                "archive");
    }

    @Test
    void writeDevelopmentExportPlist(@TempDir Path dir) throws Exception {
        Path plist = dir.resolve("ExportOptions.plist");
        IosPackCommand.writeDevelopmentExportPlist(plist, "Z3U4KP2R7K");
        String xml = Files.readString(plist);
        assertThat(xml).contains("<string>development</string>");
        assertThat(xml).contains("<string>Z3U4KP2R7K</string>");
        assertThat(xml).contains("<string>automatic</string>");
    }
}
