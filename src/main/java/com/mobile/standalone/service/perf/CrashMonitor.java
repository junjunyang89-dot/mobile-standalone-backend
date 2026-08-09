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
        StringBuilder all = new StringBuilder();
        // crash 缓冲区拿 Java/native 崩溃，main 缓冲区拿 ANR（ActivityManager 打在 main 里）
        all.append(exec(new String[]{"adb", "-s", deviceId, "logcat", "-b", "crash", "-d"}, 20));
        all.append('\n');
        all.append(exec(new String[]{"adb", "-s", deviceId, "logcat", "-b", "main", "-d",
                "-s", "ActivityManager:E", "AndroidRuntime:E"}, 20));
        return CrashLogParser.parse(all.toString(), packageName);
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
