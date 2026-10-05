package com.mobile.standalone.service.pack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 完美电竞 iOS 打包命令。
 * <p>
 * 跟仓库里 Fastlane {@code gym} 对齐：workspace + scheme {@code esport-app}，
 * Debug / development，给真机装测包。分支由页面选，每次对齐 origin。
 */
public final class IosPackCommand {

    public static final String DEFAULT_BRANCH = "testing";
    public static final String PRODUCT = "debug";
    public static final String OUTPUT_TYPE = "IPA";
    public static final String SCHEME = "esport-app";
    public static final String WORKSPACE = "ESModulizeCloud.xcworkspace";
    public static final String CONFIGURATION = "Debug";
    public static final String EXPORT_METHOD = "development";
    public static final String DEFAULT_TEAM_ID = "Z3U4KP2R7K";

    private IosPackCommand() {
    }

    public static List<String> archiveArgs(String workspace, String archivePath, String teamId) {
        List<String> args = new ArrayList<>();
        args.add("-workspace");
        args.add(workspace);
        args.add("-scheme");
        args.add(SCHEME);
        args.add("-configuration");
        args.add(CONFIGURATION);
        args.add("-destination");
        args.add("generic/platform=iOS");
        args.add("-archivePath");
        args.add(archivePath);
        args.add("CODE_SIGN_STYLE=Automatic");
        if (teamId != null && !teamId.isBlank()) {
            args.add("DEVELOPMENT_TEAM=" + teamId.trim());
        }
        args.add("-allowProvisioningUpdates");
        args.add("archive");
        return args;
    }

    public static List<String> exportArgs(String archivePath, String exportPath, String plistPath) {
        return List.of(
                "-exportArchive",
                "-archivePath", archivePath,
                "-exportPath", exportPath,
                "-exportOptionsPlist", plistPath,
                "-allowProvisioningUpdates");
    }

    /** development 导出清单写到任务目录，不改仓库里那份 AdHoc ExportOptions.plist */
    public static void writeDevelopmentExportPlist(Path file, String teamId) throws Exception {
        String team = teamId == null || teamId.isBlank() ? DEFAULT_TEAM_ID : teamId.trim();
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                	<key>method</key>
                	<string>development</string>
                	<key>signingStyle</key>
                	<string>automatic</string>
                	<key>teamID</key>
                	<string>%s</string>
                	<key>compileBitcode</key>
                	<false/>
                	<key>stripSwiftSymbols</key>
                	<true/>
                </dict>
                </plist>
                """.formatted(team);
        Files.createDirectories(file.getParent());
        Files.writeString(file, xml, StandardCharsets.UTF_8);
    }
}
