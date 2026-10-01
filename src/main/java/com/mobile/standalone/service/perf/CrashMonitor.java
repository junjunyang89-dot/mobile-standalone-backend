package com.mobile.standalone.service.perf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 遍历期间盯着 logcat 抓崩溃和 ANR。
 * <p>
 * 采集器已经在真机上跑几十分钟了，但只记资源曲线——<b>崩了都不知道</b>。
 * 这是稳定性专项最核心的产出：跑完能说出「这一轮崩了 3 次，都在 LoginActivity:88」。
 */
@Slf4j
@Service
public class CrashMonitor {

    /**
     * 开始监控。
     * <p>
     * 先清一遍缓冲区：logcat 环形缓冲里躺着上一轮甚至上一个版本的崩溃，
     * 不清的话这一轮会把别人的账算到自己头上，「崩溃次数」这个指标直接失真。
     */
    public void start(String deviceId) {
        exec(new String[]{"adb", "-s", deviceId, "logcat", "-c"}, 5);
        // crash 缓冲区单独再清一次：-c 有些机型不会连 crash buffer 一起清
        exec(new String[]{"adb", "-s", deviceId, "logcat", "-b", "crash", "-c"}, 5);
    }

    /**
     * 收尾时把这段时间的崩溃/ANR 捞出来。
     * <p>
     * 用 {@code -d}（dump 完就退）而不是常驻读流：常驻要额外管一个进程的生命周期，
     * 而遍历本来就是有始有终的一段，结束时一次性取更简单，也不会漏。
     *
     * @param packageName 只关心这个包的
     */
    public List<CrashLogParser.Incident> collect(String deviceId, String packageName) {
        return collectSince(deviceId, packageName, null);
    }

    /**
     * 记下设备此刻的时间，收尾时用 {@link #collectSince} 只取这之后的。
     * <p>
     * 流程用例一天跑几十次，每次都 {@code logcat -c} 会把开发正要看的日志一起清掉；
     * 按时间切则什么都不删。取的是<b>设备</b>时钟，不是本机的——两边差几分钟很常见。
     *
     * @return logcat {@code -T} 认的格式 {@code MM-dd HH:mm:ss.000}；读不到返回 null
     */
    public String mark(String deviceId) {
        // 整条命令必须是一个参数且带引号：adb shell 会把参数拼成一串交给设备 shell 重新按空格拆，
        // 格式串里的空格会把它拆成两个参数，结果只剩「09-29」
        String out = exec(new String[]{"adb", "-s", deviceId, "shell", "date '+%m-%d %H:%M:%S'"}, 5).trim();
        return out.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}") ? out + ".000" : null;
    }

    /**
     * @param since {@link #mark} 的返回值；null 表示不按时间切（调用方应已 {@link #start} 清过缓冲）
     */
    public List<CrashLogParser.Incident> collectSince(String deviceId, String packageName, String since) {
        List<String> window = since == null ? List.of() : List.of("-T", since);
        // crash 缓冲区拿 Java/native 崩溃，main 缓冲区拿 ANR（ActivityManager 打在 main 里）
        String crash = exec(logcat(deviceId, window, "-b", "crash", "-d"), 20);
        String main = exec(logcat(deviceId, window, "-b", "main", "-d",
                "-s", "ActivityManager:E", "AndroidRuntime:E", "chromium:F"), 20);
        List<CrashLogParser.Incident> out = new java.util.ArrayList<>(
                CrashLogParser.parse(crash + "\n" + main, packageName));
        // events 不会被 logcat -c 清掉，没有时间窗就会把历史上的进程死亡全算进来
        boolean alreadyCrashed = out.stream().anyMatch(i -> !"ANR".equals(i.getType()));
        if (since != null && !alreadyCrashed) {
            String events = exec(logcat(deviceId, window, "-b", "events", "-d"), 20);
            out.addAll(CrashLogParser.unexpectedDeaths(events, main, packageName));
        }
        return out;
    }

    private static String[] logcat(String deviceId, List<String> window, String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("adb", "-s", deviceId, "logcat"));
        cmd.addAll(List.of(args));
        cmd.addAll(window);
        return cmd.toArray(String[]::new);
    }

    private String exec(String[] cmd, int timeoutSeconds) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    // logcat 可能非常大，超过这个量再往下读也没意义——
                    // 崩溃堆栈都在前面，后面全是业务日志
                    if (sb.length() > 2_000_000) {
                        break;
                    }
                }
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return sb.toString();
        } catch (Exception e) {
            // 抓不到崩溃日志不该让整轮性能测试白跑——资源曲线照样有价值
            log.warn("[crash-monitor] 执行失败 {}: {}", String.join(" ", cmd), e.getMessage());
            return "";
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
