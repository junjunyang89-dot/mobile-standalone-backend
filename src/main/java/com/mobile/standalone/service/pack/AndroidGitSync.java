package com.mobile.standalone.service.pack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 打包前把完美电竞安卓仓库切到 {@code testing}，并快进到 {@code origin/testing}。
 * <p>
 * 工作区脏或本地和远端分叉时直接失败，不 {@code reset --hard}，避免把人未提交的改动抹掉。
 */
public final class AndroidGitSync {

    public record Result(String branch, String commit) {
    }

    private AndroidGitSync() {
    }

    public static Path gitRoot(Path projectDir, String configured) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(p.resolve(".git")) || Files.isRegularFile(p.resolve(".git"))) {
                return p;
            }
        }
        try {
            String out = runGit(projectDir, 10, "rev-parse", "--show-toplevel").trim();
            if (!out.isBlank()) {
                return Path.of(out).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            // 下面再按工程目录判断
        }
        Path parent = projectDir.toAbsolutePath().normalize().getParent();
        if (parent != null && Files.isDirectory(parent.resolve(".git"))) {
            return parent;
        }
        if (Files.isDirectory(projectDir.resolve(".git"))) {
            return projectDir.toAbsolutePath().normalize();
        }
        return null;
    }

    public static Result syncTesting(Path gitDir, String branch) throws Exception {
        String b = (branch == null || branch.isBlank()) ? AndroidPackCommand.BRANCH : branch.trim();
        if (!Files.isDirectory(gitDir)) {
            throw new IllegalStateException("找不到安卓 Git 仓库：" + gitDir);
        }
        String dirty = runGit(gitDir, 15, "status", "--porcelain");
        String blocking = blockingDirtySummary(dirty);
        if (blocking != null) {
            throw new IllegalStateException("安卓工程有未提交改动，不敢自动切到 "
                    + b + "。请先处理：" + gitDir + " → " + blocking);
        }
        runGit(gitDir, 180, "fetch", "origin", b);
        String local = runGit(gitDir, 10, "branch", "--list", b).trim();
        if (local.isBlank()) {
            runGit(gitDir, 30, "checkout", "-b", b, "origin/" + b);
        } else {
            runGit(gitDir, 30, "checkout", b);
            try {
                runGit(gitDir, 30, "merge", "--ff-only", "origin/" + b);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("本地 " + b + " 和 origin/" + b
                        + " 分叉了，不敢覆盖。请先处理这个仓库：" + gitDir);
            }
        }
        String head = runGit(gitDir, 10, "rev-parse", "--abbrev-ref", "HEAD").trim();
        if (!b.equals(head)) {
            throw new IllegalStateException("切分支失败，当前在 " + head + "，要的是 " + b);
        }
        String commit = runGit(gitDir, 10, "rev-parse", "--short", "HEAD").trim();
        return new Result(head, commit);
    }

    /**
     * 本地抓包用的 debug 配置 / .gitignore 改动不挡打包；其它脏文件仍拦截。
     * @return 需要人处理的路径摘要；没有阻塞则 null
     */
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
            if (isLocalCaptureNoise(path)) {
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

    private static boolean isLocalCaptureNoise(String path) {
        String p = path.replace('\\', '/');
        if (".gitignore".equals(p)) {
            return true;
        }
        if (p.contains("/src/debug/") || p.startsWith("SteamESports/app/src/debug/")) {
            return true;
        }
        return p.endsWith("network_security_config.xml") && p.contains("debug");
    }

    static String runGit(Path dir, long timeoutSeconds, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(AndroidPackTools.git());
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IllegalStateException("git 超时：" + String.join(" ", args));
        }
        if (proc.exitValue() != 0) {
            String msg = out == null ? "" : out.trim();
            if (msg.length() > 400) {
                msg = msg.substring(0, 400);
            }
            throw new IllegalStateException("git " + args[0] + " 失败" + (msg.isEmpty() ? "" : "：" + msg));
        }
        return out == null ? "" : out;
    }
}
