package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformRouter;
import com.mobile.standalone.service.harmony.HarmonyHierarchyXml;
import com.mobile.standalone.service.inspect.LocatorMatcher;
import com.mobile.standalone.service.inspect.UiHierarchyBuilder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真机点选：截图 + page source，按点击坐标命中控件并推荐 locator。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceInspectService {

    private static final Pattern BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
    private static final Pattern WM_SIZE = Pattern.compile("(\\d+)x(\\d+)");
    private static final Path SCREENSHOTS_DIR = Path.of("/tmp/screenshots").toAbsolutePath().normalize();

    private final DeviceMapper deviceMapper;
    private final AutomationEngine automationEngine;
    private final UiElementService uiElementService;
    private final DeviceLockService deviceLockService;
    private final PlatformRouter platformRouter;

    /** 每台设备最近一次点选截图路径，供 /inspect/screenshot 读取 */
    private final ConcurrentHashMap<String, Path> lastScreenshotByDevice = new ConcurrentHashMap<>();
    /** 截图像素尺寸（与前端点击坐标系一致，常含导航栏；控件树可能更矮） */
    private final ConcurrentHashMap<String, int[]> lastScreenshotSizeByDevice = new ConcurrentHashMap<>();
    /** 与最近一次截图同一时刻的 page source，避免滚动后 dump 与截图不一致 / 长列表 bounds 干扰 */
    private final ConcurrentHashMap<String, String> lastPageSourceByDevice = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    @Autowired(required = false)
    private AppiumServerManager appiumServerManager;

    @Autowired(required = false)
    private DeviceManager deviceManager;

    public SnapshotResult snapshot(String deviceId) {
        Device device = requireConnected(deviceId);
        try {
            Files.createDirectories(SCREENSHOTS_DIR);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建截图目录: " + e.getMessage());
        }
        String file = "inspect_" + deviceId.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + System.currentTimeMillis() + ".png";
        Path path = SCREENSHOTS_DIR.resolve(file);
        String saved = null;
        if (Device.usesHdcAutomation(device.getPlatform())) {
            try {
                saved = platformRouter.getDriver(device).takeScreenshot(deviceId, path.toString());
            } catch (Exception e) {
                throw new IllegalStateException("鸿蒙截图失败（请确认 hdc 已授权且 hmdriver2 可用）: " + e.getMessage(), e);
            }
        } else if (appiumDriverAdapter != null) {
            saved = appiumDriverAdapter.takeScreenshot(deviceId, path.toString());
        }
        if (saved == null && !Device.usesHdcAutomation(device.getPlatform())) {
            try {
                saved = automationEngine.takeScreenshot(deviceId, device.getPlatform(), path.toString());
            } catch (Exception e) {
                throw new IllegalStateException(iosScreenshotHint(device, e.getMessage()));
            }
        }
        Path savedPath = Path.of(saved != null ? saved : path.toString()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(savedPath)) {
            throw new IllegalStateException(Device.usesHdcAutomation(device.getPlatform())
                    ? "鸿蒙截图未生成文件（hdc/hmdriver2）"
                    : iosScreenshotHint(device, "未生成截图文件"));
        }
        if (!savedPath.startsWith(SCREENSHOTS_DIR)) {
            throw new IllegalStateException("截图路径非法");
        }
        String id = deviceId.trim();
        lastScreenshotByDevice.put(id, savedPath);

        int[] pngSize = readPngSize(savedPath);
        int[] size = pngSize != null ? pngSize : resolveWindowSize(deviceId, device);
        if (size != null) {
            lastScreenshotSizeByDevice.put(id, size);
        }

        // 与截图同一时刻冻结控件树，点选时不要再现场 dump（手机滚动后树会变，长列表 max bounds 也会把坐标映射坏）
        String xml = fetchPageSource(deviceId, device);
        if (usableHierarchy(xml)) {
            lastPageSourceByDevice.put(id, xml);
        } else {
            lastPageSourceByDevice.remove(id);
            log.warn("snapshot 未拿到 page source，后续 hit-test 将尝试现场 dump: deviceId={}", id);
        }

        long ts = System.currentTimeMillis();
        SnapshotResult r = new SnapshotResult();
        r.setDeviceId(deviceId);
        r.setPlatform(device.getPlatform() != null ? device.getPlatform().name() : null);
        r.setScreenshotUrl("devices/" + id + "/inspect/screenshot?t=" + ts);
        r.setScreenshotPath(savedPath.toString());
        if (size != null) {
            r.setWidth(size[0]);
            r.setHeight(size[1]);
        }
        return r;
    }

    /** iOS 截图失败时给出可操作提示；Android 保持简短。 */
    private String iosScreenshotHint(Device device, String detail) {
        boolean ios = device != null && Device.usesIosAutomation(device.getPlatform());
        if (!ios) {
            return "截图失败：" + (detail != null ? detail : "未知错误");
        }
        String wda = deviceManager != null ? deviceManager.getLastIosWdaError(device.getId()) : null;
        StringBuilder sb = new StringBuilder("iOS 截图失败。");
        if (wda != null && !wda.isBlank()) {
            sb.append(wda);
        } else {
            sb.append("WDA 未就绪。");
        }
        sb.append(" 备选 idevicescreenshot 也不可用（需挂载 Developer Disk，iOS 17+ 常失败）。");
        sb.append(" 请先在 Xcode 对真机重新签名并安装 WebDriverAgentRunner，再点「刷新截图」。");
        if (detail != null && !detail.isBlank() && (wda == null || !wda.contains(detail))) {
            sb.append("（").append(detail).append("）");
        }
        return sb.toString();
    }

    /** 返回该设备最近一次 snapshot 截图字节；无则 404 由控制器处理 */
    public byte[] readLatestScreenshot(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        Path path = lastScreenshotByDevice.get(deviceId.trim());
        if (path == null || !Files.isRegularFile(path) || !path.startsWith(SCREENSHOTS_DIR)) {
            // 回退：找该设备最新 inspect_ 文件
            path = findLatestInspectFile(deviceId.trim());
        }
        if (path == null) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (Exception e) {
            log.warn("读取点选截图失败: {}", e.getMessage());
            return null;
        }
    }

    private Path findLatestInspectFile(String deviceId) {
        String prefix = "inspect_" + deviceId.replaceAll("[^a-zA-Z0-9._-]", "_") + "_";
        try {
            if (!Files.isDirectory(SCREENSHOTS_DIR)) return null;
            return Files.list(SCREENSHOTS_DIR)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefix) && n.endsWith(".png");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public HitTestResult hitTest(String deviceId, Double xRatio, Double yRatio, Integer x, Integer y,
                                 String appId) {
        Device device = requireConnected(deviceId);
        String id = deviceId.trim();
        int[] shotSize = resolveShotSize(deviceId, device);
        if (shotSize == null || shotSize[0] <= 0 || shotSize[1] <= 0) {
            throw new IllegalStateException("无法读取截图/屏幕尺寸");
        }

        // 点击坐标优先用前端传来的截图像素（与 naturalWidth/Height 一致）；否则用比例乘截图尺寸
        int shotX;
        int shotY;
        if (x != null && y != null) {
            shotX = Math.max(0, Math.min(x, shotSize[0] - 1));
            shotY = Math.max(0, Math.min(y, shotSize[1] - 1));
        } else if (xRatio != null && yRatio != null) {
            shotX = (int) Math.round(clamp01(xRatio) * (shotSize[0] - 1));
            shotY = (int) Math.round(clamp01(yRatio) * (shotSize[1] - 1));
        } else {
            throw new IllegalArgumentException("请传 xRatio/yRatio 或像素 x/y");
        }

        // Android / 鸿蒙：每次点选都重新 dump；iOS 优先用冻结树
        String xml;
        if (Device.isAndroidOrPogAndroid(device.getPlatform()) || Device.usesHdcAutomation(device.getPlatform())) {
            xml = fetchPageSource(deviceId, device);
            if (usableHierarchy(xml)) {
                lastPageSourceByDevice.put(id, xml);
            }
        } else {
            xml = lastPageSourceByDevice.get(id);
            if (!usableHierarchy(xml)) {
                xml = fetchPageSource(deviceId, device);
                if (usableHierarchy(xml)) {
                    lastPageSourceByDevice.put(id, xml);
                }
            }
        }
        if (!usableHierarchy(xml)) {
            throw new IllegalStateException(
                    "无法获取控件树：请先点「刷新截图」。"
                            + (Device.usesHdcAutomation(device.getPlatform())
                            ? "鸿蒙请确认 hdc 已授权且 hmdriver2 可用。"
                            : "Android 请确认 adb 可用；iOS 请确认 WDA 已启动（设置里信任开发者证书）"));
        }
        int xmlStart = xml.indexOf('<');
        if (xmlStart > 0) {
            xml = xml.substring(xmlStart);
        }
        int xmlEnd = xml.lastIndexOf('>');
        if (xmlEnd > 0 && xmlEnd < xml.length() - 1) {
            xml = xml.substring(0, xmlEnd + 1);
        }

        // 坐标映射：
        // - Android uiautomator bounds 与 screencap 顶部对齐、同宽；截图往往更高（底部手势条）。
        // - 禁止按 hierarchy@height 比例压 Y（会把侧栏宫格点飞）。
        // - 点击落在截图多出来的底部区域时，夹紧到控件树底边内，否则「退出登录」等底栏按钮点空。
        int[] hierSize = detectHierarchySize(xml, shotSize);
        int contentBottom = detectContentBottom(xml, hierSize != null ? hierSize[1] : 0);
        int px = shotX;
        int py = shotY;
        if (hierSize != null && hierSize[0] > 0 && hierSize[1] > 0
                && (hierSize[0] != shotSize[0] || hierSize[1] != shotSize[1])) {
            boolean sameWidth = Math.abs(hierSize[0] - shotSize[0]) <= 2;
            int heightGap = Math.abs(shotSize[1] - hierSize[1]);
            boolean statusBarLikeGap = sameWidth && heightGap > 0 && heightGap <= Math.max(320, shotSize[1] / 8);
            if (statusBarLikeGap && Device.isAndroidOrPogAndroid(device.getPlatform())) {
                px = Math.max(0, Math.min(shotX, Math.max(hierSize[0], shotSize[0]) - 1));
                int bottom = Math.max(hierSize[1], contentBottom);
                if (shotY < bottom) {
                    py = shotY;
                } else {
                    py = Math.max(0, bottom - 1);
                }
                log.info("点选坐标: Android 同宽映射 shot=({},{})@{}x{} -> ({},{}) contentBottom={}",
                        shotX, shotY, shotSize[0], shotSize[1], px, py, bottom);
            } else {
                px = (int) Math.round(shotX * (hierSize[0] - 1.0) / (shotSize[0] - 1.0));
                py = (int) Math.round(shotY * (hierSize[1] - 1.0) / (shotSize[1] - 1.0));
                px = Math.max(0, Math.min(px, hierSize[0] - 1));
                py = Math.max(0, Math.min(py, hierSize[1] - 1));
                log.info("点选坐标映射: shot=({},{})@{}x{} -> hier=({},{})@{}x{}",
                        shotX, shotY, shotSize[0], shotSize[1], px, py, hierSize[0], hierSize[1]);
            }
        } else if (contentBottom > 0 && py >= contentBottom) {
            py = contentBottom - 1;
        }

        List<HitCandidate> hits = parseAndHit(xml, px, py, device.getPlatform(), appId);
        hits = dropUselessFullScreenContainers(hits, shotSize[0], shotSize[1]);
        // 坐标始终作为可选方案之一；H5/WebView 无子树时提到最前并写明原因
        hits = appendCoordinateCandidate(hits, px, py, shotSize[0], shotSize[1]);
        hits = promoteCoordinateWhenOnlyH5Shell(hits);
        String platform = device.getPlatform() != null ? device.getPlatform().name() : null;
        enrichMatchedElements(hits, appId, platform);

        HitTestResult result = new HitTestResult();
        result.setDeviceId(deviceId);
        result.setPlatform(platform);
        result.setX(px);
        result.setY(py);
        result.setWidth(shotSize[0]);
        result.setHeight(shotSize[1]);
        if (hierSize != null && hierSize[0] > 0 && hierSize[1] > 0) {
            result.setHierarchyWidth(hierSize[0]);
            result.setHierarchyHeight(hierSize[1]);
        }
        // 不替人拍板：把 locator 唯一性和「命中的是树上哪个节点」一起给出，选错了前端能往上跳一级
        try {
            org.w3c.dom.Document doc = UiHierarchyBuilder.parseDocument(xml);
            enrichMatchCounts(doc, hits);
            String selected = firstNodePath(hits);
            result.setSelectedNodePath(selected);
            result.setAncestors(ancestorChain(doc, selected));
        } catch (Exception e) {
            log.debug("补充 nodePath/locator 唯一性失败: {}", e.getMessage());
        }
        result.setCandidates(hits);
        return result;
    }

    /** 坐标方案追加到末尾，不抢默认首选 */
    private static List<HitCandidate> appendCoordinateCandidate(List<HitCandidate> hits, int px, int py,
                                                                int screenW, int screenH) {
        List<HitCandidate> out = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        int r = 0;
        if (hits != null) {
            for (HitCandidate h : hits) {
                String key = h.getLocatorType() + "|" + h.getLocatorValue();
                if (!seen.add(key)) continue;
                h.setRank(r++);
                out.add(h);
            }
        }
        String coordVal = px + "," + py;
        if (seen.add("coordinate|" + coordVal)) {
            HitCandidate coord = new HitCandidate();
            coord.setLocatorType("coordinate");
            coord.setLocatorValue(coordVal);
            coord.setSuggestedNameZh("坐标点选");
            coord.setSuggestedNameEn("tap_" + px + "_" + py);
            coord.setVisualHint(out.isEmpty()
                    ? "屏幕坐标 (" + px + "," + py + ") · 无控件树时兜底"
                    : "屏幕坐标 (" + px + "," + py + ") · 可选");
            coord.setClickable(true);
            coord.setEnabled(true);
            int pad = 36;
            coord.setBounds(Map.of(
                    "left", Math.max(0, px - pad),
                    "top", Math.max(0, py - pad),
                    "right", Math.min(Math.max(screenW, 1), px + pad),
                    "bottom", Math.min(Math.max(screenH, 1), py + pad)
            ));
            coord.setRank(r);
            out.add(coord);
        }
        return out;
    }

    /**
     * 「我的动态」等 H5：原生树只有 WebView 大壳时，把坐标提到首位，避免误推 App 标题。
     */
    private static List<HitCandidate> promoteCoordinateWhenOnlyH5Shell(List<HitCandidate> hits) {
        if (hits == null || hits.isEmpty()) return hits;
        HitCandidate coord = null;
        List<HitCandidate> others = new ArrayList<>();
        for (HitCandidate c : hits) {
            if ("coordinate".equalsIgnoreCase(c.getLocatorType())) {
                coord = c;
            } else {
                others.add(c);
            }
        }
        if (coord == null) return hits;
        boolean onlyShell = others.isEmpty();
        if (!onlyShell) {
            onlyShell = true;
            for (HitCandidate c : others) {
                String type = c.getLocatorType() != null ? c.getLocatorType().toLowerCase(Locale.ROOT) : "";
                String val = c.getLocatorValue() != null ? c.getLocatorValue() : "";
                boolean shellLike = val.contains("WebView")
                        || isAppChromeLabel(c.getText())
                        || isAppChromeLabel(c.getSuggestedNameZh())
                        || isAppChromeLabel(val)
                        || ("classname".equals(type) && (val.contains("FrameLayout") || val.contains("ViewGroup")));
                if (!shellLike) {
                    onlyShell = false;
                    break;
                }
            }
        }
        if (!onlyShell) return hits;
        coord.setSuggestedNameZh("H5坐标点选");
        coord.setVisualHint("动态/社区列表在 WebView 内，原生树看不到帖子卡片；请用坐标，或点原生 Tab/评论框。"
                + " 坐标 " + coord.getLocatorValue());
        List<HitCandidate> out = new ArrayList<>();
        out.add(coord);
        out.addAll(others);
        for (int i = 0; i < out.size(); i++) out.get(i).setRank(i);
        return out;
    }

    private Device requireConnected(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        Device device = deviceMapper.findById(deviceId.trim());
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
        if (device.getStatus() != Device.DeviceStatus.CONNECTED) {
            throw new IllegalStateException("设备未连接: " + deviceId);
        }
        // 采集会 dump 控件树并操作前台，和正在跑的任务/回归互斥
        DeviceLockService.Holder holder = deviceLockService.current(deviceId.trim());
        if (holder != null) {
            throw new IllegalStateException("设备 " + deviceId + " 正被占用：" + holder.describe()
                    + "。请等其结束后再采集元素。");
        }
        return device;
    }

    private String fetchPageSource(String deviceId, Device device) {
        if (Device.usesHdcAutomation(device.getPlatform())) {
            try {
                String raw = platformRouter.getDriver(device).getPageSource(deviceId);
                String xml = HarmonyHierarchyXml.toInspectXml(raw);
                if (usableHierarchy(xml)) {
                    log.info("点选 pageSource 选用鸿蒙 dump（hmdriver2）");
                    return xml;
                }
                log.warn("鸿蒙 dump 无法转为控件树 XML");
            } catch (Exception e) {
                log.warn("鸿蒙 pageSource 失败: {}", e.getMessage());
            }
            return null;
        }
        // Android：必须先 adb dump。先 Appium 会挂旧首页树，且温馨提示页 waitForIdle 易卡死，导致 dump 来不及/被盖掉。
        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            String dump = dumpUiAutomatorXml(deviceId);
            if (dump == null || !dump.contains("<")) {
                try {
                    dump = automationEngine.getPageSource(deviceId, device.getPlatform());
                } catch (Exception e) {
                    log.warn("uiautomator dump 失败: {}", e.getMessage());
                }
            }
            if (dump != null && dump.contains("<")) {
                if (looksLikePrivacyWarmTip(dump)) {
                    log.info("点选 pageSource 只用 dump（协议/温馨提示弹窗，跳过 Appium）");
                    return dump;
                }
                // dump 已有足够文案：默认采用，避免 Appium 旧会话污染
                int dumpTexts = countAttr(dump, "text=\"");
                if (dumpTexts >= 2 && !looksLikeDumpMissingOverlay(dump)) {
                    log.info("点选 pageSource 选用 dump（先 dump；文案节点 {}）", dumpTexts);
                    return dump;
                }
            }
            // dump 过瘦或疑似缺弹层时，再试 Appium multiWindows（系统 PopupWindow）
            String appium = fetchAppiumPageSource(deviceId, device);
            if (appium != null && dump != null && dump.contains("<")) {
                if (looksLikeStaleHomeTree(appium) && !looksLikeStaleHomeTree(dump)) {
                    log.info("点选 pageSource 选用 dump（Appium 仍是侧栏/首页旧树）");
                    return dump;
                }
                int dumpTexts = countAttr(dump, "text=\"");
                int appiumTexts = countAttr(appium, "text=\"");
                if (appiumTexts > dumpTexts + 2) {
                    log.info("点选 pageSource 选用 Appium multiWindows（文案 {} vs dump {}）", appiumTexts, dumpTexts);
                    return appium;
                }
                return dump;
            }
            if (dump != null && dump.contains("<")) return dump;
            return appium;
        }

        // iOS：优先 Appium；无会话时走 WDA HTTP /source（真机点选截图同源）
        String appium = fetchAppiumPageSource(deviceId, device);
        if (appium != null && appium.contains("<")) {
            return appium;
        }
        if (appiumDriverAdapter != null && Device.usesIosAutomation(device.getPlatform())) {
            try {
                String viaWda = appiumDriverAdapter.getPageSourceViaWdaHttp(deviceId);
                if (viaWda != null && viaWda.contains("<")) {
                    return viaWda;
                }
            } catch (Exception e) {
                log.warn("WDA pageSource 失败: {}", e.getMessage());
            }
        }
        return null;
    }

    private static boolean usableHierarchy(String xml) {
        return xml != null && !xml.isBlank() && xml.contains("<");
    }

    private String fetchAppiumPageSource(String deviceId, Device device) {
        if (appiumDriverAdapter == null) {
            return null;
        }
        try {
            if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
                if (appiumServerManager != null) {
                    appiumServerManager.ensureRunning();
                }
                appiumDriverAdapter.attachAndroidUiSession(deviceId);
                String appium = appiumDriverAdapter.getPageSourceWithMultiWindows(deviceId);
                if (appium != null && (appium.isBlank() || !appium.contains("<"))) {
                    return null;
                }
                return appium;
            }
            String appium = appiumDriverAdapter.getPageSource(deviceId);
            if (appium != null && (appium.isBlank() || !appium.contains("<"))) {
                return null;
            }
            return appium;
        } catch (Exception e) {
            log.warn("Appium 点选 pageSource 失败: {}", e.getMessage());
            return null;
        }
    }

    /** dump 几乎只有背景壳、缺可点文案时，才值得再问 Appium */
    private static boolean looksLikeDumpMissingOverlay(String dump) {
        if (dump == null) return true;
        int clickable = countAttr(dump, "clickable=\"true\"");
        return clickable <= 1 && countAttr(dump, "text=\"") <= 1;
    }

    /** 丢掉全屏 side_layout / 巨大 FrameLayout 等误导候选 */
    private static List<HitCandidate> dropUselessFullScreenContainers(List<HitCandidate> hits, int screenW, int screenH) {
        if (hits == null || hits.isEmpty()) return hits;
        long screenArea = Math.max(1L, (long) screenW * screenH);
        List<HitCandidate> kept = new ArrayList<>();
        for (HitCandidate c : hits) {
            String val = c.getLocatorValue() != null ? c.getLocatorValue() : "";
            String type = c.getLocatorType() != null ? c.getLocatorType().toLowerCase(Locale.ROOT) : "";
            if (("id".equals(type) || "resource-id".equals(type) || "resourceid".equals(type))
                    && (val.endsWith("/side_layout") || val.contains(":id/side_layout") || "side_layout".equals(val))) {
                log.info("点选丢弃误导候选 side_layout");
                continue;
            }
            // 全屏 WebView 壳：动态/社区 H5 里点帖子只会命中这层，不能当定位
            if ("classname".equals(type) && val.contains("WebView")) {
                log.info("点选丢弃 WebView className 壳");
                continue;
            }
            if ("xpath".equals(type) && val.contains("WebView") && val.contains("@bounds=")) {
                log.info("点选丢弃全屏 WebView bounds xpath");
                continue;
            }
            Map<String, Integer> b = c.getBounds();
            if (b != null) {
                int l = b.getOrDefault("left", 0);
                int t = b.getOrDefault("top", 0);
                int r = b.getOrDefault("right", 0);
                int bt = b.getOrDefault("bottom", 0);
                long area = Math.max(0L, (long) (r - l) * (bt - t));
                boolean huge = area >= screenArea * 0.85;
                if (huge
                        && ("classname".equals(type) || "xpath".equals(type))
                        && (val.contains("FrameLayout") || val.contains("side_layout") || val.contains("WebView"))) {
                    log.info("点选丢弃全屏容器候选: {} {}", type, val);
                    continue;
                }
                // 大壳上挂的 App 标题文案（如「完美世界电竞」）不是你点的帖子
                if (huge && ("text".equals(type) || "xpath".equals(type) || "accessibilityid".equals(type))
                        && isAppChromeLabel(c.getText() != null ? c.getText() : val)) {
                    log.info("点选丢弃大壳标题文案: {}", val);
                    continue;
                }
            }
            kept.add(c);
        }
        if (kept.isEmpty()) {
            log.info("点选：过滤后无可靠候选，交由坐标兜底");
            return List.of();
        }
        for (int i = 0; i < kept.size(); i++) {
            kept.get(i).setRank(i);
        }
        return kept;
    }

    /** 顶栏/壳上的品牌名，不能当内容区点选结果 */
    private static boolean isAppChromeLabel(String label) {
        if (label == null || label.isBlank()) return false;
        String s = label.trim();
        if (s.contains("@text=")) {
            int i = s.indexOf("@text=");
            s = s.substring(i + 6).replace("'", "").replace("\"", "");
            if (s.contains("]")) s = s.substring(0, s.indexOf(']'));
        }
        return "完美世界电竞".equals(s)
                || "PogPog".equalsIgnoreCase(s)
                || "Steam".equalsIgnoreCase(s);
    }

    /** 完美世界电竞首启「温馨提示」协议弹窗（及同类 id） */
    private static boolean looksLikePrivacyWarmTip(String xml) {
        if (xml == null || xml.isBlank()) return false;
        return xml.contains("温馨提示")
                || xml.contains("tv_agree")
                || xml.contains("tv_not_agree")
                || xml.contains("tv_user_protocol")
                || xml.contains("tv_privacy_policy");
    }

    /** Appium 仍挂着带侧栏的首页树（与当前弹窗 dump 冲突） */
    private static boolean looksLikeStaleHomeTree(String xml) {
        if (xml == null || xml.isBlank()) return false;
        return xml.contains("side_layout")
                || xml.contains("sideView")
                || xml.contains("home_tab_mine")
                || xml.contains("pw_more_gridview");
    }

    private static int countAttr(String xml, String token) {
        if (xml == null || token == null) return 0;
        int n = 0;
        int from = 0;
        while (true) {
            int i = xml.indexOf(token, from);
            if (i < 0) break;
            n++;
            from = i + token.length();
        }
        return n;
    }

    /** 可靠 dump：写入设备文件再 pull，避开 MiUI dump /dev/tty 时 stdout 混入异常栈 */
    private String dumpUiAutomatorXml(String deviceId) {
        String remote = "/sdcard/ms_inspect_uidump.xml";
        try {
            Process dump = new ProcessBuilder("adb", "-s", deviceId, "shell", "uiautomator", "dump", remote)
                    .redirectErrorStream(true)
                    .start();
            // 消费输出，避免阻塞
            try (BufferedReader r = new BufferedReader(new InputStreamReader(dump.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            dump.waitFor();
            Path local = SCREENSHOTS_DIR.resolve("uidump_" + deviceId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".xml");
            Files.createDirectories(SCREENSHOTS_DIR);
            Process pull = new ProcessBuilder("adb", "-s", deviceId, "pull", remote, local.toString())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pull.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            pull.waitFor();
            if (!Files.isRegularFile(local)) {
                return null;
            }
            return Files.readString(local, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("dumpUiAutomatorXml 失败: {}", e.getMessage());
            return null;
        }
    }

    private static int[] readPngSize(Path png) {
        // hmdriver2 截图常为 JPEG；ImageIO 同时支持 png/jpeg
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(png.toFile());
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                return new int[]{img.getWidth(), img.getHeight()};
            }
        } catch (Exception ignored) {
            // fall through
        }
        try (var in = Files.newInputStream(png)) {
            byte[] header = in.readNBytes(24);
            if (header.length < 24) return null;
            // PNG 签名
            if (header[0] == (byte) 0x89 && header[1] == 'P') {
                int w = ((header[16] & 0xff) << 24) | ((header[17] & 0xff) << 16)
                        | ((header[18] & 0xff) << 8) | (header[19] & 0xff);
                int h = ((header[20] & 0xff) << 24) | ((header[21] & 0xff) << 16)
                        | ((header[22] & 0xff) << 8) | (header[23] & 0xff);
                if (w <= 0 || h <= 0) return null;
                return new int[]{w, h};
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 控件树「视口」尺寸，用于与截图坐标互映射。
     * 禁止取全树 max(bounds)：长列表/ScrollView 子节点 bottom 可达上万，会把点击 Y 映射飞掉，表现为拖滚动条点下方无命中。
     */
    private static int[] detectHierarchySize(String xml, int[] shotSize) {
        if (xml == null || xml.isBlank()) return null;

        // 1) uiautomator: <hierarchy ... width="1080" height="2206">
        Matcher hw = Pattern.compile(
                "<hierarchy\\b[^>]*\\bwidth\\s*=\\s*\"(\\d+)\"[^>]*\\bheight\\s*=\\s*\"(\\d+)\"",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml);
        if (!hw.find()) {
            hw = Pattern.compile(
                    "<hierarchy\\b[^>]*\\bheight\\s*=\\s*\"(\\d+)\"[^>]*\\bwidth\\s*=\\s*\"(\\d+)\"",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml);
            if (hw.find()) {
                int h = Integer.parseInt(hw.group(1));
                int w = Integer.parseInt(hw.group(2));
                if (w > 0 && h > 0) return new int[]{w, h};
            }
        } else {
            int w = Integer.parseInt(hw.group(1));
            int h = Integer.parseInt(hw.group(2));
            if (w > 0 && h > 0) return new int[]{w, h};
        }

        // 2) 优先 [0,0][w][h] 且接近截图尺寸的节点（状态栏差常见 100~300px）
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        Matcher m = BOUNDS.matcher(xml);
        int scanned = 0;
        while (m.find() && scanned < 80) {
            scanned++;
            int l = Integer.parseInt(m.group(1));
            int t = Integer.parseInt(m.group(2));
            int r = Integer.parseInt(m.group(3));
            int b = Integer.parseInt(m.group(4));
            if (l != 0 || t != 0) continue;
            int w = r - l;
            int h = b - t;
            if (w < 200 || h < 400) continue;
            // 明显大于截图（长列表内容高度）直接跳过
            if (shotSize != null && shotSize[1] > 0 && h > shotSize[1] * 1.35) continue;
            if (shotSize != null && shotSize[0] > 0 && w > shotSize[0] * 1.2) continue;
            double score;
            if (shotSize != null && shotSize[0] > 0 && shotSize[1] > 0) {
                score = Math.abs(w - shotSize[0]) + Math.abs(h - shotSize[1]);
            } else {
                score = -(double) w * h; // 无截图时取较大的屏幕级节点
            }
            if (score < bestScore) {
                bestScore = score;
                best = new int[]{w, h};
            }
        }
        if (best != null) return best;

        // 3) 退回截图尺寸（不做错误缩放，比 max-bounds 安全）
        if (shotSize != null && shotSize[0] > 0 && shotSize[1] > 0) {
            return new int[]{shotSize[0], shotSize[1]};
        }
        return null;
    }

    /** 控件树中实际出现的最大 bottom，用于夹紧截图底部多出的点击 */
    private static int detectContentBottom(String xml, int hierHeightAttr) {
        int maxB = Math.max(0, hierHeightAttr);
        if (xml == null || xml.isBlank()) return maxB;
        Matcher m = BOUNDS.matcher(xml);
        while (m.find()) {
            int b = Integer.parseInt(m.group(4));
            if (b > maxB) maxB = b;
        }
        return maxB;
    }

    private int[] resolveWindowSize(String deviceId, Device device) {
        if (Device.usesHdcAutomation(device.getPlatform())) {
            try {
                String raw = platformRouter.getDriver(device).getPageSource(deviceId);
                String xml = HarmonyHierarchyXml.toInspectXml(raw);
                int[] fromDump = detectHierarchySize(xml, null);
                if (fromDump != null) {
                    return fromDump;
                }
            } catch (Exception e) {
                log.debug("鸿蒙窗口尺寸从 dump 解析失败: {}", e.getMessage());
            }
        }
        if (appiumDriverAdapter != null) {
            int[] s = appiumDriverAdapter.getWindowSize(deviceId);
            if (s != null && s[0] > 0 && s[1] > 0) return s;
        }
        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            return adbWmSize(deviceId);
        }
        return null;
    }

    private int[] adbWmSize(String deviceId) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "wm", "size")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            Matcher m = WM_SIZE.matcher(out.toString());
            // 优先 Override，否则 Physical
            int[] physical = null;
            int[] override = null;
            while (m.find()) {
                int w = Integer.parseInt(m.group(1));
                int h = Integer.parseInt(m.group(2));
                String ctx = out.substring(Math.max(0, m.start() - 20), m.start()).toLowerCase(Locale.ROOT);
                if (ctx.contains("override")) {
                    override = new int[]{w, h};
                } else {
                    physical = new int[]{w, h};
                }
            }
            if (override != null) return override;
            return physical;
        } catch (Exception e) {
            log.warn("adb wm size 失败: {}", e.getMessage());
            return null;
        }
    }

    private List<HitCandidate> parseAndHit(String xml, int px, int py, Device.Platform platform, String appId) {
        List<HitNode> nodes = new ArrayList<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = f.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            int[] seq = {0};
            walk(doc.getDocumentElement(), nodes, null, seq, null, null);
        } catch (Exception e) {
            log.warn("解析 page source 失败，尝试正则扫描 bounds: {}", e.getMessage());
            return regexHitFallback(xml, px, py);
        }

        List<HitNode> containing = new ArrayList<>();
        for (HitNode n : nodes) {
            if (!n.displayed) continue;
            if (n.left <= px && px < n.right && n.top <= py && py < n.bottom) {
                containing.add(n);
                continue;
            }
            int h = n.bottom - n.top;
            if (n.clickable && h > 0 && h <= 48) {
                int pad = 56;
                int top = Math.max(0, n.top - pad);
                int bottom = n.bottom + pad;
                if (n.left <= px && px < n.right && top <= py && py < bottom) {
                    containing.add(n);
                }
            }
        }
        if (containing.isEmpty()) {
            return List.of();
        }

        int screenArea = estimateScreenArea(nodes);
        HitNode drawer = findFrontSurface(containing, nodes);
        boolean scopeToDrawer = shouldScopeToFrontSurface(drawer, containing, px, py, screenArea);
        List<HitNode> scoped = containing;
        if (scopeToDrawer) {
            List<HitNode> inside = new ArrayList<>();
            for (HitNode n : containing) {
                if (isTreeDescendantOrSelf(n, drawer)) {
                    inside.add(n);
                }
            }
            if (!inside.isEmpty()) {
                scoped = inside;
                log.info("点选限定前景容器(树内): {} 候选 {}→{}",
                        shortResourceId(drawer.resourceId), containing.size(), scoped.size());
            } else {
                scopeToDrawer = false;
            }
        } else if (drawer != null && "side_layout".equalsIgnoreCase(shortResourceId(drawer.resourceId))) {
            log.info("点选跳过 side_layout 裁剪（主内容更优命中，如 sideView）");
        }

        List<HitNode> labelPool = new ArrayList<>();
        if (scopeToDrawer) {
            for (HitNode n : nodes) {
                if (isTreeDescendantOrSelf(n, drawer)) labelPool.add(n);
            }
        } else {
            labelPool.addAll(nodes);
        }

        String plat = platform != null ? platform.name() : null;
        List<HitCandidate> out = collectHitCandidates(scoped, labelPool, screenArea, px, py, appId, plat);
        if (out.isEmpty() && scoped != containing) {
            log.info("点选前景裁剪后无候选，回退全量命中集");
            out = collectHitCandidates(containing, nodes, screenArea, px, py, appId, plat);
        }
        return out;
    }

    /** 点击落点分区：各区规则互斥，避免「修评论又搞坏侧栏」 */
    private enum HitZone {
        TITLE_LEFT,
        BOTTOM_TAB,
        MINE_TAB,
        COMPOSER,
        LIST_CARD,
        H5_FILTER,
        DEFAULT
    }

    private static HitZone detectHitZone(List<HitNode> scoped, List<HitNode> labelPool, int px, int py) {
        if (hasComposerActionAt(labelPool, px, py) || isPointInsideAnyEditText(labelPool, px, py)) {
            return HitZone.COMPOSER;
        }
        for (HitNode n : labelPool) {
            if (!n.displayed || !isEditText(n) || !n.focused) continue;
            if (Math.abs(((n.top + n.bottom) / 2) - py) < 400) return HitZone.COMPOSER;
        }
        if (pointInContainerId(labelPool, px, py, "bottomTabLayout")) return HitZone.BOTTOM_TAB;
        if (pointInContainerId(labelPool, px, py, "view_mine_tab")) return HitZone.MINE_TAB;
        if (px < 240 && py < 320) {
            return HitZone.TITLE_LEFT;
        }
        if (scoped != null) {
            for (HitNode n : scoped) {
                String sid = shortResourceId(n.resourceId);
                if (isListRowBadgeId(sid) || "cv_activity_card".equalsIgnoreCase(sid)) {
                    return HitZone.LIST_CARD;
                }
                String lab = firstNonBlank(n.text, n.contentDesc);
                if (pickMergedH5FilterLabel(lab, n, px) != null) return HitZone.H5_FILTER;
            }
        }
        return HitZone.DEFAULT;
    }

    private static boolean pointInContainerId(List<HitNode> pool, int px, int py, String shortId) {
        if (pool == null || shortId == null) return false;
        for (HitNode n : pool) {
            if (!n.displayed) continue;
            if (!shortId.equalsIgnoreCase(shortResourceId(n.resourceId))) continue;
            if (n.left <= px && px < n.right && n.top <= py && py < n.bottom) return true;
        }
        return false;
    }

    private List<HitCandidate> collectHitCandidates(List<HitNode> scoped, List<HitNode> labelPool, int screenArea,
                                                    int px, int py, String appId, String platform) {
        List<HitNode> ranked = new ArrayList<>(scoped);
        ranked.sort(Comparator
                .comparingInt((HitNode n) -> hitRankScore(n, screenArea)).reversed()
                .thenComparingInt(HitNode::area)
                .thenComparingInt((HitNode n) -> -n.docOrder));

        List<HitCandidate> out = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();

        HitZone zone = detectHitZone(scoped, labelPool, px, py);
        log.info("点选分区 zone={} @({},{})", zone, px, py);

        // 现用现选：点上自带文案的节点优先（避免父壳套用「退出登录」等远处文案）
        if (zone == HitZone.DEFAULT || zone == HitZone.LIST_CARD || zone == HitZone.H5_FILTER) {
            prependDirectTextUnderClick(out, seen, labelPool, px, py);
        }

        // 库内 ACTIVE：仅当与点上控件文案不冲突时才置顶
        prependActiveLibraryHits(out, seen, scoped, px, py, appId, platform);

        // 分区专属规则互斥
        switch (zone) {
            case TITLE_LEFT -> prependSideMenuCandidates(out, seen, labelPool, scoped, px, py);
            case COMPOSER -> {
                prependComposerActionCandidates(out, seen, labelPool, px, py);
                prependInputFieldCandidates(out, seen, labelPool, scoped, px, py);
            }
            case BOTTOM_TAB, MINE_TAB -> {
                HitCandidate stripTab = buildScopedTabCandidate(scoped, labelPool, px, py);
                if (stripTab != null) {
                    String key = stripTab.getLocatorType() + "|" + stripTab.getLocatorValue();
                    if (seen.add(key)) {
                        stripTab.setRank(out.size());
                        out.add(stripTab);
                    }
                }
            }
            case LIST_CARD, H5_FILTER, DEFAULT -> {
                if (zone == HitZone.DEFAULT) {
                    HitCandidate stripTab = buildScopedTabCandidate(scoped, labelPool, px, py);
                    if (stripTab != null) {
                        String key = stripTab.getLocatorType() + "|" + stripTab.getLocatorValue();
                        if (seen.add(key)) {
                            stripTab.setRank(out.size());
                            out.add(stripTab);
                        }
                    }
                }
            }
        }

        int nodesUsed = 0;
        for (HitNode n : ranked) {
            if (nodesUsed >= 3 || out.size() >= 18) break;
            if (!isInteresting(n)) continue;
            if (isGenericContainer(n)) continue;
            if (zone == HitZone.TITLE_LEFT) {
                String sid = shortResourceId(n.resourceId);
                if ("layoutTitleBar".equalsIgnoreCase(sid) || "vg_title_bar".equalsIgnoreCase(sid)) continue;
            }

            HitNode target = n;
            if (isGenericToolIcon(n) || isBottomTabSharedId(n.resourceId)) {
                HitNode cell = findClickableAncestor(n);
                if (cell != null) target = cell;
            }

            List<HitCandidate> strategies = expandLocatorStrategies(target, labelPool, px, py);
            if (strategies.isEmpty()) continue;
            nodesUsed++;
            for (HitCandidate c : strategies) {
                if (out.size() >= 18) break;
                if (c.getLocatorValue() == null || c.getLocatorValue().isBlank()) continue;
                String key = c.getLocatorType() + "|" + c.getLocatorValue();
                if (!seen.add(key)) continue;
                c.setRank(out.size());
                out.add(c);
            }
        }
        if (out.isEmpty()) {
            for (HitNode n : ranked) {
                if (nodesUsed >= 2 || out.size() >= 12) break;
                if (isWebViewShell(n)) continue;
                if (!isInteresting(n) && !isGenericContainer(n)) continue;
                List<HitCandidate> strategies = expandLocatorStrategies(n, labelPool, px, py);
                nodesUsed++;
                for (HitCandidate c : strategies) {
                    if (out.size() >= 12) break;
                    String key = c.getLocatorType() + "|" + c.getLocatorValue();
                    if (!seen.add(key)) continue;
                    c.setRank(out.size());
                    out.add(c);
                }
            }
        }
        for (int i = 0; i < out.size(); i++) out.get(i).setRank(i);
        return out;
    }

    /**
     * 点击落点下带自身文案的节点优先（现用现选）。
     * 修复：点「登录注册…」却被父容器套上「退出登录」/ logout_confirm。
     */
    private void prependDirectTextUnderClick(List<HitCandidate> out, java.util.LinkedHashSet<String> seen,
                                             List<HitNode> labelPool, int px, int py) {
        if (labelPool == null) return;
        List<HitNode> texts = new ArrayList<>();
        for (HitNode n : labelPool) {
            if (!n.displayed) continue;
            if (!(n.left <= px && px < n.right && n.top <= py && py < n.bottom)) continue;
            if (isGenericContainer(n) || isWebViewShell(n) || isScrollShell(n)) continue;
            String t = firstNonBlank(n.text, n.contentDesc);
            if (t == null || t.isBlank() || t.length() > 40 || isNoiseLabel(t) || isAppChromeLabel(t)) continue;
            texts.add(n);
        }
        texts.sort(Comparator.comparingInt(HitNode::area));
        int added = 0;
        for (HitNode n : texts) {
            if (added >= 3 || out.size() >= 10) break;
            String t = firstNonBlank(n.text, n.contentDesc).trim();
            HitCandidate text = baseCandidate(n, t);
            text.setLocatorType("text");
            text.setLocatorValue(t);
            text.setSuggestedNameZh(t.length() <= 20 ? t : t.substring(0, 20));
            text.setSuggestedNameEn(toSnake(t));
            text.setVisualHint("点上文案 · 「" + t + "」");
            text.setText(t);
            // 仅当文案本身就是退出登录才套别名，勿把其它文案改成 logout
            if ("退出登录".equals(t)) {
                text.setSuggestedNameEn("logout_confirm");
                text.setSuggestedNameZh("退出登录确认");
            }
            String key = text.getLocatorType() + "|" + text.getLocatorValue();
            if (seen.add(key)) {
                text.setRank(out.size());
                out.add(text);
                added++;
            }
            HitCandidate xp = baseCandidate(n, t);
            xp.setLocatorType("xpath");
            xp.setLocatorValue("//*[@text=" + xpathLiteral(t) + "]");
            xp.setSuggestedNameZh(text.getSuggestedNameZh());
            xp.setSuggestedNameEn(text.getSuggestedNameEn());
            xp.setVisualHint("xpath@text · 「" + t + "」");
            xp.setText(t);
            key = xp.getLocatorType() + "|" + xp.getLocatorValue();
            if (seen.add(key)) {
                xp.setRank(out.size());
                out.add(xp);
            }
            if (n.clickable || findClickableAncestor(n) != null) {
                HitCandidate clickable = baseCandidate(n, t);
                clickable.setLocatorType("xpath");
                clickable.setLocatorValue("//*[@text=" + xpathLiteral(t)
                        + "]/ancestor-or-self::*[@clickable=\"true\"][1]");
                clickable.setSuggestedNameZh(text.getSuggestedNameZh());
                clickable.setSuggestedNameEn(text.getSuggestedNameEn());
                clickable.setVisualHint("xpath@text+clickable · 「" + t + "」");
                clickable.setText(t);
                key = clickable.getLocatorType() + "|" + clickable.getLocatorValue();
                if (seen.add(key)) {
                    clickable.setRank(out.size());
                    out.add(clickable);
                }
            }
        }
    }

    /**
     * 点击落点下的小控件，若已有 ACTIVE 元素（同 resource-id），直接置顶推荐。
     * 文案冲突时跳过（点「登录注册」不能推荐「退出登录」）。
     */
    private void prependActiveLibraryHits(List<HitCandidate> out, java.util.LinkedHashSet<String> seen,
                                          List<HitNode> scoped, int px, int py,
                                          String appId, String platform) {
        if (scoped == null || scoped.isEmpty() || appId == null || appId.isBlank()) return;
        List<HitNode> under = new ArrayList<>();
        for (HitNode n : scoped) {
            if (!n.displayed) continue;
            if (!(n.left <= px && px < n.right && n.top <= py && py < n.bottom)) continue;
            if (isGenericContainer(n) || isWebViewShell(n)) continue;
            if (n.resourceId == null || n.resourceId.isBlank()) continue;
            under.add(n);
        }
        under.sort(Comparator.comparingInt(HitNode::area));
        int added = 0;
        for (HitNode n : under) {
            if (added >= 4) break;
            String nodeText = firstNonBlank(n.text, n.contentDesc);
            List<UiElement> matched = uiElementService.findByLocatorHint(appId, platform, n.resourceId.trim(), 8);
            if (matched == null || matched.isEmpty()) continue;
            for (UiElement e : matched) {
                if (e == null || e.getStatus() == null || !"ACTIVE".equalsIgnoreCase(e.getStatus())) continue;
                if (!libraryHitTextCompatible(nodeText, e)) {
                    log.info("点选跳过冲突的库元素: nodeText={} lib={} {}", nodeText, e.getNameEn(), e.getNameZh());
                    continue;
                }
                String type = e.getLocatorType() != null ? e.getLocatorType().trim() : "id";
                String val = e.getLocatorValue() != null ? e.getLocatorValue().trim() : n.resourceId.trim();
                // 仅接受定位值与当前节点 id 一致（或 xpath 含该 id），避免 hint 模糊命中
                if (!locatorBelongsToNode(e, n)) continue;
                String key = type + "|" + val;
                if (!seen.add(key)) continue;
                HitCandidate c = baseCandidate(n, e.getNameZh());
                c.setLocatorType(type);
                c.setLocatorValue(val);
                c.setSuggestedNameZh(e.getNameZh() != null ? e.getNameZh() : e.getNameEn());
                c.setSuggestedNameEn(e.getNameEn());
                c.setVisualHint("库内 ACTIVE · " + e.getNameEn());
                c.setMatchedElementId(e.getId());
                c.setMatchedNameEn(e.getNameEn());
                c.setMatchedNameZh(e.getNameZh());
                c.setMatchedStatus(e.getStatus());
                c.setRank(out.size());
                out.add(c);
                added++;
                if (added >= 4) break;
            }
        }
    }

    /** 库元素与点上节点文案不能明显打架（登录 vs 退出） */
    private static boolean libraryHitTextCompatible(String nodeText, UiElement e) {
        if (e == null) return false;
        String lib = (nullToEmpty(e.getNameZh()) + " " + nullToEmpty(e.getNameEn()) + " "
                + nullToEmpty(e.getLocatorValue())).toLowerCase(Locale.ROOT);
        String node = nullToEmpty(nodeText);
        if (node.isBlank()) return true;
        String n = node.toLowerCase(Locale.ROOT);
        boolean nodeLogin = n.contains("登录") && !n.contains("退出");
        boolean libLogout = lib.contains("退出登录") || lib.contains("logout");
        if (nodeLogin && libLogout) return false;
        boolean nodeLogout = n.contains("退出登录") || n.equals("退出");
        boolean libLoginCta = (lib.contains("登录注册") || lib.contains("立即登录") || lib.contains("点击登录"))
                && !lib.contains("退出");
        if (nodeLogout && libLoginCta) return false;
        // 库 xpath/text 指定了文案时，节点文案应相关
        String lv = nullToEmpty(e.getLocatorValue());
        if (lv.contains("@text=") || "text".equalsIgnoreCase(e.getLocatorType())) {
            String expect = e.getNameZh();
            if (expect != null && !expect.isBlank() && !node.contains(expect) && !expect.contains(node)
                    && !lv.contains(node) && !node.isBlank()) {
                // 宽松：若库名与节点完全无关则拒绝
                if (!sharesLoginFamily(node, expect)) return false;
            }
        }
        return true;
    }

    private static boolean sharesLoginFamily(String a, String b) {
        if (a == null || b == null) return false;
        boolean aOut = a.contains("退出");
        boolean bOut = b.contains("退出");
        if (aOut != bOut) return false;
        return a.contains("登录") && b.contains("登录");
    }

    private static boolean locatorBelongsToNode(UiElement e, HitNode n) {
        if (e == null || n == null || n.resourceId == null) return false;
        String val = nullToEmpty(e.getLocatorValue());
        String rid = n.resourceId.trim();
        String sid = shortResourceId(rid);
        if (val.equals(rid) || val.equals(sid)) return true;
        if (val.contains(rid) || (sid != null && val.contains("/" + sid))) return true;
        // text/xpath 纯文案元素：允许（由文案兼容性把关）
        String type = e.getLocatorType() != null ? e.getLocatorType().toLowerCase(Locale.ROOT) : "";
        return "text".equals(type) || (type.contains("xpath") && val.contains("@text"));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 发评论弹层打开时：键盘已出，点输入区应优先 EditText / 友好评论文案，
     * 即使点击坐标同时落在背后 HorizontalScrollView 上。
     * 注意：仅当点击落在输入框 bounds 内才推；点「发布」时不要用 focused 兜底抢走。
     */
    private void prependInputFieldCandidates(List<HitCandidate> out, java.util.LinkedHashSet<String> seen,
                                             List<HitNode> labelPool, List<HitNode> scoped, int px, int py) {
        if (labelPool == null || labelPool.isEmpty()) return;
        List<HitNode> picks = new ArrayList<>();
        for (HitNode n : labelPool) {
            if (!n.displayed) continue;
            boolean covers = n.left <= px && px < n.right && n.top <= py && py < n.bottom;
            if (!covers) continue;
            // 同一点上有更小的「发布」节点时：点的是按钮，跳过大 EditText 作为首选来源
            // （EditText 仍可能在后面普通排序里出现，但不会盖住发布）
            if (isEditText(n) && hasSmallerComposerActionInside(labelPool, n, px, py)) {
                continue;
            }
            if (isEditText(n)) {
                picks.add(n);
                continue;
            }
            String lab = firstNonBlank(n.text, n.contentDesc);
            if (looksLikeCommentHint(lab)) {
                picks.add(n);
            }
        }
        picks.sort(Comparator
                .comparingInt((HitNode n) -> (n.focused ? 2 : 0) + (isEditText(n) ? 2 : 0)).reversed()
                .thenComparingInt(HitNode::area));
        for (HitNode n : picks) {
            if (out.size() >= 8) break;
            List<HitCandidate> strategies = expandLocatorStrategies(n, labelPool, px, py);
            // EditText 额外给稳定 class / focused xpath
            if (isEditText(n)) {
                HitCandidate focusedXp = baseCandidate(n, firstNonBlank(n.text, "评论输入"));
                focusedXp.setLocatorType("xpath");
                focusedXp.setLocatorValue(n.focused
                        ? "//android.widget.EditText[@focused='true']"
                        : "//android.widget.EditText");
                focusedXp.setSuggestedNameZh("评论输入框");
                focusedXp.setSuggestedNameEn("community_comment_edit");
                focusedXp.setVisualHint(n.focused ? "EditText@focused · 评论弹层输入" : "EditText · 评论弹层输入");
                strategies = new ArrayList<>(strategies);
                strategies.add(0, focusedXp);
                if (n.resourceId != null && !n.resourceId.isBlank()) {
                    HitCandidate idc = baseCandidate(n, "评论输入");
                    idc.setLocatorType("id");
                    idc.setLocatorValue(n.resourceId.trim());
                    idc.setSuggestedNameZh("评论输入框");
                    idc.setSuggestedNameEn("community_comment_edit");
                    idc.setVisualHint("id · EditText");
                    strategies.add(0, idc);
                }
            }
            if (looksLikeCommentHint(firstNonBlank(n.text, n.contentDesc)) && !isEditText(n)) {
                HitCandidate hint = baseCandidate(n, "友好评论，友善社区");
                hint.setLocatorType("xpath");
                hint.setLocatorValue("//*[contains(@text,'友好评论')]");
                hint.setSuggestedNameZh("评论输入提示");
                hint.setSuggestedNameEn("community_comment_hint");
                hint.setVisualHint("占位文案 · 需再点一次才进 EditText；输入请用 EditText 方案");
                strategies = new ArrayList<>(strategies);
                strategies.add(0, hint);
            }
            for (HitCandidate c : strategies) {
                if (out.size() >= 12) break;
                if (c.getLocatorValue() == null || c.getLocatorValue().isBlank()) continue;
                String key = c.getLocatorType() + "|" + c.getLocatorValue();
                if (!seen.add(key)) continue;
                c.setRank(out.size());
                out.add(c);
            }
        }
    }

    /**
     * 顶栏左侧汉堡：点选常落在 layoutTitleBar 大壳上，再套活动标题「Go搭子…」。
     * 左上角点击时优先推出 sideView / vg_slide_entrance。
     */
    private void prependSideMenuCandidates(List<HitCandidate> out, java.util.LinkedHashSet<String> seen,
                                           List<HitNode> labelPool, List<HitNode> scoped, int px, int py) {
        if (labelPool == null) return;
        HitNode side = findSideMenuNode(scoped, px, py);
        if (side == null) side = findSideMenuNode(labelPool, px, py);
        if (side == null && px >= 0 && py >= 0 && px < 240 && py < 320) {
            side = findNearestSideMenu(labelPool, px, py);
        }
        if (side == null || side.resourceId == null || side.resourceId.isBlank()) return;

        HitCandidate idc = baseCandidate(side, "侧边栏入口");
        idc.setLocatorType("id");
        idc.setLocatorValue(side.resourceId.trim());
        idc.setSuggestedNameZh("侧边栏入口");
        idc.setSuggestedNameEn("home_side_menu");
        idc.setVisualHint("顶栏汉堡 · sideView（勿用 layoutTitleBar / 活动标题）");
        idc.setText(null);
        String key = idc.getLocatorType() + "|" + idc.getLocatorValue();
        if (seen.add(key)) {
            idc.setRank(out.size());
            out.add(idc);
        }

        HitCandidate xp = baseCandidate(side, "侧边栏入口");
        xp.setLocatorType("xpath");
        xp.setLocatorValue("//*[@resource-id=" + xpathLiteral(side.resourceId.trim()) + "]");
        xp.setSuggestedNameZh("侧边栏入口");
        xp.setSuggestedNameEn("home_side_menu");
        xp.setVisualHint("xpath@id · 侧栏汉堡");
        key = xp.getLocatorType() + "|" + xp.getLocatorValue();
        if (seen.add(key)) {
            xp.setRank(out.size());
            out.add(xp);
        }
    }

    private static HitNode findSideMenuNode(List<HitNode> pool, int px, int py) {
        if (pool == null) return null;
        HitNode best = null;
        for (HitNode n : pool) {
            if (!n.displayed) continue;
            String sid = shortResourceId(n.resourceId);
            if (!"sideView".equalsIgnoreCase(sid) && !"vg_slide_entrance".equalsIgnoreCase(sid)) continue;
            if (px >= 0 && py >= 0 && !(n.left <= px && px < n.right && n.top <= py && py < n.bottom)) {
                continue;
            }
            if (best == null || n.area() < best.area()) best = n;
        }
        return best;
    }

    private static HitNode findNearestSideMenu(List<HitNode> pool, int px, int py) {
        if (pool == null) return null;
        HitNode best = null;
        int bestDist = Integer.MAX_VALUE;
        for (HitNode n : pool) {
            if (!n.displayed) continue;
            String sid = shortResourceId(n.resourceId);
            if (!"sideView".equalsIgnoreCase(sid) && !"vg_slide_entrance".equalsIgnoreCase(sid)) continue;
            int cx = (n.left + n.right) / 2;
            int cy = (n.top + n.bottom) / 2;
            int dist = Math.abs(cx - px) + Math.abs(cy - py);
            if (dist > 160) continue;
            if (dist < bestDist) {
                best = n;
                bestDist = dist;
            }
        }
        return best;
    }

    private void prependComposerActionCandidates(List<HitCandidate> out, java.util.LinkedHashSet<String> seen,
                                                 List<HitNode> labelPool, int px, int py) {
        if (labelPool == null) return;
        HitNode best = null;
        for (HitNode n : labelPool) {
            if (!n.displayed) continue;
            if (!(n.left <= px && px < n.right && n.top <= py && py < n.bottom)) continue;
            String lab = firstNonBlank(n.text, n.contentDesc);
            if (!isComposerActionLabel(lab)) continue;
            if (best == null || n.area() < best.area()) best = n;
        }
        // 近邻吸附：只在「当前点不在任何 EditText 内」时启用，避免点输入框下半区被吸成发布
        if (best == null && !isPointInsideAnyEditText(labelPool, px, py)) {
            best = findNearbyComposerAction(labelPool, px, py);
        }
        if (best == null) return;
        String lab = firstNonBlank(best.text, best.contentDesc);
        if (lab == null) lab = "发布";
        List<HitCandidate> strategies = new ArrayList<>();
        if (best.resourceId != null && !best.resourceId.isBlank() && !isUnstableResourceId(best.resourceId)) {
            HitCandidate idc = baseCandidate(best, lab);
            idc.setLocatorType("id");
            idc.setLocatorValue(best.resourceId.trim());
            applyComposerActionNames(idc, lab);
            idc.setVisualHint("id · 评论弹层动作");
            strategies.add(idc);
        }
        HitCandidate text = baseCandidate(best, lab);
        text.setLocatorType("text");
        text.setLocatorValue(lab.trim());
        applyComposerActionNames(text, lab);
        text.setVisualHint("text · " + lab);
        strategies.add(text);

        HitCandidate xp = baseCandidate(best, lab);
        xp.setLocatorType("xpath");
        xp.setLocatorValue("//*[@text=" + xpathLiteral(lab.trim()) + "]");
        applyComposerActionNames(xp, lab);
        xp.setVisualHint("xpath@text · " + lab);
        strategies.add(xp);

        HitCandidate clickable = baseCandidate(best, lab);
        clickable.setLocatorType("xpath");
        clickable.setLocatorValue("//*[@text=" + xpathLiteral(lab.trim())
                + "]/ancestor-or-self::*[@clickable=\"true\"][1]");
        applyComposerActionNames(clickable, lab);
        clickable.setVisualHint("xpath@text+clickable · " + lab);
        strategies.add(clickable);

        for (HitCandidate c : strategies) {
            String key = c.getLocatorType() + "|" + c.getLocatorValue();
            if (!seen.add(key)) continue;
            c.setRank(out.size());
            out.add(c);
        }
    }

    private static boolean isPointInsideAnyEditText(List<HitNode> pool, int px, int py) {
        if (pool == null) return false;
        for (HitNode n : pool) {
            if (!n.displayed || !isEditText(n)) continue;
            if (n.left <= px && px < n.right && n.top <= py && py < n.bottom) return true;
        }
        return false;
    }

    private static void applyComposerActionNames(HitCandidate c, String lab) {
        String t = lab != null ? lab.trim() : "发布";
        c.setSuggestedNameZh(t);
        c.setSuggestedNameEn(switch (t) {
            case "发布" -> "community_comment_publish";
            case "发送" -> "community_comment_send";
            case "发表" -> "community_comment_post";
            case "取消" -> "community_comment_cancel";
            default -> toSnake(t);
        });
    }

    private static boolean isComposerActionLabel(String label) {
        if (label == null) return false;
        String t = label.trim();
        return "发布".equals(t) || "发送".equals(t) || "发表".equals(t) || "取消".equals(t);
    }

    private static boolean hasComposerActionAt(List<HitNode> pool, int px, int py) {
        if (pool == null) return false;
        for (HitNode n : pool) {
            if (!n.displayed) continue;
            if (!(n.left <= px && px < n.right && n.top <= py && py < n.bottom)) continue;
            if (isComposerActionLabel(firstNonBlank(n.text, n.contentDesc))) return true;
        }
        return findNearbyComposerAction(pool, px, py) != null;
    }

    private static boolean hasSmallerComposerActionInside(List<HitNode> pool, HitNode edit, int px, int py) {
        if (pool == null || edit == null) return false;
        for (HitNode n : pool) {
            if (!n.displayed || n == edit) continue;
            if (!(n.left <= px && px < n.right && n.top <= py && py < n.bottom)) continue;
            if (!isComposerActionLabel(firstNonBlank(n.text, n.contentDesc))) continue;
            if (n.area() < edit.area()) return true;
        }
        return false;
    }

    /** 点在「发布」按钮色块上时，文案节点可能略偏，按距离吸附 */
    private static HitNode findNearbyComposerAction(List<HitNode> pool, int px, int py) {
        HitNode best = null;
        int bestDist = Integer.MAX_VALUE;
        for (HitNode n : pool) {
            if (!n.displayed) continue;
            if (!isComposerActionLabel(firstNonBlank(n.text, n.contentDesc))) continue;
            int cx = (n.left + n.right) / 2;
            int cy = (n.top + n.bottom) / 2;
            int dist = Math.abs(cx - px) + Math.abs(cy - py);
            // 仅吸附很近的按钮（约半指宽）；且调用方已保证不在 EditText 内
            if (dist > 72) continue;
            if (dist < bestDist) {
                best = n;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * 仅当「当前点上最优命中」本身就在前景容器树内时才裁剪。
     * 避免关闭态 side_layout 铺满屏幕时，把标题栏汉堡 sideView 裁进侧栏树。
     */
    private static boolean shouldScopeToFrontSurface(HitNode drawer, List<HitNode> containing,
                                                     int px, int py, int screenArea) {
        if (drawer == null || containing == null || containing.isEmpty()) return false;
        if (!(drawer.left <= px && px < drawer.right && drawer.top <= py && py < drawer.bottom)) {
            return false;
        }
        if (drawer.area() <= screenArea * 0.12) return false;

        HitNode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (HitNode n : containing) {
            if (!isInteresting(n) || isGenericContainer(n)) continue;
            int score = hitRankScore(n, screenArea);
            if (best == null || score > bestScore
                    || (score == bestScore && n.area() < best.area())) {
                best = n;
                bestScore = score;
            }
        }
        if (best == null) {
            return true;
        }
        return isTreeDescendantOrSelf(best, drawer);
    }

    private static boolean isGenericContainer(HitNode n) {
        if (isWebViewShell(n)) return true;
        if (isScrollShell(n)) return true;
        String id = shortResourceId(n.resourceId);
        if (id == null) return false;
        String l = id.toLowerCase(Locale.ROOT);
        if ("title_container".equals(l) && findAncestorByShortId(n, "bottomTabLayout") != null) {
            return true;
        }
        return "pw_more_gridview".equals(l)
                || "side_layout".equals(l)
                || "drawerlayout".equals(l)
                || "srl_refresh".equals(l)
                || "pwx5webview".equals(l)
                || "hsv_game_info".equals(l)
                || "layouttitlebar".equals(l)
                || "vg_title_bar".equals(l)
                || "vg_titlebar".equals(l)
                || l.contains("viewpager")
                || l.contains("recyclerview")
                || "content".equals(l)
                || "action_bar_root".equals(l);
    }

    /** 横滑/列表壳：评论弹层打开时易穿透命中背后游戏卡 */
    private static boolean isScrollShell(HitNode n) {
        if (n == null || n.className == null) return false;
        String c = n.className;
        return c.contains("HorizontalScrollView")
                || c.contains("ScrollView")
                || c.contains("RecyclerView")
                || c.contains("ViewPager");
    }

    private static boolean isEditText(HitNode n) {
        if (n == null || n.className == null) return false;
        String c = n.className;
        return c.endsWith("EditText") || c.contains(".EditText") || c.contains("TextField");
    }

    private static boolean looksLikeCommentHint(String label) {
        if (label == null) return false;
        return label.contains("友好评论") || label.contains("友善社区") || label.contains("说点什么");
    }

    /** 全屏/半屏 WebView 壳：H5 列表点选只能看到这一层 */
    private static boolean isWebViewShell(HitNode n) {
        if (n == null) return false;
        String id = shortResourceId(n.resourceId);
        if (id != null && id.toLowerCase(Locale.ROOT).contains("webview")) return true;
        String cls = n.className != null ? n.className : "";
        return cls.contains("WebView");
    }

    private static boolean isGenericToolIcon(HitNode n) {
        String id = shortResourceId(n.resourceId);
        return id != null && "img_tool_icon".equalsIgnoreCase(id);
    }

    /** 底栏/多 Tab 复用 id（裸 id 会点到错误 Tab） */
    private static boolean isBottomTabSharedId(String resourceId) {
        String n = normalizeIdKey(shortResourceId(resourceId));
        if (n == null) return false;
        return "ivimage".equals(n)
                || "tvtext".equals(n)
                || "titlecontainer".equals(n)
                || "tvtoolmame".equals(n);
    }

    private static String normalizeIdKey(String shortId) {
        if (shortId == null || shortId.isBlank()) return null;
        return shortId.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static HitNode findAncestorByShortId(HitNode n, String shortId) {
        if (shortId == null || shortId.isBlank()) return null;
        HitNode p = n;
        while (p != null) {
            if (shortId.equalsIgnoreCase(shortResourceId(p.resourceId))) return p;
            p = p.parent;
        }
        return null;
    }

    private static boolean isUselessClassNameLocator(String className) {
        if (className == null || className.isBlank()) return true;
        String c = className.trim();
        // 这些 class 满页都是，单独当定位等于没定位（底栏「我的」曾因此入库后永远判不存在）
        return "android.widget.FrameLayout".equals(c)
                || "android.widget.LinearLayout".equals(c)
                || "android.widget.RelativeLayout".equals(c)
                || "android.view.ViewGroup".equals(c)
                || "android.view.View".equals(c)
                || "android.widget.ImageView".equals(c)
                || c.contains("WebView")
                || c.contains("HorizontalScrollView")
                || c.contains("ScrollView");
    }

    /** 红点/分隔符等噪声文案，不能当 Tab 名 */
    private static boolean isNoiseLabel(String label) {
        if (label == null || label.isBlank()) return true;
        String s = label.trim();
        if ("·".equals(s) || "•".equals(s) || "‧".equals(s) || "・".equals(s) || "●".equals(s) || ".".equals(s)) {
            return true;
        }
        if (s.matches("\\d{1,4}")) return true; // 角标数字
        return s.matches("\\p{P}+");
    }

    private static final class TabStripDef {
        final String containerShortId;
        final java.util.Set<String> labels;
        final String hintTag;
        final boolean clickableFrameAncestor;

        TabStripDef(String containerShortId, java.util.Set<String> labels, String hintTag, boolean clickableFrameAncestor) {
            this.containerShortId = containerShortId;
            this.labels = labels;
            this.hintTag = hintTag;
            this.clickableFrameAncestor = clickableFrameAncestor;
        }
    }

    private static final List<TabStripDef> TAB_STRIPS = List.of(
            new TabStripDef("bottomTabLayout",
                    java.util.Set.of("发现", "赛事", "社区", "数据", "我的"),
                    "xpath@bottomTab", true),
            new TabStripDef("view_mine_tab",
                    java.util.Set.of("动态", "评论", "梗词条", "完美时刻", "留言"),
                    "xpath@mineTab", false)
    );

    /**
     * 帖内评论筛选/排序：H5 无障碍常把「全部评论只看楼主 热门…」挤在同一 text 节点。
     * 点选时按点击 X 拆成单段候选（contains），避免整串入库。
     */
    private static final List<String> MERGED_H5_FILTER_LABELS = List.of(
            "全部评论", "只看楼主", "热门", "最早", "最新"
    );

    /** 若文案是多段筛选项挤在一起，按点击 X 在节点内的比例选出最可能的一段；否则 null */
    private static String pickMergedH5FilterLabel(String merged, HitNode n, int px) {
        List<String> hits = orderedMergedFilterHits(merged);
        if (hits.size() < 2) return null;
        int idx = mergedFilterIndexAtX(n, hits, px);
        if (idx < 0 || idx >= hits.size()) return hits.get(0);
        return hits.get(idx);
    }

    private static List<String> orderedMergedFilterHits(String merged) {
        List<String> hits = new ArrayList<>();
        if (merged == null || merged.isBlank()) return hits;
        // 按在整串中出现顺序（「全部评论」先于「热门」等）
        List<int[]> pos = new ArrayList<>();
        for (String lab : MERGED_H5_FILTER_LABELS) {
            int i = merged.indexOf(lab);
            if (i >= 0) pos.add(new int[]{i, MERGED_H5_FILTER_LABELS.indexOf(lab)});
        }
        pos.sort(Comparator.comparingInt(a -> a[0]));
        for (int[] p : pos) {
            hits.add(MERGED_H5_FILTER_LABELS.get(p[1]));
        }
        return hits;
    }

    /** 按各段字数占比切条带，比均分更贴近「全部评论」宽、「最新」窄 */
    private static int mergedFilterIndexAtX(HitNode n, List<String> hits, int px) {
        if (hits == null || hits.isEmpty()) return 0;
        if (n == null || n.right <= n.left || px < 0) return 0;
        int total = 0;
        for (String h : hits) total += Math.max(1, h.length());
        int width = n.right - n.left;
        int x = px;
        int acc = 0;
        for (int i = 0; i < hits.size(); i++) {
            int seg = Math.max(1, (int) Math.round(width * (Math.max(1, hits.get(i).length()) / (double) total)));
            int left = n.left + acc;
            int right = (i == hits.size() - 1) ? n.right : left + seg;
            if (x >= left && x < right) return i;
            acc += seg;
        }
        return hits.size() - 1;
    }

    /** 拆分段的近似 bounds，供红框只罩「最新」而不是整条筛选栏 */
    private static Map<String, Integer> segmentBoundsForMergedFilter(HitNode n, String merged, String picked, int px) {
        List<String> hits = orderedMergedFilterHits(merged);
        if (n == null || hits.isEmpty()) {
            return n == null ? Map.of() : Map.of("left", n.left, "top", n.top, "right", n.right, "bottom", n.bottom);
        }
        int idx = hits.indexOf(picked);
        if (idx < 0) idx = mergedFilterIndexAtX(n, hits, px);
        int total = 0;
        for (String h : hits) total += Math.max(1, h.length());
        int width = Math.max(1, n.right - n.left);
        int acc = 0;
        for (int i = 0; i < hits.size(); i++) {
            int seg = Math.max(1, (int) Math.round(width * (Math.max(1, hits.get(i).length()) / (double) total)));
            int left = n.left + acc;
            int right = (i == hits.size() - 1) ? n.right : Math.min(n.right, left + seg);
            if (i == idx) {
                // 略收一点左右边距，红框更像单段
                int pad = Math.min(8, Math.max(0, (right - left) / 10));
                return Map.of(
                        "left", left + pad,
                        "top", n.top,
                        "right", Math.max(left + pad + 1, right - pad),
                        "bottom", n.bottom
                );
            }
            acc += seg;
        }
        return Map.of("left", n.left, "top", n.top, "right", n.right, "bottom", n.bottom);
    }

    private static String mergedFilterNameEn(String label) {
        return switch (label) {
            case "全部评论" -> "community_comment_filter_all";
            case "只看楼主" -> "community_comment_filter_op";
            case "热门" -> "community_comment_sort_hot";
            case "最早" -> "community_comment_sort_oldest";
            case "最新" -> "community_comment_sort_newest";
            default -> toSnake(label);
        };
    }

    private static boolean isBottomNavTabLabel(String label) {
        if (label == null || isNoiseLabel(label)) return false;
        return switch (label.trim()) {
            case "发现", "赛事", "社区", "数据", "我的" -> true;
            default -> false;
        };
    }

    private static boolean isMineContentTabLabel(String label) {
        if (label == null || isNoiseLabel(label)) return false;
        return switch (label.trim()) {
            case "动态", "评论", "梗词条", "完美时刻", "留言" -> true;
            default -> false;
        };
    }

    private static boolean isKnownStripTabLabel(String label) {
        return isBottomNavTabLabel(label) || isMineContentTabLabel(label);
    }

    /**
     * 底栏图标格子常无 text，「我的」在兄弟 TextView。按点击 X（或节点中心）对齐找最近底栏文案。
     */
    private static String findStripLabelNear(TabStripDef strip, HitNode n, List<HitNode> pool, int px) {
        if (strip == null || pool == null) return null;
        HitNode bar = findAncestorByShortId(n, strip.containerShortId);
        if (bar == null) {
            for (HitNode o : pool) {
                if (strip.containerShortId.equalsIgnoreCase(shortResourceId(o.resourceId))) {
                    bar = o;
                    break;
                }
            }
        }
        if (bar == null) return null;
        int anchorX = px >= 0 ? px : (n != null ? (n.left + n.right) / 2 : -1);
        if (anchorX < 0) return null;
        HitNode best = null;
        int bestDist = Integer.MAX_VALUE;
        for (HitNode o : pool) {
            if (!isTreeDescendantOrSelf(o, bar)) continue;
            String t = firstNonBlank(o.text, o.contentDesc);
            if (t == null || isNoiseLabel(t) || !strip.labels.contains(t.trim())) continue;
            int ocx = (o.left + o.right) / 2;
            int dist = Math.abs(ocx - anchorX);
            if (dist < bestDist) {
                best = o;
                bestDist = dist;
            }
        }
        if (best == null) return null;
        int tabW = Math.max(80, (bar.right - bar.left) / Math.max(3, strip.labels.size()));
        if (bestDist > tabW) return null;
        return firstNonBlank(best.text, best.contentDesc);
    }

    private HitCandidate buildScopedTabCandidate(List<HitNode> scoped, List<HitNode> labelPool, int px, int py) {
        if (scoped == null || scoped.isEmpty() || labelPool == null) return null;
        for (TabStripDef strip : TAB_STRIPS) {
            HitNode bar = null;
            for (HitNode o : labelPool) {
                if (strip.containerShortId.equalsIgnoreCase(shortResourceId(o.resourceId))) {
                    bar = o;
                    break;
                }
            }
            if (bar == null || bar.resourceId == null) continue;

            // 必须点在 Tab 条纵向范围内；全屏 WebView 与条几何相交不能算点中 Tab
            int pad = 24;
            if (px < 0 || py < bar.top - pad || py >= bar.bottom + pad) {
                continue;
            }

            boolean hitBar = false;
            for (HitNode n : scoped) {
                if (isTreeDescendantOrSelf(n, bar)
                        || (n.left < bar.right && n.right > bar.left && n.top < bar.bottom && n.bottom > bar.top
                        && !isWebViewShell(n) && n.area() < bar.area() * 4)) {
                    hitBar = true;
                    break;
                }
            }
            if (!hitBar) continue;

            String tabLabel = findStripLabelNear(strip, bar, labelPool, px);
            if (tabLabel == null) continue;

            HitNode labelNode = null;
            for (HitNode o : labelPool) {
                if (!isTreeDescendantOrSelf(o, bar)) continue;
                if (tabLabel.equals(firstNonBlank(o.text, o.contentDesc))) {
                    labelNode = o;
                    break;
                }
            }
            HitNode base = labelNode != null ? labelNode : bar;
            HitCandidate cand = baseCandidate(base, tabLabel);
            cand.setLocatorType("xpath");
            if (strip.clickableFrameAncestor) {
                cand.setLocatorValue("//*[@resource-id=" + xpathLiteral(bar.resourceId.trim())
                        + "]//*[@text=" + xpathLiteral(tabLabel)
                        + "]/ancestor::android.widget.FrameLayout[@clickable=\"true\"][1]");
            } else {
                cand.setLocatorValue("//*[@resource-id=" + xpathLiteral(bar.resourceId.trim())
                        + "]//*[@text=" + xpathLiteral(tabLabel) + "]");
            }
            applySuggestedNames(cand, tabLabel, base);
            cand.setVisualHint(strip.hintTag + " · " + tabLabel);
            return cand;
        }
        return null;
    }

    private static HitNode findClickableAncestor(HitNode n) {
        HitNode p = n;
        HitNode wideFallback = null;
        while (p != null) {
            if (p.clickable && p.area() < 200_000) {
                // 底栏 title_container 横跨整条，跳过，优先 Tab 格子
                if ("title_container".equalsIgnoreCase(shortResourceId(p.resourceId))) {
                    wideFallback = p;
                } else {
                    return p;
                }
            }
            p = p.parent;
        }
        return wideFallback;
    }

    /** 侧栏/弹层/对话框前景：优先限定到前景容器内，避免穿透背后页 */
    private static HitNode findFrontSurface(List<HitNode> containing, List<HitNode> all) {
        HitNode privacy = findPrivacyWarmTipSurface(all);
        if (privacy != null) return privacy;
        HitNode comment = findCommentComposerSurface(all);
        if (comment != null) return comment;
        HitNode dialog = findDialogLikeSurface(containing, all);
        if (dialog != null) return dialog;
        // side_layout：仅候选；是否裁剪由 shouldScopeToFrontSurface 决定（关闭态常铺满勿误伤）
        for (HitNode n : containing) {
            String id = shortResourceId(n.resourceId);
            if (id == null) continue;
            if ("side_layout".equalsIgnoreCase(id)) {
                return n;
            }
        }
        for (HitNode n : containing) {
            String id = shortResourceId(n.resourceId);
            if (id == null) continue;
            String lower = id.toLowerCase(Locale.ROOT);
            if ("design_bottom_sheet".equals(lower) || "pw_more_gridview".equals(lower)) {
                return n;
            }
        }
        return null;
    }

    /** 协议温馨提示：以含同意/不同意的面板为前景，避免误用首页 side_layout */
    private static HitNode findPrivacyWarmTipSurface(List<HitNode> all) {
        if (all == null) return null;
        HitNode tipBtn = null;
        for (HitNode n : all) {
            String id = shortResourceId(n.resourceId);
            if ("tv_agree".equalsIgnoreCase(id) || "tv_not_agree".equalsIgnoreCase(id)
                    || "tv_state".equalsIgnoreCase(id)) {
                tipBtn = n;
                break;
            }
            if ("温馨提示".equals(firstNonBlank(n.text, n.contentDesc))) {
                tipBtn = n;
                break;
            }
        }
        if (tipBtn == null) return null;
        HitNode best = tipBtn;
        HitNode p = tipBtn.parent;
        while (p != null) {
            String cls = p.className != null ? p.className : "";
            // 弹窗内容根多为 LinearLayout / 带 android:id/content 的 FrameLayout
            if (cls.contains("LinearLayout") || "content".equalsIgnoreCase(shortResourceId(p.resourceId))) {
                best = p;
            }
            if (p.parent == null) break;
            p = p.parent;
        }
        return best;
    }

    /** 发评论半屏：含「友好评论」或 focused EditText +「发布」 */
    private static HitNode findCommentComposerSurface(List<HitNode> all) {
        if (all == null) return null;
        HitNode anchor = null;
        for (HitNode n : all) {
            if (!n.displayed) continue;
            if (isEditText(n) && n.focused) {
                anchor = n;
                break;
            }
            if (looksLikeCommentHint(firstNonBlank(n.text, n.contentDesc))) {
                anchor = n;
                break;
            }
        }
        if (anchor == null) {
            boolean hasPublish = false;
            HitNode edit = null;
            for (HitNode n : all) {
                if (!n.displayed) continue;
                if ("发布".equals(firstNonBlank(n.text, n.contentDesc))) hasPublish = true;
                if (isEditText(n)) edit = n;
            }
            if (hasPublish && edit != null) anchor = edit;
        }
        if (anchor == null) return null;
        int screenArea = estimateScreenArea(all);
        HitNode best = anchor;
        HitNode p = anchor.parent;
        while (p != null) {
            double ratio = screenArea > 0 ? (double) p.area() / screenArea : 1;
            if (ratio >= 0.08 && ratio <= 0.65 && !isWebViewShell(p) && !isScrollShell(p)) {
                best = p;
            }
            if (ratio > 0.7) break;
            p = p.parent;
        }
        return best;
    }

    private static HitNode findDialogLikeSurface(List<HitNode> containing, List<HitNode> all) {
        List<HitNode> pool = all != null ? all : containing;
        if (pool == null) return null;
        int screenArea = estimateScreenArea(pool);
        HitNode best = null;
        for (HitNode n : pool) {
            if (!looksLikeDialogRoot(n, screenArea)) continue;
            // 优先面积更小的对话框面板
            if (best == null || n.area() < best.area()) best = n;
        }
        if (best != null) return best;
        // 点落在含「取消/确定/退出」文案的节点上：上溯到中等面积祖先当弹层根
        if (containing == null) return null;
        for (HitNode n : containing) {
            String label = firstNonBlank(n.text, n.contentDesc);
            if (label == null) continue;
            if (!isDialogActionText(label) && !label.contains("确定") && !label.contains("是否")) continue;
            HitNode root = n;
            HitNode p = n.parent;
            while (p != null) {
                double ratio = screenArea > 0 ? (double) p.area() / screenArea : 1;
                if (ratio >= 0.04 && ratio <= 0.55) {
                    root = p;
                    break;
                }
                if (ratio > 0.55) break;
                root = p;
                p = p.parent;
            }
            return root;
        }
        return null;
    }

    private static boolean looksLikeDialogRoot(HitNode n, int screenArea) {
        if (n == null) return false;
        String id = shortResourceId(n.resourceId);
        if (id != null) {
            String l = id.toLowerCase(Locale.ROOT);
            if ("parentpanel".equals(l) || "buttonpanel".equals(l) || "contentpanel".equals(l)
                    || "toppanel".equals(l) || "custompanel".equals(l)
                    || l.contains("dialog") || "design_bottom_sheet".equals(l)) {
                return true;
            }
        }
        String cls = n.className != null ? n.className.toLowerCase(Locale.ROOT) : "";
        if (cls.contains("alertdialog") || cls.contains("dialogtitle")) return true;
        if (screenArea <= 0) return false;
        double ratio = (double) n.area() / screenArea;
        return ratio >= 0.05 && ratio <= 0.45 && n.left > 40 && n.right < (int) Math.sqrt(screenArea) * 1.2;
    }

    private static boolean isDialogActionText(String label) {
        String t = label.trim();
        return "取消".equals(t) || "确定".equals(t) || "确认".equals(t)
                || "退出登录".equals(t) || "退出".equals(t) || "关闭".equals(t)
                || "同意".equals(t) || "不同意".equals(t)
                || t.contains("退出登录");
    }

    private static boolean isTreeDescendantOrSelf(HitNode node, HitNode ancestor) {
        if (node == null || ancestor == null) return false;
        HitNode p = node;
        while (p != null) {
            if (p == ancestor) return true;
            p = p.parent;
        }
        return false;
    }

    private static boolean isInsideOrSame(HitNode inner, HitNode outer) {
        if (inner == null || outer == null) return false;
        if (inner == outer) return true;
        return inner.left >= outer.left && inner.right <= outer.right
                && inner.top >= outer.top && inner.bottom <= outer.bottom;
    }

    private static int estimateScreenArea(List<HitNode> nodes) {
        int max = 0;
        for (HitNode n : nodes) {
            int a = n.area();
            if (a > max) max = a;
        }
        return Math.max(max, 1080 * 1920);
    }

    private static boolean isInteresting(HitNode n) {
        if (n == null) return false;
        if (isEditText(n) || n.focused) return true;
        if (n.clickable) return true;
        if (firstNonBlank(n.text, n.contentDesc) != null) return true;
        return n.resourceId != null && !n.resourceId.isBlank() && !"null".equalsIgnoreCase(n.resourceId);
    }

    private List<HitCandidate> regexHitFallback(String xml, int px, int py) {
        List<HitCandidate> out = new ArrayList<>();
        Matcher m = BOUNDS.matcher(xml);
        List<int[]> boxes = new ArrayList<>();
        while (m.find()) {
            int l = Integer.parseInt(m.group(1));
            int t = Integer.parseInt(m.group(2));
            int r = Integer.parseInt(m.group(3));
            int b = Integer.parseInt(m.group(4));
            if (l <= px && px < r && t <= py && py < b) {
                boxes.add(new int[]{l, t, r, b, (r - l) * (b - t)});
            }
        }
        boxes.sort(Comparator.comparingInt(a -> a[4]));
        for (int i = 0; i < Math.min(3, boxes.size()); i++) {
            int[] b = boxes.get(i);
            HitCandidate c = new HitCandidate();
            c.setRank(i);
            c.setBounds(Map.of("left", b[0], "top", b[1], "right", b[2], "bottom", b[3]));
            c.setLocatorType("xpath");
            c.setLocatorValue("//*[@bounds='[" + b[0] + "," + b[1] + "][" + b[2] + "," + b[3] + "]']");
            c.setSuggestedNameEn("picked_node_" + i);
            c.setSuggestedNameZh("点选控件" + (i + 1));
            c.setVisualHint("bounds " + b[0] + "," + b[1] + "-" + b[2] + "," + b[3]);
            out.add(c);
        }
        return out;
    }

    private void walk(Element el, List<HitNode> out, HitNode parent, int[] seq,
                      String parentPath, String parentXpath) {
        if (el == null) return;
        String path = UiHierarchyBuilder.pathOf(parentPath, el);
        String xpath = UiHierarchyBuilder.xpathOf(parentXpath, el);
        HitNode node = fromElement(el, parent);
        if (node != null) {
            node.nodePath = path;
            node.xpath = xpath;
        }
        if (node != null && node.right > node.left && node.bottom > node.top) {
            node.docOrder = seq[0]++;
            out.add(node);
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                walk((Element) n, out, node != null ? node : parent, seq, path, xpath);
            }
        }
    }

    private HitNode fromElement(Element el, HitNode parent) {
        String bounds = attr(el, "bounds");
        int left = 0, top = 0, right = 0, bottom = 0;
        boolean hasBounds = false;
        if (bounds != null && !bounds.isBlank()) {
            Matcher m = BOUNDS.matcher(bounds.trim());
            if (m.matches()) {
                left = Integer.parseInt(m.group(1));
                top = Integer.parseInt(m.group(2));
                right = Integer.parseInt(m.group(3));
                bottom = Integer.parseInt(m.group(4));
                hasBounds = true;
            }
        }
        // iOS XCUITest 常见 x/y/width/height
        if (!hasBounds) {
            String xs = attr(el, "x");
            String ys = attr(el, "y");
            String ws = attr(el, "width");
            String hs = attr(el, "height");
            if (xs != null && ys != null && ws != null && hs != null) {
                try {
                    left = (int) Double.parseDouble(xs);
                    top = (int) Double.parseDouble(ys);
                    right = left + (int) Double.parseDouble(ws);
                    bottom = top + (int) Double.parseDouble(hs);
                    hasBounds = true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!hasBounds) return null;

        HitNode n = new HitNode();
        n.parent = parent;
        n.left = left;
        n.top = top;
        n.right = right;
        n.bottom = bottom;
        n.className = firstNonBlank(attr(el, "class"), el.getTagName());
        n.resourceId = firstNonBlank(attr(el, "resource-id"), attr(el, "name"));
        n.text = firstNonBlank(attr(el, "text"), attr(el, "label"), attr(el, "value"));
        n.contentDesc = firstNonBlank(attr(el, "content-desc"), attr(el, "label"));
        n.clickable = truthy(attr(el, "clickable")) || truthy(attr(el, "accessible"))
                || (el.getTagName() != null && el.getTagName().contains("Button"));
        n.enabled = attr(el, "enabled") == null || truthy(attr(el, "enabled"));
        n.focused = truthy(attr(el, "focused"));
        // Android: displayed；iOS XCUITest: visible
        String displayed = attr(el, "displayed");
        String visible = attr(el, "visible");
        if (displayed != null) {
            n.displayed = truthy(displayed);
        } else if (visible != null) {
            n.displayed = truthy(visible);
        } else {
            n.displayed = true;
        }
        Map<String, String> all = new HashMap<>();
        NamedNodeMap attrs = el.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                all.put(a.getNodeName(), a.getNodeValue());
            }
        }
        n.attrs = all;
        return n;
    }

    private HitCandidate toCandidate(HitNode n, List<HitNode> containing, boolean primary) {
        List<HitCandidate> all = expandLocatorStrategies(n, containing, -1, -1);
        if (all.isEmpty()) {
            HitCandidate empty = baseCandidate(n, null);
            empty.setLocatorType("xpath");
            empty.setLocatorValue("//*[@bounds='[" + n.left + "," + n.top + "][" + n.right + "," + n.bottom + "]']");
            empty.setSuggestedNameZh("点选控件");
            empty.setSuggestedNameEn("picked_" + n.left + "_" + n.top);
            return empty;
        }
        HitCandidate c = all.get(0);
        if (primary) c.setRank(0);
        return c;
    }

    /**
     * 同一命中控件展开多种定位方案，供用户自选：id / text / xpath / className / accessibilityId 等。
     */
    private List<HitCandidate> expandLocatorStrategies(HitNode n, List<HitNode> labelPool, int px, int py) {
        List<HitCandidate> list = new ArrayList<>();
        if (n == null) return list;
        // WebView 大壳：不要用壳上的 App 标题冒充点中的帖子
        if (isWebViewShell(n)) {
            return list;
        }

        // 列表项（如多个「进行中」）：裸 id 会命中整列，优先给出 [1]/卡片祖先
        prependListItemStrategies(list, n, labelPool, px, py);

        String ownLabel = firstNonBlank(n.text, n.contentDesc);
        if (isNoiseLabel(ownLabel) || isAppChromeLabel(ownLabel)) ownLabel = null;
        String nestedLabel = findNestedLabel(n, labelPool, px, py);
        if (isAppChromeLabel(nestedLabel)) nestedLabel = null;
        // 父壳套子文案且语义冲突时丢掉（tv_certify + 退出登录 盖住「登录注册」）
        if (ownLabel != null && nestedLabel != null && labelsConflict(ownLabel, nestedLabel)) {
            nestedLabel = null;
        }
        if (ownLabel == null && nestedLabel != null && px >= 0 && py >= 0) {
            // 嵌套文案必须盖住点击，否则易串台
            HitNode nestedNode = findNodeWithLabel(labelPool, nestedLabel);
            if (nestedNode != null
                    && !(nestedNode.left <= px && px < nestedNode.right
                    && nestedNode.top <= py && py < nestedNode.bottom)) {
                nestedLabel = null;
            }
        }
        String nearBottom = findStripLabelNear(TAB_STRIPS.get(0), n, labelPool, px);
        String nearMine = findStripLabelNear(TAB_STRIPS.get(1), n, labelPool, px);
        // 命名优先用控件自身文案
        String displayName = firstNonBlank(ownLabel, nestedLabel, nearMine, nearBottom);
        boolean unstableId = isUnstableResourceId(n.resourceId) || isBottomTabSharedId(n.resourceId);
        boolean hasId = n.resourceId != null && !n.resourceId.isBlank() && !"null".equalsIgnoreCase(n.resourceId);

        // H5 评论筛选条：无障碍把多 Tab 挤成一串 text → 按点击 X 拆成单段
        String mergedSrc = firstNonBlank(ownLabel, nestedLabel, displayName);
        String splitFilter = pickMergedH5FilterLabel(mergedSrc, n, px);
        boolean h5FilterSplit = splitFilter != null;
        if (h5FilterSplit) {
            HitNode web = findAncestorByShortId(n, "pwX5WebView");
            String webId = web != null && web.resourceId != null
                    ? web.resourceId.trim()
                    : "com.pwrd.steam.esports:id/pwX5WebView";
            Map<String, Integer> segBounds = segmentBoundsForMergedFilter(n, mergedSrc, splitFilter, px);
            HitCandidate split = baseCandidate(n, splitFilter);
            split.setBounds(segBounds);
            split.setLocatorType("xpath");
            split.setLocatorValue("//*[@resource-id=" + xpathLiteral(webId)
                    + "]//*[contains(@text," + xpathLiteral(splitFilter) + ")]");
            split.setSuggestedNameZh(splitFilter);
            split.setSuggestedNameEn(mergedFilterNameEn(splitFilter));
            split.setVisualHint("H5合并拆分 · " + splitFilter + " · 红框为估算段；执行点击可用坐标更准");
            split.setText(splitFilter);
            list.add(split);

            // 段中心坐标：真正点「最新」时比点整条栏更稳
            int sl = segBounds.getOrDefault("left", n.left);
            int st = segBounds.getOrDefault("top", n.top);
            int sr = segBounds.getOrDefault("right", n.right);
            int sb = segBounds.getOrDefault("bottom", n.bottom);
            int cx = (sl + sr) / 2;
            int cy = (st + sb) / 2;
            HitCandidate segCoord = baseCandidate(n, splitFilter);
            segCoord.setBounds(segBounds);
            segCoord.setLocatorType("coordinate");
            segCoord.setLocatorValue(cx + "," + cy);
            segCoord.setSuggestedNameZh(splitFilter);
            segCoord.setSuggestedNameEn(mergedFilterNameEn(splitFilter) + "_tap");
            segCoord.setVisualHint("段中心坐标 (" + cx + "," + cy + ") · 精确点「" + splitFilter + "」");
            list.add(segCoord);

            // 表单展示用单段名；勿再推 exact @text=单段或整串（树里实际是整串）
            displayName = splitFilter;
            ownLabel = null;
            if (nestedLabel != null && pickMergedH5FilterLabel(nestedLabel, n, px) != null) {
                nestedLabel = null;
            }
        }

        // 已知 Tab 条：优先带容器范围的 xpath
        for (TabStripDef strip : TAB_STRIPS) {
            HitNode bar = findAncestorByShortId(n, strip.containerShortId);
            if (bar == null && labelPool != null) {
                for (HitNode o : labelPool) {
                    if (strip.containerShortId.equalsIgnoreCase(shortResourceId(o.resourceId))) {
                        if (n.left < o.right && n.right > o.left && n.top < o.bottom && n.bottom > o.top) {
                            bar = o;
                        }
                        break;
                    }
                }
            }
            if (bar == null || bar.resourceId == null) continue;
            String tabLabel = firstNonBlank(
                    (ownLabel != null && strip.labels.contains(ownLabel.trim())) ? ownLabel : null,
                    (nestedLabel != null && strip.labels.contains(nestedLabel.trim())) ? nestedLabel : null,
                    findStripLabelNear(strip, n, labelPool, px));
            if (tabLabel == null) continue;
            HitCandidate scoped = baseCandidate(n, tabLabel);
            scoped.setLocatorType("xpath");
            if (strip.clickableFrameAncestor) {
                scoped.setLocatorValue("//*[@resource-id=" + xpathLiteral(bar.resourceId.trim())
                        + "]//*[@text=" + xpathLiteral(tabLabel)
                        + "]/ancestor::android.widget.FrameLayout[@clickable=\"true\"][1]");
            } else {
                scoped.setLocatorValue("//*[@resource-id=" + xpathLiteral(bar.resourceId.trim())
                        + "]//*[@text=" + xpathLiteral(tabLabel) + "]");
            }
            applySuggestedNames(scoped, tabLabel, n);
            scoped.setVisualHint(strip.hintTag + " · " + tabLabel);
            list.add(scoped);
            break;
        }

        if (hasId && !unstableId) {
            HitCandidate c = baseCandidate(n, displayName);
            c.setLocatorType("id");
            c.setLocatorValue(n.resourceId.trim());
            applySuggestedNames(c, displayName, n);
            if (isListRowBadgeId(shortResourceId(n.resourceId))) {
                c.setVisualHint("id(列表复用，慎用) · 多条「进行中」会点到不确定项；请用上方 [1]/卡片 xpath");
                if (looksLikeOngoingBadge(shortResourceId(n.resourceId), n)) {
                    c.setSuggestedNameZh("进行中(裸id慎用)");
                    c.setSuggestedNameEn("activity_ongoing_badge_raw");
                }
            } else {
                c.setVisualHint(strategyHint("id", n, displayName));
            }
            list.add(c);
        }

        if (ownLabel != null && ownLabel.length() <= 40) {
            // 已知 Tab 文案不推裸 text（二级页也可能有「赛事/动态」）
            if (!isKnownStripTabLabel(ownLabel)) {
                HitCandidate c = baseCandidate(n, ownLabel);
                c.setLocatorType("text");
                c.setLocatorValue(ownLabel);
                applySuggestedNames(c, ownLabel, n);
                c.setVisualHint(strategyHint("text", n, ownLabel));
                list.add(c);

                HitCandidate xp = baseCandidate(n, ownLabel);
                xp.setLocatorType("xpath");
                xp.setLocatorValue("//*[@text=" + xpathLiteral(ownLabel) + "]");
                applySuggestedNames(xp, ownLabel, n);
                xp.setVisualHint(strategyHint("xpath@text", n, ownLabel));
                list.add(xp);
            }

            if (n.clickable || findClickableAncestor(n) != null) {
                HitCandidate xpClick = baseCandidate(n, ownLabel);
                xpClick.setLocatorType("xpath");
                xpClick.setLocatorValue("//*[@text=" + xpathLiteral(ownLabel)
                        + "]/ancestor-or-self::*[@clickable=\"true\"][1]");
                applySuggestedNames(xpClick, ownLabel, n);
                xpClick.setVisualHint(strategyHint("xpath@text+clickable", n, ownLabel));
                list.add(xpClick);
            }
        } else if (nestedLabel != null && nestedLabel.length() <= 40 && (!hasId || unstableId || nearMine != null || nearBottom != null)) {
            // 可点父节点无自身文案时，用子文案生成 text/xpath（底栏/复用 id 也走这里）
            if (!isKnownStripTabLabel(nestedLabel)) {
                HitCandidate xp = baseCandidate(n, nestedLabel);
                xp.setLocatorType("xpath");
                xp.setLocatorValue("//*[@text=" + xpathLiteral(nestedLabel)
                        + "]/ancestor-or-self::*[@clickable=\"true\"][1]");
                applySuggestedNames(xp, nestedLabel, n);
                xp.setVisualHint(strategyHint("xpath@子文案", n, nestedLabel));
                list.add(xp);
            }
        }

        if (n.contentDesc != null && !n.contentDesc.isBlank() && !"null".equalsIgnoreCase(n.contentDesc)) {
            HitCandidate c = baseCandidate(n, n.contentDesc);
            c.setLocatorType("accessibilityId");
            c.setLocatorValue(n.contentDesc.trim());
            applySuggestedNames(c, n.contentDesc, n);
            c.setVisualHint(strategyHint("accessibilityId", n, n.contentDesc));
            list.add(c);
        }

        if (n.className != null && !n.className.isBlank() && !isUselessClassNameLocator(n.className)) {
            HitCandidate c = baseCandidate(n, displayName);
            c.setLocatorType("className");
            c.setLocatorValue(n.className.trim());
            applySuggestedNames(c, displayName, n);
            c.setVisualHint(strategyHint("className", n, n.className));
            list.add(c);
        }

        if (hasId && unstableId) {
            HitCandidate c = baseCandidate(n, displayName);
            c.setLocatorType("id");
            c.setLocatorValue(n.resourceId.trim());
            applySuggestedNames(c, displayName, n);
            c.setVisualHint("id(页面复用，慎用) · " + shortResourceId(n.resourceId));
            list.add(c);
            if (ownLabel != null) {
                HitCandidate xp = baseCandidate(n, ownLabel);
                xp.setLocatorType("xpath");
                xp.setLocatorValue("//*[@resource-id=" + xpathLiteral(n.resourceId.trim())
                        + " and @text=" + xpathLiteral(ownLabel) + "]");
                applySuggestedNames(xp, ownLabel, n);
                xp.setVisualHint(strategyHint("xpath@id+text", n, ownLabel));
                list.add(xp);
            }
        }

        // 已拆 H5 筛选段时不要再推整条栏 bounds（红框会罩满）
        if (!h5FilterSplit) {
            HitCandidate bounds = baseCandidate(n, displayName);
            bounds.setLocatorType("xpath");
            String cls = n.className != null ? n.className : "*";
            bounds.setLocatorValue("//" + cls + "[@bounds='[" + n.left + "," + n.top + "][" + n.right + "," + n.bottom + "]']");
            applySuggestedNames(bounds, displayName, n);
            bounds.setVisualHint(strategyHint("xpath@bounds", n, displayName));
            list.add(bounds);
        }

        return list;
    }

    /**
     * 列表重复控件：如活动列表多个「进行中」/活动卡。
     * 点第 N 条时默认给出第 N 条；另附首条模板供回归复用。
     */
    private void prependListItemStrategies(List<HitCandidate> list, HitNode n, List<HitNode> labelPool, int px, int py) {
        if (n == null || labelPool == null) return;

        HitNode card = findAncestorByShortId(n, "cv_activity_card");
        if (card == null && "cv_activity_card".equalsIgnoreCase(shortResourceId(n.resourceId))) {
            card = n;
        }
        HitNode badge = n;
        String badgeSid = shortResourceId(n.resourceId);
        if (!isListRowBadgeId(badgeSid) || "cv_activity_card".equalsIgnoreCase(badgeSid)) {
            // 点在卡片内容上：用卡内「进行中」角标做序号
            HitNode found = null;
            if (card != null) {
                for (HitNode o : labelPool) {
                    if (!o.displayed || !isTreeDescendantOrSelf(o, card)) continue;
                    if ("iv_activity_state".equalsIgnoreCase(shortResourceId(o.resourceId))
                            || "tv_activity_state".equalsIgnoreCase(shortResourceId(o.resourceId))) {
                        found = o;
                        break;
                    }
                }
            }
            if (found != null) badge = found;
        }
        if (badge.resourceId == null || badge.resourceId.isBlank()) return;
        String rid = badge.resourceId.trim();
        String sid = shortResourceId(rid);
        if (sid == null || (!isListRowBadgeId(sid) && card == null)) return;

        List<HitNode> sameBadges = new ArrayList<>();
        for (HitNode o : labelPool) {
            if (!o.displayed) continue;
            if (rid.equals(o.resourceId) || sid.equalsIgnoreCase(shortResourceId(o.resourceId))) {
                sameBadges.add(o);
            }
        }
        sameBadges.sort(Comparator.comparingInt((HitNode o) -> o.top).thenComparingInt(o -> o.left).thenComparingInt(o -> o.docOrder));
        if (sameBadges.size() < 2 && card == null && !isListRowBadgeId(sid)) return;

        int index = 1;
        for (int i = 0; i < sameBadges.size(); i++) {
            HitNode o = sameBadges.get(i);
            if (o == badge || (o.left == badge.left && o.top == badge.top && o.right == badge.right && o.bottom == badge.bottom)) {
                index = i + 1;
                break;
            }
            if (card != null && isTreeDescendantOrSelf(o, card)) {
                index = i + 1;
                break;
            }
        }
        // 若点的是卡片：按卡片纵向顺序算第几张
        if (card != null && card.resourceId != null) {
            List<HitNode> cards = new ArrayList<>();
            String cardRid = card.resourceId.trim();
            for (HitNode o : labelPool) {
                if (!o.displayed) continue;
                if (cardRid.equals(o.resourceId)
                        || "cv_activity_card".equalsIgnoreCase(shortResourceId(o.resourceId))) {
                    cards.add(o);
                }
            }
            cards.sort(Comparator.comparingInt((HitNode o) -> o.top).thenComparingInt(o -> o.left));
            for (int i = 0; i < cards.size(); i++) {
                if (cards.get(i) == card
                        || (cards.get(i).left == card.left && cards.get(i).top == card.top)) {
                    index = i + 1;
                    break;
                }
            }
        }

        String label = "进行中";
        String cardRid = card != null && card.resourceId != null ? card.resourceId.trim() : null;

        // —— 当前点中的第 N 条（默认首选）——
        List<HitCandidate> primary = new ArrayList<>();
        List<HitCandidate> firstTemplates = new ArrayList<>();

        if (cardRid != null) {
            HitCandidate nthCard = baseCandidate(card != null ? card : n, label);
            nthCard.setLocatorType("xpath");
            nthCard.setLocatorValue("(//*[@resource-id=" + xpathLiteral(cardRid) + "])[" + index + "]");
            nthCard.setSuggestedNameZh(index == 1 ? "进行中活动卡(首条)" : ("活动卡(第" + index + "条)"));
            nthCard.setSuggestedNameEn(index == 1 ? "activity_card_ongoing_first" : ("activity_card_" + index));
            nthCard.setVisualHint(index == 1
                    ? "当前点中 · 第1张活动卡（整卡点击）"
                    : "当前点中 · 第" + index + "张活动卡（你点的是这条，不是首条）");
            if (card != null) {
                nthCard.setBounds(Map.of("left", card.left, "top", card.top, "right", card.right, "bottom", card.bottom));
            }
            primary.add(nthCard);

            HitCandidate via = baseCandidate(card != null ? card : n, label);
            via.setLocatorType("xpath");
            via.setLocatorValue("(//*[@resource-id=" + xpathLiteral(rid) + "])[" + index
                    + "]/ancestor::*[@resource-id=" + xpathLiteral(cardRid) + "][1]");
            via.setSuggestedNameZh(index == 1 ? "进行中活动卡(首条)" : ("活动卡(第" + index + "条)"));
            via.setSuggestedNameEn(index == 1 ? "activity_card_via_ongoing_first" : ("activity_card_via_" + index));
            via.setVisualHint("由第" + index + "个「进行中」角标定位到所属卡片");
            primary.add(via);

            if (index != 1) {
                HitCandidate firstCard = baseCandidate(card, label);
                firstCard.setLocatorType("xpath");
                firstCard.setLocatorValue("(//*[@resource-id=" + xpathLiteral(cardRid) + "])[1]");
                firstCard.setSuggestedNameZh("进行中活动卡(首条·备用)");
                firstCard.setSuggestedNameEn("activity_card_ongoing_first");
                firstCard.setVisualHint("备用 · 回归常用「永远点列表第1张」；本次你点的是第" + index + "张");
                if (!sameBadges.isEmpty()) {
                    // 用首卡 bounds 若能找到
                    HitNode firstBadgeNode = sameBadges.get(0);
                    HitNode firstCardNode = findAncestorByShortId(firstBadgeNode, "cv_activity_card");
                    if (firstCardNode != null) {
                        firstCard.setBounds(Map.of("left", firstCardNode.left, "top", firstCardNode.top,
                                "right", firstCardNode.right, "bottom", firstCardNode.bottom));
                    }
                }
                firstTemplates.add(firstCard);
            }
        } else {
            HitCandidate nth = baseCandidate(badge, label);
            nth.setLocatorType("xpath");
            nth.setLocatorValue("(//*[@resource-id=" + xpathLiteral(rid) + "])[" + index + "]");
            nth.setSuggestedNameZh(index == 1 ? (label + "(首条)") : (label + "(第" + index + "条)"));
            nth.setSuggestedNameEn(listItemNameEn(sid, label, index == 1, false) + (index == 1 ? "" : "_" + index));
            nth.setVisualHint("当前点中 · 第" + index + "个「" + label + "」");
            nth.setBounds(Map.of("left", badge.left, "top", badge.top, "right", badge.right, "bottom", badge.bottom));
            primary.add(nth);

            HitCandidate clickable = baseCandidate(badge, label);
            clickable.setLocatorType("xpath");
            clickable.setLocatorValue("(//*[@resource-id=" + xpathLiteral(rid) + "])[" + index
                    + "]/ancestor-or-self::*[@clickable=\"true\"][1]");
            clickable.setSuggestedNameZh(label + "可点区(第" + index + "条)");
            clickable.setSuggestedNameEn(listItemNameEn(sid, label, index == 1, true) + (index == 1 ? "" : "_" + index));
            clickable.setVisualHint("角标上溯可点父节点 · 第" + index + "条");
            primary.add(clickable);

            if (index != 1 && !sameBadges.isEmpty()) {
                HitCandidate first = baseCandidate(sameBadges.get(0), label);
                first.setLocatorType("xpath");
                first.setLocatorValue("(//*[@resource-id=" + xpathLiteral(rid) + "])[1]");
                first.setSuggestedNameZh(label + "(首条·备用)");
                first.setSuggestedNameEn(listItemNameEn(sid, label, true, false));
                first.setVisualHint("备用 · 列表第1条；本次点的是第" + index + "条");
                firstTemplates.add(first);
            }
        }

        // 角标 [N] 对照
        HitCandidate badgeN = baseCandidate(badge, label);
        badgeN.setLocatorType("xpath");
        badgeN.setLocatorValue("(//*[@resource-id=" + xpathLiteral(rid) + "])[" + index + "]");
        badgeN.setSuggestedNameZh(index == 1 ? (label + "(首条角标)") : (label + "(第" + index + "条角标)"));
        badgeN.setSuggestedNameEn(listItemNameEn(sid, label, index == 1, false) + (index == 1 ? "" : "_" + index));
        badgeN.setVisualHint("第" + index + "个角标本身（点卡请用上方卡片方案）");
        badgeN.setBounds(Map.of("left", badge.left, "top", badge.top, "right", badge.right, "bottom", badge.bottom));
        primary.add(badgeN);

        for (HitCandidate c : primary) list.add(c);
        for (HitCandidate c : firstTemplates) list.add(c);
    }

    private static boolean isListRowBadgeId(String shortId) {
        if (shortId == null) return false;
        String s = shortId.toLowerCase(Locale.ROOT);
        return "iv_activity_state".equals(s)
                || "tv_activity_state".equals(s)
                || "cv_activity_card".equals(s)
                || s.endsWith("_state") && s.contains("activity");
    }

    private static boolean looksLikeOngoingBadge(String shortId, HitNode n) {
        if ("进行中".equals(firstNonBlank(n.text, n.contentDesc))) return true;
        return isListRowBadgeId(shortId);
    }

    private static String listItemNameEn(String shortId, String label, boolean first, boolean clickable) {
        String base = "进行中".equals(label) || (shortId != null && shortId.contains("activity"))
                ? "activity_ongoing"
                : toSnake(label != null ? label : "list_item");
        if (clickable) base = base + "_clickable";
        if (first) base = base + "_first";
        return base;
    }

    private static HitCandidate baseCandidate(HitNode n, String label) {
        HitCandidate c = new HitCandidate();
        c.setClassName(n.className);
        c.setText(n.text);
        c.setContentDesc(n.contentDesc);
        c.setResourceId(n.resourceId);
        c.setClickable(n.clickable);
        c.setEnabled(n.enabled);
        c.setBounds(Map.of("left", n.left, "top", n.top, "right", n.right, "bottom", n.bottom));
        c.setNodePath(n.nodePath);
        return c;
    }

    private static boolean labelsConflict(String a, String b) {
        if (a == null || b == null) return false;
        boolean aLogin = a.contains("登录") && !a.contains("退出");
        boolean bLogout = b.contains("退出登录") || "退出".equals(b.trim());
        boolean bLogin = b.contains("登录") && !b.contains("退出");
        boolean aLogout = a.contains("退出登录") || "退出".equals(a.trim());
        return (aLogin && bLogout) || (bLogin && aLogout);
    }

    private static HitNode findNodeWithLabel(List<HitNode> pool, String label) {
        if (pool == null || label == null) return null;
        for (HitNode o : pool) {
            if (label.equals(firstNonBlank(o.text, o.contentDesc))) return o;
        }
        return null;
    }

    private void applySuggestedNames(HitCandidate c, String label, HitNode n) {
        if (label != null && !label.isBlank()) {
            c.setSuggestedNameZh(label.length() <= 20 ? label : label.substring(0, 20));
            c.setSuggestedNameEn(toSnake(label));
            String trimmed = label.trim();
            // 别名只认「节点自身文案」，避免父 id + 嵌套「退出登录」误命名
            String own = firstNonBlank(n != null ? n.text : null, n != null ? n.contentDesc : null);
            boolean ownExact = own != null && own.trim().equals(trimmed);
            if ("设置".equals(trimmed) && ownExact) {
                c.setSuggestedNameEn("side_drawer_settings");
                c.setSuggestedNameZh("侧栏设置");
            } else if ("客服中心".equals(trimmed) && ownExact) {
                c.setSuggestedNameEn("side_drawer_customer_service");
                c.setSuggestedNameZh("侧栏客服中心");
            } else if ("退出登录".equals(trimmed) && ownExact) {
                c.setSuggestedNameEn("logout_confirm");
                c.setSuggestedNameZh("退出登录确认");
            } else if ("取消".equals(trimmed) && ownExact) {
                c.setSuggestedNameEn("logout_cancel");
                c.setSuggestedNameZh("取消退出");
            } else if ("发现".equals(trimmed)) {
                c.setSuggestedNameEn("home_tab_discover");
                c.setSuggestedNameZh("底栏发现");
            } else if ("赛事".equals(trimmed)) {
                c.setSuggestedNameEn("home_tab_match");
                c.setSuggestedNameZh("底栏赛事");
            } else if ("社区".equals(trimmed)) {
                c.setSuggestedNameEn("home_tab_community");
                c.setSuggestedNameZh("底栏社区");
            } else if ("数据".equals(trimmed)) {
                c.setSuggestedNameEn("home_tab_data");
                c.setSuggestedNameZh("底栏数据");
            } else if ("我的".equals(trimmed)) {
                c.setSuggestedNameEn("home_tab_mine");
                c.setSuggestedNameZh("底栏我的");
            } else if ("动态".equals(trimmed)) {
                c.setSuggestedNameEn("mine_tab_feed");
                c.setSuggestedNameZh("我的-动态");
            } else if ("评论".equals(trimmed)) {
                c.setSuggestedNameEn("mine_tab_comment");
                c.setSuggestedNameZh("我的-评论");
            } else if ("梗词条".equals(trimmed)) {
                c.setSuggestedNameEn("mine_tab_meme");
                c.setSuggestedNameZh("我的-梗词条");
            } else if ("完美时刻".equals(trimmed)) {
                c.setSuggestedNameEn("mine_tab_moment");
                c.setSuggestedNameZh("我的-完美时刻");
            } else if ("留言".equals(trimmed)) {
                c.setSuggestedNameEn("mine_tab_guestbook");
                c.setSuggestedNameZh("我的-留言");
            }
        } else {
            String shortId = shortResourceId(n.resourceId);
            if (shortId != null) {
                c.setSuggestedNameEn(toSnake(shortId));
                c.setSuggestedNameZh(suggestZh(n, shortId));
            } else {
                c.setSuggestedNameEn("picked_" + n.left + "_" + n.top);
                c.setSuggestedNameZh("点选控件");
            }
        }
    }

    private static String strategyHint(String strategy, HitNode n, String label) {
        StringBuilder sb = new StringBuilder(strategy);
        if (label != null && !label.isBlank()) sb.append(" · ").append(label);
        else if (shortResourceId(n.resourceId) != null) sb.append(" · ").append(shortResourceId(n.resourceId));
        return sb.toString();
    }

    /** 取容器内文案子节点；优先覆盖点击坐标的节点，避免 WebView 大壳乱配远处文案 */
    private static String findNestedLabel(HitNode container, List<HitNode> pool, int px, int py) {
        if (container == null || pool == null) return null;
        HitNode best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean hasClick = px >= 0 && py >= 0;
        boolean hugeContainer = container.area() > 80_000 || isWebViewShell(container);
        for (HitNode o : pool) {
            if (o == container) continue;
            String t = firstNonBlank(o.text, o.contentDesc);
            if (t == null || t.isBlank() || t.length() > 40 || isNoiseLabel(t) || isAppChromeLabel(t)) continue;
            if (!isInsideOrSame(o, container)) continue;
            int score = 0;
            boolean covers = hasClick && o.left <= px && px < o.right && o.top <= py && py < o.bottom;
            if (covers) {
                score += 200;
            } else if (hasClick) {
                // 大壳/WebView：远处顶栏文案一律不要
                if (hugeContainer) continue;
                int ocx = (o.left + o.right) / 2;
                int ocy = (o.top + o.bottom) / 2;
                int dist = Math.abs(ocx - px) + Math.abs(ocy - py);
                score += Math.max(0, 80 - dist / 20);
            }
            if (isKnownStripTabLabel(t)) score += 120;
            score += Math.min(30, t.trim().length() * 3);
            int area = o.area();
            if (area > 0 && area < 50_000) score += 5;
            if (area > 200_000) score -= 20;
            if (best == null || score > bestScore || (score == bestScore && area < best.area())) {
                best = o;
                bestScore = score;
            }
        }
        return best != null ? firstNonBlank(best.text, best.contentDesc) : null;
    }

    /** 全页复用、不能单独当定位的 id（忽略下划线/大小写，覆盖 ivImage / tvText 等） */
    private static boolean isUnstableResourceId(String resourceId) {
        String n = normalizeIdKey(shortResourceId(resourceId));
        if (n == null) return true;
        return "tvtoolmame".equals(n)
                || "tvtitle".equals(n)
                || "tvtext".equals(n)
                || "ivimage".equals(n)
                || "title".equals(n)
                || "titlecontainer".equals(n)
                || "srlrefresh".equals(n)
                || isBottomTabSharedId(resourceId);
    }

    private static String buildVisualHint(HitNode n) {
        StringBuilder sb = new StringBuilder();
        if (n.top < 200 && n.left < 200) sb.append("左上角");
        else if (n.top < 200) sb.append("顶部");
        else if (n.left < 120) sb.append("左侧");
        else sb.append("区域");
        if (n.contentDesc != null && !n.contentDesc.isBlank() && !"null".equalsIgnoreCase(n.contentDesc)) {
            sb.append(' ').append(n.contentDesc);
        } else if (n.text != null && !n.text.isBlank()) {
            sb.append(' ').append(n.text);
        } else if (shortResourceId(n.resourceId) != null) {
            sb.append(' ').append(shortResourceId(n.resourceId));
        }
        return sb.toString().trim();
    }

    private static String suggestZh(HitNode n, String shortId) {
        if (shortId != null) {
            String s = shortId.toLowerCase(Locale.ROOT);
            if (s.contains("side") || s.contains("menu") || s.contains("drawer") || s.contains("slide")) {
                return "侧边栏入口";
            }
        }
        if (n.contentDesc != null && !n.contentDesc.isBlank()) return n.contentDesc;
        if (n.text != null && !n.text.isBlank()) return n.text;
        return shortId != null ? shortId : "点选控件";
    }

    private static String shortResourceId(String resourceId) {
        if (resourceId == null || resourceId.isBlank() || "null".equalsIgnoreCase(resourceId)) return null;
        int slash = resourceId.lastIndexOf('/');
        if (slash >= 0 && slash < resourceId.length() - 1) return resourceId.substring(slash + 1);
        return resourceId;
    }

    private static String toSnake(String raw) {
        if (raw == null || raw.isBlank()) return "picked_node";
        String s = raw.trim().replaceAll("[^a-zA-Z0-9]+", "_");
        s = s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        s = s.replaceAll("_+", "_");
        if (s.startsWith("_")) s = s.substring(1);
        if (s.length() > 48) s = s.substring(0, 48);
        // 中文等非 ASCII 被剥光时，给可入库的英文名（中文名仍用原文）
        if (s.isBlank()) {
            s = "el_" + Integer.toHexString(raw.trim().hashCode() & 0xffff);
        }
        return s;
    }

    private static int hitRankScore(HitNode n, int screenArea) {
        int s = 0;
        if (isGenericContainer(n)) return -100;
        if (isGenericToolIcon(n)) s -= 15;
        // 输入框 / 焦点：评论弹层点选应压过背后列表
        if (isEditText(n)) s += 55;
        if (n.focused) s += 40;
        // 点在「发布」上时，动作文案优先于大面积 focused 输入框
        if (isComposerActionLabel(firstNonBlank(n.text, n.contentDesc))) s += 70;
        if (looksLikeCommentHint(firstNonBlank(n.text, n.contentDesc))) s += 35;
        String sidEarly = shortResourceId(n.resourceId);
        if ("hsv_game_info".equalsIgnoreCase(sidEarly)) s -= 50;
        int area = n.area();
        if (area > 0 && screenArea > 0) {
            double ratio = (double) area / (double) screenArea;
            if (ratio < 0.005) s += 50;
            else if (ratio < 0.015) s += 40;
            else if (ratio < 0.04) s += 28;
            else if (ratio < 0.1) s += 14;
            else if (ratio < 0.25) s += 4;
            else s -= 8;
            if (ratio > 0.45) s -= 25;
        }
        String label = firstNonBlank(n.text, n.contentDesc);
        if (label != null && !label.isBlank() && label.length() <= 40) {
            s += 10;
            if (label.length() <= 10) s += 3;
            if ("同意".equals(label) || "不同意".equals(label) || "取消".equals(label) || "确定".equals(label)
                    || "发布".equals(label)) {
                s += 20;
            }
        }
        String sid = shortResourceId(n.resourceId);
        if ("tv_agree".equalsIgnoreCase(sid) || "tv_not_agree".equalsIgnoreCase(sid)) {
            s += 25;
        }
        // 首页侧栏入口：面积小且易被顶栏大壳 / 活动标题盖住，显式加权
        if ("sideView".equalsIgnoreCase(sid) || "vg_slide_entrance".equalsIgnoreCase(sid)) {
            s += 55;
        }
        if ("layoutTitleBar".equalsIgnoreCase(sid) || "vg_title_bar".equalsIgnoreCase(sid)) {
            s -= 45;
        }
        // 底栏 / 我的内容 Tab 文案：避免被大容器 / 红点「·」盖住
        if (isKnownStripTabLabel(label)) {
            s += 28;
        }
        if (isNoiseLabel(label)) {
            s -= 40;
        }
        if (isBottomTabSharedId(n.resourceId)) {
            s -= 20;
        }
        if ("title_container".equalsIgnoreCase(sid) && findAncestorByShortId(n, "bottomTabLayout") != null) {
            s -= 30;
        }
        if ("srl_refresh".equalsIgnoreCase(sid)) {
            s -= 40;
        }
        if (n.clickable) s += 8;
        if (n.enabled) s += 1;
        if (n.resourceId != null && !n.resourceId.isBlank() && !"null".equalsIgnoreCase(n.resourceId)
                && !isUnstableResourceId(n.resourceId) && !isGenericToolIcon(n)) {
            s += 6;
        }
        return s;
    }

    private static boolean truthy(String v) {
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private static String attr(Element el, String name) {
        if (!el.hasAttribute(name)) return null;
        String v = el.getAttribute(name);
        return v == null || v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank() && !"null".equalsIgnoreCase(v)) return v;
        }
        return null;
    }

    private static String xpathLiteral(String text) {
        if (!text.contains("'")) return "'" + text + "'";
        if (!text.contains("\"")) return "\"" + text + "\"";
        return "concat('" + text.replace("'", "',\"'\",'") + "')";
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static class HitNode {
        HitNode parent;
        /** 树内唯一路径，前端点选/回选用 */
        String nodePath;
        /** Appium 风格绝对 xpath，容器类节点的兜底 locator */
        String xpath;
        int left, top, right, bottom;
        String className;
        String resourceId;
        String text;
        String contentDesc;
        boolean clickable;
        boolean enabled;
        boolean focused;
        boolean displayed = true;
        int docOrder;
        Map<String, String> attrs;

        int area() {
            return Math.max(0, right - left) * Math.max(0, bottom - top);
        }
    }

    // ==================== Inspector 式控件树：整棵交给前端，选哪个节点是人的事 ====================

    /**
     * 返回当前屏的完整控件树，供前端做「截图 + 树」双栏：所有节点边框叠加、hover 高亮、点树选中。
     *
     * @param refresh true 强制重新 dump；false 优先用 snapshot 那一刻冻结的树（与截图严格对齐）
     */
    public HierarchyResult hierarchy(String deviceId, boolean refresh) {
        Device device = requireConnected(deviceId);
        String id = deviceId.trim();
        int[] shotSize = resolveShotSize(deviceId, device);
        if (shotSize == null || shotSize[0] <= 0 || shotSize[1] <= 0) {
            throw new IllegalStateException("无法读取截图/屏幕尺寸，请先调用 snapshot");
        }
        String xml = currentPageSource(deviceId, device, refresh);
        int[] hierSize = detectHierarchySize(xml, shotSize);
        double[] scale = hierToShotScale(device.getPlatform(), shotSize, hierSize);

        HierarchyResult r = new HierarchyResult();
        r.setDeviceId(deviceId);
        r.setPlatform(device.getPlatform() != null ? device.getPlatform().name() : null);
        r.setScreenshotUrl("devices/" + id + "/inspect/screenshot?t=" + System.currentTimeMillis());
        r.setWidth(shotSize[0]);
        r.setHeight(shotSize[1]);
        if (hierSize != null && hierSize[0] > 0 && hierSize[1] > 0) {
            r.setHierarchyWidth(hierSize[0]);
            r.setHierarchyHeight(hierSize[1]);
        }
        r.setScaleX(scale[0]);
        r.setScaleY(scale[1]);
        r.setRefreshed(refresh);
        try {
            org.w3c.dom.Document doc = UiHierarchyBuilder.parseDocument(xml);
            UiHierarchyBuilder.HierarchyNode root = UiHierarchyBuilder.build(doc, scale[0], scale[1]);
            r.setRoot(root);
            r.setNodeCount(UiHierarchyBuilder.countNodes(root));
        } catch (Exception e) {
            throw new IllegalStateException("控件树解析失败: " + e.getMessage(), e);
        }
        log.info("控件树: deviceId={} 节点数={} 截图={}x{} 控件树={}x{} scale=({},{})",
                id, r.getNodeCount(), shotSize[0], shotSize[1],
                r.getHierarchyWidth(), r.getHierarchyHeight(), scale[0], scale[1]);
        return r;
    }

    /**
     * 按 nodePath 取某个节点的全部候选 locator（含唯一性校验）。
     * 前端在树上点节点、或点「往上跳一级」后调这个接口，拿到的候选与 hit-test 同构，可直接落库。
     */
    public NodeLocatorsResult nodeLocators(String deviceId, String nodePath, String appId) {
        if (nodePath == null || nodePath.isBlank()) {
            throw new IllegalArgumentException("nodePath 不能为空");
        }
        Device device = requireConnected(deviceId);
        String platform = device.getPlatform() != null ? device.getPlatform().name() : null;
        int[] shotSize = resolveShotSize(deviceId, device);
        String xml = currentPageSource(deviceId, device, false);
        org.w3c.dom.Document doc;
        try {
            doc = UiHierarchyBuilder.parseDocument(xml);
        } catch (Exception e) {
            throw new IllegalStateException("控件树解析失败: " + e.getMessage(), e);
        }
        List<HitNode> nodes = new ArrayList<>();
        walk(doc.getDocumentElement(), nodes, null, new int[]{0}, null, null);
        HitNode target = null;
        for (HitNode n : nodes) {
            if (nodePath.equals(n.nodePath)) {
                target = n;
                break;
            }
        }
        if (target == null) {
            // hierarchy 返回的是完整 XML 树，而命中用的 HitNode 只收录「有 bounds 且非零尺寸」的节点：
            // 根节点、零尺寸节点在树上点得到却查不到 HitNode。这时报「页面已变」是误导，走降级分支。
            return boundlessNodeLocators(deviceId, platform, device, doc, xml, nodePath, shotSize, appId);
        }

        List<HitCandidate> list = new ArrayList<>(expandLocatorStrategies(target, nodes, -1, -1));
        // 容器类节点常常无 id 无文案：绝对 xpath 兜底，保证任何节点都拿得到一个能用的 locator
        if (target.xpath != null && !target.xpath.isBlank()) {
            boolean dup = false;
            for (HitCandidate c : list) {
                if (target.xpath.equals(c.getLocatorValue())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                HitCandidate abs = baseCandidate(target, firstNonBlank(target.text, target.contentDesc));
                abs.setLocatorType("xpath");
                abs.setLocatorValue(target.xpath);
                abs.setVisualHint("绝对 xpath · 位置强相关，页面结构一变就失效");
                if (abs.getSuggestedNameEn() == null || abs.getSuggestedNameEn().isBlank()) {
                    abs.setSuggestedNameEn("node_" + target.left + "_" + target.top);
                }
                if (abs.getSuggestedNameZh() == null || abs.getSuggestedNameZh().isBlank()) {
                    abs.setSuggestedNameZh("点选控件");
                }
                list.add(abs);
            }
        }
        int rank = 0;
        for (HitCandidate c : list) {
            c.setRank(rank++);
            if (c.getNodePath() == null) c.setNodePath(target.nodePath);
        }
        enrichMatchedElements(list, appId, platform);
        enrichMatchCounts(doc, list);

        double[] scale = hierToShotScale(device.getPlatform(), shotSize, detectHierarchySize(xml, shotSize));
        NodeLocatorsResult r = new NodeLocatorsResult();
        r.setDeviceId(deviceId);
        r.setPlatform(platform);
        r.setNodePath(target.nodePath);
        r.setXpath(target.xpath);
        r.setClassName(target.className);
        r.setResourceId(target.resourceId);
        r.setText(target.text);
        r.setContentDesc(target.contentDesc);
        r.setClickable(target.clickable);
        r.setEnabled(target.enabled);
        r.setBounds(Map.of("left", target.left, "top", target.top, "right", target.right, "bottom", target.bottom));
        r.setScreenBounds(Map.of(
                "left", (int) Math.round(target.left * scale[0]),
                "top", (int) Math.round(target.top * scale[1]),
                "right", (int) Math.round(target.right * scale[0]),
                "bottom", (int) Math.round(target.bottom * scale[1])));
        r.setAttrs(target.attrs);
        r.setAncestors(ancestorChain(nodes, target));
        r.setCandidates(list);
        return r;
    }

    /**
     * 无 bounds / 零尺寸节点（根节点、布局占位）的降级定位：拿不到 HitNode，就直接按 XML 属性出候选。
     * 只给绝对 xpath 和属性型 locator，不做任何「哪个更好」的判断——够前端把节点信息铺开就行。
     */
    private NodeLocatorsResult boundlessNodeLocators(String deviceId, String platform, Device device,
                                                     org.w3c.dom.Document doc, String xml, String nodePath,
                                                     int[] shotSize, String appId) {
        double[] scale = hierToShotScale(device.getPlatform(), shotSize, detectHierarchySize(xml, shotSize));
        UiHierarchyBuilder.HierarchyNode root = UiHierarchyBuilder.build(doc, scale[0], scale[1]);
        UiHierarchyBuilder.HierarchyNode node = UiHierarchyBuilder.findByPath(root, nodePath);
        if (node == null) {
            throw new IllegalArgumentException("nodePath 不在当前控件树上（页面可能已变），请重新刷新截图: " + nodePath);
        }

        List<HitCandidate> list = new ArrayList<>();
        addAttrCandidate(list, node, "xpath", node.getXpath(), "绝对 xpath · 位置强相关，页面结构一变就失效");
        addAttrCandidate(list, node, "id", node.getResourceId(), "resource-id");
        addAttrCandidate(list, node, "text", node.getText(), "text");
        addAttrCandidate(list, node, "accessibilityId", node.getContentDesc(), "content-desc / accessibility id");
        addAttrCandidate(list, node, "className", node.getClassName(), "class · 通常不唯一");
        int rank = 0;
        for (HitCandidate c : list) {
            c.setRank(rank++);
            c.setNodePath(node.getNodePath());
        }
        enrichMatchedElements(list, appId, platform);
        enrichMatchCounts(doc, list);

        NodeLocatorsResult r = new NodeLocatorsResult();
        r.setDeviceId(deviceId);
        r.setPlatform(platform);
        r.setNodePath(node.getNodePath());
        r.setXpath(node.getXpath());
        r.setClassName(node.getClassName());
        r.setResourceId(node.getResourceId());
        r.setText(node.getText());
        r.setContentDesc(node.getContentDesc());
        r.setClickable(node.getClickable());
        r.setEnabled(node.getEnabled());
        r.setBounds(node.getBounds());
        r.setScreenBounds(node.getScreenBounds());
        r.setAttrs(node.getAttrs());
        r.setAncestors(ancestorRefsByPath(root, node.getNodePath()));
        r.setCandidates(list);
        log.info("控件树降级取值(无 bounds 节点): deviceId={} nodePath={} 候选={}", deviceId, nodePath, list.size());
        return r;
    }

    private static void addAttrCandidate(List<HitCandidate> out, UiHierarchyBuilder.HierarchyNode n,
                                         String type, String value, String hint) {
        if (value == null || value.isBlank()) return;
        HitCandidate c = new HitCandidate();
        c.setClassName(n.getClassName());
        c.setResourceId(n.getResourceId());
        c.setText(n.getText());
        c.setContentDesc(n.getContentDesc());
        c.setClickable(n.getClickable());
        c.setEnabled(n.getEnabled());
        c.setBounds(n.getBounds());
        c.setLocatorType(type);
        c.setLocatorValue(value);
        c.setVisualHint(hint);
        c.setSuggestedNameZh("点选控件");
        c.setSuggestedNameEn("node_" + n.getNodePath().replaceAll("[^A-Za-z0-9]+", "_"));
        out.add(c);
    }

    /** nodePath 是 /a/b[2]/c[1] 这种绝对路径，逐段截断即可拿到由近及远的祖先 */
    private static List<NodeRef> ancestorRefsByPath(UiHierarchyBuilder.HierarchyNode root, String nodePath) {
        List<NodeRef> chain = new ArrayList<>();
        if (root == null || nodePath == null) return chain;
        String p = nodePath;
        while (true) {
            int cut = p.lastIndexOf('/');
            if (cut <= 0) break;
            p = p.substring(0, cut);
            UiHierarchyBuilder.HierarchyNode a = UiHierarchyBuilder.findByPath(root, p);
            if (a == null) break;
            NodeRef r = new NodeRef();
            r.setNodePath(a.getNodePath());
            r.setXpath(a.getXpath());
            r.setClassName(a.getClassName());
            r.setResourceId(a.getResourceId());
            r.setText(a.getText());
            r.setContentDesc(a.getContentDesc());
            r.setClickable(a.getClickable());
            r.setBounds(a.getBounds());
            chain.add(r);
        }
        return chain;
    }

    /** 截图像素尺寸：优先上次 snapshot 记录，其次读 PNG，最后问设备 */
    private int[] resolveShotSize(String deviceId, Device device) {
        String id = deviceId.trim();
        int[] shotSize = lastScreenshotSizeByDevice.get(id);
        if (shotSize == null) {
            Path shot = lastScreenshotByDevice.get(id);
            if (shot == null) shot = findLatestInspectFile(id);
            if (shot != null) shotSize = readPngSize(shot);
        }
        if (shotSize == null) {
            shotSize = resolveWindowSize(deviceId, device);
        }
        return shotSize;
    }

    /** 取当前可用的 page source（冻结优先），并裁掉 XML 前后杂字符 */
    private String currentPageSource(String deviceId, Device device, boolean refresh) {
        String id = deviceId.trim();
        String xml = refresh ? null : lastPageSourceByDevice.get(id);
        if (!usableHierarchy(xml)) {
            xml = fetchPageSource(deviceId, device);
            if (usableHierarchy(xml)) {
                lastPageSourceByDevice.put(id, xml);
            }
        }
        if (!usableHierarchy(xml)) {
            throw new IllegalStateException(
                    "无法获取控件树：请先点「刷新截图」。"
                            + (Device.usesHdcAutomation(device.getPlatform())
                            ? "鸿蒙请确认 hdc 已授权且 hmdriver2 可用。"
                            : "Android 请确认 adb 可用；iOS 请确认 WDA 已启动（设置里信任开发者证书）"));
        }
        int start = xml.indexOf('<');
        if (start > 0) xml = xml.substring(start);
        int end = xml.lastIndexOf('>');
        if (end > 0 && end < xml.length() - 1) xml = xml.substring(0, end + 1);
        return xml;
    }

    /**
     * 控件树坐标 → 截图像素坐标的比例。规则与 hitTest 的反向映射严格一致，
     * 否则前端画的框会和点选结果对不上。
     */
    private static double[] hierToShotScale(Device.Platform platform, int[] shotSize, int[] hierSize) {
        if (shotSize == null || shotSize[0] <= 1 || shotSize[1] <= 1) return new double[]{1.0, 1.0};
        if (hierSize == null || hierSize[0] <= 1 || hierSize[1] <= 1) return new double[]{1.0, 1.0};
        if (hierSize[0] == shotSize[0] && hierSize[1] == shotSize[1]) return new double[]{1.0, 1.0};
        boolean sameWidth = Math.abs(hierSize[0] - shotSize[0]) <= 2;
        int heightGap = Math.abs(shotSize[1] - hierSize[1]);
        boolean statusBarLikeGap = sameWidth && heightGap > 0 && heightGap <= Math.max(320, shotSize[1] / 8);
        if (statusBarLikeGap && Device.isAndroidOrPogAndroid(platform)) {
            // Android 截图与 dump 顶部对齐、同宽，底部多出手势条：不缩放
            return new double[]{1.0, 1.0};
        }
        return new double[]{
                (shotSize[0] - 1.0) / (hierSize[0] - 1.0),
                (shotSize[1] - 1.0) / (hierSize[1] - 1.0)};
    }

    /** 候选 locator 关联元素库已有记录 */
    private void enrichMatchedElements(List<HitCandidate> hits, String appId, String platform) {
        if (hits == null) return;
        for (HitCandidate c : hits) {
            if (c.getLocatorValue() == null || c.getLocatorValue().isBlank()) continue;
            if (c.getMatchedElementId() != null) continue; // 库内 ACTIVE 已绑定
            List<UiElement> matched = uiElementService.findByLocatorHint(appId, platform, c.getLocatorValue(), 5);
            if (!matched.isEmpty()) {
                UiElement m = matched.get(0);
                c.setMatchedElementId(m.getId());
                c.setMatchedNameEn(m.getNameEn());
                c.setMatchedNameZh(m.getNameZh());
                c.setMatchedStatus(m.getStatus());
            }
        }
    }

    /** 每个候选在当前树上跑一次匹配：「这个 xpath 命中 3 个」比任何推荐理由都直接 */
    private static void enrichMatchCounts(org.w3c.dom.Document doc, List<HitCandidate> hits) {
        if (doc == null || hits == null) return;
        for (HitCandidate c : hits) {
            int n = LocatorMatcher.count(doc, c.getLocatorType(), c.getLocatorValue());
            c.setMatchCount(n);
            c.setUnique(n < 0 ? null : (n == 1));
        }
    }

    private static String firstNodePath(List<HitCandidate> hits) {
        if (hits == null) return null;
        for (HitCandidate c : hits) {
            if (c.getNodePath() != null && !c.getNodePath().isBlank()) return c.getNodePath();
        }
        return null;
    }

    private List<NodeRef> ancestorChain(org.w3c.dom.Document doc, String nodePath) {
        if (doc == null || nodePath == null || nodePath.isBlank()) return List.of();
        List<HitNode> nodes = new ArrayList<>();
        walk(doc.getDocumentElement(), nodes, null, new int[]{0}, null, null);
        HitNode target = null;
        for (HitNode n : nodes) {
            if (nodePath.equals(n.nodePath)) {
                target = n;
                break;
            }
        }
        return ancestorChain(nodes, target);
    }

    /** 由近及远的祖先链 */
    private static List<NodeRef> ancestorChain(List<HitNode> nodes, HitNode target) {
        if (target == null) return List.of();
        List<NodeRef> chain = new ArrayList<>();
        for (HitNode p = target.parent; p != null; p = p.parent) {
            chain.add(toRef(p));
        }
        return chain;
    }

    private static NodeRef toRef(HitNode n) {
        NodeRef r = new NodeRef();
        r.setNodePath(n.nodePath);
        r.setXpath(n.xpath);
        r.setClassName(n.className);
        r.setResourceId(n.resourceId);
        r.setText(n.text);
        r.setContentDesc(n.contentDesc);
        r.setClickable(n.clickable);
        r.setBounds(Map.of("left", n.left, "top", n.top, "right", n.right, "bottom", n.bottom));
        return r;
    }

    @Data
    public static class SnapshotResult {
        private String deviceId;
        private String platform;
        private String screenshotUrl;
        private String screenshotPath;
        private Integer width;
        private Integer height;
    }

    @Data
    public static class HitTestResult {
        private String deviceId;
        private String platform;
        private int x;
        private int y;
        private int width;
        private int height;
        /** 控件树自身尺寸，前端换算用 */
        private Integer hierarchyWidth;
        private Integer hierarchyHeight;
        /** 首选候选对应的树节点，前端据此在树上高亮同一个节点 */
        private String selectedNodePath;
        /** 由近及远的祖先链，支持「选错了往上跳一级」 */
        private List<NodeRef> ancestors;
        private List<HitCandidate> candidates;
    }

    /** 树上一个节点的轻量引用，够前端展示和再取 locator */
    @Data
    public static class NodeRef {
        private String nodePath;
        private String xpath;
        private String className;
        private String resourceId;
        private String text;
        private String contentDesc;
        private Boolean clickable;
        private Map<String, Integer> bounds;
    }

    @Data
    public static class HierarchyResult {
        private String deviceId;
        private String platform;
        private String screenshotUrl;
        /** 截图像素尺寸，screenBounds 就在这个坐标系里 */
        private Integer width;
        private Integer height;
        private Integer hierarchyWidth;
        private Integer hierarchyHeight;
        private Double scaleX;
        private Double scaleY;
        private Integer nodeCount;
        private Boolean refreshed;
        private UiHierarchyBuilder.HierarchyNode root;
    }

    @Data
    public static class NodeLocatorsResult {
        private String deviceId;
        private String platform;
        private String nodePath;
        private String xpath;
        private String className;
        private String resourceId;
        private String text;
        private String contentDesc;
        private Boolean clickable;
        private Boolean enabled;
        private Map<String, Integer> bounds;
        private Map<String, Integer> screenBounds;
        private Map<String, String> attrs;
        private List<NodeRef> ancestors;
        private List<HitCandidate> candidates;
    }

    @Data
    public static class HitCandidate {
        private int rank;
        private String className;
        private String resourceId;
        private String text;
        private String contentDesc;
        private Boolean clickable;
        private Boolean enabled;
        private Map<String, Integer> bounds;
        private String locatorType;
        private String locatorValue;
        private String suggestedNameEn;
        private String suggestedNameZh;
        private String visualHint;
        private String matchedElementId;
        private String matchedNameEn;
        private String matchedNameZh;
        private String matchedStatus;
        /** 该候选对应的树节点路径，坐标方案等无节点来源时为 null */
        private String nodePath;
        /** 该 locator 在当前控件树上命中几个元素；-1 表示无法校验（如坐标方案） */
        private Integer matchCount;
        /** matchCount == 1；无法校验时为 null */
        private Boolean unique;
    }
}

