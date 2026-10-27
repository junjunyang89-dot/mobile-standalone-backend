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
        assertThat(HarmonyGitSync.blockingDirtySummary(
                " M SteamESports/oh-package-lock.json5\n"
                        + " M SteamESports/entry/oh-package-lock.json5\n")).isNull();
    }

    @Test
    void pasteboardPatchedModuleIsRecognisedAsPlatformChange() {
        String head = """
                { "requestPermissions": [
                    { "name": "ohos.permission.INTERNET" },
                    {
                      "name": "ohos.permission.READ_PASTEBOARD",
                      "reason": "$string:EntryAbility_READ_PASTEBOARD",
                      "usedScene": { "when": "inuse" }
                    }
                ] }
                """;
        String patched = HarmonyPasteboardPermissionPatch.stripPermission(head);
        assertThat(HarmonyGitSync.samePasteboardPatch(head, patched)).isTrue();
        assertThat(HarmonyGitSync.samePasteboardPatch(head, patched.replace("INTERNET", "CAMERA"))).isFalse();
    }

    @Test
    void otherDirtyFilesStillBlock() {
        assertThat(HarmonyGitSync.blockingDirtySummary(
                " M SteamESports/entry/src/main/ets/pages/Foo.ets\n"))
                .contains("Foo.ets");
    }
}
