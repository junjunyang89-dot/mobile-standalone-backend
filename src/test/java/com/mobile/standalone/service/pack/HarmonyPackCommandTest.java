package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarmonyPackCommandTest {

    @Test
    void release落到default产品release模式() {
        assertEquals("default", HarmonyPackCommand.normalizePackKind("release"));
        assertEquals("default", HarmonyPackCommand.normalizePackKind("default"));
        assertEquals("release", HarmonyPackCommand.buildMode("default"));
        assertEquals("default", HarmonyPackCommand.hvigorProduct("default"));
        List<String> args = HarmonyPackCommand.hvigorArgs("default", "HAP", "entry");
        assertTrue(args.contains("assembleHap"));
        assertTrue(args.contains("product=default"));
        assertTrue(args.contains("buildMode=release"));
        assertTrue(args.contains("module=entry@default"));
        assertTrue(args.contains("--no-daemon"));
    }

    @Test
    void debug产品用debug模式() {
        assertEquals("debug", HarmonyPackCommand.normalizePackKind("Debug"));
        assertEquals("debug", HarmonyPackCommand.buildMode("debug"));
        assertEquals("debug", HarmonyPackCommand.hvigorProduct("debug"));
        List<String> args = HarmonyPackCommand.hvigorArgs("debug", "HAP", "entry");
        assertTrue(args.contains("product=debug"));
        assertTrue(args.contains("buildMode=debug"));
    }

    @Test
    void testing包用debug产品加devTest模式() {
        assertEquals("devTest", HarmonyPackCommand.normalizePackKind("testing"));
        assertEquals("devTest", HarmonyPackCommand.normalizePackKind(null));
        assertEquals("devTest", HarmonyPackCommand.buildMode("devTest"));
        assertEquals("debug", HarmonyPackCommand.hvigorProduct("devTest"));
        List<String> args = HarmonyPackCommand.hvigorArgs("devTest", "HAP", "entry");
        assertTrue(args.contains("product=debug"));
        assertTrue(args.contains("buildMode=devTest"));
        assertEquals("Testing", HarmonyPackCommand.label("devTest"));
    }

    @Test
    void app走assembleApp() {
        List<String> args = HarmonyPackCommand.hvigorArgs("default", "APP", "entry");
        assertEquals("assembleApp", args.get(0));
        assertTrue(args.contains("project"));
        assertFalse(args.stream().anyMatch(a -> a.startsWith("module=")));
    }
}

class HarmonyPackArtifactsTest {

    @TempDir
    Path tmp;

    @Test
    void 优先signed的hap() throws Exception {
        Path dir = tmp.resolve("entry/build/default/outputs/default");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("entry-default-unsigned.hap"), "u");
        Path signed = dir.resolve("entry-default-signed.hap");
        Files.writeString(signed, "s");
        assertEquals(signed, HarmonyPackArtifacts.find(tmp, "default", "HAP").orElseThrow());
    }

    @Test
    void testing包产物在debug目录() throws Exception {
        Path debugDir = tmp.resolve("entry/build/debug/outputs/default");
        Files.createDirectories(debugDir);
        Path hap = debugDir.resolve("entry-default-signed.hap");
        Files.writeString(hap, "t");
        assertEquals(hap, HarmonyPackArtifacts.find(tmp, "devTest", "HAP").orElseThrow());
    }

    @Test
    void 按产品目录区分() throws Exception {
        Path debugDir = tmp.resolve("entry/build/debug/outputs/default");
        Files.createDirectories(debugDir);
        Path hap = debugDir.resolve("entry-default-signed.hap");
        Files.writeString(hap, "d");
        Files.createDirectories(tmp.resolve("entry/build/default/outputs/default"));
        assertEquals(hap, HarmonyPackArtifacts.find(tmp, "debug", "HAP").orElseThrow());
        assertTrue(HarmonyPackArtifacts.find(tmp, "default", "HAP").isEmpty());
    }
}

class HarmonyBuildProfileTest {

    @Test
    void 读产品和签名路径是否在本机() throws Exception {
        Path p12 = Files.createTempFile("pack-sign", ".p12");
        String json5 = """
                {
                  "app": {
                    "signingConfigs": [
                      {
                        "name": "default",
                        "type": "HarmonyOS",
                        "material": {
                          "storeFile": "%s",
                          "storePassword": "secret-not-used"
                        }
                      },
                      {
                        "name": "debug",
                        "type": "HarmonyOS",
                        "material": {
                          "storeFile": "C:\\\\Users\\\\user\\\\.ohos\\\\config\\\\debug.p12"
                        }
                      }
                    ],
                    "products": [
                      {
                        "name": "default",
                        // Release包
                        "signingConfig": "default"
                      },
                      {
                        "name": "debug",
                        // Debug包
                        "signingConfig": "debug"
                      }
                    ]
                  }
                }
                """.formatted(p12.toString().replace("\\", "\\\\"));
        List<HarmonyBuildProfile.Product> products = HarmonyBuildProfile.products(json5);
        assertEquals(2, products.size());
        assertEquals("default", products.get(0).name());
        assertEquals("Release", products.get(0).label());
        assertTrue(products.get(0).signingReady());
        assertEquals("debug", products.get(1).name());
        assertFalse(products.get(1).signingReady());
    }

    @Test
    void 能读真实工程产品名() {
        Path project = Path.of(System.getProperty("user.home"),
                "projects/qa_coding/app_coding/app_esports_harmonyos/SteamESports");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(project.resolve("build-profile.json5")));
        List<HarmonyBuildProfile.Product> products = HarmonyBuildProfile.products(project);
        assertEquals(2, products.size());
        assertEquals("default", products.get(0).name());
        assertEquals("debug", products.get(1).name());
        assertTrue(products.get(0).signingReady());
        // 本机 debug 也改成了 DevEco 自动签名，通常也是 ready
        assertTrue(products.get(1).signingReady());
    }
}
