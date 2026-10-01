package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HarmonyGitSyncTest {

    @Test
    void buildProfileLocalSigningDoesNotBlockSync() {
        assertThat(HarmonyGitSync.blockingDirtySummary(
                " M SteamESports/build-profile.json5\n")).isNull();
        assertThat(HarmonyGitSync.blockingDirtySummary(
                "MM SteamESports/build-profile.json5\n")).isNull();
    }

    @Test
    void otherDirtyFilesStillBlock() {
        assertThat(HarmonyGitSync.blockingDirtySummary(
                " M SteamESports/entry/src/main/ets/pages/Foo.ets\n"))
                .contains("Foo.ets");
    }
}
