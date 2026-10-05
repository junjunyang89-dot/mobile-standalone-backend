package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 按项目的 GitLab 地址把代码同步到本机：没有本地仓库就 clone 并回填 repo_path，有就 fetch。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectRepoSync {

    private static final Pattern GIT_URL = Pattern.compile("^(https?://|ssh://|git@)\\S+$");

    private final ApiProjectMapper projectMapper;

    /** clone 的落地目录，每个仓库一个子目录（名字取地址最后一段） */
    @Value("${api-change.repos-dir:${user.home}/mobile-standalone-repos}")
    private String reposDir;

    public static boolean looksLikeGitUrl(String url) {
        return url != null && GIT_URL.matcher(url.trim()).matches();
    }

    /** http://host/group/name.git、git@host:group/name.git → host/group/name，比较是不是同一个仓库用 */
    static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z+]+://", "");
        s = s.replaceFirst("^[^@/]+@", "");
        if (!url.contains("://")) {
            s = s.replaceFirst(":", "/");
        }
        s = s.replaceAll("/+$", "");
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }

    static String repoNameOf(String url) {
        String n = normalizeUrl(url);
        String name = n.substring(n.lastIndexOf('/') + 1);
        if (name.isBlank() || name.contains("..")) {
            throw new IllegalArgumentException("从 GitLab 地址里取不出仓库名: " + url);
        }
        return name;
    }

    public Map<String, Object> sync(String code) {
        ApiProject p = projectMapper.findByCode(code);
        if (p == null) {
            throw new IllegalArgumentException("项目不存在: " + code);
        }
        String gitUrl = blankToNull(p.getGitUrl());
        Path local = isGitRepo(p.getRepoPath()) ? Path.of(p.getRepoPath().trim()) : null;

        if (local == null) {
            if (gitUrl == null) {
                throw new IllegalArgumentException("先在项目里填 GitLab 地址");
            }
            Path target = Path.of(reposDir).toAbsolutePath().normalize().resolve(repoNameOf(gitUrl));
            String action;
            if (isGitRepo(target.toString())) {
                requireSameOrigin(target, gitUrl);
                fetch(target);
                action = "FETCH";
            } else {
                if (Files.exists(target)) {
                    throw new IllegalArgumentException("目标目录已存在但不是 git 仓库，先手动处理: " + target);
                }
                clone(gitUrl, target);
                action = "CLONE";
            }
            projectMapper.updateRepoPath(code, target.toString());
            return result(action, target);
        }

        if (gitUrl != null) {
            requireSameOrigin(local, gitUrl);
        }
        fetch(local);
        return result("FETCH", local);
    }

    /**
     * 对比分析前顺手拉一次。拉不下来（没网、没凭据）不拦分析，只把原因带回去：
     * 本地已有仓库时用旧代码比也比不比强，但页面上得说清楚是旧的。
     * {@code maxAgeMs} 内拉过的不再拉，选项目、点分析各拉一遍没有意义。
     */
    public Map<String, Object> syncQuietly(String code, long maxAgeMs) {
        String key = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        Long last = lastSynced.get(key);
        long now = System.currentTimeMillis();
        if (last != null && now - last < maxAgeMs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("action", "RECENT");
            m.put("message", "刚拉过最新代码");
            return m;
        }
        try {
            Map<String, Object> r = new LinkedHashMap<>(sync(code));
            r.put("ok", true);
            lastSynced.put(key, now);
            return r;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("action", "FAILED");
            m.put("message", "拉取最新代码失败，用的是本地已有代码：" + e.getMessage());
            return m;
        }
    }

    private final Map<String, Long> lastSynced = new java.util.concurrent.ConcurrentHashMap<>();

    private static Map<String, Object> result(String action, Path repo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("repoPath", repo.toString());
        m.put("message", ("CLONE".equals(action) ? "已 clone 到 " : "已拉取最新：") + repo);
        return m;
    }

    private void requireSameOrigin(Path repo, String gitUrl) {
        String origin = run(repo, 30, "git", "remote", "get-url", "origin").trim();
        if (!normalizeUrl(origin).equals(normalizeUrl(gitUrl))) {
            throw new IllegalArgumentException("本地仓库 " + repo + " 的 origin 是 " + origin
                    + "，跟填的 GitLab 地址 " + gitUrl + " 不是同一个仓库");
        }
    }

    private void fetch(Path repo) {
        run(repo, 300, "git", "fetch", "--all", "--prune");
    }

    private void clone(String gitUrl, Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("建目录失败: " + target.getParent() + "，" + e.getMessage());
        }
        log.info("[repo-sync] clone {} → {}", gitUrl, target);
        run(target.getParent(), 1800, "git", "clone", gitUrl, target.toString());
    }

    private static boolean isGitRepo(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Path p = Path.of(path.trim());
        return Files.isDirectory(p) && Files.exists(p.resolve(".git"));
    }

    /** 不让 git 弹账号密码提示：没有可用凭据时直接失败，而不是把请求挂住 */
    private static String run(Path dir, long timeoutSec, String... cmd) {
        List<String> command = new ArrayList<>(List.of(cmd));
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
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
                throw new IllegalStateException(String.join(" ", cmd) + " 超时（" + timeoutSec + " 秒）");
            }
            reader.join(5000);
            String out = buf.toString(StandardCharsets.UTF_8);
            if (proc.exitValue() != 0) {
                throw new IllegalStateException(String.join(" ", cmd.length > 2 ? List.of(cmd).subList(0, 2) : List.of(cmd))
                        + " 失败：" + tail(out));
            }
            return out;
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
