package com.mobile.standalone.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android 设备性能数据采集（通过 adb）：应用 CPU 使用率、内存 PSS。
 * 用于性能基础质量测试中的资源监控。
 */
@Component
@Slf4j
public class AndroidPerformanceCollector {

    private static final int ADB_TIMEOUT_MS = 8000;

    /**
     * 获取当前应用进程 PID；未运行返回 null。
     */
    public Integer getPid(String deviceId, String packageName) {
        if (deviceId == null || packageName == null || packageName.isBlank()) return null;
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "pidof", packageName)
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            if (output == null) return null;
            String s = output.trim().split("\\s+")[0].trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception e) {
            log.debug("getPid 失败: deviceId={}, package={}", deviceId, packageName, e);
            return null;
        }
    }

    /**
     * 采集当前应用 CPU 使用率（%）。dumpsys cpuinfo 中该进程的 user+kernel 占比。
     * 无法解析时返回 null。
     */
    public Double getCpuUsagePercent(String deviceId, String packageName) {
        if (deviceId == null || packageName == null) return null;
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "cpuinfo")
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            if (output == null) return null;
            // 格式示例: "  45% 1234/com.pwrd.steam.esports: 30% user + 15% kernel" 或 "  2% 1234/package.name"
            String safePkg = Pattern.quote(packageName);
            for (String line : output.split("\n")) {
                if (!line.contains(packageName)) continue;
                // 匹配行首第一个百分比数字（该进程总 CPU）
                Pattern simple = Pattern.compile("\\s*(\\d+)%\\s+\\d+/" + safePkg);
                Matcher sm = simple.matcher(line);
                if (sm.find()) {
                    return (double) Integer.parseInt(sm.group(1));
                }
            }
            return null;
        } catch (Exception e) {
            log.trace("getCpuUsagePercent 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 采集当前应用内存 PSS（KB）。dumpsys meminfo &lt;package&gt; 的 TOTAL PSS。
     * 无法解析时返回 null。
     */
    public Long getMemoryPssKb(String deviceId, String packageName) {
        if (deviceId == null || packageName == null) return null;
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "meminfo", packageName)
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            if (output == null) return null;
            // 找 "Total PSS by process" 或 "TOTAL" 行中的 PSS 数字（KB）
            for (String line : output.split("\n")) {
                if (line.contains("TOTAL") && line.contains("PSS")) {
                    // 格式: "   TOTAL    ...  123456    12345    1234"
                    Pattern pat = Pattern.compile("TOTAL\\s+\\d+\\s+\\d+\\s+(\\d+)");
                    Matcher m = pat.matcher(line);
                    if (m.find()) {
                        return Long.parseLong(m.group(1));
                    }
                    // 另一种: "Total PSS by process: 123456"
                    Pattern p2 = Pattern.compile("Total PSS.*?(\\d+)");
                    Matcher m2 = p2.matcher(line);
                    if (m2.find()) {
                        return Long.parseLong(m2.group(1));
                    }
                }
            }
            // 常见格式: "  TOTAL    xxxx    yyyy    123456  ..." 第四列为 PSS
            for (String line : output.split("\n")) {
                String t = line.trim();
                if (t.startsWith("TOTAL")) {
                    String[] parts = t.split("\\s+");
                    if (parts.length >= 4) {
                        try {
                            return Long.parseLong(parts[3]);
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.trace("getMemoryPssKb 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 采集整机网络流量累计字节数（rx/tx）。adb shell cat /proc/net/dev，汇总各接口。
     * 返回 [rxBytes, txBytes]，解析失败返回 null。
     */
    public long[] getNetworkRxTxBytes(String deviceId) {
        if (deviceId == null) return null;
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "cat", "/proc/net/dev")
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            if (output == null) return null;
            long rx = 0, tx = 0;
            String[] lines = output.split("\n");
            for (int i = 2; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String nums = line.substring(colon + 1).trim();
                String[] parts = nums.split("\\s+");
                if (parts.length >= 10) {
                    try {
                        rx += Long.parseLong(parts[0]);
                        tx += Long.parseLong(parts[8]);
                    } catch (NumberFormatException ignored) { }
                }
            }
            return new long[]{rx, tx};
        } catch (Exception e) {
            log.trace("getNetworkRxTxBytes 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 采集电池温度（单位：0.1℃）。dumpsys battery 中的 temperature。
     * 无法解析时返回 null。
     */
    public Integer getBatteryTemperatureTenthsC(String deviceId) {
        if (deviceId == null) return null;
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "battery")
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            if (output == null) return null;
            Pattern pat = Pattern.compile("temperature:\\s*(\\d+)");
            Matcher m = pat.matcher(output);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
            return null;
        } catch (Exception e) {
            log.trace("getBatteryTemperature 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 一次采样：返回 cpu%、内存 PSS、网络 rx/tx 字节、电池温度（0.1℃）。任一失败则对应项为 null。
     */
    public Sample sample(String deviceId, String packageName) {
        Double cpu = getCpuUsagePercent(deviceId, packageName);
        Long mem = getMemoryPssKb(deviceId, packageName);
        long[] net = getNetworkRxTxBytes(deviceId);
        Long rx = (net != null && net.length >= 2) ? net[0] : null;
        Long tx = (net != null && net.length >= 2) ? net[1] : null;
        Integer temp = getBatteryTemperatureTenthsC(deviceId);
        return new Sample(cpu, mem, rx, tx, temp);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class Sample {
        private Double cpuPercent;
        private Long memoryPssKb;
        private Long networkRxBytes;
        private Long networkTxBytes;
        private Integer temperatureTenthsC;
    }

    private static String readOutput(Process p, int timeoutMs) {
        try {
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
