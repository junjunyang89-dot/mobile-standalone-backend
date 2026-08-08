package com.mobile.standalone.service.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * iOS Tidevice Python 旁路：读 stdout NDJSON，映射到统一 {@link PerformanceMetricSample}。
 * 按 deviceId（UDID）隔离会话。iOS 17+ 可能不稳定，探测/启动失败由 Resolver 回退 NONE。
 */
@Component
@Slf4j
public class TidevicePerformanceCollector implements PerformanceMetricCollector {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${performance.tidevice.enabled:true}")
    private boolean enabled;

    @Value("${performance.tidevice.python-path:python3}")
    private String pythonPath;

    @Value("${performance.tidevice.script-path:scripts/perf/ios_tidevice_sampler.py}")
    private String scriptPathConfig;

    @Value("${performance.tidevice.sample-interval-ms:2000}")
    private long sampleIntervalMs;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile Boolean probeOk;

    private static final class Session {
        final AtomicReference<PerformanceMetricSample> latest = new AtomicReference<>();
        volatile Process process;
        volatile Thread readerThread;
    }

    @Override
    public String engineId() {
        return "TIDEVICE";
    }

    @Override
    public synchronized boolean available(String deviceId, String packageName) {
        if (!enabled) return false;
        if (deviceId == null || deviceId.isBlank() || packageName == null || packageName.isBlank()) {
            return false;
        }
        Path script = resolveScriptPath();
        if (script == null || !Files.isRegularFile(script)) {
            log.debug("Tidevice 脚本不存在: {}", script);
            return false;
        }
        if (probeOk != null) return probeOk;
        probeOk = probe(script);
        return probeOk;
    }

    private boolean probe(Path script) {
        try {
            Process p = new ProcessBuilder(pythonPath, script.toAbsolutePath().toString(), "--ping")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
                out = sb.toString();
            }
            if (p.exitValue() != 0) {
                log.info("Tidevice --ping 失败 exit={} out={}", p.exitValue(), out);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.info("Tidevice 探测失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void startSession(String deviceId, String packageName) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalStateException("deviceId(UDID) 为空");
        }
        stopSession(deviceId);
        Path script = resolveScriptPath();
        if (script == null) {
            throw new IllegalStateException("Tidevice 脚本路径无效");
        }
        Session session = new Session();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pythonPath,
                    script.toAbsolutePath().toString(),
                    "--device", deviceId,
                    "--pkg", packageName,
                    "--interval", String.valueOf(Math.max(500, sampleIntervalMs) / 1000.0)
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            session.process = proc;
            Thread reader = new Thread(() -> readLoop(proc, session), "tidevice-stdout-" + deviceId);
            reader.setDaemon(true);
            session.readerThread = reader;
            sessions.put(deviceId, session);
            reader.start();
            log.info("Tidevice 采样进程已启动 udid={} bundle={}", deviceId, packageName);
        } catch (Exception e) {
            stopSession(deviceId);
            throw new IllegalStateException("启动 Tidevice 采样失败: " + e.getMessage(), e);
        }
    }

    private void readLoop(Process proc, Session session) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;
                try {
                    JsonNode n = JSON.readTree(line);
                    if (n.has("waiting") && n.get("waiting").asBoolean(false)
                            && !n.has("cpu") && !n.has("mem_pss_kb") && !n.has("fps")) {
                        continue;
                    }
                    Double cpu = n.has("cpu") && !n.get("cpu").isNull() ? n.get("cpu").asDouble() : null;
                    Long mem = n.has("mem_pss_kb") && !n.get("mem_pss_kb").isNull()
                            ? n.get("mem_pss_kb").asLong() : null;
                    Double fps = n.has("fps") && !n.get("fps").isNull() ? n.get("fps").asDouble() : null;
                    Double jank = n.has("jank_percent") && !n.get("jank_percent").isNull()
                            ? n.get("jank_percent").asDouble() : null;
                    Long rx = n.has("network_rx") && !n.get("network_rx").isNull()
                            ? n.get("network_rx").asLong() : null;
                    Long tx = n.has("network_tx") && !n.get("network_tx").isNull()
                            ? n.get("network_tx").asLong() : null;
                    session.latest.set(new PerformanceMetricSample(cpu, mem, rx, tx, null, fps, jank));
                } catch (Exception e) {
                    log.trace("Tidevice NDJSON 解析失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Tidevice stdout 读取结束: {}", e.getMessage());
        }
    }

    @Override
    public PerformanceMetricSample sample(String deviceId, String packageName) {
        Session session = deviceId != null ? sessions.get(deviceId) : null;
        if (session == null) {
            return new PerformanceMetricSample(null, null, null, null, null, null, null);
        }
        PerformanceMetricSample s = session.latest.get();
        return s != null ? s : new PerformanceMetricSample(null, null, null, null, null, null, null);
    }

    @Override
    public void stopSession() {
        // 需带 deviceId
    }

    @Override
    public void stopSession(String deviceId) {
        if (deviceId == null) return;
        Session session = sessions.remove(deviceId);
        if (session == null) return;
        Process p = session.process;
        Thread t = session.readerThread;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @PreDestroy
    public void destroyAll() {
        for (String id : sessions.keySet().toArray(new String[0])) {
            stopSession(id);
        }
    }

    public synchronized void invalidateProbeCache() {
        probeOk = null;
    }

    private Path resolveScriptPath() {
        if (scriptPathConfig != null && !scriptPathConfig.isBlank()) {
            Path p = Paths.get(scriptPathConfig).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) return p;
        }
        Path cwd = Paths.get("scripts/perf/ios_tidevice_sampler.py").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd)) return cwd;
        return Paths.get(System.getProperty("user.dir"), "scripts/perf/ios_tidevice_sampler.py");
    }
}
