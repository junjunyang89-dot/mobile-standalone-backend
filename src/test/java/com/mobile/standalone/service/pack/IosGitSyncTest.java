package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IosGitSyncTest {

    @Test
    void podInstallNoiseDoesNotBlockSync() {
        assertThat(IosGitSync.blockingDirtySummary(
                " M Podfile.lock\n?? Pods/Alamofire/\n?? pgyer/esport-app.ipa\n")).isNull();
    }

    @Test
    void sourceDirtyStillBlocks() {
        assertThat(IosGitSync.blockingDirtySummary(
                " M esport-app/esport-app/AppDelegate.swift\n"))
                .contains("AppDelegate.swift");
    }

    @Test
    void normalizeBranch_stripsOrigin() {
        assertThat(IosGitSync.normalizeBranch("origin/feat/APP-1", "testing")).isEqualTo("feat/APP-1");
        assertThat(IosGitSync.normalizeBranch(null, null)).isEqualTo("testing");
    }
}
