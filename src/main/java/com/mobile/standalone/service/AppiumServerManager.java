package com.mobile.standalone.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 确保本机 Appium Server 可用：不可达时按配置自动拉起进程。
 * 流程编排「启动应用程序」依赖 Appium 用任意 appPackage/appActivity 建会话，不写死业务包名。
 */
@Service
@Slf4j
public class AppiumServerManager {

    private final boolean enabled;
    private final boolean autoStart;
    private final String serverUrl;
    private final int startWaitSeconds;
    private final String configuredCommand;
    private final AtomicReference<Process> managedProcess = new AtomicReference<>();

    public AppiumServerManager(
            @Value("${appium.enabled:false}") boolean enabled,
            @Value("${appium.auto-start:true}") boolean autoStart,
            @Value("${appium.server-url:http://127.0.0.1:4723}") String serverUrl,
            @Value("${appium.start-wait-seconds:45}") int startWaitSeconds,
            @Value("${appium.start-command:}") String configuredCommand
    ) {
        this.enabled = enabled;
        this.autoStart = autoStart;
        this.serverUrl = serverUrl != null ? serverUrl.trim() : "http://127.0.0.1:4723";
        this.startWaitSeconds = Math.max(5, startWaitSeconds);
        this.configuredCommand = configuredCommand != null ? configuredCommand.trim() : "";
    }

    /**
     * @return true 表示 Appium /status 可达，可用于创建会话
     */
    public synchronized boolean ensureRunning() {
        if (!enabled) {
            log.warn("appium.enabled=false，跳过自动拉起 Appium");
            return false;
        }
        if (isReachable()) {
            return true;
        }
        if (!autoStart) {
            log.warn("Appium 不可达且 auto-start=false: {}", serverUrl);
            return false;
        }
        if (!startProcess()) {
            return false;
        }
        long deadline = System.currentTimeMillis() + startWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isReachable()) {
                log.info("Appium Server 已就绪: {}", serverUrl);
                return true;
            }
            Process p = managedProcess.get();
            if (p != null && !p.isAlive()) {
                log.error("Appium 进程已退出，exitCode={}", p.exitValue());
                return false;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.error("等待 Appium 就绪超时（{}s）: {}", startWaitSeconds, serverUrl);
        return false;
    }

    public boolean isReachable() {
        try {
            URL url = URI.create(statusUrl()).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean startProcess() {
        Process existing = managedProcess.get();
        if (existing != null && existing.isAlive()) {
            return true;
        }
        HostPort hp = parseHostPort(serverUrl);
        List<String> cmd = buildStartCommand(hp.host, hp.port);
        if (cmd.isEmpty()) {
            log.error("未找到 appium 可执行文件，请安装 Appium 或配置 appium.start-command");
            return false;
        }
        try {
            Path logDir = Path.of(System.getProperty("user.home"), ".mobile-standalone", "logs");
            Files.createDirectories(logDir);
            Path out = logDir.resolve("appium-server.log");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(out.toFile());
            pb.directory(Path.of(System.getProperty("user.home")).toFile());
            Process p = pb.start();
            managedProcess.set(p);
            log.info("已自动启动 Appium: {} （日志 {}）", String.join(" ", cmd), out);
            // 给进程一点启动时间，避免立刻探活失败
            Thread.sleep(1500);
            if (!p.isAlive()) {
                log.error("Appium 启动后立即退出，exitCode={}，详见 {}", p.exitValue(), out);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("自动启动 Appium 失败: {}", e.getMessage(), e);
            return false;
        }
    }

    private List<String> buildStartCommand(String host, int port) {
        List<String> cmd = new ArrayList<>();
        if (!configuredCommand.isEmpty()) {
            // 简单按空格拆（高级用户可自行写完整命令）
            for (String part : configuredCommand.split("\\s+")) {
                if (!part.isBlank()) cmd.add(part);
            }
            return cmd;
        }
        String bin = resolveAppiumBinary();
        if (bin == null) {
            return List.of();
        }
        cmd.add(bin);
        cmd.add("--address");
        cmd.add(host);
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--base-path");
        cmd.add("/");
        cmd.add("--log-level");
        cmd.add("info");
        return cmd;
    }

    private String resolveAppiumBinary() {
        String[] candidates = {
                "/opt/homebrew/bin/appium",
                "/usr/local/bin/appium",
                System.getProperty("user.home") + "/.npm-global/bin/appium"
        };
        for (String c : candidates) {
            if (c != null && Files.isExecutable(Path.of(c))) {
                return c;
            }
        }
        try {
            Process p = new ProcessBuilder("which", "appium")
                    .redirectErrorStream(true)
                    .start();
            String line;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = br.readLine();
            }
            boolean ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok && line != null && !line.isBlank() && Files.isExecutable(Path.of(line.trim()))) {
                return line.trim();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private String statusUrl() {
        String base = serverUrl.replaceAll("/+$", "");
        return base + "/status";
    }

    private static HostPort parseHostPort(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
            int port = uri.getPort() > 0 ? uri.getPort() : 4723;
            return new HostPort(host, port);
        } catch (Exception e) {
            return new HostPort("127.0.0.1", 4723);
        }
    }

    @PreDestroy
    public void shutdown() {
        Process p = managedProcess.getAndSet(null);
        if (p != null && p.isAlive()) {
            log.info("关闭本服务拉起的 Appium 进程");
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }

    private record HostPort(String host, int port) {}
}
