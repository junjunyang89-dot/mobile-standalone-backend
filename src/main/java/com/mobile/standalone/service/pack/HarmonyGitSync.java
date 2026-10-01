package com.mobile.standalone.service.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 鸿蒙打包前对齐到 {@code origin/&lt;branch&gt;}。
 * <p>
 * 本机 {@code dev} 经常被误 pull 进 {@code dev221}/{@code dev222} 之类，和远端分叉后
 * {@code merge --ff-only} 会失败。打包要的是远端那份，所以用
 * {@code checkout -B &lt;branch&gt; origin/&lt;branch&gt;} 强制对齐；
 * 未提交改动仍拦截（{@code build-profile.json5} 本机签名路径除外）。
 */
public final class HarmonyGitSync {

    public static final String DEFAULT_BRANCH = "dev";

    public record Result(String branch, String commit) {
    }

    private HarmonyGitSync() {
    }

    public static Path gitRoot(Path projectDir, String configured) {
        return AndroidGitSync.gitRoot(projectDir, configured);
    }

    public static Result sync(Path gitDir, String branch) throws Exception {
        String b = normalizeBranch(branch, DEFAULT_BRANCH);
        if (!Files.isDirectory(gitDir)) {
            throw new IllegalStateException("找不到鸿蒙 Git 仓库：" + gitDir);
        }
        String dirty = AndroidGitSync.runGit(gitDir, 15, "status", "--porcelain");
        String blocking = blockingDirtySummary(dirty);
        if (blocking != null) {
            throw new IllegalStateException("鸿蒙工程有未提交改动，不敢自动切到 "
                    + b + "。请先处理：" + gitDir + " → " + blocking);
        }
        AndroidGitSync.runGit(gitDir, 180, "fetch", "origin", b);
        // -B：本地同名分支若已分叉，直接指到 origin，保证打的就是远端
        AndroidGitSync.runGit(gitDir, 30, "checkout", "-B", b, "origin/" + b);
        String head = AndroidGitSync.runGit(gitDir, 10, "rev-parse", "--abbrev-ref", "HEAD").trim();
        if (!b.equals(head)) {
            throw new IllegalStateException("切分支失败，当前在 " + head + "，要的是 " + b);
        }
        String commit = AndroidGitSync.runGit(gitDir, 10, "rev-parse", "--short", "HEAD").trim();
        return new Result(head, commit);
    }

    /**
     * 列 origin 上的远程分支短名（不含 {@code origin/}）。失败返回空列表，不挡 options。
     */
    public static List<String> listRemoteBranches(Path gitDir) {
        if (gitDir == null || !Files.isDirectory(gitDir)) {
            return List.of();
        }
        try {
            String out = AndroidGitSync.runGit(gitDir, 20, "branch", "-r");
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                if (line.isBlank() || line.contains("->")) {
                    continue;
                }
                if (line.startsWith("origin/")) {
                    line = line.substring("origin/".length());
                }
                if (line.isBlank() || "HEAD".equals(line)) {
                    continue;
                }
                try {
                    names.add(normalizeBranch(line, line));
                } catch (IllegalArgumentException ignored) {
                    // 跳过奇怪名字
                }
            }
            return new ArrayList<>(names);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String normalizeBranch(String raw, String fallback) {
        String b = (raw == null || raw.isBlank())
                ? (fallback == null || fallback.isBlank() ? DEFAULT_BRANCH : fallback.trim())
                : raw.trim();
        if (b.startsWith("origin/")) {
            b = b.substring("origin/".length()).trim();
        }
        if (b.isBlank() || b.contains("..") || b.startsWith("-") || b.contains("\\")
                || !b.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("非法分支名：" + raw);
        }
        return b;
    }

    /** 本机签名路径改动不挡同步；其它脏文件仍拦截。 */
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
            if (path == null || path.isBlank()) {
                continue;
            }
            if (isLocalSigningNoise(path)) {
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

    private static boolean isLocalSigningNoise(String path) {
        String p = path.replace('\\', '/');
        return p.endsWith("build-profile.json5");
    }
}
