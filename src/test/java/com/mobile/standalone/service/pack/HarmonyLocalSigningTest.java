package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HarmonyLocalSigningTest {

    @TempDir
    Path tmp;

    private String realHome;

    @BeforeEach
    void isolateHome() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", realHome);
    }

    @Test
    void extractAndReplaceSigningConfigsKeepsProducts() {
        String remote = """
                {
                  "app": {
                    "signingConfigs": [
                      {
                        "name": "debug",
                        "type": "HarmonyOS",
                        "material": {
                          "storeFile": "C:/Users/user/.ohos/config/win.p12"
                        }
                      }
                    ],
                    "products": [
                      { "name": "debug", "signingConfig": "debug", "targetSdkVersion": "6.1.1(24)" }
                    ]
                  }
                }
                """;
        String localBlock = """
                "signingConfigs": [
                      {
                        "name": "debug",
                        "type": "HarmonyOS",
                        "material": {
                          "storeFile": "/Users/me/.ohos/config/mac.p12"
                        }
                      }
                    ]""";
        String merged = HarmonyLocalSigning.replaceSigningConfigsBlock(remote, localBlock);
        assertThat(merged).contains("/Users/me/.ohos/config/mac.p12");
        assertThat(merged).doesNotContain("C:/Users/user");
        assertThat(merged).contains("targetSdkVersion");
    }

    @Test
    void rememberAndRestoreRoundTrip() throws Exception {
        Path project = tmp.resolve("SteamESports");
        Files.createDirectories(project);
        Path p12 = tmp.resolve("debug.p12");
        Files.writeString(p12, "x");
        String local = profileJson(p12.toString(), "debug", "debug");
        Path profile = project.resolve("build-profile.json5");
        Files.writeString(profile, local, StandardCharsets.UTF_8);

        HarmonyLocalSigning.rememberIfLocal(project);
        Path cache = HarmonyLocalSigning.cacheFile(project);
        assertThat(cache).startsWith(tmp);
        assertThat(Files.isRegularFile(cache)).isTrue();

        Files.writeString(profile, local.replace(p12.toString(), "C:/Users/user/win.p12"), StandardCharsets.UTF_8);
        assertThat(HarmonyLocalSigning.hasUsableLocalStore(project)).isFalse();

        assertThat(HarmonyLocalSigning.restore(project)).isTrue();
        assertThat(Files.readString(profile)).contains(p12.toString());
        assertThat(HarmonyLocalSigning.restore(project)).isFalse();
    }

    @Test
    void missingProductSigningConfigReusesLocalOne() throws Exception {
        Path p12 = tmp.resolve("default.p12");
        Files.writeString(p12, "x");
        String json = profileJson(p12.toString(), "default", "debug");

        String next = HarmonyLocalSigning.ensureProductSigningAliases(json);

        String block = HarmonyLocalSigning.extractSigningConfigsBlock(next);
        assertThat(HarmonyLocalSigning.configObjects(block)).hasSize(2);
        assertThat(block).contains("\"name\": \"debug\"").contains("\"name\": \"default\"");
        assertThat(HarmonyLocalSigning.ensureProductSigningAliases(next)).isEqualTo(next);
    }

    @Test
    void profileOfFindsNamedSigningConfig() throws Exception {
        Path p12 = tmp.resolve("default.p12");
        Path p7b = tmp.resolve("default.p7b");
        Files.writeString(p12, "x");
        Files.writeString(p7b, "x");
        String json = profileJson(p12.toString(), "default", "default")
                .replace("\"storeFile\"", "\"profile\": \"" + p7b + "\",\n          \"storeFile\"");

        assertThat(HarmonyLocalSigning.profileOf(json, "default")).isEqualTo(p7b);
        assertThat(HarmonyLocalSigning.profileOf(json, "debug")).isNull();
    }

    private static String profileJson(String storeFile, String signingName, String productSigning) {
        return """
                {
                  "app": {
                    "signingConfigs": [
                      {
                        "name": "%s",
                        "type": "HarmonyOS",
                        "material": {
                          "storeFile": "%s"
                        }
                      }
                    ],
                    "products": [ { "name": "debug", "signingConfig": "%s" } ]
                  }
                }
                """.formatted(signingName, storeFile, productSigning);
    }
}
