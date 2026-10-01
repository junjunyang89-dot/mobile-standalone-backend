package com.mobile.standalone.service.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidPackCommandTest {

    @Test
    void 固定testing分支debug包() {
        assertEquals("testing", AndroidPackCommand.BRANCH);
        assertEquals("debug", AndroidPackCommand.PRODUCT);
        assertEquals("APK", AndroidPackCommand.OUTPUT_TYPE);
        List<String> args = AndroidPackCommand.gradleArgs();
        assertEquals(":app:assembleDebug", args.get(0));
        assertTrue(args.contains("--no-daemon"));
    }
}

class AndroidPackArtifactsTest {

    @TempDir
    Path tmp;

    @Test
    void 优先app_debug_apk() throws Exception {
        Path dir = tmp.resolve("app/build/outputs/apk/debug");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("other.apk"), "old");
        Path expected = dir.resolve("app-debug.apk");
        Files.writeString(expected, "debug");
        assertEquals(expected, AndroidPackArtifacts.findDebugApk(tmp).orElseThrow());
    }

    @Test
    void 没有标准名就取最新apk() throws Exception {
        Path dir = tmp.resolve("app/build/outputs/apk/debug");
        Files.createDirectories(dir);
        Path older = dir.resolve("app-debug-1.apk");
        Path newer = dir.resolve("app-debug-2.apk");
        Files.writeString(older, "1");
        Thread.sleep(20);
        Files.writeString(newer, "2");
        assertEquals(newer, AndroidPackArtifacts.findDebugApk(tmp).orElseThrow());
    }
}

class AndroidBuildProfileTest {

    @Test
    void 从config_gradle读版本() {
        String gradle = """
                ext {
                    android = [
                            versionCode      : 223,
                            versionName      : '4.1.6.223'
                    ]
                }
                """;
        assertEquals("4.1.6.223", AndroidBuildProfile.versionNameFrom(gradle));
        assertEquals("223", AndroidBuildProfile.versionCodeFrom(gradle));
    }

    @Test
    void 能读真实工程版本() {
        Path project = Path.of(System.getProperty("user.home"),
                "projects/qa_coding/app_coding/app_esports_android/SteamESports");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(project.resolve("config.gradle")));
        String name = AndroidBuildProfile.versionName(project);
        String code = AndroidBuildProfile.versionCode(project);
        assertTrue(name != null && !name.isBlank());
        assertTrue(code != null && code.matches("\\d+"));
    }
}

class AndroidGitSyncTest {

    @TempDir
    Path tmp;

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 脏工作区拒绝切分支() throws Exception {
        Path repo = initRepo(tmp.resolve("dirty"));
        Files.writeString(repo.resolve("dirty.txt"), "x");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AndroidGitSync.syncTesting(repo, "testing"));
        assertTrue(e.getMessage().contains("未提交"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 从origin快进到testing() throws Exception {
        Path origin = tmp.resolve("origin");
        initRepo(origin);
        git(origin, "checkout", "-b", "testing");
        Files.writeString(origin.resolve("app.txt"), "testing-build");
        git(origin, "add", "app.txt");
        git(origin, "commit", "-m", "testing");
        git(origin, "checkout", "master");

        Path clone = tmp.resolve("clone");
        git(tmp, "clone", origin.toString(), clone.toString());
        gitConfig(clone);

        AndroidGitSync.Result r = AndroidGitSync.syncTesting(clone, "testing");
        assertEquals("testing", r.branch());
        assertTrue(Files.readString(clone.resolve("app.txt")).contains("testing-build"));
        assertEquals("testing", git(clone, "rev-parse", "--abbrev-ref", "HEAD").trim());
    }

    private static Path initRepo(Path dir) throws Exception {
        Files.createDirectories(dir);
        git(dir, "init", "-b", "master");
        gitConfig(dir);
        Files.writeString(dir.resolve("README"), "init");
        git(dir, "add", "README");
        git(dir, "commit", "-m", "init");
        return dir;
    }

    private static void gitConfig(Path dir) throws Exception {
        git(dir, "config", "user.email", "pack-test@example.com");
        git(dir, "config", "user.name", "pack-test");
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git 超时 " + String.join(" ", args));
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git 失败 " + String.join(" ", args) + ": " + out);
        }
        return out;
    }
}
