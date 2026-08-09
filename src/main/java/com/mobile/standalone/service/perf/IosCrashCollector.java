package com.mobile.standalone.service.perf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * iOS 崩溃采集：靠 {@code idevicecrashreport}（libimobiledevice）把设备上的 {@code .ips} 导出来。
 * <p>
 * 跟 Android 的差别不只是命令不同：
 * <ul>
 *   <li>Android 的 logcat 是环形缓冲，读完还在；iOS 的崩溃是<b>文件</b>，导出默认会从设备上移走</li>
 *   <li>所以「开跑前清一遍」这件事在 iOS 上是真的把旧日志搬走，不是清缓冲</li>
 * </ul>
 */
@Slf4j
@Service
public class IosCrashCollector {

    /** 没装 libimobiledevice 时全链路降级，不该让性能测试跑不了 */
    private volatile Boolean available;

    public boolean isAvailable() {
        if (available == null) {
            available = which("idevicecrashreport");
        }
        return available;
    }

    /**
     * 开跑前把设备上已有的崩溃搬走。
     * <p>
     * 不清的话，上一轮甚至上个版本的崩溃会算到这一轮头上——
     * 「崩溃次数」这个指标正是要拿去和上个版本比的，混了就直接失真。
     */
    public void start(String udid) {
        if (!isAvailable()) {
            return;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("ios-crash-clear-");
            exec(new String[]{"idevicecrashreport", "-u", udid, tmp.toString()}, 60);
        } catch (Exception e) {
            log.warn("[ios-crash] 清理旧崩溃失败: {}", e.getMessage());
        } finally {
            deleteQuietly(tmp);
        }
    }

    /**
     * 收尾时导出并解析。
     *
     * @param bundleId 只关心这个 App 的
     */
    public List<CrashLogParser.Incident> collect(String udid, String bundleId) {
        List<CrashLogParser.Incident> out = new ArrayList<>();
        if (!isAvailable()) {
            return out;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ios-crash-");
            exec(new String[]{"idevicecrashreport", "-u", udid, dir.toString()}, 120);
            try (Stream<Path> files = Files.walk(dir)) {
                List<Path> ips = files.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".ips") || n.endsWith(".crash");
                        })
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
                for (Path p : ips) {
                    try {
                        CrashLogParser.Incident hit = IosCrashParser.parse(
                                Files.readString(p, StandardCharsets.UTF_8), bundleId);
                        if (hit != null) {
                            out.add(hit);
                        }
                    } catch (IOException e) {
                        log.debug("[ios-crash] 读取 {} 失败: {}", p.getFileName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            // 采不到崩溃不该让整轮真机跑白费——资源曲线照样有价值
            log.warn("[ios-crash] 采集失败: {}", e.getMessage());
        } finally {
            deleteQuietly(dir);
        }
        return out;
    }

    private boolean which(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).redirectErrorStream(true).start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            boolean ok = done && p.exitValue() == 0;
            if (!ok) {
                log.warn("[ios-crash] 未找到 {}，iOS 崩溃采集将跳过。安装：brew install libimobiledevice", cmd);
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private void exec(String[] cmd, int timeoutSeconds) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            log.warn("[ios-crash] 执行失败 {}: {}", String.join(" ", cmd), e.getMessage());
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清不掉不影响结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
