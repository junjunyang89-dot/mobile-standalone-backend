package com.mobile.standalone.service.pack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本机 debug 抓包补丁：写入 {@code src/debug/res/xml/network_security_config.xml}，
 * 让 debug 包信任用户证书。文件应在安卓仓 gitignore，不进远程 testing。
 */
final class AndroidLocalCapturePatch {

    private static final String REL =
            "app/src/debug/res/xml/network_security_config.xml";

    private static final String CONTENT = """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- 平台打包自动写入：仅 debug 信任用户证书，便于 mitm 抓包。勿提交。 -->
            <network-security-config>
                <base-config cleartextTrafficPermitted="true">
                    <trust-anchors>
                        <certificates src="system" />
                        <certificates src="user" />
                    </trust-anchors>
                </base-config>
                <debug-overrides>
                    <trust-anchors>
                        <certificates src="system" />
                        <certificates src="user" />
                    </trust-anchors>
                </debug-overrides>
            </network-security-config>
            """;

    private AndroidLocalCapturePatch() {
    }

    /** @return 写入路径；已是目标内容则仍返回该路径 */
    static Path ensure(Path steamESportsProject) throws Exception {
        Path file = steamESportsProject.resolve(REL).normalize();
        Files.createDirectories(file.getParent());
        String existing = Files.isRegularFile(file)
                ? Files.readString(file, StandardCharsets.UTF_8)
                : "";
        if (!existing.contains("certificates src=\"user\"")) {
            Files.writeString(file, CONTENT, StandardCharsets.UTF_8);
        }
        return file;
    }
}
