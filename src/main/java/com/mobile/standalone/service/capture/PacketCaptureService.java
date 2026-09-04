package com.mobile.standalone.service.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 抓包。
 * <p>
 * <b>安卓走纯 USB</b>：{@code adb reverse} 把手机上的 8080 转到电脑上，
 * 手机代理设成 {@code 127.0.0.1:8080} —— 流量从 USB 线走，不碰 WiFi。
 * 这意味着换个网络环境、连不上同一个局域网都不影响，插上线就能抓。
 * <p>
 * <b>iOS 做不到纯 USB</b>：系统的代理设置只挂在 WiFi 条目上，没有 adb reverse 的等价物。
 * 所以 iOS 要手机和电脑在同一个局域网，代理填电脑的内网 IP。
 * 这一条不写清楚的话，人会插上线等半天然后以为功能坏了。
 * <p>
 * 只留 {@code method/path/status/耗时/traceId}，<b>不存响应体</b>：
 * 一次抓包几十 MB，而「这个需求走了哪些接口」根本不需要 body。
 * <p>
 * H5 页面域名（{@code pogpog.gg}）必须 {@code --ignore-hosts} 原样转发。
 * 解密的话 X5 WebView 不认用户证书，动态 import 会 Failed to fetch。
 * 不能改成 {@code --allow-hosts} 只开白名单：mitmproxy 按 host:port 匹配，
 * 正则对不上时会变成「全部放行、一条都抓不到」。
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class PacketCaptureService {

    @Value("${app.capture.mitmdump:mitmdump}")
    private String mitmdumpBin;

    @Value("${app.capture.port:8080}")
    private int proxyPort;

    /** 只抓这些域名。留空＝全抓，但那样一次操作会抓出几十条埋点和图片 */
    @Value("${app.capture.hosts:}")
    private String defaultHosts;

    /**
     * 这些域名只转发、不解密。H5 页面和 CDN 必须走这里——解密的话
     * WebView 不认用户证书，动态加载的 JS 分包会直接 Failed to fetch。
     */
    @Value("${app.capture.passthrough-hosts:pogpog.gg,unpkg.com}")
    private String passthroughHosts;

    private final CaptureStore captureStore;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    /** 上一场抓包见到的域名。结束之后 session 就没了，但这份清单还得能查——
     *  「一条都没抓到」正是结束之后才发现的 */
    private volatile java.util.Map<String, Integer> lastSeen = java.util.Map.of();
    /** 上一场抓包的落盘位置。结束之后还要能点开看响应体——
     *  「抓完了再看」才是常态，抓的时候人在手机上操作，根本没空看屏幕 */
    private volatile Path lastOut;

    /**
     * 找到 mitmdump 可执行文件。
     * <p>
     * 不能直接依赖 PATH：从 IDE 或 launchd 起的进程拿不到登录 shell 的 PATH，
     * {@code /opt/homebrew/bin} 常常不在里面。同一个坑 AppiumServerManager
     * 已经踩过并用候选路径解决了，这里照做。
     * <p>
     * 配置里填了绝对路径就直接用它——那是给装在非常规位置的人留的出口。
     */
    private String resolveMitmdump() throws IOException {
        String configured = mitmdumpBin == null ? "" : mitmdumpBin.trim();
        if (configured.contains("/")) {
            if (Files.isExecutable(Path.of(configured))) {
                return configured;
            }
            throw new IOException("配置的 mitmdump 路径不可执行：" + configured
                    + "（app.capture.mitmdump）");
        }
        String home = System.getProperty("user.home");
        List<String> candidates = List.of(
                "/opt/homebrew/bin/mitmdump",              // Apple Silicon 的 brew
                "/usr/local/bin/mitmdump",                 // Intel 的 brew
                home + "/.local/bin/mitmdump",             // pipx / pip --user
                "/opt/homebrew/opt/mitmproxy/bin/mitmdump");
        for (String c : candidates) {
            if (Files.isExecutable(Path.of(c))) {
                return c;
            }
        }
        // 兜底交给 PATH：这条在终端里起的后端能走通，从 IDE 起的多半走不通
        if (looksOnPath(configured.isEmpty() ? "mitmdump" : configured)) {
            return configured.isEmpty() ? "mitmdump" : configured;
        }
        throw new IOException("没找到 mitmdump，抓包起不来。先装上：brew install mitmproxy"
                + "；装在别处的话在 application.yml 里配 app.capture.mitmdump 为绝对路径");
    }

    private boolean looksOnPath(String bin) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, bin))) {
                return true;
            }
        }
        return false;
    }

    public java.util.Map<String, Integer> lastSeenHosts() {
        return lastSeen;
    }

    /**
     * 某一条流水的详情：请求头、Cookies、请求/响应体。
     * <p>
     * 抓包最常用的就是看响应——原来要在手机上翻抓包工具再复制出来。
     * body 只在临时目录里躺着，<b>不进库</b>：几十 MB 的响应存进去，
     * 换来的只是把库撑大，而「这个需求走了哪些接口」根本不需要它。
     *
     * @param sessionId 会话 id；传 {@code last} 表示上一场已结束的抓包
     */
    public JsonNode body(String sessionId, int seq) {
        Path out = "last".equals(sessionId) ? lastOut : outOf(sessionId);
        if (out == null) {
            return null;
        }
        Path f = Path.of(out.toAbsolutePath() + ".details", seq + ".json");
        try {
            return Files.exists(f) ? json.readTree(Files.readString(f, StandardCharsets.UTF_8)) : null;
        } catch (Exception e) {
            log.warn("[capture] 读取 body 失败 seq={}: {}", seq, e.getMessage());
            return null;
        }
    }

    private Path outOf(String sessionId) {
        Session s = sessions.get(sessionId);
        return s == null ? lastOut : s.outFile;
    }

    @Getter
    public static class Session {
        private String id;
        private String deviceId;
        private String platform;
        private String requirementNo;
        private String hosts;
        private Path outFile;
        private Process process;
        private long startedAt;
        /** iOS 要人手动设代理，这里给出要填什么 */
        private String manualProxyTip;
    }

    /**
     * 开始抓包。
     *
     * @param deviceId  设备序列号；安卓用它做 adb reverse
     * @param platform  ANDROID / IOS
     * @param hosts     只抓这些域名，逗号分隔；留空用配置里的默认值
     */
    public Session start(String deviceId, String platform, String requirementNo, String hosts)
            throws IOException {
        if (!sessions.isEmpty()) {
            // 同时跑两场会抢同一个端口，而且抓出来的东西会混进同一个需求
            throw new IllegalStateException("已经有一场抓包在进行中，先结束它");
        }
        // 不再删上一场：那正是「重启/再抓一次就没了」的原因。
        // 磁盘增长改由 CaptureStore 的保留策略兜（默认留 30 天）
        Path script = extractScript();
        String sessionId = "cap-" + System.currentTimeMillis();
        // 落到稳定目录而不是 createTempFile：/tmp 会被系统清理，
        // 而压测配比和「昨天那次再看一眼」都要求它留得住
        Path out = captureStore.flowFileOf(sessionId);
        String hostList = hosts == null || hosts.isBlank() ? defaultHosts : hosts.trim();

        List<String> cmd = new ArrayList<>(List.of(
                resolveMitmdump(), "-q", "-p", String.valueOf(proxyPort),
                "-s", script.toAbsolutePath().toString(),
                "--set", "out=" + out.toAbsolutePath(),
                "--set", "hosts=" + hostList));
        // 只放行 H5/CDN，不解密。不能用 --allow-hosts 只开白名单：
        // mitmproxy 按 host:port 匹配，正则稍错就会变成「全部放行、一条都抓不到」。
        appendIgnoreHosts(cmd, passthroughHosts);
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(
                        out.getParent().resolve(out.getFileName() + ".log").toFile()))
                .start();

        Session s = new Session();
        s.id = sessionId;
        s.deviceId = deviceId;
        s.platform = platform == null ? "ANDROID" : platform.toUpperCase(Locale.ROOT);
        s.requirementNo = requirementNo;
        s.hosts = hostList;
        s.outFile = out;
        s.process = p;
        s.startedAt = System.currentTimeMillis();

        // mitmdump 起来要一两秒，太早设代理会让手机第一批请求直接失败
        sleep(2000);
        if (!p.isAlive()) {
            throw new IOException("mitmdump 没起来，检查是否已安装：brew install mitmproxy");
        }

        if ("ANDROID".equals(s.platform)) {
            s.manualProxyTip = setupAndroid(deviceId);
        } else {
            s.manualProxyTip = iosTip();
        }
        sessions.put(s.id, s);
        captureStore.open(s.id, deviceId, s.platform, requirementNo, null, hostList, out.getParent());
        log.info("[capture] 开始 {} device={} 域名={} 不解密={} 落盘={}",
                s.id, deviceId, hostList, ignoreHostPatterns(passthroughHosts), out.getParent());
        return s;
    }

    /**
     * 安卓：adb reverse + 设备代理，全程走 USB。
     * <p>
     * reverse 必须在设代理<b>之前</b>做完：先设代理的话，手机会立刻往一个还没通的端口发请求，
     * 那几秒里所有网络请求都是失败的，而人会以为是抓包把网弄坏了。
     */
    private String setupAndroid(String deviceId) {
        exec("adb", "-s", deviceId, "reverse", "tcp:" + proxyPort, "tcp:" + proxyPort);
        exec("adb", "-s", deviceId, "shell", "settings", "put", "global",
                "http_proxy", "127.0.0.1:" + proxyPort);
        return null;
    }

    private void teardownAndroid(String deviceId) {
        // 顺序和设置时相反：先撤代理再撤 reverse。
        // 反过来的话，手机会有一小段时间指向一个已经不存在的端口，整机断网
        exec("adb", "-s", deviceId, "shell", "settings", "put", "global", "http_proxy", ":0");
        exec("adb", "-s", deviceId, "shell", "settings", "delete", "global", "http_proxy");
        exec("adb", "-s", deviceId, "reverse", "--remove", "tcp:" + proxyPort);
    }

    private String iosTip() {
        String ip = lanIp();
        return "iOS 的代理只能挂在 WiFi 上，没有 USB 直连的办法。"
                + "请在 手机 设置 → 无线局域网 → 当前网络 → 配置代理 → 手动，"
                + "服务器填 " + (ip == null ? "（本机内网 IP）" : ip) + "，端口填 " + proxyPort
                + "。抓完记得改回「关闭」。";
    }

    /** 结束抓包，返回抓到的请求 */
    public List<JsonNode> stop(String sessionId) {
        Session s = sessions.remove(sessionId);
        if (s == null) {
            return List.of();
        }
        try {
            if ("ANDROID".equals(s.platform)) {
                teardownAndroid(s.deviceId);
            }
        } catch (Exception e) {
            // 撤代理失败要吼出来：手机会一直指着一个已经关掉的代理，表现是整机上不了网，
            // 而人不会把它和「刚才抓过包」联系起来
            log.error("[capture] 撤销设备代理失败！手机可能上不了网，请手动关掉代理设置", e);
        }
        try {
            s.process.destroy();
            if (!s.process.waitFor(5, TimeUnit.SECONDS)) {
                s.process.destroyForcibly();
            }
        } catch (Exception e) {
            log.warn("[capture] 停止 mitmdump 失败: {}", e.getMessage());
        }
        List<JsonNode> rows = readFlows(s.outFile);
        lastSeen = readSeen(s.outFile);
        lastOut = s.outFile;
        captureStore.close(sessionId, rows.size(), lastSeen.size());
        log.info("[capture] 结束 {}，抓到 {} 条（代理上共见到 {} 个域名）",
                sessionId, rows.size(), lastSeen.size());
        return rows;
    }

    /** 抓的是哪台设备——刷新页面后要能显示回来，否则不知道正在抓谁 */
    public String deviceOf(String sessionId) {
        Session s = sessions.get(sessionId);
        return s == null ? null : s.getDeviceId();
    }

    /** 抓包过程中实时看到了什么——不结束也能看，否则你不知道代理到底通没通 */
    public List<JsonNode> peek(String sessionId) {
        Session s = sessions.get(sessionId);
        return s == null ? List.of() : readFlows(s.outFile);
    }

    /**
     * 代理上看到过的<b>全部</b>域名（含被白名单挡掉的）及次数。
     * <p>
     * 「一条都没抓到」有三种原因，排查方向完全不同：
     * 代理没通（这里是空的）、证书没装（这里是空的但手机上网也断了）、
     * 域名不在白名单（这里有一堆域名）。有了这份清单，第三种一眼就能看出来。
     */
    public java.util.Map<String, Integer> seenHosts(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null) {
            return java.util.Map.of();
        }
        return readSeen(s.outFile);
    }

    /**
     * 握手/连接<b>失败</b>的域名。
     * <p>
     * 这是「抓不到」里最难自己想明白的一种：代理通着、白名单也对，就是一条都不出来。
     * 原因是 App 固定了证书，连接根本建不起来——既没有 response 也不会进 seen。
     * 不单独标出来的话，人会一直以为是自己配错了。
     */
    public java.util.Map<String, Integer> failedHosts(String sessionId) {
        Session s = sessions.get(sessionId);
        return readMap(s == null ? lastOut : s.outFile, ".failed");
    }

    public java.util.Map<String, Integer> lastFailedHosts() {
        return readMap(lastOut, ".failed");
    }

    /** 包级可见：这份诊断是「抓不到接口」时唯一的解释来源，格式坏了必须有测试兜住 */
    java.util.Map<String, Integer> readSeen(Path out) {
        return readMap(out, ".hosts");
    }

    java.util.Map<String, Integer> readMap(Path out, String suffix) {
        if (out == null) {
            return java.util.Map.of();
        }
        try {
            Path f = Path.of(out.toAbsolutePath() + suffix);
            if (!Files.exists(f)) {
                return java.util.Map.of();
            }
            JsonNode n = json.readTree(Files.readString(f, StandardCharsets.UTF_8));
            java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
            n.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asInt()));
            return m;
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    public Session current() {
        return sessions.values().stream().findFirst().orElse(null);
    }

    private List<JsonNode> readFlows(Path file) {
        List<JsonNode> out = new ArrayList<>();
        if (file == null || !Files.exists(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(json.readTree(line));
                } catch (Exception ignore) {
                    // 单行坏掉不该丢掉整场抓包
                }
            }
        } catch (IOException e) {
            log.warn("[capture] 读取抓包结果失败: {}", e.getMessage());
        }
        return out;
    }

    /** 插件脚本打在 jar 里，跑之前先落到临时目录——mitmdump 只认文件路径 */
    private Path extractScript() throws IOException {
        Path target = Path.of(System.getProperty("java.io.tmpdir"), "mobile-standalone-flow-writer.py");
        try (InputStream in = new ClassPathResource("capture/flow_writer.py").getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private void appendIgnoreHosts(List<String> cmd, String passthrough) {
        for (String pattern : ignoreHostPatterns(passthrough)) {
            cmd.add("--ignore-hosts");
            cmd.add(pattern);
        }
    }

    /**
     * mitmproxy 的 ignore/allow 匹配的是 {@code host:port}（如 {@code gateway-qa.arca42.com:443}）。
     * 正则如果用 {@code .com$} 收尾，接口域名一条都对不上，表现就是页面能开、抓包是空的。
     */
    static List<String> ignoreHostPatterns(String passthrough) {
        List<String> out = new ArrayList<>();
        for (String host : splitHosts(passthrough)) {
            out.add(ignoreHostRegex(host));
        }
        return out;
    }

    static String ignoreHostRegex(String host) {
        // mitmproxy 用 Python re，不能用 Java 的 \Q...\E
        String escaped = host.replace("\\", "\\\\").replace(".", "\\.");
        if (host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            return "^" + escaped + "(:\\d+)?$";
        }
        return "^(.+\\.)?" + escaped + "(:\\d+)?$";
    }

    private static List<String> splitHosts(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String h = part.trim().toLowerCase(Locale.ROOT);
            if (!h.isEmpty()) {
                out.add(h);
            }
        }
        return out;
    }

    private String lanIp() {
        try {
            var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                var ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignore) {
            // 拿不到就让人自己填，不要因为这个让整个抓包起不来
        }
        return null;
    }

    private void exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[capture] 命令失败 {}: {}", String.join(" ", cmd), e.getMessage());
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 服务关掉时把没结束的抓包收尾。
     * <p>
     * 少了这一步，重启后端就会留下一台代理还指向已经关掉的端口的手机——
     * 而那台手机是上不了网的，人只会觉得「手机坏了」。
     */
    @PreDestroy
    public void shutdown() {
        for (String id : List.copyOf(sessions.keySet())) {
            try {
                stop(id);
            } catch (Exception ignore) {
                // 关机路径上尽力而为
            }
        }
    }
}
