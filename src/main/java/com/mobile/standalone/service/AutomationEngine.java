package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动化测试引擎
 * 支持 Android、HarmonyOS、iOS 设备的 UI 自动化操作
 * 参考 DevEco Testing 工具的实现方式
 */
@Component
@Slf4j
public class AutomationEngine {
    
    /**
     * 点击屏幕坐标
     */
    public boolean click(String deviceId, Device.Platform platform, int x, int y) {
        log.info("点击设备 {} 坐标 ({}, {})", deviceId, x, y);
        
        try {
            if (Device.usesAndroidAutomation(platform)) {
                return clickAndroid(deviceId, x, y);
            } else if (Device.usesIosAutomation(platform)) {
                return clickIOS(deviceId, x, y);
            }
        } catch (Exception e) {
            log.error("点击操作失败", e);
            return false;
        }
        
        return false;
    }
    
    /**
     * 点击元素（通过资源ID、文本、XPath等）
     */
    public boolean clickElement(String deviceId, Device.Platform platform, String selector) {
        log.info("点击设备 {} 元素: {}", deviceId, selector);
        
        try {
            if (Device.usesAndroidAutomation(platform)) {
                return clickAndroidElement(deviceId, selector);
            } else if (Device.usesIosAutomation(platform)) {
                return clickIOSElement(deviceId, selector);
            }
        } catch (Exception e) {
            log.error("点击元素失败", e);
            return false;
        }
        
        return false;
    }
    
    /**
     * 滑动操作
     */
    public boolean swipe(String deviceId, Device.Platform platform, int x1, int y1, int x2, int y2, int duration) {
        log.info("滑动设备 {} 从 ({}, {}) 到 ({}, {})", deviceId, x1, y1, x2, y2);
        
        try {
            if (Device.usesAndroidAutomation(platform)) {
                return swipeAndroid(deviceId, x1, y1, x2, y2, duration);
            } else if (Device.usesIosAutomation(platform)) {
                return swipeIOS(deviceId, x1, y1, x2, y2, duration);
            }
        } catch (Exception e) {
            log.error("滑动操作失败", e);
            return false;
        }
        
        return false;
    }
    
    /**
     * 输入文本
     */
    public boolean inputText(String deviceId, Device.Platform platform, String text) {
        log.info("在设备 {} 输入文本: {}", deviceId, text);
        
        try {
            if (Device.usesAndroidAutomation(platform)) {
                return inputTextAndroid(deviceId, text);
            } else if (Device.usesIosAutomation(platform)) {
                return inputTextIOS(deviceId, text);
            }
        } catch (Exception e) {
            log.error("输入文本失败", e);
            return false;
        }
        
        return false;
    }
    
    /**
     * 获取屏幕截图
     */
    public String takeScreenshot(String deviceId, Device.Platform platform, String savePath) {
        log.info("截取设备 {} 屏幕，保存到: {}", deviceId, savePath);
        
        try {
            if (Device.usesAndroidAutomation(platform)) {
                return takeScreenshotAndroid(deviceId, savePath);
            } else if (Device.usesIosAutomation(platform)) {
                return takeScreenshotIOS(deviceId, savePath);
            }
        } catch (Exception e) {
            log.error("截图失败", e);
            return null;
        }
        
        return null;
    }
    
    /**
     * 获取当前页面元素树（UI Hierarchy）
     */
    public String getPageSource(String deviceId, Device.Platform platform) {
        log.info("获取设备 {} 页面元素树", deviceId);
        
        try {
            if (Device.usesAndroidAutomation(platform)) {
                return getPageSourceAndroid(deviceId);
            } else if (Device.usesIosAutomation(platform)) {
                return getPageSourceIOS(deviceId);
            }
        } catch (Exception e) {
            log.error("获取页面元素树失败", e);
            return null;
        }
        
        return null;
    }
    
    /**
     * 等待元素出现
     */
    public boolean waitForElement(String deviceId, Device.Platform platform, String selector, int timeoutSeconds) {
        log.info("等待设备 {} 元素出现: {}, 超时: {}秒", deviceId, selector, timeoutSeconds);
        
        long startTime = System.currentTimeMillis();
        long timeout = timeoutSeconds * 1000L;
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (isElementPresent(deviceId, platform, selector)) {
                return true;
            }
            try {
                Thread.sleep(500); // 每500ms检查一次
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * 检查元素是否存在
     */
    public boolean isElementPresent(String deviceId, Device.Platform platform, String selector) {
        try {
            if (Device.usesAndroidAutomation(platform)) {
                return isElementPresentAndroid(deviceId, selector);
            } else if (Device.usesIosAutomation(platform)) {
                return isElementPresentIOS(deviceId, selector);
            }
        } catch (Exception e) {
            log.error("检查元素存在性失败", e);
            return false;
        }
        
        return false;
    }
    
    // ========== Android/HarmonyOS 实现 ==========
    
    /**
     * {@code adb shell input} 的超时下限。
     *
     * <p>看着像「发个点击而已，3 秒还不够？」——不够。{@code input} 不是内核调用，
     * 是一个要现拉起 JVM 的 app_process 程序，光启动就要一两秒；
     * 模拟器只给了 1 个核（AVD 默认就是 {@code hw.cpu.ncore=1}）时，五六秒是常态。
     *
     * <p>超时的表现特别有误导性：进程被 destroyForcibly 掉，返回 false，
     * 上层报「点击失败 / 滑动失败」——看起来像定位没命中或者手势没生效，
     * 实际上命令根本没来得及跑完。为这个查错方向能白花一下午。
     */
    private static final long INPUT_CMD_TIMEOUT_MS = 20_000;

    private boolean clickAndroid(String deviceId, int x, int y) throws IOException, InterruptedException {
        return runInputCmd(deviceId, INPUT_CMD_TIMEOUT_MS,
                "input", "tap", String.valueOf(x), String.valueOf(y));
    }

    /**
     * 跑一条 {@code adb shell} 命令，超时和失败都把设备端的输出带出来。
     * <p>以前失败只留一个 false，日志里什么都没有——「滑动失败」查不出是超时还是命令报错。
     */
    private boolean runInputCmd(String deviceId, long timeoutMs, String... args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("adb", "-s", deviceId, "shell"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            } catch (Exception ignored) {
                // 超时杀进程时流会断，正常
            }
        }, "adb-input-drain");
        drain.setDaemon(true);
        drain.start();
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            drain.join(300);
            log.warn("adb {} 超时 {}ms: deviceId={}——设备太慢（模拟器给的核数/内存偏低？），命令没跑完就被掐了。已输出: {}",
                    String.join(" ", args), timeoutMs, deviceId, out.toString().trim());
            return false;
        }
        drain.join(800);
        if (p.exitValue() != 0) {
            log.warn("adb {} 失败 exit={} deviceId={} out={}",
                    String.join(" ", args), p.exitValue(), deviceId, out.toString().trim());
            return false;
        }
        return true;
    }
    
    private boolean clickAndroidElement(String deviceId, String selector) throws IOException, InterruptedException {
        // 支持多种选择器格式：
        // 1. resource-id: com.example:id/button
        // 2. text: 登录
        // 3. xpath: //android.widget.Button[@text='登录']
        // 4. coordinate:540,1200 （弹窗无障碍树不可见时）
        if (selector.startsWith("coordinate:") || selector.startsWith("coord:")
                || selector.startsWith("tap:") || selector.startsWith("point:")) {
            String raw = selector.substring(selector.indexOf(':') + 1).trim();
            String[] parts = raw.split(",");
            if (parts.length == 2) {
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    return clickAndroid(deviceId, x, y);
                } catch (NumberFormatException e) {
                    log.warn("无效坐标选择器: {}", selector);
                    return false;
                }
            }
        }
        if (selector.matches("^-?\\d+\\s*,\\s*-?\\d+$")) {
            String[] parts = selector.split(",");
            return clickAndroid(deviceId, Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        }
        
        if (selector.startsWith("resource-id:") || selector.startsWith("id:")) {
            String resourceId = selector.contains(":") ? selector.split(":", 2)[1] : selector;
            return clickByResourceId(deviceId, resourceId);
        } else if (selector.startsWith("text:")) {
            String text = selector.split(":", 2)[1];
            return clickByText(deviceId, text);
        } else if (selector.startsWith("xpath:")) {
            String xpath = selector.split(":", 2)[1];
            return clickByXPath(deviceId, xpath);
        } else {
            // 默认按文本处理
            return clickByText(deviceId, selector);
        }
    }
    
    private boolean clickByResourceId(String deviceId, String resourceId) throws IOException, InterruptedException {
        String pageSource = getPageSourceAndroid(deviceId);
        if (pageSource == null || pageSource.isBlank()) {
            return false;
        }
        int[] center = findBoundsCenter(pageSource, "resource-id=\"" + resourceId + "\"");
        if (center == null) {
            center = findBoundsCenter(pageSource, "resource-id='" + resourceId + "'");
        }
        if (center == null) {
            // 短 id 兜底：如 sideView
            String shortId = resourceId.contains("/") ? resourceId.substring(resourceId.lastIndexOf('/') + 1) : resourceId;
            if (!shortId.equals(resourceId)) {
                int idx = pageSource.indexOf(":id/" + shortId + "\"");
                if (idx < 0) idx = pageSource.indexOf("id/" + shortId);
                if (idx > 0) {
                    center = findBoundsCenterNear(pageSource, idx);
                }
            }
        }
        if (center == null) {
            log.warn("未在 dump 中找到 resource-id 坐标: {}", resourceId);
            return false;
        }
        return clickAndroid(deviceId, center[0], center[1]);
    }

    private boolean clickByText(String deviceId, String text) throws IOException, InterruptedException {
        String pageSource = getPageSourceAndroid(deviceId);
        if (pageSource == null || pageSource.isBlank()) {
            return false;
        }
        int[] center = findBoundsCenter(pageSource, "text=\"" + text + "\"");
        if (center == null) {
            center = findBoundsCenter(pageSource, "content-desc=\"" + text + "\"");
        }
        if (center == null) {
            log.warn("未在 dump 中找到文本坐标: {}", text);
            return false;
        }
        return clickAndroid(deviceId, center[0], center[1]);
    }

    /** 在含 marker 的节点附近解析 bounds="[l,t][r,b]" 并返回中心点 */
    private static int[] findBoundsCenter(String xml, String marker) {
        int idx = xml.indexOf(marker);
        if (idx < 0) return null;
        return findBoundsCenterNear(xml, idx);
    }

    private static int[] findBoundsCenterNear(String xml, int aroundIdx) {
        int nodeStart = xml.lastIndexOf('<', aroundIdx);
        int nodeEnd = xml.indexOf('>', aroundIdx);
        if (nodeStart < 0 || nodeEnd < 0 || nodeEnd <= nodeStart) return null;
        String node = xml.substring(nodeStart, nodeEnd + 1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")
                .matcher(node);
        if (!m.find()) return null;
        int l = Integer.parseInt(m.group(1));
        int t = Integer.parseInt(m.group(2));
        int r = Integer.parseInt(m.group(3));
        int b = Integer.parseInt(m.group(4));
        return new int[]{(l + r) / 2, (t + b) / 2};
    }
    
    private boolean clickByXPath(String deviceId, String xpath) throws IOException, InterruptedException {
        // 使用 uiautomator 或 Appium 的 XPath 支持
        // 这里需要集成 uiautomator2 或 Appium
        return false; // TODO: 实现 XPath 支持
    }
    
    private boolean swipeAndroid(String deviceId, int x1, int y1, int x2, int y2, int duration) throws IOException, InterruptedException {
        // 滑动本身要耗掉 duration 毫秒，超时必须把这段加上——
        // 否则「滑得慢一点」和「设备卡」这两件事会一起把它顶穿
        long timeout = Math.max(INPUT_CMD_TIMEOUT_MS, duration + 15_000L);
        return runInputCmd(deviceId, timeout, "input", "swipe",
                String.valueOf(x1), String.valueOf(y1),
                String.valueOf(x2), String.valueOf(y2),
                String.valueOf(duration));
    }
    
    private boolean inputTextAndroid(String deviceId, String text) throws IOException, InterruptedException {
        // 先点击输入框（需要知道输入框位置或使用元素定位）
        // 然后输入文本
        // 走 adb input text，完全不碰设备上的软键盘——
        // 模拟器上 Gboard 每敲一个数字都要重绘一次，1 核机上人肉输入卡到没法用，
        // 而这条命令是一次性把整串塞进焦点控件的
        return runInputCmd(deviceId, INPUT_CMD_TIMEOUT_MS, "input", "text", text);
    }
    
    private String takeScreenshotAndroid(String deviceId, String savePath) throws IOException, InterruptedException {
        java.io.File outFile = new java.io.File(savePath);
        java.io.File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (screencapExecOut(deviceId, savePath) || screencapPull(deviceId, savePath)) {
            com.mobile.standalone.service.inspect.CrushedScreenshotLift.liftIfNeeded(outFile.toPath());
            return savePath;
        }
        log.warn("ADB 截图失败: deviceId={} savePath={}", deviceId, savePath);
        return null;
    }

    /** 不落地设备文件。读流必须另开线程，否则 dump/screencap 卡住时永远走不到 waitFor。 */
    private boolean screencapExecOut(String deviceId, String savePath) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("adb", "-s", deviceId, "exec-out", "screencap", "-p").start();
        java.nio.file.Path dest = java.nio.file.Path.of(savePath);
        Thread drain = new Thread(() -> {
            try (var in = p.getInputStream(); var out = java.nio.file.Files.newOutputStream(dest)) {
                in.transferTo(out);
            } catch (Exception ignored) {
                // 超时杀进程时流会断
            }
        }, "screencap-drain");
        drain.setDaemon(true);
        drain.start();
        if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            drain.join(500);
            return false;
        }
        drain.join(1500);
        java.io.File f = new java.io.File(savePath);
        return p.exitValue() == 0 && f.length() > 100 && isPng(f);
    }

    private boolean screencapPull(String deviceId, String savePath) throws IOException, InterruptedException {
        String devicePath = "/data/local/tmp/ms_screenshot.png";
        Process p1 = new ProcessBuilder("adb", "-s", deviceId, "shell", "screencap", "-p", devicePath)
                .redirectErrorStream(true).start();
        if (!p1.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
            p1.destroyForcibly();
            return false;
        }
        Process p2 = new ProcessBuilder("adb", "-s", deviceId, "pull", devicePath, savePath)
                .redirectErrorStream(true).start();
        if (!p2.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
            p2.destroyForcibly();
            return false;
        }
        java.io.File f = new java.io.File(savePath);
        return p2.exitValue() == 0 && f.length() > 100;
    }

    private static boolean isPng(java.io.File f) {
        try (var in = java.nio.file.Files.newInputStream(f.toPath())) {
            byte[] h = in.readNBytes(4);
            return h.length == 4 && h[0] == (byte) 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
        } catch (Exception e) {
            return false;
        }
    }
    
    private String getPageSourceAndroid(String deviceId) throws IOException, InterruptedException {
        // 写入设备文件再 pull：MiUI 上 dump /dev/tty 常把异常栈混进 XML，导致永远匹配不到元素
        String remote = "/sdcard/ms_uidump.xml";
        Process dump = new ProcessBuilder("adb", "-s", deviceId, "shell", "uiautomator", "dump", remote)
                .redirectErrorStream(true)
                .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(dump.getInputStream()))) {
            while (reader.readLine() != null) { /* drain */ }
        }
        dump.waitFor();

        java.nio.file.Path local = java.nio.file.Files.createTempFile("ms_uidump_", ".xml");
        try {
            Process pull = new ProcessBuilder("adb", "-s", deviceId, "pull", remote, local.toString())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(pull.getInputStream()))) {
                while (reader.readLine() != null) { /* drain */ }
            }
            pull.waitFor();
            if (!java.nio.file.Files.isRegularFile(local) || java.nio.file.Files.size(local) == 0) {
                log.warn("uiautomator dump pull 失败或为空: deviceId={}", deviceId);
                return null;
            }
            return java.nio.file.Files.readString(local, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(local);
            } catch (Exception ignored) {
            }
        }
    }
    
    private boolean isElementPresentAndroid(String deviceId, String selector) throws IOException, InterruptedException {
        String pageSource = getPageSourceAndroid(deviceId);
        if (pageSource == null || pageSource.isBlank()) {
            return false;
        }
        
        // 根据选择器类型检查元素是否存在
        if (selector.startsWith("resource-id:") || selector.startsWith("id:")) {
            String resourceId = selector.contains(":") ? selector.split(":", 2)[1] : selector;
            return pageSource.contains("resource-id=\"" + resourceId + "\"")
                    || pageSource.contains("resource-id='" + resourceId + "'");
        } else if (selector.startsWith("text:")) {
            String text = selector.split(":", 2)[1];
            return pageSource.contains("text=\"" + text + "\"")
                    || pageSource.contains("text='" + text + "'");
        }
        
        return false;
    }
    
    // ========== iOS 实现 ==========
    
    private boolean clickIOS(String deviceId, int x, int y) throws IOException, InterruptedException {
        // iOS 需要使用 WebDriverAgent 或 idevice 工具
        // 使用 idevice 的点击功能（需要越狱或使用 WebDriverAgent）
        // 这里使用 WebDriverAgent 的方式
        return false; // TODO: 实现 iOS 点击
    }
    
    private boolean clickIOSElement(String deviceId, String selector) throws IOException, InterruptedException {
        // 使用 WebDriverAgent 或 Appium
        return false; // TODO: 实现 iOS 元素点击
    }
    
    private boolean swipeIOS(String deviceId, int x1, int y1, int x2, int y2, int duration) throws IOException, InterruptedException {
        // 使用 WebDriverAgent 或 idevice
        return false; // TODO: 实现 iOS 滑动
    }
    
    private boolean inputTextIOS(String deviceId, String text) throws IOException, InterruptedException {
        // 使用 WebDriverAgent 或 idevice
        return false; // TODO: 实现 iOS 文本输入
    }
    
    private String takeScreenshotIOS(String deviceId, String savePath) throws IOException, InterruptedException {
        // 优先 homebrew 绝对路径；未挂 Developer Disk 时 screenshotr 常不可用（需改走 WDA）
        String[] bins = {
                "/opt/homebrew/bin/idevicescreenshot",
                "/usr/local/bin/idevicescreenshot",
                "idevicescreenshot"
        };
        java.io.File out = new java.io.File(savePath);
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        for (String bin : bins) {
            Process process = new ProcessBuilder(bin, "-u", deviceId, savePath)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder outSb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outSb.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0 && out.exists() && out.length() > 0) {
                return savePath;
            }
            log.warn("idevicescreenshot 失败 bin={} exit={} out={}", bin, exitCode,
                    outSb.toString().trim());
            if (!"idevicescreenshot".equals(bin) && !new java.io.File(bin).exists()) {
                continue;
            }
            // 已找到可执行文件但仍失败（通常缺 Developer Disk），不必再试别名
            if (new java.io.File(bin).exists() || "idevicescreenshot".equals(bin)) {
                break;
            }
        }
        return null;
    }
    
    private String getPageSourceIOS(String deviceId) throws IOException, InterruptedException {
        // 使用 WebDriverAgent 获取页面源
        // 需要 WebDriverAgent 服务运行
        return null; // TODO: 实现 iOS 页面源获取
    }
    
    private boolean isElementPresentIOS(String deviceId, String selector) throws IOException, InterruptedException {
        // 使用 WebDriverAgent 检查元素
        return false; // TODO: 实现 iOS 元素检查
    }
}
