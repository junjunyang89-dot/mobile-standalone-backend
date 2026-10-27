package com.mobile.standalone.service.harmony;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * HarmonyOS NEXT 设备连接器：仅通过 hdc（不走 adb）。
 */
@Component
@Slf4j
public class HdcClient {

    @Value("${harmony.hdc-path:}")
    private String hdcPathConfig;

    @Value("${harmony.hdc-timeout-seconds:60}")
    private int defaultTimeoutSeconds;

    private volatile String resolvedHdc;

    @PostConstruct
    public void init() {
        resolvedHdc = resolveHdcBinary();
        if (resolvedHdc == null) {
            log.warn("未找到 hdc。纯鸿蒙设备扫描/安装不可用。"
                    + "请安装 DevEco Studio SDK（toolchains），并配置 harmony.hdc-path 或将 hdc 加入 PATH。"
                    + "参见 docs/鸿蒙hdc自动化.md");
        } else {
            log.info("hdc 已就绪: {}", resolvedHdc);
        }
    }

    public boolean available() {
        return resolvedHdc != null;
    }

    public String hdcBinary() {
        return resolvedHdc;
    }

    /** 刷新解析（安装 hdc 后可不重启时手动调用）。 */
    public synchronized String refresh() {
        resolvedHdc = resolveHdcBinary();
        return resolvedHdc;
    }

    public List<String> listTargets() {
        return listTargetEntries().stream()
                .filter(HdcTarget::authorized)
                .map(HdcTarget::serial)
                .toList();
    }

    /** 含未授权/离线目标，供设备管理展示。 */
    public List<HdcTarget> listTargetEntries() {
        List<HdcTarget> targets = new ArrayList<>();
        if (!available()) {
            refresh();
        }
        if (!available()) {
            return targets;
        }
        // -v 才能看到 Offline/Unauthorized；无 -v 时离线机会直接变 [Empty]，设备列表里消失
        ExecResult r = exec(defaultTimeoutSeconds, "list", "targets", "-v");
        if (r.exitCode != 0 || blankOrEmptyTargets(r.stdout)) {
            ExecResult plain = exec(defaultTimeoutSeconds, "list", "targets");
            if (plain.exitCode == 0 && !blankOrEmptyTargets(plain.stdout)) {
                r = plain;
            } else if (r.exitCode != 0) {
                log.debug("hdc list targets 失败 exit={} out={}", r.exitCode, r.combined());
                return targets;
            }
        }
        for (String line : r.stdout.split("\\R")) {
            HdcTarget t = parseTargetLine(line);
            if (t == null) {
                continue;
            }
            if (!t.authorized()) {
                String reason = t.unauthorized() ? "Unauthorized" : (t.offline() ? "Offline" : "?");
                log.warn("鸿蒙设备 {} 状态为 {}（请解锁手机并点允许 USB/HDC 调试）", t.serial(), reason);
            }
            targets.add(t);
        }
        return targets;
    }

    static boolean blankOrEmptyTargets(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return true;
        }
        String t = stdout.trim();
        return t.equalsIgnoreCase("Empty")
                || t.equalsIgnoreCase("[Empty]")
                || t.toLowerCase().contains("[empty]");
    }

    /**
     * 解析 {@code hdc list targets [-v]} 一行。
     * 无 -v：{@code SERIAL} 或 {@code SERIAL Unauthorized}
     * 有 -v：{@code SERIAL  USB  Offline  localhost} / {@code SERIAL  USB  Connected  localhost}
     */
    static HdcTarget parseTargetLine(String line) {
        if (line == null) {
            return null;
        }
        String t = line.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("Empty") || t.toLowerCase().contains("empty")) {
            return null;
        }
        if (t.startsWith("[") && t.toLowerCase().contains("empty")) {
            return null;
        }
        String[] parts = t.split("\\s+");
        String serial = parts[0].trim();
        if (serial.isEmpty()) {
            return null;
        }
        // 跳过表头
        if ("serial".equalsIgnoreCase(serial) || "connect-key".equalsIgnoreCase(serial)) {
            return null;
        }
        String lower = t.toLowerCase();
        boolean unauthorized = lower.contains("unauthorized");
        boolean offline = lower.contains("offline");
        boolean authorized = !unauthorized && !offline;
        return new HdcTarget(serial, authorized, unauthorized, offline);
    }

    public record HdcTarget(String serial, boolean authorized, boolean unauthorized, boolean offline) {}

    public String shell(String deviceId, String command) {
        ExecResult r = exec(defaultTimeoutSeconds, "-t", deviceId, "shell", command);
        return r.stdout == null ? "" : r.stdout.trim();
    }

    public ExecResult install(String deviceId, String hapPath) {
        // -g：安装时尝试授予 user_grant 权限；hdc 失败时仍常返回 exit 0，见 ExecResult.ok()
        return exec(Math.max(defaultTimeoutSeconds, 300), "-t", deviceId, "install", "-g", hapPath);
    }

    public ExecResult uninstall(String deviceId, String bundleName) {
        return exec(defaultTimeoutSeconds, "-t", deviceId, "uninstall", bundleName);
    }

    public ExecResult exec(int timeoutSeconds, String... args) {
        if (!available()) {
            return ExecResult.fail("hdc not found");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedHdc);
        cmd.addAll(Arrays.asList(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            try (BufferedReader or = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = or.readLine()) != null) {
                    out.append(line).append('\n');
                }
                while ((line = er.readLine()) != null) {
                    err.append(line).append('\n');
                }
            }
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return ExecResult.fail("hdc timeout after " + timeoutSeconds + "s: " + String.join(" ", args));
            }
            return new ExecResult(p.exitValue(), out.toString(), err.toString());
        } catch (Exception e) {
            log.warn("执行 hdc 失败: {} -> {}", String.join(" ", args), e.getMessage());
            return ExecResult.fail(e.getMessage());
        }
    }

    private String resolveHdcBinary() {
        if (hdcPathConfig != null && !hdcPathConfig.isBlank()) {
            Path p = Paths.get(hdcPathConfig.trim());
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p.toAbsolutePath().toString();
            }
            log.warn("harmony.hdc-path 无效: {}", hdcPathConfig);
        }
        String fromPath = which("hdc");
        if (fromPath != null) {
            return fromPath;
        }
        String home = System.getProperty("user.home");
        List<Path> roots = List.of(
                Paths.get(home, "Library", "Huawei", "Sdk"),
                Paths.get(home, "Library", "OpenHarmony", "Sdk"),
                Paths.get(home, "Huawei", "Sdk"),
                Paths.get("/Applications/DevEco-Studio.app/Contents/sdk")
        );
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 8)) {
                Path found = walk
                        .filter(f -> f.getFileName().toString().equals("hdc"))
                        .filter(Files::isRegularFile)
                        .filter(Files::isExecutable)
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    return found.toAbsolutePath().toString();
                }
            } catch (Exception e) {
                log.debug("搜索 hdc 于 {} 失败: {}", root, e.getMessage());
            }
        }
        return null;
    }

    private static String which(String bin) {
        try {
            Process p = new ProcessBuilder("which", bin).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line != null && !line.isBlank() && Files.isExecutable(Paths.get(line.trim()))) {
                    return line.trim();
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    public record ExecResult(int exitCode, String stdout, String stderr) {
        static ExecResult fail(String msg) {
            return new ExecResult(-1, "", msg == null ? "" : msg);
        }

        public boolean ok() {
            if (exitCode != 0) {
                return false;
            }
            // hdc install/uninstall 失败时也经常 exit 0，只在 stdout 里写 msg:error
            String c = combined();
            if (c.isBlank()) {
                return true;
            }
            String lower = c.toLowerCase();
            return !lower.contains("msg:error")
                    && !lower.contains("error: failed")
                    && !lower.contains("failed to install")
                    && !lower.contains("failed to uninstall");
        }

        public String combined() {
            String s = (stdout == null ? "" : stdout) + (stderr == null ? "" : stderr);
            return s.trim();
        }
    }
}
