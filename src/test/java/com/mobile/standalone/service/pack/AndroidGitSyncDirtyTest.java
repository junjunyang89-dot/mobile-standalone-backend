package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidGitSyncDirtyTest {

    @Test
    void 本地抓包相关脏文件不挡打包() {
        assertNull(AndroidGitSync.blockingDirtySummary(""));
        assertNull(AndroidGitSync.blockingDirtySummary(" M .gitignore\n"));
        assertNull(AndroidGitSync.blockingDirtySummary(
                "?? SteamESports/app/src/debug/res/xml/network_security_config.xml\n"));
        assertNull(AndroidGitSync.blockingDirtySummary(
                " M .gitignore\n?? SteamESports/app/src/debug/res/xml/network_security_config.xml\n"));
    }

    @Test
    void 其它改动仍要拦() {
        String s = AndroidGitSync.blockingDirtySummary(
                " M .gitignore\n M SteamESports/app/build.gradle\n");
        assertTrue(s != null && s.contains("build.gradle"));
    }
}
