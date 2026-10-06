package com.mobile.standalone.service.releasemerge;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 上线专用目录里的 master 仓库。只 clone / fetch / 读，不切分支、不合并、不推送——
 * 合并走 GitLab，这里只负责「合之前看清楚、合之后核对」。
 */
@Slf4j
final class MasterRepo {

    record Out(int code, String text) {
        boolean ok() {
            return code == 0;
        }
    }

    record Commit(String sha, String author, String subject, String time) {
    }

    private MasterRepo() {
    }

    /** 没有就按 GitLab 地址只拉目标分支；有就只 fetch 目标分支 */
    static Path ensure(Path root, String gitUrl, String target) {
        String name = repoName(gitUrl);
        Path dir = root.resolve(name).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException("仓库名不对：" + name);
        }
        if (Files.isDirectory(dir.resolve(".git"))) {
            String origin = must(run(dir, 30, "git", "remote", "get-url", "origin"), "读 origin").trim();
            if (!sameRepo(origin, gitUrl)) {
                throw new IllegalStateException("上线目录里的 " + dir + " 不是这个项目的仓库（origin 是 " + origin + "）");
            }
            fetch(dir, target);
            fastForward(dir, target);
            return dir;
        }
        if (Files.exists(dir)) {
            throw new IllegalStateException("上线目录里已有 " + dir + "，但不是 git 仓库，先手动处理");
        }
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            throw new IllegalStateException("建上线目录失败：" + root + "，" + e.getMessage());
        }
        log.info("[release-merge] clone {} ({}) → {}", gitUrl, target, dir);
        must(run(root, 1800, "git", "clone", "--branch", target, "--single-branch", "--no-tags", gitUrl, dir.toString()),
                "拉取仓库");
        return dir;
    }

    static void fetch(Path dir, String branch) {
        must(run(dir, 300, "git", "fetch", "--no-tags", "origin",
                "+refs/heads/" + branch + ":refs/remotes/origin/" + branch), "拉取 " + branch);
    }

    /** 远程没有这个分支时返回 false，其它失败照常抛 */
    static boolean fetchBranch(Path dir, String branch) {
        Out o = run(dir, 300, "git", "fetch", "--no-tags", "origin",
                "+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
        if (o.ok()) {
            return true;
        }
        String t = o.text().toLowerCase(Locale.ROOT);
        if (t.contains("couldn't find remote ref") || t.contains("could not find remote ref")) {
            return false;
        }
        throw new IllegalStateException("拉取分支失败：" + tail(o.text()));
    }

    /** 目录里一直是 master：工作区干净且停在目标分支时快进一下，否则不动（核对只看远程引用） */
    private static void fastForward(Path dir, String target) {
        Out head = run(dir, 30, "git", "rev-parse", "--abbrev-ref", "HEAD");
        Out status = run(dir, 30, "git", "status", "--porcelain");
        if (head.ok() && target.equals(head.text().trim()) && status.ok() && status.text().isBlank()) {
            run(dir, 60, "git", "merge", "--ff-only", "origin/" + target);
        }
    }

    static String sha(Path dir, String ref) {
        return must(run(dir, 30, "git", "rev-parse", "--verify", ref + "^{commit}"), "读提交").trim();
    }

    static boolean isAncestor(Path dir, String a, String b) {
        Out o = run(dir, 60, "git", "merge-base", "--is-ancestor", a, b);
        if (o.code() == 0) {
            return true;
        }
        if (o.code() == 1) {
            return false;
        }
        throw new IllegalStateException("比较提交失败：" + tail(o.text()));
    }

    static int count(Path dir, String range) {
        return Integer.parseInt(must(run(dir, 60, "git", "rev-list", "--count", range), "数提交").trim());
    }

    static List<Commit> commits(Path dir, String range, int limit) {
        String out = must(run(dir, 60, "git", "log", "--no-merges", "-n", String.valueOf(limit),
                "--format=%H%x1f%an%x1f%s%x1f%cI", range), "读提交");
        List<Commit> list = new ArrayList<>();
        for (String l : out.split("\\R")) {
            String[] p = l.split("\u001f", -1);
            if (p.length >= 4) {
                list.add(new Commit(p[0], p[1], p[2], p[3]));
            }
        }
        return list;
    }

    static List<String> mergeSubjects(Path dir, String range) {
        return lines(must(run(dir, 60, "git", "log", "--merges", "-n", "200", "--format=%s", range), "读合并记录"));
    }

    static List<String> nameStatus(Path dir, String base, String head) {
        return lines(must(run(dir, 60, "git", "diff", "--name-status", "--no-renames", base + "..." + head), "读改动文件"));
    }

    static List<String> diffLines(Path dir, String base, String head, String... pathspecs) {
        List<String> cmd = new ArrayList<>(List.of("git", "diff", "-U0", base + "..." + head, "--"));
        cmd.addAll(Arrays.asList(pathspecs));
        Out o = run(dir, 60, cmd.toArray(String[]::new));
        return o.ok() ? lines(o.text()) : List.of();
    }

    static String show(Path dir, String ref, String path, int maxChars) {
        Out o = run(dir, 30, "git", "show", ref + ":" + path);
        if (!o.ok()) {
            return "";
        }
        return o.text().length() > maxChars ? o.text().substring(0, maxChars) : o.text();
    }

    /** null = 没冲突；否则是冲突文件（可能为空列表，表示 git 说有冲突但没列文件） */
    static List<String> conflicts(Path dir, String base, String head) {
        Out o = run(dir, 120, "git", "merge-tree", "--write-tree", "--name-only", "--no-messages", base, head);
        if (o.code() == 0) {
            return null;
        }
        if (o.code() == 1) {
            List<String> ls = lines(o.text());
            return ls.size() > 1 ? new ArrayList<>(ls.subList(1, ls.size())) : new ArrayList<>();
        }
        throw new IllegalStateException("试合并失败：" + tail(o.text()));
    }

    static String repoName(String gitUrl) {
        String s = gitUrl.trim().replaceAll("/+$", "");
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        String name = s.substring(Math.max(s.lastIndexOf('/'), s.lastIndexOf(':')) + 1);
        if (name.isBlank() || name.contains("..")) {
            throw new IllegalArgumentException("从 GitLab 地址里取不出仓库名：" + gitUrl);
        }
        return name;
    }

    static boolean sameRepo(String a, String b) {
        String pa = MergeRules.projectPath(a);
        String pb = MergeRules.projectPath(b);
        return pa != null && pa.equalsIgnoreCase(pb);
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String l : s.split("\\R")) {
            if (!l.isBlank()) {
                out.add(l);
            }
        }
        return out;
    }

    private static String must(Out o, String what) {
        if (!o.ok()) {
            throw new IllegalStateException(what + "失败：" + tail(o.text()));
        }
        return o.text();
    }

    /** 不让 git 弹账号密码提示：没有可用凭据时直接失败，而不是把请求挂住 */
    static Out run(Path dir, long timeoutSec, String... cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("LC_ALL", "C");
        try {
            Process proc = pb.start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    in.transferTo(buf);
                } catch (Exception ignored) {
                    // 进程被杀时流会断
                }
            });
            reader.start();
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IllegalStateException(String.join(" ", List.of(cmd).subList(0, Math.min(2, cmd.length)))
                        + " 超时（" + timeoutSec + " 秒）");
            }
            reader.join(5000);
            return new Out(proc.exitValue(), buf.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("被中断");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("执行 git 失败（本机装了 git 吗）：" + e.getMessage());
        }
    }

    private static String tail(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 400 ? "…" + t.substring(t.length() - 400) : t;
    }
}
