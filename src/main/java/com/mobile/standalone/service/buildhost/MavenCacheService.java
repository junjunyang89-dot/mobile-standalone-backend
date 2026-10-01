package com.mobile.standalone.service.buildhost;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SSH 到构建机看某个 Maven 坐标的本地缓存。
 * <p>
 * 密码不进配置：只走本机已经能用的 SSH 密钥（和连 Mini 库同一套）。
 */
@Slf4j
@Service
public class MavenCacheService {

    @Value("${app.build-host.ssh-user:${BUILD_SSH_USER:yangjun}}")
    private String sshUser;

    @Value("${app.build-host.ssh-host:${BUILD_SSH_HOST:192.168.10.5}}")
    private String sshHost;

    @Value("${app.build-host.ssh-port:${BUILD_SSH_PORT:22}}")
    private int sshPort;

    @Value("${app.build-host.identity-file:${BUILD_SSH_KEY:}}")
    private String identityFile;

    @Value("${app.build-host.m2-repo:${BUILD_M2_REPO:}}")
    private String m2Repo;

    @Value("${app.build-host.workspace:${BUILD_WS_ROOT:}}")
    private String workspace;

    @Value("${app.build-host.stale-hours:6}")
    private int staleHours;

    @Value("${app.build-host.timeout-seconds:25}")
    private int timeoutSeconds;

    public Map<String, Object> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ready = sshHost != null && !sshHost.isBlank() && sshUser != null && !sshUser.isBlank();
        out.put("ready", ready);
        out.put("host", sshHost);
        out.put("user", sshUser);
        out.put("port", sshPort);
        out.put("m2Repo", blankToNull(m2Repo));
        out.put("workspace", blankToNull(workspace));
        out.put("staleHours", staleHours);
        out.put("defaultGroupId", "com.steamcn");
        out.put("defaultArtifactId", "appuser-api");
        out.put("defaultVersion", "1.0.74-SNAPSHOT");
        if (!ready) {
            out.put("hint", "还没配构建机。环境变量 BUILD_SSH_HOST / BUILD_SSH_USER，密钥登录（不要把密码写进 yml）");
        } else {
            out.put("hint", "SSH " + sshUser + "@" + sshHost + "，看这台机器上的 .m2。Jenkins 若是另一台，改 BUILD_SSH_HOST");
        }
        return out;
    }

    public Map<String, Object> inspect(String groupId, String artifactId, String version) {
        String g = nz(groupId, "com.steamcn");
        String a = nz(artifactId, "appuser-api");
        String v = nz(version, "1.0.74-SNAPSHOT");
        String script = MavenCacheCommand.inspectScript(m2Repo, workspace, g, a, v);
        Exec exec = run(script);
        return decorate(g, a, v, exec, false);
    }

    public Map<String, Object> clean(String groupId, String artifactId, String version) {
        String g = nz(groupId, "com.steamcn");
        String a = nz(artifactId, "appuser-api");
        String v = nz(version, "1.0.74-SNAPSHOT");
        String script = MavenCacheCommand.cleanScript(m2Repo, g, a, v);
        Exec exec = run(script);
        Map<String, Object> out = decorate(g, a, v, exec, true);
        out.put("cleaned", exec.stdout.contains("CLEANED="));
        return out;
    }

    private Map<String, Object> decorate(String groupId, String artifactId, String version,
                                         Exec exec, boolean cleaned) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", exec.exit == 0);
        out.put("host", sshHost);
        out.put("groupId", groupId);
        out.put("artifactId", artifactId);
        out.put("version", version);
        out.put("exitCode", exec.exit);
        if (exec.stdout.contains("M2_MISSING")) {
            out.put("message", "这台机器上没找到 ~/.m2/repository 或 /root/.m2/repository。把 BUILD_M2_REPO 指到真实仓库目录");
        } else if (exec.stdout.contains("DIR_MISSING")) {
            out.put("message", "本地还没有这个版本，下次构建会从 Nexus 拉");
        } else if (exec.exit != 0) {
            out.put("message", firstLine(exec.stderr.isBlank() ? exec.stdout : exec.stderr));
        } else if (cleaned && exec.stdout.contains("CLEANED=")) {
            out.put("message", "已删该版本缓存，下次构建请加 -U");
        } else {
            out.put("message", "已读到缓存目录");
        }
        String repo = extract(exec.stdout, "REPO=");
        String dir = extract(exec.stdout, "DIR=");
        out.put("repo", repo);
        out.put("dir", dir);
        List<MavenCacheCommand.JarRow> jars = MavenCacheCommand.parseJars(exec.stdout);
        List<Map<String, Object>> jarVos = new ArrayList<>();
        LocalDateTime newest = null;
        for (MavenCacheCommand.JarRow j : jars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", j.name());
            row.put("mtime", j.date() + " " + j.time());
            jarVos.add(row);
            try {
                LocalDateTime t = LocalDateTime.parse(j.date() + "T" + j.time(),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                if (newest == null || t.isAfter(newest)) {
                    newest = t;
                }
            } catch (Exception ignored) {
                // 解析不了时间就不判过旧
            }
        }
        out.put("jars", jarVos);
        boolean stale = false;
        Long ageHours = null;
        if (newest != null) {
            ageHours = ChronoUnit.HOURS.between(newest, LocalDateTime.now());
            stale = ageHours != null && ageHours >= staleHours;
        }
        out.put("newestMtime", newest == null ? null : newest.toString());
        out.put("ageHours", ageHours);
        out.put("stale", stale);
        if (stale) {
            out.put("staleHint", "jar 已经 " + ageHours + " 小时没动过。目录名还是这个 SNAPSHOT，内容可能是早上那包，不是刚 deploy 的");
        }
        out.put("pomHits", extractBlock(exec.stdout, "BEGIN_POMS", "END_POMS"));
        out.put("raw", trimRaw(exec.stdout + (exec.stderr.isBlank() ? "" : "\n" + exec.stderr)));
        return out;
    }

    private Exec run(String remoteScript) {
        List<String> cmd = MavenCacheCommand.sshArgv(sshUser, sshHost, sshPort, identityFile, remoteScript);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        try {
            Process p = pb.start();
            String stdout = readAll(p.getInputStream());
            String stderr = readAll(p.getErrorStream());
            boolean finished = p.waitFor(Math.max(8, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IllegalStateException("SSH 超过 " + timeoutSeconds + " 秒没回来。是不是不在公司网，或主机不是这台");
            }
            return new Exec(p.exitValue(), stdout, stderr);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            throw new IllegalStateException("SSH 失败：" + msg + "。确认本机已能 ssh " + sshUser + "@" + sshHost);
        }
    }

    private static String readAll(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String extract(String text, String prefix) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) {
                String v = line.substring(prefix.length()).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static List<String> extractBlock(String text, String begin, String end) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        boolean in = false;
        for (String line : text.split("\\R")) {
            if (begin.equals(line.trim())) {
                in = true;
                continue;
            }
            if (end.equals(line.trim())) {
                break;
            }
            if (in && !line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "SSH 失败";
        }
        return s.strip().split("\\R")[0];
    }

    private static String trimRaw(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 8000 ? s.substring(0, 8000) + "\n...(截断)" : s;
    }

    private static String nz(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private record Exec(int exit, String stdout, String stderr) {
    }
}
