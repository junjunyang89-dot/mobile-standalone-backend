package com.mobile.standalone.service.apitest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 按分支读源码，不看工作区。
 * <p>
 * 本地仓库平时停在 testing 或某个需求分支上，还常带着没提交的改动；直接扫目录，
 * 扫出来的是「当前分支 + 本地改动」，跟「master 上有哪些接口」是两回事。
 * 这里只做三件只读的事：{@code git fetch} 更新远程分支、{@code git rev-parse} 定位提交、
 * {@code git archive} 把那个提交的文件导到缓存目录——不切分支、不碰工作区。
 */
@Slf4j
@Service
public class GitSnapshotService {

    /** 同一个仓库多久内不重复 fetch：体检面板一打开就要读源码，每次都 fetch 太慢 */
    private static final long FETCH_THROTTLE_MS = 10 * 60 * 1000L;
    private static final int KEEP_PER_REPO = 3;

    private final Path baseDir;
    private final Map<Path, Long> lastFetch = new ConcurrentHashMap<>();
    private final Map<Path, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public GitSnapshotService(@Value("${app.data-dir:${user.home}/.mobile-standalone/data}") String dataDir) {
        this.baseDir = Path.of(dataDir, "source-snapshots");
    }

    GitSnapshotService(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * @param dir       导出来的源码目录（只读用）
     * @param ref       人给的分支名，如 origin/master
     * @param sha       实际读的提交
     * @param committed 那个提交的时间
     * @param fetch     fetch 的结果；null＝这次没 fetch（节流内）
     */
    public record Snapshot(Path dir, String ref, String sha, LocalDateTime committed, String fetch) {
        public String shortSha() {
            return sha == null ? null : sha.substring(0, Math.min(10, sha.length()));
        }
    }

    public record FetchResult(boolean ok, String message) {
    }

    /** 一个仓库的基线分支：优先远程的 {@code origin/<base>}，没有就退到本地同名分支 */
    public String baseRef(Path repo, String baseBranch) {
        String b = baseBranch == null || baseBranch.isBlank() ? "master" : baseBranch.trim();
        if (b.startsWith("origin/")) {
            return b;
        }
        for (String candidate : List.of("origin/" + b, b, "origin/main", "main")) {
            if (resolve(repo, candidate) != null) {
                return candidate;
            }
        }
        return "origin/" + b;
    }

    public FetchResult fetch(Path repo, boolean force) {
        Path key = repo.toAbsolutePath().normalize();
        Long at = lastFetch.get(key);
        if (!force && at != null && System.currentTimeMillis() - at < FETCH_THROTTLE_MS) {
            return null;
        }
        Run r = git(repo, 90, "fetch", "--prune", "--quiet", "origin");
        lastFetch.put(key, System.currentTimeMillis());
        if (r.code == 0) {
            return new FetchResult(true, "已从远程更新");
        }
        String msg = r.out.isBlank() ? "git fetch 失败" : r.out.trim();
        log.warn("[git-snapshot] fetch 失败 {}: {}", repo, msg);
        return new FetchResult(false, "fetch 失败，用的是本地已有的远程分支：" + abbreviate(msg, 200));
    }

    /** 分支 / 提交 → 完整 sha；不存在返回 null */
    public String resolve(Path repo, String ref) {
        if (ref == null || ref.isBlank() || ref.startsWith("-")) {
            return null;
        }
        Run r = git(repo, 15, "rev-parse", "--verify", "--quiet", ref.trim() + "^{commit}");
        String sha = r.out.trim();
        return r.code == 0 && sha.matches("[0-9a-f]{40}") ? sha : null;
    }

    /** 两个分支的共同祖先；和 {@code git diff a...b} 的起点一致。找不到返回 null */
    public String mergeBase(Path repo, String a, String b) {
        if (a == null || b == null || a.startsWith("-") || b.startsWith("-")) {
            return null;
        }
        Run r = git(repo, 30, "merge-base", a.trim(), b.trim());
        String sha = r.out.trim();
        return r.code == 0 && sha.matches("[0-9a-f]{40}") ? sha : null;
    }

    public LocalDateTime commitTime(Path repo, String sha) {
        Run r = git(repo, 15, "show", "-s", "--format=%ct", sha);
        try {
            long sec = Long.parseLong(r.out.trim());
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.systemDefault());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 导出某个分支的源码。同一个提交导过就直接复用。
     *
     * @param fetchFirst 先 fetch（节流）；fetch 失败不算失败，照样用本地已有的远程分支
     * @throws IllegalArgumentException 分支不存在、导出失败，消息是给人看的
     */
    public Snapshot snapshot(Path repo, String ref, boolean fetchFirst, boolean forceFetch) {
        if (repo == null || !Files.isDirectory(repo.resolve(".git")) && !Files.isRegularFile(repo.resolve(".git"))) {
            throw new IllegalArgumentException("不是 git 仓库：" + repo);
        }
        String fetchMsg = null;
        if (fetchFirst) {
            FetchResult f = fetch(repo, forceFetch);
            fetchMsg = f == null ? null : f.message();
        }
        String sha = resolve(repo, ref);
        if (sha == null) {
            throw new IllegalArgumentException("仓库里找不到分支 " + ref + "（" + repo.getFileName() + "）");
        }
        Path dir = baseDir.resolve(safeName(repo) + "-" + sha.substring(0, 12));
        synchronized (locks.computeIfAbsent(repo.toAbsolutePath().normalize(), k -> new Object())) {
            if (!Files.isRegularFile(dir.resolve(".snapshot-ok"))) {
                export(repo, sha, dir);
                prune(repo, dir);
            }
        }
        return new Snapshot(dir, ref, sha, commitTime(repo, sha), fetchMsg);
    }

    private void export(Path repo, String sha, Path dir) {
        Path tar = null;
        try {
            Files.createDirectories(baseDir);
            deleteTree(dir);
            Files.createDirectories(dir);
            tar = Files.createTempFile(baseDir, "export-", ".tar");
            Run a = git(repo, 300, "archive", "--format=tar", "--output=" + tar, sha);
            if (a.code != 0) {
                throw new IllegalArgumentException("导出源码失败：" + abbreviate(a.out.trim(), 200));
            }
            Run x = run(baseDir, 300, List.of("tar", "-xf", tar.toString(), "-C", dir.toString()));
            if (x.code != 0) {
                throw new IllegalArgumentException("解包源码失败：" + abbreviate(x.out.trim(), 200));
            }
            Files.writeString(dir.resolve(".snapshot-ok"), sha);
        } catch (IOException e) {
            throw new IllegalArgumentException("导出源码失败：" + e.getMessage());
        } finally {
            if (tar != null) {
                try {
                    Files.deleteIfExists(tar);
                } catch (IOException ignore) {
                    // 临时文件删不掉不影响结果
                }
            }
        }
    }

    /** 每个仓库只留最近几份，老的删掉 */
    private void prune(Path repo, Path keep) {
        String prefix = safeName(repo) + "-";
        try (Stream<Path> s = Files.list(baseDir)) {
            List<Path> mine = new ArrayList<>(s.filter(p -> Files.isDirectory(p)
                    && p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().length() == prefix.length() + 12).toList());
            mine.sort(Comparator.comparingLong(GitSnapshotService::mtime).reversed());
            for (int i = 0; i < mine.size(); i++) {
                if (i >= KEEP_PER_REPO && !mine.get(i).equals(keep)) {
                    deleteTree(mine.get(i));
                }
            }
        } catch (IOException e) {
            log.debug("[git-snapshot] 清理旧快照失败: {}", e.getMessage());
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String safeName(Path repo) {
        String n = repo.toAbsolutePath().normalize().getFileName().toString();
        return n.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private record Run(int code, String out) {
    }

    private static Run git(Path repo, int timeoutSec, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=false", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        return run(repo, timeoutSec, cmd);
    }

    private static Run run(Path cwd, int timeoutSec, List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            if (Files.isDirectory(cwd)) {
                pb.directory(cwd.toFile());
            }
            Process p = pb.start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread pump = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    in.transferTo(buf);
                } catch (IOException ignore) {
                    // 进程被杀时流会断
                }
            });
            pump.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                pump.join(1000);
                return new Run(-1, "超时（" + timeoutSec + " 秒）");
            }
            pump.join(5000);
            return new Run(p.exitValue(), buf.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new Run(-1, String.valueOf(e.getMessage()));
        }
    }
}
