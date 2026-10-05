package com.mobile.standalone.service.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * iOS 打包前对齐到 {@code origin/&lt;branch&gt;}，规则和鸿蒙一样：
 * {@code checkout -B} 指到远端，避免本机同名分支分叉打错包。
 */
public final class IosGitSync {

    private IosGitSync() {
    }

    public static Path gitRoot(Path projectDir, String configured) {
        return AndroidGitSync.gitRoot(projectDir, configured);
    }

    public static List<String> listRemoteBranches(Path gitDir) {
        return HarmonyGitSync.listRemoteBranches(gitDir);
    }

    public static String normalizeBranch(String raw, String fallback) {
        return HarmonyGitSync.normalizeBranch(raw,
                fallback == null || fallback.isBlank() ? IosPackCommand.DEFAULT_BRANCH : fallback);
    }

    public static AndroidGitSync.Result sync(Path gitDir, String branch) throws Exception {
        String b = normalizeBranch(branch, IosPackCommand.DEFAULT_BRANCH);
        if (!Files.isDirectory(gitDir)) {
            throw new IllegalStateException("找不到 iOS Git 仓库：" + gitDir);
        }
        String dirty = AndroidGitSync.runGit(gitDir, 15, "status", "--porcelain");
        String blocking = blockingDirtySummary(dirty);
        if (blocking != null) {
            throw new IllegalStateException("iOS 工程有未提交改动，不敢自动切到 "
                    + b + "。请先处理：" + gitDir + " → " + blocking);
        }
        AndroidGitSync.runGit(gitDir, 180, "fetch", "origin", b);
        AndroidGitSync.runGit(gitDir, 30, "checkout", "-B", b, "origin/" + b);
        String head = AndroidGitSync.runGit(gitDir, 10, "rev-parse", "--abbrev-ref", "HEAD").trim();
        if (!b.equals(head)) {
            throw new IllegalStateException("切分支失败，当前在 " + head + "，要的是 " + b);
        }
        String commit = AndroidGitSync.runGit(gitDir, 10, "rev-parse", "--short", "HEAD").trim();
        return new AndroidGitSync.Result(head, commit);
    }

    static String blockingDirtySummary(String porcelain) {
        if (porcelain == null || porcelain.isBlank()) {
            return null;
        }
        List<String> blocking = new ArrayList<>();
        for (String raw : porcelain.split("\n")) {
            String line = raw.stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            String path = porcelainPath(line);
            if (path == null || path.isBlank() || isLocalPackNoise(path)) {
                continue;
            }
            blocking.add(path);
        }
        if (blocking.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", blocking);
        return joined.length() > 200 ? joined.substring(0, 200) + "…" : joined;
    }

    private static String porcelainPath(String line) {
        String rest = line.length() >= 3 ? line.substring(2).trim() : line.trim();
        int arrow = rest.indexOf(" -> ");
        if (arrow >= 0) {
            return rest.substring(arrow + 4).trim();
        }
        return rest;
    }

    /** pod install / 本机 DerivedData / 上次导出目录不挡切分支 */
    static boolean isLocalPackNoise(String path) {
        String p = path.replace('\\', '/');
        if (p.startsWith("Pods/") || "/Pods".equals(p) || "Pods".equals(p) || "Podfile.lock".equals(p)) {
            return true;
        }
        if (p.startsWith("pgyer/") || "pgyer".equals(p)) {
            return true;
        }
        if (p.contains("xcuserdata") || p.endsWith(".xcuserstate") || p.contains("UserInterfaceState")) {
            return true;
        }
        if (p.startsWith("DerivedData/") || p.startsWith("build/") || ".DS_Store".equals(p)) {
            return true;
        }
        return p.endsWith(".ipa");
    }
}
