package com.mobile.standalone.service.pool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主控机上的 iOS Simulator。权威来源是 {@code xcrun simctl}，不是 idevice_id——
 * 后者只认 USB 真机，模拟器永远不会出现。
 */
@Service
@Slf4j
public class IosSimulatorCatalog {

    private static final long CMD_TIMEOUT_SEC = 15;
    /** Device Manager 一行：iPhone 17 Pro (UUID) (Shutdown) */
    private static final Pattern DEVICE_LINE = Pattern.compile(
            "^\\s+(.+?)\\s+\\(([0-9A-Fa-f-]{36})\\)\\s+\\((Shutdown|Booted|Booting|Creating)\\)");

    public record SimInfo(String name, String udid, String state, String runtime) {
        public boolean booted() {
            return "Booted".equalsIgnoreCase(state);
        }

        public boolean booting() {
            return "Booting".equalsIgnoreCase(state) || "Creating".equalsIgnoreCase(state);
        }
    }

    public List<SimInfo> list() {
        List<String> lines = runLines("xcrun", "simctl", "list", "devices", "available");
        return parse(lines);
    }

    public SimInfo find(String nameOrUdid) {
        if (nameOrUdid == null || nameOrUdid.isBlank()) return null;
        String key = nameOrUdid.trim();
        for (SimInfo s : list()) {
            if (key.equals(s.udid()) || key.equals(s.name())) return s;
        }
        return null;
    }

    /**
     * 解析 {@code simctl list devices available} 文本。抽出来是为了不连 Xcode 也能测。
     */
    static List<SimInfo> parse(List<String> lines) {
        List<SimInfo> out = new ArrayList<>();
        if (lines == null) return out;
        String runtime = "";
        for (String raw : lines) {
            String line = raw == null ? "" : raw;
            String trimmed = line.trim();
            if (trimmed.startsWith("--") && trimmed.endsWith("--")) {
                runtime = trimmed.replace("-", "").trim();
                continue;
            }
            Matcher m = DEVICE_LINE.matcher(line);
            if (!m.find()) continue;
            String name = m.group(1).trim();
            // 只收手机/平板；Apple Watch / TV 这套自动化用不上
            if (!name.startsWith("iPhone") && !name.startsWith("iPad")) continue;
            out.add(new SimInfo(name, m.group(2), m.group(3), runtime));
        }
        return out;
    }

    boolean boot(String udid) {
        if (!run("xcrun", "simctl", "boot", udid)) return false;
        // 打开 Simulator.app，否则有的机型 WDA 起不来（没窗口宿主）
        run("open", "-a", "Simulator", "--args", "-CurrentDeviceUDID", udid);
        return true;
    }

    boolean shutdown(String udid) {
        return run("xcrun", "simctl", "shutdown", udid);
    }

    boolean erase(String udid) {
        return run("xcrun", "simctl", "erase", udid);
    }

    private List<String> runLines(String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.add(line);
            }
            if (!p.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return List.of();
            }
            return out;
        } catch (Exception e) {
            log.debug("[ios-sim] 命令跑不了 {}: {}", String.join(" ", command), e.getMessage());
            return List.of();
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private boolean run(String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) {
                    /* drain */
                }
            }
            return p.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("[ios-sim] 命令失败 {}: {}", String.join(" ", command), e.getMessage());
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }
}
