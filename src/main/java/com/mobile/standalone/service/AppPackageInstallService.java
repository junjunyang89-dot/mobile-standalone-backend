package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.constant.AppPackageConstants;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.OnlineRegressionJob;
import com.mobile.standalone.entity.UiApp;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 包体卸载 / 多方式安装（蒲公英 / 渠道页 / 本地 APK / 扫码辅助）。
 * <p>
 * Android 先落地；iOS 渠道/扫码后续接。CHANNEL_PAGE 按厂商打开商店并文案点击安装。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppPackageInstallService {

    public static final String APP_DOMESTIC = "DOMESTIC_ESPORTS";
    public static final String APP_POG = "OVERSEAS_POGPOG";

    private static final String PKG_ESPORTS_ANDROID = "com.pwrd.steam.esports";
    private static final String PKG_ESPORTS_IOS = "com.wmzq.esportapp";
    private static final String PKG_POG_ANDROID = AppPackageConstants.POGPOG_INTL_PACKAGE;
    private static final String PKG_POG_IOS = "com.arca.egamesintl";

    private static final String SEARCH_ESPORTS = "完美世界电竞";
    private static final String SEARCH_POG = "PogPog";
    /** 国内电竞 App Store 官方下载页（完美世界电竞） */
    private static final String APPSTORE_URL_ESPORTS =
            "https://apps.apple.com/cn/app/%E5%AE%8C%E7%BE%8E%E4%B8%96%E7%95%8C%E7%94%B5%E7%AB%9E/id1460786904";
    private static final String APPSTORE_ID_POG = "6747575699";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DeviceManager deviceManager;
    private final PlatformRouter platformRouter;
    private final AutomationEngine automationEngine;
    private final UiAppService uiAppService;
    private final Environment environment;
    private final ApplicationService applicationService;

    @Autowired
    @Lazy
    private PgyerAppService pgyerAppService;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    @Value("${app.upload-dir:${user.home}/.mobile-standalone/uploads/applications}")
    private String uploadDir;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${app.install.store-wait-seconds:180}")
    private int storeWaitSeconds;

    @Value("${app.install.store-poll-seconds:5}")
    private int storePollSeconds;

    /** 点「获取/安装」后先冷静等待，再开始轮询是否已装上 */
    /**
     * 系统「安装」确认框上按钮的文案，逗号分隔，按顺序试。
     * <p>
     * 原来写死成 "安装" / "Install" 两个字面量。手机系统语言换成英文、繁中，
     * 或者 iOS 版本把文案改成「安装 App」，都要改代码重新部署——
     * 而这种事每年至少发生一次，且现象是「装不上但也没报错」，很难联想到是文案变了。
     */
    @Value("${app.install.ios-install-button-labels:安装,Install,安裝,インストール}")
    private String iosInstallButtonLabels;

    /**
     * 点不到「安装」时最多再试几轮。
     * <p>
     * 每试一次都要 dump 一次 iOS 控件树（1~3 秒），60 秒轮询会试 20 次，
     * 光这一项就能把轮询拖垮。而点不到通常是弹窗压根没出来（证书没信任、页面没加载），
     * 再试一百次也一样——这跟 WDA 无限重装是同一类问题。
     */
    @Value("${app.install.ios-install-click-attempts:5}")
    private int iosInstallClickAttempts;

    @Value("${app.install.store-grace-seconds:25}")
    private int storeGraceSeconds;

    /** Android 商店「安装」按钮固定坐标（本机校准：369,1483），只点一次 */
    @Value("${app.install.android-tap-x:369}")
    private int androidTapX;

    @Value("${app.install.android-tap-y:1483}")
    private int androidTapY;

    /** iOS App Store「获取」固定坐标（点）；0 表示不用固定坐标，改用元素查找 / 比率点击 */
    @Value("${app.install.ios-tap-x:0}")
    private int iosTapX;

    @Value("${app.install.ios-tap-y:0}")
    private int iosTapY;

    /** Android 启动后等待再截图（毫秒） */
    @Value("${app.install.android-launch-settle-ms:2500}")
    private long androidLaunchSettleMs;

    /** iOS：启动后等弹窗出现再点（毫秒）；安装后启动几乎必现 */
    @Value("${app.install.ios-alert-appear-ms:1500}")
    private long iosAlertAppearMs;

    /**
     * iOS 无线数据弹窗「无线局域网与蜂窝网络」坐标。
     * Inspector 若给出控件左上角 x（如 40），请把 {@code ios-alert-tap-x} 设为 0 使用屏幕水平居中，
     * 或直接填按钮中心 x；y 用 Inspector 的中心/校准值（如 485）。
     */
    @Value("${app.install.ios-alert-tap-x:0}")
    private int iosAlertTapX;

    @Value("${app.install.ios-alert-tap-y:485}")
    private int iosAlertTapY;

    /** iOS：点完弹窗后再等一会截图（毫秒） */
    @Value("${app.install.ios-after-alert-settle-ms:2500}")
    private long iosAfterAlertSettleMs;

    /**
     * 优先应用字典 {@code ui_apps}；字典缺失时回退内置电竞/Pog 常量（兼容旧库）。
     */
    public String resolvePackage(String appId, Device device) {
        boolean android = Device.isAndroidOrPogAndroid(device.getPlatform());
        boolean ios = Device.usesIosAutomation(device.getPlatform());
        if (appId != null && !appId.isBlank()) {
            UiApp app = uiAppService.get(appId.trim());
            if (app != null) {
                String pkg = ios ? app.getIosBundleId() : app.getAndroidPackage();
                if (pkg != null && !pkg.isBlank()) {
                    return pkg.trim();
                }
            }
        }
        if (APP_POG.equals(appId)) {
            if (android) return PKG_POG_ANDROID;
            if (ios) return PKG_POG_IOS;
        }
        if (APP_DOMESTIC.equals(appId)) {
            if (android) return PKG_ESPORTS_ANDROID;
            if (ios) return PKG_ESPORTS_IOS;
        }
        throw new IllegalArgumentException(
                "无法解析包名: appId=" + appId + ", platform=" + device.getPlatform()
                        + "（请在应用字典配置 Android 包名 / iOS BundleId）");
    }

    /** 应用字典里配的蒲公英 appKey，按设备平台取 */
    private String resolvePgyerAppKey(String appId, Device device) {
        if (appId == null || appId.isBlank()) {
            return null;
        }
        UiApp app = uiAppService.get(appId.trim());
        if (app == null) {
            return null;
        }
        return Device.usesIosAutomation(device.getPlatform())
                ? blankToNullStr(app.getPgyerAppKeyIos())
                : blankToNullStr(app.getPgyerAppKeyAndroid());
    }

    /**
     * 升级测试要装的<b>上一版</b>的 buildKey。
     * <p>
     * 必须显式取出来传给安装：不传 buildKey 走的是「装最新版」，
     * 那就成了拿新版覆盖新版——一步升级路径都没走到，还照样是绿的。
     *
     * @return null＝取不到上一版（只有一个版本，或没配 appKey）
     */
    public String resolvePreviousBuildKey(String appId, Device device) {
        String appKey = resolvePgyerAppKey(appId, device);
        if (appKey == null) {
            return null;
        }
        PgyerAppService.PgyerBuildVo prev = pgyerAppService.previousBuild(
                appKey,
                Device.usesIosAutomation(device.getPlatform()) ? "IOS" : "ANDROID",
                resolvePgyerHost(appId, device));
        return prev == null ? null : blankToNullStr(prev.getBuildKey());
    }

    /** 该应用该平台的分发域名；没配则返回 null，由 PgyerAppService 退回全局默认 */
    private String resolvePgyerHost(String appId, Device device) {
        if (appId == null || appId.isBlank()) {
            return null;
        }
        UiApp app = uiAppService.get(appId.trim());
        if (app == null) {
            return null;
        }
        return Device.usesIosAutomation(device.getPlatform())
                ? blankToNullStr(app.getPgyerHostIos())
                : blankToNullStr(app.getPgyerHostAndroid());
    }

    private static String blankToNullStr(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String resolveDisplayName(String appId) {
        if (appId != null && !appId.isBlank()) {
            UiApp app = uiAppService.get(appId.trim());
            if (app != null && app.getNameZh() != null && !app.getNameZh().isBlank()) {
                return app.getNameZh().trim();
            }
        }
        if (APP_POG.equals(appId)) return SEARCH_POG;
        if (APP_DOMESTIC.equals(appId)) return SEARCH_ESPORTS;
        return appId != null ? appId : "应用";
    }

    /**
     * 先判断是否已安装：未安装则跳过；已安装才卸载。
     * {@code packageNameOverride} 非空时优先生效，否则按 appId + 设备平台解析。
     */
    public OpResult uninstallApp(String appId, String packageNameOverride, Device device) {
        String pkg = packageNameOverride != null && !packageNameOverride.isBlank()
                ? packageNameOverride.trim()
                : resolvePackage(appId, device);
        log.info("卸载检查: device={}, appId={}, pkg={}", device.getId(), appId, pkg);
        if (!isInstalled(device, pkg)) {
            log.info("包未安装，跳过卸载: {}", pkg);
            return OpResult.ok("未安装，跳过卸载: " + pkg);
        }
        boolean ok;
        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            ok = deviceManager.uninstallAndroidApp(device.getId(), pkg);
        } else if (Device.usesIosAutomation(device.getPlatform())) {
            ok = deviceManager.uninstallIOSApp(device.getId(), pkg);
        } else {
            return OpResult.fail("不支持的平台: " + device.getPlatform());
        }
        if (!ok) {
            return OpResult.fail("卸载失败: " + pkg);
        }
        for (int i = 0; i < 10 && isInstalled(device, pkg); i++) {
            sleep(500);
        }
        if (isInstalled(device, pkg)) {
            return OpResult.fail("卸载后仍检测到包: " + pkg);
        }
        return OpResult.ok("已卸载: " + pkg);
    }

    /** @deprecated 请用 {@link #uninstallApp(String, String, Device)} */
    public OpResult uninstallForOnlineRegression(OnlineRegressionJob job, Device device) {
        return uninstallApp(job.getAppId(), null, device);
    }

    /**
     * 多方式安装入口。默认 {@link InstallMode#CHANNEL_PAGE}。
     */
    public OpResult install(String appId, Device device, InstallRequest request) {
        if (appId == null || appId.isBlank()) {
            return OpResult.fail("安装缺少 appId");
        }
        if (device == null) {
            return OpResult.fail("安装缺少设备");
        }
        InstallRequest req = request != null ? request : InstallRequest.builder().build();
        InstallMode mode = req.getInstallMode() != null ? req.getInstallMode() : InstallMode.CHANNEL_PAGE;
        log.info("多方式安装: appId={}, device={}, mode={}", appId, device.getId(), mode);

        if (!Device.isAndroidOrPogAndroid(device.getPlatform()) && !Device.usesIosAutomation(device.getPlatform())) {
            return OpResult.fail("不支持的平台: " + device.getPlatform());
        }
        // 原来这里拦掉了 iOS 上除 CHANNEL_PAGE 外的所有模式。那道拦截现在可以去掉了：
        // installLocalPackage 早就会把 iOS 路由到 ideviceinstaller / devicectl，
        // 蒲公英那边也是 API 下包再从 USB 装，不用扫码、不用 Safari。
        //
        // 留下的唯一真限制是 ad-hoc 签名：包只能装进描述文件里列了 UDID 的那几台。
        // 这个拦不住也不该拦——装的时候会明确报 ApplicationVerificationFailed，
        // 比事先一句「暂不支持」有用得多，后者会让人以为是平台没做。

        return switch (mode) {
            case PGYER -> installViaPgyer(appId, device, req);
            case LOCAL_APK -> installViaLocalApk(appId, device, req);
            case QR_ASSIST -> installViaQrAssist(appId, device, req);
            case CHANNEL_PAGE -> installViaChannelPage(appId, device, req);
        };
    }

    /** 兼容旧调用：等同 CHANNEL_PAGE */
    public OpResult installFromStore(String appId, Device device) {
        return install(appId, device, InstallRequest.builder().installMode(InstallMode.CHANNEL_PAGE).build());
    }

    private OpResult installViaPgyer(String appId, Device device, InstallRequest req) {
        try {
            String pkg = resolvePackage(appId, device);
            if (req.isUninstallFirst() && isInstalled(device, pkg)) {
                OpResult u = uninstallApp(appId, pkg, device);
                if (!u.success) {
                    return OpResult.fail("蒲公英安装前卸载失败: " + u.message);
                }
            }

            // iOS：蒲公英 install 接口会 302 到 itms-services，电脑跟不了、下到的是 0 字节空文件。
            // 官方要求「在 iOS/Android 系统中打开」——真机 Safari 打开安装页，再轮询是否装上。
            if (isIosDevice(device)) {
                return installPgyerOnIosDevice(appId, device, req, pkg);
            }

            if (req.getPgyerBuildKey() != null && !req.getPgyerBuildKey().isBlank()) {
                PgyerAppService.InstallResult r = pgyerAppService.downloadAndInstall(
                        device.getId(), req.getPgyerBuildKey().trim(), false);
                if (!r.isSuccess()) {
                    return OpResult.fail("PGYER 安装失败: " + r.getMessage());
                }
                return OpResult.ok("PGYER 安装成功: " + pkg + " | buildKey=" + req.getPgyerBuildKey()
                        + " | path=" + r.getLocalPath());
            }
            if (req.getPgyerUrl() != null && !req.getPgyerUrl().isBlank()) {
                Path file = downloadPackageUrl(req.getPgyerUrl().trim(), "pgyer_url");
                String failure = installLocalPackageDetailed(device, file);
                if (failure != null) {
                    return OpResult.fail("PGYER URL 包已下载但安装失败：" + failure + " | file=" + file);
                }
                return OpResult.ok("PGYER URL 安装成功: " + pkg + " | url=" + req.getPgyerUrl());
            }
            // 都没填就按「应用字典 + 平台」取最新版：buildKey 每次发版都变，
            // 写死在流程里等于每次发版前先改一遍流程，那正是自动化该消掉的人工
            String appKey = firstNonBlank(req.getPgyerAppKey(), resolvePgyerAppKey(appId, device));
            if (appKey != null) {
                PgyerAppService.PgyerBuildVo latest =
                        pgyerAppService.latestBuild(appKey,
                                device.getPlatform() == null ? null : device.getPlatform().name(),
                                resolvePgyerHost(appId, device));
                PgyerAppService.InstallResult r = pgyerAppService.downloadAndInstall(
                        device.getId(), latest.getBuildKey(), false);
                if (!r.isSuccess()) {
                    return OpResult.fail("PGYER 最新版安装失败: " + r.getMessage()
                            + " | " + latest.getBuildVersion() + "(" + latest.getBuildBuildVersion() + ")");
                }
                return OpResult.ok("PGYER 最新版安装成功: " + pkg
                        + " | " + latest.getBuildVersion() + "(build " + latest.getBuildBuildVersion() + ")"
                        + " | appKey=" + appKey);
            }
            return OpResult.fail("PGYER 模式需要 pgyerBuildKey / pgyerUrl，"
                    + "或在应用字典里为 " + appId + " 配置蒲公英 appKey（配了就自动装最新版）");
        } catch (Exception e) {
            log.warn("蒲公英安装异常: {}", e.getMessage());
            return OpResult.fail("PGYER 安装异常: " + e.getMessage());
        }
    }

    /**
     * iOS 蒲公英安装：在真机 Safari 打开官方 install URL（设备侧才能跟 itms-services），
     * 然后轮询 Bundle ID 是否出现。
     */
    private OpResult installPgyerOnIosDevice(String appId, Device device, InstallRequest req, String pkg) {
        String buildKey = firstNonBlank(req.getPgyerBuildKey(), null);
        String versionHint = "";
        if (buildKey == null) {
            String appKey = firstNonBlank(req.getPgyerAppKey(), resolvePgyerAppKey(appId, device));
            if (appKey == null) {
                return OpResult.fail("iOS 蒲公英安装需要 pgyerBuildKey，或在应用字典配置 iOS 蒲公英 Key");
            }
            PgyerAppService.PgyerBuildVo latest = pgyerAppService.latestBuild(
                    appKey, "IOS", resolvePgyerHost(appId, device));
            if (latest == null || latest.getBuildKey() == null || latest.getBuildKey().isBlank()) {
                return OpResult.fail("未找到可安装的 iOS 蒲公英版本 | appKey=" + appKey);
            }
            buildKey = latest.getBuildKey();
            versionHint = latest.getBuildVersion() + "(build " + latest.getBuildBuildVersion() + ")";
        }
        String installUrl = pgyerAppService.buildInstallPageUrl(buildKey);
        log.info("iOS 蒲公英改为真机打开安装页: device={}, buildKey={}, url={}",
                device.getId(), buildKey, installUrl.replaceAll("_api_key=[^&]+", "_api_key=***"));

        // 必须先开安装页。WDA 重编 / activate Safari 都会抢前台，导致「Safari 不动、弹窗出不来」。
        boolean opened = deviceManager.openIosHttpsUrl(device.getId(), installUrl);
        if (!opened && appiumDriverAdapter != null) {
            opened = appiumDriverAdapter.openUrlInSafari(device.getId(), installUrl);
        }
        if (!opened) {
            String loc = pgyerAppService.peekInstallRedirect(buildKey);
            if (loc != null && loc.toLowerCase(Locale.ROOT).startsWith("itms-services:")) {
                opened = deviceManager.openIosHttpsUrl(device.getId(), loc)
                        || (appiumDriverAdapter != null
                        && appiumDriverAdapter.openDeepLink(device.getId(), loc, "com.apple.mobilesafari"));
            }
        }
        if (!opened) {
            return OpResult.fail("无法在 iPhone 上打开蒲公英安装页（devicectl/Safari）。"
                    + "可改用本地 IPA（LOCAL_APK），或手动装好后跳过安装步骤。"
                    + " buildKey=" + buildKey);
        }

        int waitSec = Math.max(60, storeWaitSeconds);
        int pollSec = Math.max(3, storePollSeconds);
        long deadline = System.currentTimeMillis() + waitSec * 1000L;
        try {
            Thread.sleep(Math.min(5_000L, storeGraceSeconds * 1000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // 轻量自动点：不重编 WDA、不 activateApp，失败则仍可人工点「安装」
        boolean uiReady = softPrepareIosPgyerClickUi(device);
        boolean clickedInstall = uiReady && tryClickIosPgyerInstallConfirm(device);
        int clickAttempts = 1;
        if (clickedInstall) {
            log.info("iOS 蒲公英：首轮已自动点击系统「安装」");
        } else {
            log.info("iOS 蒲公英：首轮未自动点到「安装」 uiReady={}（安装页已打开，可手动点；轮询中仍会轻量重试）",
                    uiReady);
        }
        // 点完「安装」后 Safari 会停在原页，需底部上滑回主屏才能看到桌面安装进度
        boolean wentHome = false;
        if (clickedInstall) {
            wentHome = goIosHomeAfterPgyerConfirm(device);
        }
        while (System.currentTimeMillis() < deadline) {
            if (isInstalled(device, pkg)) {
                return OpResult.ok("PGYER iOS 真机安装成功: " + pkg
                        + " | buildKey=" + buildKey
                        + (versionHint.isBlank() ? "" : " | " + versionHint)
                        + "（已打开安装页"
                        + (clickedInstall ? "，已点「安装」" : "")
                        + (wentHome ? "，已回主屏" : "")
                        + "）");
            }
            // 点不到就一直试是错的：每试一次要 dump 一次 iOS 控件树（1~3 秒），
            // 60 秒轮询会试 20 次，光这一项就把轮询拖垮。而点不到通常是弹窗压根没出来
            // （证书没信任、页面没加载完），再试一百次也一样。
            // 试满就停手，改为纯等待——安装页已经开着，人可以自己点，轮询照样能检测到装好
            if (!clickedInstall && clickAttempts < Math.max(1, iosInstallClickAttempts)) {
                clickAttempts++;
                if (!uiReady) {
                    uiReady = softPrepareIosPgyerClickUi(device);
                }
                if (uiReady && tryClickIosPgyerInstallConfirm(device)) {
                    clickedInstall = true;
                    log.info("iOS 蒲公英：轮询中点到「安装」（第 {} 次尝试），接着回主屏", clickAttempts);
                } else if (clickAttempts >= Math.max(1, iosInstallClickAttempts)) {
                    log.warn("iOS 蒲公英：试了 {} 次都没点到「安装」，停止自动点击，改为纯等待。"
                            + "多半是弹窗没出来——检查证书是否已信任、安装页是否加载完",
                            clickAttempts);
                }
            }
            if (clickedInstall && !wentHome) {
                wentHome = goIosHomeAfterPgyerConfirm(device);
            }
            try {
                Thread.sleep(pollSec * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return OpResult.fail("已在 iPhone Safari 打开蒲公英安装页，但 "
                + waitSec + "s 内未检测到 " + pkg
                + (clickedInstall
                        ? "（曾点过「安装」" + (wentHome ? "并已回主屏" : "") + "，请检查企业证书信任）"
                        : "（未能自动点到「安装」，请手动点确认后上滑回主屏）")
                + "。或改用本地 IPA。 buildKey=" + buildKey);
    }

    /**
     * 点完系统「安装」后离开 Safari：底部上滑 → Home 键 → 拉起 SpringBoard。
     */
    private boolean goIosHomeAfterPgyerConfirm(Device device) {
        if (device == null) {
            return false;
        }
        String deviceId = device.getId();
        boolean ok = false;
        try {
            if (appiumDriverAdapter != null) {
                ok = appiumDriverAdapter.goIosHomeScreen(deviceId);
            }
            // 无 WDA 会话时也能回桌面
            if (deviceManager.launchIosAppCli(deviceId, "com.apple.springboard")) {
                log.info("iOS 蒲公英：已拉起 SpringBoard 回主屏 deviceId={}", deviceId);
                ok = true;
            }
            sleep(800);
        } catch (Exception e) {
            log.warn("iOS 蒲公英回主屏失败: {}", e.getMessage());
        }
        return ok;
    }

    /**
     * 轻量挂接：只 ensure WDA，失败不重编；挂会话时不 activateApp，避免冲掉系统安装确认框。
     */
    private boolean softPrepareIosPgyerClickUi(Device device) {
        if (appiumDriverAdapter == null || device == null) {
            return false;
        }
        String deviceId = device.getId();
        try {
            deviceManager.clearIosWdaCooldown(deviceId);
            int wdaPort = appiumDriverAdapter.resolveWdaLocalPort(deviceId);
            if (!deviceManager.ensureIosWdaRunning(deviceId, wdaPort)) {
                log.warn("iOS 蒲公英自动点「安装」跳过：WDA 未就绪 port={} lastError={}",
                        wdaPort, deviceManager.getLastIosWdaError(deviceId));
                return false;
            }
            // 不 activate：安装确认框已在前台，再 activate Safari 会把弹窗冲掉
            if (appiumDriverAdapter.attachIosUiSession(deviceId, "com.apple.mobilesafari", true, false)) {
                return true;
            }
            log.warn("iOS 蒲公英自动点「安装」：挂接 Safari 会话失败（不激活前台）");
            return false;
        } catch (Exception e) {
            log.warn("iOS 蒲公英软准备 UI 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 自动点系统「安装」确认（「xxx 想要安装应用」）。
     */
    private boolean tryClickIosPgyerInstallConfirm(Device device) {
        if (appiumDriverAdapter == null || device == null) {
            return false;
        }
        String deviceId = device.getId();
        try {
            for (String label : installButtonLabels()) {
                if (appiumDriverAdapter.dismissIosSpringboardSystemAlert(deviceId, label, 0, 0)
                        || appiumDriverAdapter.dismissIosSystemAlertButton(deviceId, label)) {
                    log.info("iOS 蒲公英：已点系统「{}」", label);
                    return true;
                }
            }
            // 前面几种走的是 Appium 的告警接口，拿不到时才退到控件树——
            // dump 控件树在 iOS 上要 1~3 秒，能不做就不做
            PlatformDriver driver = platformRouter.getDriver(device);
            if (driver != null) {
                for (String label : installButtonLabels()) {
                    String sel = "xpath://XCUIElementTypeButton[@name='" + label
                            + "' or @label='" + label + "']";
                    if (driver.isElementPresent(deviceId, sel)) {
                        driver.clickElement(deviceId, sel);
                        log.info("iOS 蒲公英：xpath 点击「{}」成功", label);
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("iOS 蒲公英点「安装」本轮未成功: {}", e.getMessage());
            return false;
        }
    }

    /** 系统语言/版本一变文案就变，所以做成配置而不是字面量 */
    private List<String> installButtonLabels() {
        String raw = iosInstallButtonLabels == null || iosInstallButtonLabels.isBlank()
                ? "安装,Install" : iosInstallButtonLabels;
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static boolean isIosDevice(Device device) {
        if (device == null || device.getPlatform() == null) {
            return false;
        }
        String p = device.getPlatform().name();
        return p != null && p.toUpperCase(Locale.ROOT).contains("IOS");
    }

    private OpResult installViaLocalApk(String appId, Device device, InstallRequest req) {
        try {
            String pkg = resolvePackage(appId, device);
            Path file = resolveLocalApkPath(req);
            if (file == null || !Files.isRegularFile(file)) {
                return OpResult.fail("LOCAL_APK 未找到包文件：请配置 apkArtifactId（applications.id）或 apkPath");
            }
            if (req.isUninstallFirst() && isInstalled(device, pkg)) {
                OpResult u = uninstallApp(appId, pkg, device);
                if (!u.success) {
                    return OpResult.fail("本地安装前卸载失败: " + u.message);
                }
            }
            String failure = installLocalPackageDetailed(device, file);
            if (failure != null) {
                return OpResult.fail("LOCAL_APK 安装失败：" + failure + " | file=" + file);
            }
            return OpResult.ok("LOCAL_APK 安装成功: " + pkg + " | file=" + file);
        } catch (Exception e) {
            return OpResult.fail("LOCAL_APK 安装异常: " + e.getMessage());
        }
    }

    private Path resolveLocalApkPath(InstallRequest req) {
        if (req.getApkPath() != null && !req.getApkPath().isBlank()) {
            return Path.of(req.getApkPath().trim());
        }
        if (req.getApkArtifactId() != null && !req.getApkArtifactId().isBlank()) {
            Application app = applicationService.getApplicationById(req.getApkArtifactId().trim());
            if (app != null && app.getInstallPath() != null && !app.getInstallPath().isBlank()) {
                return Path.of(app.getInstallPath());
            }
        }
        return null;
    }

    private OpResult installViaQrAssist(String appId, Device device, InstallRequest req) {
        String pkg;
        try {
            pkg = resolvePackage(appId, device);
        } catch (Exception e) {
            return OpResult.fail(e.getMessage());
        }
        String qr = firstNonBlank(req.getQrPayload(), req.getPgyerUrl(), req.getChannelUrl());
        if (qr == null || qr.isBlank()) {
            return OpResult.fail("QR_ASSIST 需要 qrPayload（或 pgyerUrl/channelUrl）作为扫码安装页");
        }
        if (req.isUninstallFirst() && isInstalled(device, pkg)) {
            OpResult u = uninstallApp(appId, pkg, device);
            if (!u.success) {
                return OpResult.fail("扫码安装前卸载失败: " + u.message);
            }
        }
        log.info("QR_ASSIST 等待人工扫码安装: pkg={}, qr={}", pkg, qr);
        boolean installed = waitUntilInstalled(device, pkg, storeWaitSeconds, false);
        if (!installed) {
            return OpResult.fail("QR_ASSIST 超时：包名未出现 " + pkg + "。请用手机扫码安装后重试", null, qr);
        }
        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            deviceManager.pressAndroidHome(device.getId());
        }
        return OpResult.ok("QR_ASSIST 安装成功: " + pkg, null, qr);
    }

    private OpResult installViaChannelPage(String appId, Device device, InstallRequest req) {
        String pkg = resolvePackage(appId, device);
        log.info("渠道页安装: device={}, pkg={}, appId={}", device.getId(), pkg, appId);

        if (isInstalled(device, pkg)) {
            log.info("安装前发现仍已安装，先卸载: {}", pkg);
            OpResult uninstall = uninstallApp(appId, pkg, device);
            if (!uninstall.success) {
                return OpResult.fail("安装前卸载失败: " + uninstall.message);
            }
        }

        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            String preferredUrl = firstNonBlank(req.getChannelUrl(), resolveChannelUrlFromAppDict(appId, device));
            StoreOpenResult opened = openAndroidStore(device, appId, pkg, preferredUrl);
            if (!opened.ok) {
                return OpResult.fail("无法打开应用市场/Play（strategy=" + opened.oem
                        + ", manufacturer=" + opened.manufacturer
                        + "），请确认设备联网且已装对应厂商商店，或配置 channelUrl / ui_apps.channel_urls");
            }
            sleep(2500);
            TapInstallResult tap = tapAndroidInstall(device, opened.oem);
            log.info("Android 商店安装冷静等待 {}s 后再检测包名: {} (tapMode={})",
                    storeGraceSeconds, pkg, tap.mode);
            sleep(Math.max(5, storeGraceSeconds) * 1000L);
            boolean installed = waitUntilInstalled(device, pkg, storeWaitSeconds, false);
            if (!installed) {
                return OpResult.fail("等待商店安装超时（冷静 " + storeGraceSeconds + "s + 轮询 "
                        + storeWaitSeconds + "s），包名仍未出现: " + pkg
                        + "。strategy=" + opened.oem + " uri=" + opened.uriUsed
                        + " tapMode=" + tap.mode
                        + "。请确认已登录对应应用市场账号，或为该设备配置 installTapX/Y");
            }
            deviceManager.pressAndroidHome(device.getId());
            sleep(800);
            return OpResult.ok("CHANNEL_PAGE 安装成功并已回桌面: " + pkg
                    + " | strategy=" + opened.oem
                    + " store=" + opened.oem.storePackageHint()
                    + " uri=" + opened.uriUsed
                    + " tapMode=" + tap.mode
                    + (tap.detail != null ? " detail=" + tap.detail : ""));
        }

        if (Device.usesIosAutomation(device.getPlatform())) {
            String expectedName = resolveDisplayName(appId);
            // 先拉起本机 WDA（不重装）；WDA 会抢前台，所以之后再开 App Store 详情
            if (appiumDriverAdapter != null) {
                int wdaPort = appiumDriverAdapter.resolveWdaLocalPort(device.getId());
                if (!deviceManager.ensureIosWdaRunning(device.getId(), wdaPort)) {
                    return OpResult.fail("无法拉起 WebDriverAgent（请解锁手机并确认已信任开发者证书）");
                }
            }
            if (!openIosAppStore(device, appId)) {
                return OpResult.fail("无法打开 App Store「" + expectedName + "」详情页（devicectl/idevice）");
            }
            sleep(5000); // 等详情页渲染（含云下载图标）
            boolean tapped = tapIosAppStoreInstall(device);
            if (!tapped) {
                // WDA 抢前台后偶发详情页丢失：再开一次详情并重试点击
                log.warn("首次点击未成功，重新打开 App Store 详情后重试");
                openIosAppStore(device, appId);
                sleep(4000);
                tapped = tapIosAppStoreInstall(device);
            }
            if (!tapped) {
                log.warn("自动点击「获取/云下载」仍失败，将继续轮询；也可在手机上手动点一次");
            }
            log.info("iOS 商店安装冷静等待 {}s 后再检测 Bundle: {}", storeGraceSeconds, pkg);
            sleep(Math.max(5, storeGraceSeconds) * 1000L);
            boolean installed = waitUntilInstalled(device, pkg, storeWaitSeconds, true);
            if (!installed) {
                return OpResult.fail("等待 App Store 安装「" + expectedName + "」超时（冷静 "
                        + storeGraceSeconds + "s + 轮询 " + storeWaitSeconds + "s），Bundle 仍未出现: " + pkg
                        + "。请确认详情页「获取/云下载」已点下并登录 Apple ID");
            }
            return OpResult.ok("App Store 已安装「" + expectedName + "」: " + pkg);
        }

        return OpResult.fail("不支持的平台: " + device.getPlatform());
    }

    /** @deprecated 请用 {@link #installFromStore(String, Device)} */
    public OpResult installFromStoreForOnlineRegression(OnlineRegressionJob job, Device device) {
        return installFromStore(job.getAppId(), device);
    }

    /** 本地 APK/IPA 安装到设备 */
    public boolean installLocalPackage(Device device, Path packageFile) {
        return installLocalPackageDetailed(device, packageFile) == null;
    }

    /** 失败时返回原因（adb 原始输出），供执行日志与归因使用；成功返回 null */
    public String installLocalPackageDetailed(Device device, Path packageFile) {
        if (device == null || packageFile == null || !Files.isRegularFile(packageFile)) {
            return "安装包文件不存在: " + packageFile;
        }
        String path = packageFile.toAbsolutePath().toString();
        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            // 先探一下「USB 安装」开关：关着的话 adb install 会停在设备的确认框上，
            // 白等五分钟超时。提前说清楚该开哪个开关，比事后猜快得多
            String notReady = deviceManager.checkAdbInstallReady(device.getId());
            if (notReady != null) {
                log.warn("安装前检查: {}", notReady);
            }
            String failure = deviceManager.installAndroidAppDetailed(device.getId(), path);
            if (failure == null) {
                return null;
            }
            // 失败了才谈原因：adb 那句 Failure[...] 单看基本没法照着做
            StringBuilder msg = new StringBuilder(failure);
            if (notReady != null) {
                msg.append(" ｜ ").append(notReady);
            }
            String hint = deviceManager.installFailureHint(device.getId(), failure);
            if (hint != null) {
                msg.append(" ｜ ").append(hint);
            }
            return msg.toString();
        }
        if (Device.usesIosAutomation(device.getPlatform())) {
            return deviceManager.installIOSApp(device.getId(), path) ? null : "iOS 安装失败";
        }
        return "本地安装不支持平台: " + device.getPlatform();
    }

    public boolean uninstallPackage(Device device, String packageOrBundle) {
        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            return deviceManager.uninstallAndroidApp(device.getId(), packageOrBundle);
        }
        if (Device.usesIosAutomation(device.getPlatform())) {
            return deviceManager.uninstallIOSApp(device.getId(), packageOrBundle);
        }
        return false;
    }

    private StoreOpenResult openAndroidStore(Device device, String appId, String packageName, String preferredUrl) {
        String deviceId = device.getId();
        String mfr = deviceManager.getAndroidManufacturer(deviceId);
        AndroidStoreOem oem = APP_POG.equals(appId) ? AndroidStoreOem.PLAY : AndroidStoreOem.fromManufacturer(mfr);
        if (preferredUrl != null && !preferredUrl.isBlank()) {
            log.info("优先打开配置渠道页: manufacturer={}, strategy={}, url={}", mfr, oem, preferredUrl);
            if (deviceManager.openAndroidUri(deviceId, preferredUrl.trim())) {
                return StoreOpenResult.ok(oem, mfr, preferredUrl.trim());
            }
            log.warn("配置渠道页打开失败，回退厂商 URI: {}", preferredUrl);
        }
        String keyword = resolveDisplayName(appId);
        List<String> candidates = oem.openUris(packageName, keyword);
        log.info("打开 Android 商店: manufacturer={}, strategy={}, storeHint={}, uris={}",
                mfr, oem, oem.storePackageHint(), candidates);
        for (String uri : candidates) {
            if (deviceManager.openAndroidUri(deviceId, uri)) {
                log.info("已打开商店 URI: strategy={}, uri={}", oem, uri);
                return StoreOpenResult.ok(oem, mfr, uri);
            }
        }
        if (oem != AndroidStoreOem.DEFAULT && oem != AndroidStoreOem.PLAY) {
            String fallback = "market://details?id=" + packageName;
            if (deviceManager.openAndroidUri(deviceId, fallback)) {
                log.info("厂商 URI 均失败，通用 market 兜底成功: {}", fallback);
                return StoreOpenResult.ok(oem, mfr, fallback);
            }
        }
        return StoreOpenResult.fail(oem, mfr);
    }

    /** 从 ui_apps.channel_urls JSON 按当前厂商取详情页 */
    private String resolveChannelUrlFromAppDict(String appId, Device device) {
        if (appId == null || appId.isBlank()) {
            return null;
        }
        UiApp app = uiAppService.get(appId.trim());
        if (app == null || app.getChannelUrls() == null || app.getChannelUrls().isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(app.getChannelUrls());
            if (root == null || !root.isObject()) {
                return null;
            }
            String mfr = deviceManager.getAndroidManufacturer(device.getId());
            AndroidStoreOem oem = APP_POG.equals(appId) ? AndroidStoreOem.PLAY : AndroidStoreOem.fromManufacturer(mfr);
            String url = textNode(root, oem.name());
            if (url == null) {
                url = textNode(root, oem.configKey());
            }
            if (url == null && oem == AndroidStoreOem.HONOR) {
                url = textNode(root, AndroidStoreOem.HUAWEI.name());
            }
            if (url == null) {
                // 兼容小写 key
                Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase(oem.name())) {
                        url = e.getValue() != null ? e.getValue().asText(null) : null;
                        break;
                    }
                }
            }
            return url != null && !url.isBlank() ? url.trim() : null;
        } catch (Exception e) {
            log.warn("解析 channel_urls 失败 appId={}: {}", appId, e.getMessage());
            return null;
        }
    }

    private static String textNode(JsonNode root, String key) {
        if (root == null || key == null) {
            return null;
        }
        JsonNode n = root.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        String t = n.asText(null);
        return t != null && !t.isBlank() ? t.trim() : null;
    }

    private Path downloadPackageUrl(String url, String prefix) throws Exception {
        Files.createDirectories(Path.of(uploadDir));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("下载失败 HTTP " + resp.statusCode() + " url=" + url);
        }
        String ext = ".apk";
        String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (ct.contains("ios") || ct.contains("iphone") || url.toLowerCase(Locale.ROOT).contains(".ipa")) {
            ext = ".ipa";
        }
        Path target = Path.of(uploadDir, prefix + "_" + System.currentTimeMillis() + ext);
        try (InputStream in = resp.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        assertLooksLikePackage(target, url);
        return target;
    }

    /**
     * 下载完立刻校验是不是安装包。
     * <p>
     * 蒲公英的应用短链（{@code https://www.pgyer.com/xxx}）是一张**下载页面**，不是直链。
     * 拿它下载会得到一个几十 KB 的 HTML，存成 .apk 之后 adb 只会回一句含糊的安装失败，
     * 人和 AI 都只能猜是签名、存储还是权限——实测就踩了这一次。
     * <p>
     * APK 和 IPA 都是 ZIP，首四字节是 {@code PK\x03\x04}，判起来足够可靠。
     */
    private void assertLooksLikePackage(Path file, String url) throws java.io.IOException {
        long size = Files.size(file);
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.read(head) == 4 && head[0] == 'P' && head[1] == 'K'
                    && head[2] == 3 && head[3] == 4) {
                return;
            }
        }
        String peek = new String(Files.readAllBytes(file), 0,
                (int) Math.min(size, 200), java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
        Files.deleteIfExists(file);
        throw new IllegalStateException(
                "该 URL 返回的不是安装包（" + size + " 字节，开头是：" + peek.substring(0, Math.min(80, peek.length()))
                        + "…）。蒲公英的应用页面链接不能直接下载。"
                        + "建议清空这个 URL，改为在应用字典里配置蒲公英 appKey——"
                        + "配好后会自动装最新版，每次发版都不用再改流程。"
                        + "也可以改填 buildKey 或真正的 APK/IPA 直链。url=" + url);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * 点击安装：文案/控件优先 → 设备 config 校准坐标 → 厂商 yml 坐标 → 全局默认坐标。
     */
    private TapInstallResult tapAndroidInstall(Device device, AndroidStoreOem oem) {
        String deviceId = device.getId();
        List<String> texts = oem.installButtonTexts();
        String hit = deviceManager.clickAndroidByExactTexts(deviceId, texts);
        if (hit != null) {
            return TapInstallResult.of("TEXT", hit);
        }
        log.info("未命中商店安装文案 {}，尝试校准坐标兜底", texts);

        int[] deviceTap = parseDeviceInstallTap(device);
        if (deviceTap != null) {
            log.info("使用设备 config 校准坐标: ({},{})", deviceTap[0], deviceTap[1]);
            if (deviceManager.adbTap(deviceId, deviceTap[0], deviceTap[1])) {
                return TapInstallResult.of("DEVICE_COORD", deviceTap[0] + "," + deviceTap[1]);
            }
        }

        int[] vendorTap = resolveVendorTap(oem);
        if (vendorTap != null) {
            log.info("使用厂商默认坐标 {}: ({},{})", oem, vendorTap[0], vendorTap[1]);
            if (deviceManager.adbTap(deviceId, vendorTap[0], vendorTap[1])) {
                return TapInstallResult.of("VENDOR_COORD", oem.configKey() + "=" + vendorTap[0] + "," + vendorTap[1]);
            }
        }

        log.info("使用全局固定坐标兜底: ({},{}) tapMode=FIXED_FALLBACK", androidTapX, androidTapY);
        deviceManager.adbTap(deviceId, androidTapX, androidTapY);
        return TapInstallResult.of("FIXED_FALLBACK", androidTapX + "," + androidTapY);
    }

    /** device.config JSON：{"installTapX":369,"installTapY":1483} */
    private int[] parseDeviceInstallTap(Device device) {
        String cfg = device != null ? device.getConfig() : null;
        if (cfg == null || cfg.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(cfg);
            if (n == null || !n.has("installTapX") || !n.has("installTapY")) {
                return null;
            }
            int x = n.get("installTapX").asInt(0);
            int y = n.get("installTapY").asInt(0);
            if (x > 0 && y > 0) {
                return new int[]{x, y};
            }
        } catch (Exception e) {
            log.debug("解析设备 installTap 失败: {}", e.getMessage());
        }
        return null;
    }

    /** app.install.vendor-tap.xiaomi=x,y */
    private int[] resolveVendorTap(AndroidStoreOem oem) {
        if (oem == null || environment == null) {
            return null;
        }
        String raw = environment.getProperty("app.install.vendor-tap." + oem.configKey());
        if (raw == null || raw.isBlank()) {
            // honor 可回退 huawei 校准
            if (oem == AndroidStoreOem.HONOR) {
                raw = environment.getProperty("app.install.vendor-tap.huawei");
            }
        }
        return parseXy(raw);
    }

    private static int[] parseXy(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split("[,xX\\s]+");
        if (parts.length < 2) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            if (x > 0 && y > 0) {
                return new int[]{x, y};
            }
        } catch (NumberFormatException ignored) {
            // ignore
        }
        return null;
    }

    private static final class StoreOpenResult {
        final boolean ok;
        final AndroidStoreOem oem;
        final String manufacturer;
        final String uriUsed;

        private StoreOpenResult(boolean ok, AndroidStoreOem oem, String manufacturer, String uriUsed) {
            this.ok = ok;
            this.oem = oem;
            this.manufacturer = manufacturer != null ? manufacturer : "";
            this.uriUsed = uriUsed;
        }

        static StoreOpenResult ok(AndroidStoreOem oem, String mfr, String uri) {
            return new StoreOpenResult(true, oem, mfr, uri);
        }

        static StoreOpenResult fail(AndroidStoreOem oem, String mfr) {
            return new StoreOpenResult(false, oem, mfr, null);
        }
    }

    private static final class TapInstallResult {
        final String mode;
        final String detail;

        private TapInstallResult(String mode, String detail) {
            this.mode = mode;
            this.detail = detail;
        }

        static TapInstallResult of(String mode, String detail) {
            return new TapInstallResult(mode, detail);
        }
    }

    private boolean openIosAppStore(Device device, String appId) {
        // 内置电竞/Pog 有官方详情链；其它 APP 暂无 App Store ID 字段时无法自动打开
        // 关键不要用 Appium launchApp(AppStore)：每次会话都会重装/拉起 WebDriverAgent
        String detailHttps;
        if (APP_POG.equals(appId)) {
            detailHttps = "https://apps.apple.com/us/app/id" + APPSTORE_ID_POG;
        } else if (APP_DOMESTIC.equals(appId)) {
            detailHttps = APPSTORE_URL_ESPORTS;
        } else {
            log.warn("应用字典 {} 尚无 App Store 详情 URL，iOS 商店安装目前仅内置电竞/Pog", appId);
            return false;
        }
        String detailItms = detailHttps.replace("https://", "itms-apps://");
        log.info("打开 App Store 详情（无 WDA）: appId={}, url={}", appId, detailHttps);
        if (deviceManager.openIosAppStoreUrl(device.getId(), detailItms)
                || deviceManager.openIosAppStoreUrl(device.getId(), detailHttps)) {
            return true;
        }
        log.warn("devicectl/idevice 打开 App Store 失败 deviceId={}", device.getId());
        return false;
    }

    /**
     * 线上回归「启动」：不建 Task、不经 Appium 冷启会话。
     * iOS 功能1 固定顺序：启动成功 → 等无线数据弹窗 → 点「无线局域网与蜂窝网络」→ 截图。
     */
    public OpResult launchForOnlineRegression(OnlineRegressionJob job, Device device) {
        String pkg = resolvePackage(job.getAppId(), device);
        log.info("线上回归直接启动: job={}, device={}, pkg={}", job.getJobKey(), device.getId(), pkg);
        if (!isInstalled(device, pkg)) {
            return OpResult.fail("应用未安装，无法启动: " + pkg);
        }
        boolean ios = Device.usesIosAutomation(device.getPlatform());
        boolean ok;
        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            deviceManager.pressAndroidHome(device.getId());
            sleep(600);
            ok = deviceManager.launchAndroidApp(device.getId(), pkg);
            if (!ok) {
                return OpResult.fail("adb 启动失败: " + pkg);
            }
            String shot = captureAndroidLaunchScreenshot(device, job.getJobKey());
            return OpResult.ok("已启动: " + pkg, shot);
        }
        if (ios) {
            ok = deviceManager.launchIosAppCli(device.getId(), pkg);
            if (!ok) {
                return OpResult.fail("iOS 启动失败（devicectl/idevicedebug）: " + pkg);
            }
            log.info("iOS 启动成功，进入：等弹窗 → 点击 → 截图, pkg={}", pkg);
            String shot = clickIosWirelessDataAlertThenScreenshot(device, pkg, job.getJobKey());
            if (shot == null || shot.isBlank()) {
                sleep(Math.max(1500L, iosAfterAlertSettleMs));
                shot = captureLaunchScreenshot(device, job.getJobKey(), "ios_final");
            }
            return OpResult.ok("已启动: " + pkg, shot);
        }
        return OpResult.fail("不支持的平台: " + device.getPlatform());
    }

    /**
     * 安装后启动必现的 <b>SpringBoard 系统弹窗</b>（允许使用无线数据）。
     * 流程：等弹窗 → 按系统 Alert API 点「无线局域网与蜂窝网络」→ 截图。
     * 不能当普通 App 控件坐标点（属于 com.apple.springboard）。
     */
    private String clickIosWirelessDataAlertThenScreenshot(Device device, String pkg, String jobKey) {
        if (appiumDriverAdapter == null) {
            log.warn("Appium 未启用，跳过系统弹窗，直接截图");
            sleep(Math.max(1500L, iosAfterAlertSettleMs));
            return captureLaunchScreenshot(device, jobKey, "ios_no_alert");
        }
        try {
            long appearMs = Math.max(800L, iosAlertAppearMs);
            log.info("等待 SpringBoard 系统弹窗出现 {}ms", appearMs);
            sleep(appearMs);
            if (!appiumDriverAdapter.attachIosUiSession(device.getId(), pkg, true)) {
                log.warn("挂接会话失败，无法处理系统弹窗: device={}", device.getId());
                sleep(Math.max(1500L, iosAfterAlertSettleMs));
                return captureLaunchScreenshot(device, jobKey, "ios_no_session");
            }
            int tapY = iosAlertTapY > 0 ? iosAlertTapY : 485;
            int tapX = resolveIosAlertTapX(device.getId());
            log.info("处理系统级弹窗：切 SpringBoard + mobile:alert「无线局域网与蜂窝网络」(兜底坐标 {},{})",
                    tapX, tapY);
            boolean ok = appiumDriverAdapter.dismissIosSpringboardSystemAlert(
                    device.getId(), "无线局域网与蜂窝网络", tapX, tapY);
            log.info("系统弹窗处理结果: ok={}", ok);
            long settle = Math.max(1500L, iosAfterAlertSettleMs);
            log.info("系统弹窗处理完成，等待 {}ms 后截图", settle);
            sleep(settle);
            return captureLaunchScreenshot(device, jobKey, "ios_after_alert");
        } catch (Exception e) {
            log.warn("iOS 系统弹窗/截图异常，尝试兜底截图: {}", e.getMessage());
            return captureLaunchScreenshot(device, jobKey, "ios_fallback");
        }
    }

    /** x&lt;=0 时用窗口宽度一半（系统 Alert 按钮居中）；否则用配置值 */
    private int resolveIosAlertTapX(String deviceId) {
        if (iosAlertTapX > 0) {
            // 若配置成控件左边缘（如 40），再往右偏一点更易点中按钮
            if (iosAlertTapX < 80) {
                int[] size = appiumDriverAdapter.getWindowSize(deviceId);
                int mid = size != null && size[0] > 0 ? size[0] / 2 : 187;
                log.info("ios-alert-tap-x={} 偏左（像控件左缘），改用屏幕中心 x={}", iosAlertTapX, mid);
                return mid;
            }
            return iosAlertTapX;
        }
        int[] size = appiumDriverAdapter.getWindowSize(deviceId);
        if (size != null && size[0] > 0) {
            return size[0] / 2;
        }
        return 187;
    }

    private String captureAndroidLaunchScreenshot(Device device, String jobKey) {
        long waitMs = Math.max(1000L, androidLaunchSettleMs);
        log.info("Android 启动完成，等待 {}ms 后截图: device={}", waitMs, device.getId());
        sleep(waitMs);
        return captureLaunchScreenshot(device, jobKey, "android");
    }

    /**
     * 启动截图：Android→adb；iOS→优先 WDA HTTP /screenshot（idevicescreenshot 常因未挂 Developer Disk 失败）。
     */
    private String captureLaunchScreenshot(Device device, String jobKey, String tag) {
        try {
            String dir = "/tmp/screenshots";
            Files.createDirectories(Path.of(dir));
            String safe = jobKey != null ? jobKey.replaceAll("[^A-Za-z0-9_-]", "_") : "or";
            String suffix = tag != null ? tag : "shot";
            String path = dir + "/or_launch_" + safe + "_" + suffix + "_" + System.currentTimeMillis() + ".png";
            String saved = null;
            if (Device.usesIosAutomation(device.getPlatform()) && appiumDriverAdapter != null) {
                saved = appiumDriverAdapter.takeScreenshotViaWdaHttp(device.getId(), path);
            }
            if (saved == null || saved.isBlank()) {
                saved = automationEngine.takeScreenshot(device.getId(), device.getPlatform(), path);
            }
            if (saved != null && !saved.isBlank()) {
                return saved;
            }
            log.warn("启动截图未生成文件: device={}, path={}", device.getId(), path);
        } catch (Exception e) {
            log.warn("启动截图失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * iOS App Store：用 Appium 点「获取 / 云下载」（与系统弹窗的 SpringBoard/mobile:alert 路径分离）。
     * <p>
     * 安装步骤若排在登录之后，内存里常残留已失效、且 bundle=业务 App 的旧会话，必须 forceNew 挂到 App Store。
     */
    private boolean tapIosAppStoreInstall(Device device) {
        if (appiumDriverAdapter == null) {
            log.warn("无法自动点「获取」：Appium 未启用");
            return false;
        }
        String deviceId = device.getId();
        int wdaPort = appiumDriverAdapter.resolveWdaLocalPort(deviceId);
        if (!deviceManager.ensureIosWdaRunning(deviceId, wdaPort)) {
            log.warn("点击前 WDA 未就绪: port={}", wdaPort);
            return false;
        }
        // 强制重挂 App Store：勿复用登录/启动留下的旧会话
        if (!appiumDriverAdapter.attachIosUiSession(deviceId, "com.apple.AppStore", true)) {
            log.warn("无法挂接 App Store UI 会话，自动点「获取」失败");
            return false;
        }
        PlatformDriver driver = platformRouter.getDriver(device);
        if (driver == null) {
            return false;
        }
        if (isIosAppStoreInstalling(driver, deviceId)) {
            log.info("App Store 已处于安装/下载中，跳过点击");
            return true;
        }
        boolean anyOk = false;
        final int maxTaps = 3;
        for (int i = 1; i <= maxTaps; i++) {
            if (isIosAppStoreInstalling(driver, deviceId)) {
                log.info("第 {} 次点击前已检测到安装进度，停止继续点", i);
                return true;
            }
            boolean tapped = false;
            // 优先 Appium 坐标点云下载/获取（Inspector 校准：ios-tap-x/y）
            if (iosTapX > 0 && iosTapY > 0) {
                log.info("iOS App Store 第 {}/{} 次 Appium 坐标点击云下载/获取: ({},{})", i, maxTaps, iosTapX, iosTapY);
                tapped = appiumDriverAdapter.click(deviceId, iosTapX, iosTapY);
            }
            if (!tapped) {
                log.info("坐标未点中，回退 Appium 元素选择器 / 比率点击");
                tapped = tryTapIosInstallBySelector(driver, deviceId)
                        || appiumDriverAdapter.clickByRatio(deviceId, 0.45, 0.24);
            }
            anyOk = anyOk || tapped;
            sleep(1200);
            if (isIosAppStoreInstalling(driver, deviceId)) {
                log.info("第 {} 次点击后已进入安装状态，停止继续点", i);
                tryClickIfPresent(driver, deviceId, "xpath://XCUIElementTypeButton[@name='安装' or @label='安装']");
                return true;
            }
        }
        if (anyOk) {
            tryClickIfPresent(driver, deviceId, "xpath://XCUIElementTypeButton[@name='安装' or @label='安装']");
        }
        return anyOk;
    }

    /** 已进入下载/安装进度，或已可「打开」——此时不应再点云下载 */
    private boolean isIosAppStoreInstalling(PlatformDriver driver, String deviceId) {
        try {
            if (driver.isElementPresent(deviceId, "xpath://XCUIElementTypeProgressIndicator")) {
                return true;
            }
            if (driver.isElementPresent(deviceId,
                    "xpath://XCUIElementTypeButton[@name='打开' or @label='打开' or @name='Open' or @label='Open']")) {
                return true;
            }
            if (driver.isElementPresent(deviceId,
                    "xpath://*[contains(@name,'暂停') or contains(@label,'暂停') or contains(@name,'Waiting') or contains(@label,'Waiting')]")) {
                return true;
            }
            String src = appiumDriverAdapter != null ? appiumDriverAdapter.getPageSource(deviceId) : null;
            if (src != null) {
                String s = src.toLowerCase();
                if (s.contains("正在下载") || s.contains("正在安装") || s.contains("downloading")
                        || s.contains("installing") || s.contains("waiting")
                        || src.contains("暂停")) {
                    return true;
                }
                // 云下载/获取都消失，通常已切到圆形进度
                boolean hasCloud = src.contains("RedownloadCloudIcon");
                boolean hasGet = src.contains("name=\"获取\"") || src.contains("label=\"获取\"")
                        || src.contains("name=\"Get\"");
                if (!hasCloud && !hasGet && (src.contains("Progress") || src.contains("暂停") || src.contains("打开"))) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("检测 App Store 安装状态失败: {}", e.getMessage());
        }
        return false;
    }

    private boolean tryTapIosInstallBySelector(PlatformDriver driver, String deviceId) {
        List<String> selectors = List.of(
                "accessibility id:RedownloadCloudIcon",
                "xpath://XCUIElementTypeImage[@name='RedownloadCloudIcon']",
                "xpath://XCUIElementTypeButton[.//XCUIElementTypeImage[@name='RedownloadCloudIcon']]",
                "xpath://XCUIElementTypeButton[@name='获取' or @label='获取']",
                "xpath://XCUIElementTypeButton[@name='安装' or @label='安装']",
                "xpath://XCUIElementTypeButton[@name='Get' or @label='Get' or @name='Install' or @label='Install']",
                "accessibility id:获取",
                "accessibility id:Get"
        );
        for (String sel : selectors) {
            try {
                if (driver.isElementPresent(deviceId, sel) && driver.clickElement(deviceId, sel)) {
                    log.info("已点击 App Store 安装按钮: {}", sel);
                    return true;
                }
            } catch (Exception e) {
                log.debug("选择器点击失败 {}: {}", sel, e.getMessage());
            }
        }
        return false;
    }

    private static void tryClickIfPresent(PlatformDriver driver, String deviceId, String selector) {
        try {
            if (driver.isElementPresent(deviceId, selector)) {
                driver.clickElement(deviceId, selector);
                log.info("已点击确认按钮: {}", selector);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void maybeTapInstallButtons(Device device, boolean android) {
        if (android) {
            String mfr = deviceManager.getAndroidManufacturer(device.getId());
            tapAndroidInstall(device, AndroidStoreOem.fromManufacturer(mfr));
            return;
        }
        tapIosAppStoreInstall(device);
    }

    private boolean waitUntilInstalled(Device device, String pkg, int timeoutSeconds, boolean retapIos) {
        long deadline = System.currentTimeMillis() + Math.max(10, timeoutSeconds) * 1000L;
        int poll = Math.max(2, storePollSeconds);
        while (System.currentTimeMillis() < deadline) {
            if (isInstalled(device, pkg)) {
                return true;
            }
            // iOS：仅当尚未进入安装进度时才补点，避免打断下载
            if (retapIos && Device.usesIosAutomation(device.getPlatform()) && appiumDriverAdapter != null) {
                PlatformDriver driver = platformRouter.getDriver(device);
                if (driver != null && isIosAppStoreInstalling(driver, device.getId())) {
                    log.debug("轮询中已在安装，跳过补点: {}", pkg);
                } else {
                    maybeTapInstallButtons(device, false);
                }
            }
            sleep(poll * 1000L);
        }
        return isInstalled(device, pkg);
    }

    public boolean isInstalled(Device device, String pkg) {
        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            return deviceManager.isAndroidAppInstalled(device.getId(), pkg);
        }
        if (Device.usesIosAutomation(device.getPlatform())) {
            return deviceManager.isIOSAppInstalled(device.getId(), pkg);
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class OpResult {
        public final boolean success;
        public final String message;
        public final String screenshotPath;
        /** 扫码辅助：安装页 URL，供前端生成二维码 */
        public final String qrPayload;

        private OpResult(boolean success, String message, String screenshotPath, String qrPayload) {
            this.success = success;
            this.message = message;
            this.screenshotPath = screenshotPath;
            this.qrPayload = qrPayload;
        }

        public static OpResult ok() {
            return new OpResult(true, null, null, null);
        }

        public static OpResult ok(String message) {
            return new OpResult(true, message, null, null);
        }

        public static OpResult ok(String message, String screenshotPath) {
            return new OpResult(true, message, screenshotPath, null);
        }

        public static OpResult ok(String message, String screenshotPath, String qrPayload) {
            return new OpResult(true, message, screenshotPath, qrPayload);
        }

        public static OpResult fail(String message) {
            return new OpResult(false, message, null, null);
        }

        public static OpResult fail(String message, String screenshotPath, String qrPayload) {
            return new OpResult(false, message, screenshotPath, qrPayload);
        }
    }
}
