package com.mobile.standalone.service.pool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.harmony.HdcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 主控机上的鸿蒙模拟器。权威来源是 DevEco 的 {@code Emulator -list}，不是 hdc——
 * 没开机的实例 hdc 看不见，设备池就会以为一台都没有。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HarmonyEmulatorCatalog {

    private final HdcClient hdcClient;

    private static final long CMD_TIMEOUT_SEC = 20;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Instance(String name, String status, String serial, String deviceType, String osVersion) {
        public boolean running() {
            String s = norm(status);
            return s.equals("running") || s.equals("started") || s.equals("booted") || s.equals("ready");
        }

        public boolean booting() {
            String s = norm(status);
            return s.equals("starting") || s.equals("booting") || s.equals("creating");
        }

        private static String norm(String status) {
            return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        }
    }

    public boolean available() {
        return SdkTools.harmonyEmulator() != null;
    }

    public List<Instance> list() {
        String bin = SdkTools.harmonyEmulator();
        if (bin == null) {
            return List.of();
        }
        String raw = runText(bin, "-list", "-details");
        List<Instance> detailed = parse(raw);
        if (detailed.isEmpty() && !looksEmpty(raw)) {
            detailed = parse(runText(bin, "-list"));
        }
        return attachHdcSerials(detailed);
    }

    /**
     * DevEco 的 {@code hw.hdc.port} 经常是 {@code notset}，真正的序列号在 hdc 里。
     * 起着的实例如果不补上，设备池会拆成「目录一行 + hdc 一行」。
     */
    private List<Instance> attachHdcSerials(List<Instance> items) {
        if (items == null || items.isEmpty() || hdcClient == null || !hdcClient.available()) {
            return items;
        }
        List<String> loopbacks = new ArrayList<>();
        for (String s : hdcClient.listTargets()) {
            if (s != null && (s.startsWith("127.0.0.1:") || s.startsWith("localhost:"))) {
                loopbacks.add(s);
            }
        }
        if (loopbacks.isEmpty()) {
            return items;
        }
        List<Instance> out = new ArrayList<>();
        int next = 0;
        for (Instance i : items) {
            if (i.running() && (i.serial() == null || i.serial().isBlank()) && next < loopbacks.size()) {
                out.add(new Instance(i.name(), i.status(), loopbacks.get(next++), i.deviceType(), i.osVersion()));
            } else {
                out.add(i);
            }
        }
        return out;
    }

    public Instance find(String nameOrSerial) {
        if (nameOrSerial == null || nameOrSerial.isBlank()) {
            return null;
        }
        String key = nameOrSerial.trim();
        for (Instance i : list()) {
            if (key.equals(i.name()) || key.equals(i.serial())) {
                return i;
            }
        }
        return null;
    }

    public boolean isRunningSerial(String serial) {
        if (serial == null || serial.isBlank()) {
            return false;
        }
        String key = serial.trim();
        for (Instance i : list()) {
            if (i.running() && key.equals(i.serial())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拉起模拟器进程。立刻返回句柄，不等开机。
     *
     * @param wipeData {@code -bootmode reset}，相当于清数据再开
     */
    Process start(String name, boolean wipeData, Path logFile) throws Exception {
        String bin = SdkTools.harmonyEmulator();
        if (bin == null) {
            throw new IllegalStateException("找不到 DevEco Emulator 命令");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("-start");
        cmd.add(name);
        if (wipeData) {
            cmd.add("-bootmode");
            cmd.add("reset");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (logFile != null) {
            pb.redirectOutput(logFile.toFile());
        }
        return pb.start();
    }

    boolean stop(String nameOrSerial) {
        String bin = SdkTools.harmonyEmulator();
        if (bin == null || nameOrSerial == null || nameOrSerial.isBlank()) {
            return false;
        }
        return run(bin, "-stop", nameOrSerial.trim());
    }

    /**
     * 解析 {@code Emulator -list} / {@code -list -details}。
     * 空名单是 {@code [Empty]}；有实例时可能是 JSON，也可能是表。
     */
    static List<Instance> parse(String raw) {
        if (raw == null) {
            return List.of();
        }
        String t = raw.trim();
        if (t.isEmpty() || looksEmpty(t)) {
            return List.of();
        }
        int jsonAt = t.indexOf('[');
        if (jsonAt >= 0 && t.indexOf('{', jsonAt) > jsonAt) {
            List<Instance> fromJson = parseJson(t.substring(jsonAt));
            if (!fromJson.isEmpty()) {
                return fromJson;
            }
        }
        return parseText(t);
    }

    private static boolean looksEmpty(String raw) {
        if (raw == null) {
            return true;
        }
        String t = raw.trim();
        return t.equalsIgnoreCase("[Empty]")
                || t.equalsIgnoreCase("Empty")
                || t.equalsIgnoreCase("[]");
    }

    private static List<Instance> parseJson(String json) {
        List<Instance> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray()) {
                return out;
            }
            for (JsonNode n : root) {
                if (n == null || !n.isObject()) continue;
                String name = firstText(n, "name", "Name", "instanceName");
                if (name == null || name.isBlank()) continue;
                String status = firstText(n, "status", "Status", "state", "State");
                if (status == null || status.isBlank()) {
                    status = truthy(firstText(n, "isRunning", "running")) ? "Running" : "Stopped";
                }
                String serial = firstText(n, "serial", "Serial", "connectKey", "ConnectKey",
                        "connectkey", "hdcConnectKey");
                if (serial == null || serial.isBlank() || "-".equals(serial)) {
                    serial = loopbackSerial(firstText(n, "hdcPort", "HdcPort", "hw.hdc.port", "port"));
                }
                if ("-".equals(serial) || "notset".equalsIgnoreCase(serial)) serial = null;
                out.add(new Instance(name.trim(),
                        status.trim(),
                        blankToNull(serial),
                        firstText(n, "deviceType", "DeviceType", "type"),
                        firstText(n, "os.osVersion", "osVersion", "OSVersion", "os_version", "version")));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out;
    }

    private static List<Instance> parseText(String raw) {
        List<Instance> out = new ArrayList<>();
        String[] lines = raw.split("\\R");
        boolean sawHeader = false;
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || looksEmpty(line)) continue;
            if (line.startsWith("-") || line.startsWith("=")) continue;
            String[] cols = line.split("\\s{2,}");
            if (cols.length < 2) {
                cols = line.split("\\t+");
            }
            if (cols.length < 2) continue;
            if (!sawHeader && isHeader(cols[0], cols[1])) {
                sawHeader = true;
                continue;
            }
            String name = cols[0].trim();
            if (name.isEmpty() || isHeader(name, cols[1])) continue;
            String status = cols[1].trim();
            String serial = cols.length > 2 ? cols[2].trim() : null;
            if ("-".equals(serial) || serial != null && serial.isBlank()) serial = null;
            String type = cols.length > 3 ? cols[3].trim() : null;
            String ver = cols.length > 4 ? cols[4].trim() : null;
            out.add(new Instance(name, status, serial, type, ver));
        }
        return out;
    }

    private static boolean isHeader(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        return x.contains("name") && (y.contains("status") || y.contains("state"));
    }

    private static boolean truthy(String v) {
        if (v == null) return false;
        String s = v.trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static String firstText(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) {
                String s = v.asText();
                if (s != null && !s.isBlank()) return s;
            }
        }
        return null;
    }

    private static String loopbackSerial(String port) {
        if (port == null || port.isBlank()) return null;
        String p = port.trim();
        if (p.contains(":")) return p;
        if (!p.chars().allMatch(Character::isDigit)) return null;
        return "127.0.0.1:" + p;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String runText(String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("[harmony-emu] 命令超时: {}", String.join(" ", command));
                return "";
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[harmony-emu] 命令跑不了 {}: {}", String.join(" ", command), e.getMessage());
            return "";
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
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
            log.debug("[harmony-emu] 命令失败 {}: {}", String.join(" ", command), e.getMessage());
            return false;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
