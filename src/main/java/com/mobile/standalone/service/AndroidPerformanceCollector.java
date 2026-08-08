package com.mobile.standalone.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android 设备性能数据采集（通过 adb）：应用 CPU、内存 PSS、网络、温度、FPS/卡顿（gfxinfo）。
 * 用于性能基础质量测试中的资源监控。
 * <p>
 * 加固：CPU 在 dumpsys cpuinfo 失败时用 {@code top -n 1} 兜底；meminfo 按配置节流；
 * 可选 gfxinfo framestats 差分统计卡顿。
 */
@Component
@Slf4j
public class AndroidPerformanceCollector {

    private static final int ADB_TIMEOUT_MS = 8000;
    private static final double FRAME_BUDGET_MS = 1000.0 / 60.0; // ~16.67ms

    /** meminfo 节流：同一 device+pkg 两次拉取最小间隔 */
    @Value("${performance.adb.meminfo-min-interval-ms:2000}")
    private long meminfoMinIntervalMs;

    /** 为 true 时优先用 gfxinfo framestats 差分；不支持则回退汇总解析 */
    @Value("${performance.adb.prefer-framestats:true}")
    private boolean preferFramestats;

    private final Map<String, CachedMem> memCache = new ConcurrentHashMap<>();

    private record CachedMem(long atMs, Long pssKb) {}

    /**
     * 解析当前前台包名（dumpsys window / activity）。解析失败返回 null。
     * 用于确认「是否真的把目标 App 拉到前台」，避免仅建会话未启动。
     */
    public String getForegroundPackage(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return null;
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell",
                    "dumpsys", "window", "windows")
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            if (output != null) {
                Matcher m = Pattern.compile("mCurrentFocus=.*?\\s([a-zA-Z0-9_.]+)/").matcher(output);
                if (m.find()) return m.group(1);
                Matcher m2 = Pattern.compile("mFocusedApp=.*?\\s([a-zA-Z0-9_.]+)/").matcher(output);
                if (m2.find()) return m2.group(1);
            }
            Process p2 = new ProcessBuilder("adb", "-s", deviceId, "shell",
                    "dumpsys", "activity", "activities")
                    .redirectErrorStream(true)
                    .start();
            String out2 = readOutput(p2, ADB_TIMEOUT_MS);
            if (out2 != null) {
                Matcher m3 = Pattern.compile("mResumedActivity:.*?\\s([a-zA-Z0-9_.]+)/").matcher(out2);
                if (m3.find()) return m3.group(1);
            }
        } catch (Exception e) {
            log.debug("getForegroundPackage 失败: {}", e.getMessage());
        }
        return null;
    }

    public boolean isAppInForeground(String deviceId, String packageName) {
        if (packageName == null || packageName.isBlank()) return false;
        String fg = getForegroundPackage(deviceId);
        return fg != null && packageName.equals(fg);
    }

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
     * 采集当前应用 CPU 使用率（%）。优先 dumpsys cpuinfo；失败则 top -n 1 兜底。
     */
    public Double getCpuUsagePercent(String deviceId, String packageName) {
        if (deviceId == null || packageName == null) return null;
        Double fromCpuinfo = getCpuFromCpuinfo(deviceId, packageName);
        if (fromCpuinfo != null) return fromCpuinfo;
        return getCpuFromTop(deviceId, packageName);
    }

    private Double getCpuFromCpuinfo(String deviceId, String packageName) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "cpuinfo")
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            if (output == null) return null;
            String safePkg = Pattern.quote(packageName);
            for (String line : output.split("\n")) {
                if (!line.contains(packageName)) continue;
                Pattern simple = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)%\\s+\\d+/" + safePkg);
                Matcher sm = simple.matcher(line);
                if (sm.find()) {
                    return Double.parseDouble(sm.group(1));
                }
            }
            return null;
        } catch (Exception e) {
            log.trace("getCpuFromCpuinfo 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * top -n 1 兜底：按包名匹配行，取 %CPU 列（不同 OEM 列序略有差异，尽量兼容）。
     */
    Double getCpuFromTop(String deviceId, String packageName) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell",
                    "top", "-n", "1", "-b", "-d", "0.5")
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            if (output == null || output.isBlank()) return null;
            String shortName = packageName;
            int lastDot = packageName.lastIndexOf('.');
            if (lastDot > 0 && lastDot < packageName.length() - 1) {
                // top 常截断为末段或前 15 字符
                shortName = packageName.substring(Math.max(0, packageName.length() - 15));
            }
            for (String line : output.split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || (!t.contains(packageName) && !t.contains(shortName))) continue;
                if (t.toLowerCase().startsWith("pid") || t.contains("USER") && t.contains("CPU")) continue;
                // 常见: PID USER PR NI VIRT RES SHR S [%CPU] %MEM TIME+ ARGS
                Matcher m = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s+\\d+(?:\\.\\d+)?\\s+[\\d:.]+\\s+\\S*"
                        + Pattern.quote(packageName.substring(0, Math.min(12, packageName.length())))).matcher(t);
                if (m.find()) {
                    double v = Double.parseDouble(m.group(1));
                    if (v >= 0 && v <= 800) return v; // 多核可 >100
                }
                // 宽松：行内第一个合理百分比后跟包名
                String[] parts = t.split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].contains(packageName) || parts[i].contains(shortName)) {
                        // 向前找像 CPU% 的数字
                        for (int j = Math.max(0, i - 6); j < i; j++) {
                            try {
                                double v = Double.parseDouble(parts[j].replace("%", ""));
                                if (v >= 0 && v <= 800) return v;
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.trace("getCpuFromTop 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 采集当前应用内存 PSS（KB）。按 {@code meminfoMinIntervalMs} 节流，间隔内复用缓存。
     */
    public Long getMemoryPssKb(String deviceId, String packageName) {
        if (deviceId == null || packageName == null) return null;
        String key = deviceId + "\0" + packageName;
        long now = System.currentTimeMillis();
        CachedMem cached = memCache.get(key);
        if (cached != null && meminfoMinIntervalMs > 0
                && (now - cached.atMs()) < meminfoMinIntervalMs) {
            return cached.pssKb();
        }
        Long pss = fetchMemoryPssKb(deviceId, packageName);
        memCache.put(key, new CachedMem(now, pss));
        return pss;
    }

    private Long fetchMemoryPssKb(String deviceId, String packageName) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "meminfo", packageName)
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            if (output == null) return null;
            for (String line : output.split("\n")) {
                if (line.contains("TOTAL") && line.contains("PSS")) {
                    Pattern pat = Pattern.compile("TOTAL\\s+\\d+\\s+\\d+\\s+(\\d+)");
                    Matcher m = pat.matcher(line);
                    if (m.find()) {
                        return Long.parseLong(m.group(1));
                    }
                    Pattern p2 = Pattern.compile("Total PSS.*?(\\d+)");
                    Matcher m2 = p2.matcher(line);
                    if (m2.find()) {
                        return Long.parseLong(m2.group(1));
                    }
                }
            }
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
     * 采集帧率/卡顿：可选 framestats 差分，否则 gfxinfo 汇总。
     */
    public FrameStats getFrameStats(String deviceId, String packageName) {
        if (deviceId == null || packageName == null || packageName.isBlank()) return null;
        if (preferFramestats) {
            FrameStats fromFs = getFrameStatsFromFramestats(deviceId, packageName);
            if (fromFs != null) return fromFs;
        }
        return getFrameStatsFromSummary(deviceId, packageName);
    }

    /**
     * gfxinfo framestats：解析 Flags / IntendedVsync / FrameCompleted 等列，
     * 相邻完成时间差 &gt; 16.67ms 计为 jank。
     */
    FrameStats getFrameStatsFromFramestats(String deviceId, String packageName) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell",
                    "dumpsys", "gfxinfo", packageName, "framestats")
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            try {
                Process reset = new ProcessBuilder("adb", "-s", deviceId, "shell",
                        "dumpsys", "gfxinfo", packageName, "reset")
                        .redirectErrorStream(true)
                        .start();
                readOutput(reset, 3000);
            } catch (Exception ignored) { }

            if (output == null || output.isBlank()) return null;
            if (!output.contains("---PROFILEDATA---") && !output.toLowerCase().contains("framestats")) {
                // 部分 OEM 无 framestats 段
                if (!output.contains(",")) return null;
            }

            java.util.List<Long> frameCompletedNs = new java.util.ArrayList<>();
            boolean inProfile = false;
            for (String line : output.split("\n")) {
                String t = line.trim();
                if (t.contains("---PROFILEDATA---")) {
                    inProfile = true;
                    continue;
                }
                if (t.startsWith("---") && inProfile) break;
                if (!inProfile && !t.matches("^\\d+,.*")) continue;
                if (t.isEmpty() || t.startsWith("Flags") || !Character.isDigit(t.charAt(0))) continue;
                String[] cols = t.split(",");
                // 标准 Profile data：Flags, IntendedVsync, Vsync, OldestInputEvent, NewestInputEvent,
                // HandleInputStart, AnimationStart, PerformTraversalsStart, DrawStart, SyncQueued,
                // SyncStart, IssueDrawCommandsStart, SwapBuffers, FrameCompleted, ...
                // FrameCompleted 通常为第 13 列（0-based index 13）或倒数若干列中最大时间戳
                if (cols.length < 8) continue;
                try {
                    long completed;
                    if (cols.length > 13) {
                        completed = Long.parseLong(cols[13].trim());
                    } else {
                        completed = Long.parseLong(cols[cols.length - 1].trim());
                    }
                    if (completed > 0) frameCompletedNs.add(completed);
                } catch (NumberFormatException ignored) { }
            }
            if (frameCompletedNs.size() < 2) return null;

            int janky = 0;
            double sumDeltaMs = 0;
            int deltas = 0;
            for (int i = 1; i < frameCompletedNs.size(); i++) {
                long dNs = frameCompletedNs.get(i) - frameCompletedNs.get(i - 1);
                if (dNs <= 0 || dNs > 1_000_000_000L) continue; // 忽略回绕/异常
                double dMs = dNs / 1_000_000.0;
                sumDeltaMs += dMs;
                deltas++;
                if (dMs > FRAME_BUDGET_MS) janky++;
            }
            if (deltas == 0) return null;
            double avgMs = sumDeltaMs / deltas;
            Double fps = avgMs > 0 ? Math.min(120.0, 1000.0 / avgMs) : null;
            double jankPercent = 100.0 * janky / deltas;
            return new FrameStats(fps, jankPercent, janky, deltas);
        } catch (Exception e) {
            log.trace("getFrameStatsFromFramestats 失败: {}", e.getMessage());
            return null;
        }
    }

    private FrameStats getFrameStatsFromSummary(String deviceId, String packageName) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "gfxinfo", packageName)
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(p, ADB_TIMEOUT_MS);
            try {
                Process reset = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "gfxinfo", packageName, "reset")
                        .redirectErrorStream(true)
                        .start();
                readOutput(reset, 3000);
            } catch (Exception ignored) { }

            if (output == null || output.isBlank()) return null;

            Double jankPercent = null;
            Integer jankyFrames = null;
            Integer totalFrames = null;
            Double fps = null;

            Matcher jankM = Pattern.compile("Janky frames:\\s*(\\d+)\\s*\\(([\\d.]+)\\s*%\\)").matcher(output);
            if (jankM.find()) {
                jankyFrames = Integer.parseInt(jankM.group(1));
                jankPercent = Double.parseDouble(jankM.group(2));
            }
            Matcher totalM = Pattern.compile("Total frames rendered:\\s*(\\d+)").matcher(output);
            if (totalM.find()) {
                totalFrames = Integer.parseInt(totalM.group(1));
            }
            Matcher p50M = Pattern.compile("50th percentile:\\s*(\\d+)\\s*ms").matcher(output);
            if (p50M.find()) {
                int p50 = Integer.parseInt(p50M.group(1));
                if (p50 > 0) {
                    fps = 1000.0 / p50;
                    if (fps > 120) fps = 120.0;
                }
            }
            if (fps == null && jankPercent == null && totalFrames == null) return null;
            return new FrameStats(fps, jankPercent, jankyFrames, totalFrames);
        } catch (Exception e) {
            log.trace("getFrameStatsFromSummary 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 一次采样：返回 cpu%、内存 PSS、网络 rx/tx 字节、电池温度（0.1℃）、FPS、卡顿占比。
     */
    public Sample sample(String deviceId, String packageName) {
        Double cpu = getCpuUsagePercent(deviceId, packageName);
        Long mem = getMemoryPssKb(deviceId, packageName);
        long[] net = getNetworkRxTxBytes(deviceId);
        Long rx = (net != null && net.length >= 2) ? net[0] : null;
        Long tx = (net != null && net.length >= 2) ? net[1] : null;
        Integer temp = getBatteryTemperatureTenthsC(deviceId);
        FrameStats frame = getFrameStats(deviceId, packageName);
        Double fps = frame != null ? frame.getFps() : null;
        Double jankPercent = frame != null ? frame.getJankPercent() : null;
        return new Sample(cpu, mem, rx, tx, temp, fps, jankPercent);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class Sample {
        private Double cpuPercent;
        private Long memoryPssKb;
        private Long networkRxBytes;
        private Long networkTxBytes;
        private Integer temperatureTenthsC;
        private Double fps;
        private Double jankPercent;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class FrameStats {
        private Double fps;
        private Double jankPercent;
        private Integer jankyFrames;
        private Integer totalFrames;
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
