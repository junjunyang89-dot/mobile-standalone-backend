package com.mobile.standalone.service.harmony;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调用 scripts/harmony/hmdriver_bridge.py（基于 hmdriver2）执行鸿蒙 UI 操作。
 */
@Component
@Slf4j
public class HmdriverBridge {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${harmony.enabled:true}")
    private boolean enabled;

    @Value("${harmony.python-path:python3}")
    private String pythonPath;

    @Value("${harmony.bridge-script:scripts/harmony/hmdriver_bridge.py}")
    private String scriptPathConfig;

    @Value("${harmony.bridge-timeout-seconds:90}")
    private int timeoutSeconds;

    private Path scriptPath;
    private volatile Boolean probeOk;

    @PostConstruct
    public void init() {
        scriptPath = resolveScript();
        if (scriptPath == null) {
            log.warn("鸿蒙 bridge 脚本未找到: {}", scriptPathConfig);
        } else {
            log.info("鸿蒙 hmdriver bridge: {}", scriptPath.toAbsolutePath());
        }
    }

    public boolean available() {
        if (!enabled || scriptPath == null || !Files.isRegularFile(scriptPath)) {
            return false;
        }
        if (probeOk != null) {
            return probeOk;
        }
        JsonNode r = invoke(null, timeoutSeconds, "ping");
        probeOk = r != null && r.path("ok").asBoolean(false);
        if (!Boolean.TRUE.equals(probeOk)) {
            log.warn("hmdriver_bridge --ping 失败，UI 能力不可用（请 pip install -r scripts/harmony/requirements.txt）");
        }
        return Boolean.TRUE.equals(probeOk);
    }

    public JsonNode listDevices() {
        return invoke(null, timeoutSeconds, "list-devices");
    }

    public JsonNode deviceInfo(String serial) {
        return invoke(serial, timeoutSeconds, "device-info");
    }

    public boolean click(String serial, int x, int y) {
        return ok(invoke(serial, timeoutSeconds, "click", "--x", String.valueOf(x), "--y", String.valueOf(y)));
    }

    public boolean clickElement(String serial, String selector) {
        return ok(invoke(serial, timeoutSeconds, "click-element", "--selector", selector));
    }

    public boolean swipe(String serial, int x1, int y1, int x2, int y2, int durationMs) {
        return ok(invoke(serial, timeoutSeconds, "swipe",
                "--x1", String.valueOf(x1), "--y1", String.valueOf(y1),
                "--x2", String.valueOf(x2), "--y2", String.valueOf(y2),
                "--duration", String.valueOf(durationMs)));
    }

    public boolean inputText(String serial, String text) {
        return ok(invoke(serial, timeoutSeconds, "input-text", "--text", text));
    }

    public boolean inputTextToElement(String serial, String selector, String text) {
        return ok(invoke(serial, timeoutSeconds, "input-element", "--selector", selector, "--text", text));
    }

    public String screenshot(String serial, String savePath) {
        JsonNode r = invoke(serial, timeoutSeconds, "screenshot", "--path", savePath);
        if (ok(r)) {
            return r.path("path").asText(savePath);
        }
        return null;
    }

    public String dumpHierarchy(String serial) {
        JsonNode r = invoke(serial, Math.max(timeoutSeconds, 120), "dump");
        if (!ok(r)) {
            return null;
        }
        JsonNode h = r.get("hierarchy");
        if (h == null || h.isNull()) {
            return null;
        }
        if (h.isTextual()) {
            return h.asText();
        }
        return h.toString();
    }

    public boolean waitForElement(String serial, String selector, int timeoutSec) {
        return ok(invoke(serial, Math.max(timeoutSeconds, timeoutSec + 15),
                "wait-element", "--selector", selector, "--timeout", String.valueOf(timeoutSec)));
    }

    public boolean isElementPresent(String serial, String selector) {
        JsonNode r = invoke(serial, timeoutSeconds, "exists", "--selector", selector);
        return ok(r) && r.path("exists").asBoolean(false);
    }

    public boolean startApp(String serial, String packageName, String ability) {
        List<String> args = new ArrayList<>();
        args.add("start-app");
        args.add("--package");
        args.add(packageName);
        if (ability != null && !ability.isBlank()) {
            args.add("--ability");
            args.add(ability);
        }
        return ok(invoke(serial, timeoutSeconds, args.toArray(new String[0])));
    }

    public boolean stopApp(String serial, String packageName) {
        return ok(invoke(serial, timeoutSeconds, "stop-app", "--package", packageName));
    }

    public List<String> listApps(String serial) {
        JsonNode r = invoke(serial, Math.max(timeoutSeconds, 120), "list-apps");
        List<String> apps = new ArrayList<>();
        if (!ok(r)) {
            return apps;
        }
        JsonNode arr = r.path("apps");
        if (arr.isArray()) {
            arr.forEach(n -> apps.add(n.asText()));
        }
        return apps;
    }

    public boolean install(String serial, String hapPath) {
        return ok(invoke(serial, Math.max(timeoutSeconds, 300), "install", "--path", hapPath));
    }

    private boolean ok(JsonNode r) {
        return r != null && r.path("ok").asBoolean(false);
    }

    private JsonNode invoke(String serial, int timeoutSec, String... actionAndArgs) {
        if (scriptPath == null || !Files.isRegularFile(scriptPath)) {
            return null;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        cmd.add(scriptPath.toAbsolutePath().toString());
        if (serial != null && !serial.isBlank()) {
            cmd.add("--serial");
            cmd.add(serial);
        }
        for (String a : actionAndArgs) {
            cmd.add(a);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 不要 redirectErrorStream：hmdriver2 的 DEBUG 会污染 JSON
            pb.redirectErrorStream(false);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread errDrain = new Thread(() -> {
                try (BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = er.readLine()) != null) {
                        if (err.length() < 4000) {
                            err.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }, "hmdriver-stderr");
            errDrain.setDaemon(true);
            errDrain.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("hmdriver_bridge 超时: {}", String.join(" ", cmd));
                return null;
            }
            try {
                errDrain.join(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            String raw = out.toString().trim();
            String jsonLine = null;
            for (String line : raw.split("\\R")) {
                String t = line.trim();
                if (t.startsWith("{") && t.contains("\"ok\"")) {
                    jsonLine = t;
                }
            }
            if (jsonLine == null || jsonLine.isEmpty()) {
                log.warn("hmdriver_bridge 无 JSON 输出 exit={} err={}", p.exitValue(),
                        err.toString().trim());
                return null;
            }
            JsonNode node = JSON.readTree(jsonLine);
            if (!node.path("ok").asBoolean(false)) {
                log.debug("hmdriver_bridge 失败: {}", jsonLine.length() > 500 ? jsonLine.substring(0, 500) : jsonLine);
            }
            return node;
        } catch (Exception e) {
            log.warn("调用 hmdriver_bridge 失败: {}", e.getMessage());
            return null;
        }
    }

    private Path resolveScript() {
        if (scriptPathConfig != null && !scriptPathConfig.isBlank()) {
            Path p = Paths.get(scriptPathConfig);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
            Path cwd = Paths.get(System.getProperty("user.dir"), scriptPathConfig);
            if (Files.isRegularFile(cwd)) {
                return cwd.toAbsolutePath().normalize();
            }
        }
        Path alt = Paths.get("scripts/harmony/hmdriver_bridge.py").toAbsolutePath().normalize();
        return Files.isRegularFile(alt) ? alt : null;
    }
}
