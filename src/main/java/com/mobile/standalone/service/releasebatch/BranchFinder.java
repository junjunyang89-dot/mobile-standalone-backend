package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.entity.ApiProject;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 在项目管理的所有仓库里找这一单的分支：和 JIRA 备注里同名的分支，或分支名里带单号的。
 * 开发只在备注里写了一个后端、其实 H5 / 客户端 / 别的后端也拉了同名分支时，靠这个补上。
 * 用 {@code git ls-remote} 查远程，不需要本地先 clone。
 */
@Slf4j
final class BranchFinder {

    private static final int TIMEOUT_SEC = 20;
    private static final int MAX_PER_REPO = 3;
    /** 「全部分析」时每单都要查一遍所有仓库，远程分支列表缓存一会儿 */
    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(long at, List<String> heads) {
    }

    record Hit(ApiProject project, String branch) {
    }

    record Result(List<Hit> hits, int checked, List<String> failed) {
    }

    private BranchFinder() {
    }

    static Result find(Collection<ApiProject> projects, Set<String> branches, String issueKey) {
        List<ApiProject> todo = projects.stream()
                .filter(p -> !isBlank(remoteOf(p)))
                .toList();
        List<Hit> hits = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        if (todo.isEmpty() || (branches.isEmpty() && isBlank(issueKey))) {
            return new Result(hits, 0, failed);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, todo.size()));
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (ApiProject p : todo) {
                futures.add(pool.submit(() -> cachedHeads(p)));
            }
            for (int i = 0; i < todo.size(); i++) {
                ApiProject p = todo.get(i);
                try {
                    for (String b : pick(futures.get(i).get(TIMEOUT_SEC + 5L, TimeUnit.SECONDS), branches, issueKey)) {
                        hits.add(new Hit(p, b));
                    }
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    failed.add(p.getCode() + "：" + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(hits, todo.size(), failed);
    }

    /** 同名的排前面，其次是分支名里带单号的；一个仓库最多取几条，防止单号太短误中一堆 */
    static List<String> pick(List<String> heads, Set<String> branches, String issueKey) {
        Set<String> out = new LinkedHashSet<>();
        for (String h : heads) {
            if (branches.contains(h)) {
                out.add(h);
            }
        }
        if (!isBlank(issueKey)) {
            Pattern key = Pattern.compile("(^|[^A-Za-z0-9])" + Pattern.quote(issueKey.toLowerCase(Locale.ROOT)) + "($|[^0-9])");
            for (String h : heads) {
                if (key.matcher(h.toLowerCase(Locale.ROOT)).find()) {
                    out.add(h);
                }
            }
        }
        return out.stream().limit(MAX_PER_REPO).toList();
    }

    /** 解析 {@code git ls-remote --heads} 的输出：每行「sha<TAB>refs/heads/分支」 */
    static List<String> heads(String lsRemote) {
        List<String> out = new ArrayList<>();
        for (String line : (lsRemote == null ? "" : lsRemote).split("\\R")) {
            int i = line.indexOf("refs/heads/");
            if (i >= 0) {
                out.add(line.substring(i + "refs/heads/".length()).trim());
            }
        }
        return out;
    }

    /** 本地有仓库就用它配的 origin（凭据跟着走），没有再用项目管理里的地址 */
    private static String remoteOf(ApiProject p) {
        if (Boolean.FALSE.equals(p.getEnabled())) {
            return null;
        }
        if (!isBlank(p.getRepoPath()) && Files.isDirectory(Path.of(p.getRepoPath().trim(), ".git"))) {
            return "origin";
        }
        return isBlank(p.getGitUrl()) ? null : p.getGitUrl().trim();
    }

    private static List<String> cachedHeads(ApiProject p) throws Exception {
        String key = p.getCode() + "|" + remoteOf(p);
        Cached c = CACHE.get(key);
        if (c != null && System.currentTimeMillis() - c.at() < CACHE_MS) {
            return c.heads();
        }
        List<String> heads = heads(lsRemote(p));
        CACHE.put(key, new Cached(System.currentTimeMillis(), heads));
        return heads;
    }

    private static String lsRemote(ApiProject p) throws Exception {
        String remote = remoteOf(p);
        Path dir = "origin".equals(remote) ? Path.of(p.getRepoPath().trim()) : Path.of(System.getProperty("java.io.tmpdir"));
        Path out = Files.createTempFile("ls-remote-", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "ls-remote", "--heads", remote)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(out.toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process proc = pb.start();
            if (!proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IllegalStateException("查远程分支超时（" + TIMEOUT_SEC + "s）");
            }
            String text = Files.readString(out, StandardCharsets.UTF_8);
            if (proc.exitValue() != 0) {
                String msg = text.trim();
                throw new IllegalStateException("查远程分支失败：" + (msg.length() > 120 ? msg.substring(0, 120) : msg));
            }
            return text;
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
