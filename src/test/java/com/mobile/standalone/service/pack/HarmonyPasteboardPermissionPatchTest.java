package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HarmonyPasteboardPermissionPatchTest {

    @Test
    void stripPermissionRemovesPasteboardBlock() {
        String module = """
                {
                  "requestPermissions": [
                    { "name": "ohos.permission.INTERNET" },
                    {
                      "name": "ohos.permission.READ_PASTEBOARD",
                      "reason": "$string:EntryAbility_READ_PASTEBOARD",
                      "usedScene": {
                        "when": "inuse"
                      }
                    },
                    { "name": "ohos.permission.CAMERA", "reason": "x", "usedScene": { "when": "inuse" } }
                  ]
                }
                """;
        String next = HarmonyPasteboardPermissionPatch.stripPermission(module);
        assertThat(next).doesNotContain("READ_PASTEBOARD");
        assertThat(next).contains("INTERNET");
        assertThat(next).contains("CAMERA");
    }
}
