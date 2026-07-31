package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.OnlineRegressionJob;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 包体卸载 / 本地安装 / 线上回归「应用商店拉起安装」。
 * <p>
 * 线上回归国内电竞：Android→厂商应用市场；iOS→App Store 官方页
 * （https://apps.apple.com/cn/app/…/id1460786904）。
 * 海外 PogPog：Android→Google Play；iOS→App Store 详情（PogPog id6747575699）。
 * 点安装后先冷静等待，再轮询包名/Bundle 是否落地；功能勾选「安装」时编排会再接启动步骤。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppPackageInstallService {

    public static final String APP_DOMESTIC = "DOMESTIC_ESPORTS";
    public static final String APP_POG = "OVERSEAS_POGPOG";

    private static final String PKG_ESPORTS_ANDROID = "com.pwrd.steam.esports";
    private static final String PKG_ESPORTS_IOS = "com.wmzq.esportapp";
    private static final String PKG_POG_ANDROID = LoginTestService.POGPOG_INTL_PACKAGE;
    private static final String PKG_POG_IOS = "com.arca.egamesintl";

    private static final String SEARCH_ESPORTS = "完美世界电竞";
    private static final String SEARCH_POG = "PogPog";
    /** 国内电竞 App Store 官方下载页（完美世界电竞） */
    private static final String APPSTORE_URL_ESPORTS =
            "https://apps.apple.com/cn/app/%E5%AE%8C%E7%BE%8E%E4%B8%96%E7%95%8C%E7%94%B5%E7%AB%9E/id1460786904";
    private static final String APPSTORE_ID_POG = "6747575699";

    private final DeviceManager deviceManager;
    private final PlatformRouter platformRouter;
    private final AutomationEngine automationEngine;

    @Autowired(required = false)
    private AppiumDriverAdapter appiumDriverAdapter;

    @Value("${app.install.store-wait-seconds:180}")
    private int storeWaitSeconds;

    @Value("${app.install.store-poll-seconds:5}")
    private int storePollSeconds;

    /** 点「获取/安装」后先冷静等待，再开始轮询是否已装上 */
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

    public String resolvePackage(String appId, Device device) {
        boolean android = Device.isAndroidOrPogAndroid(device.getPlatform());
        boolean ios = Device.usesIosAutomation(device.getPlatform());
        if (APP_POG.equals(appId)) {
            if (android) return PKG_POG_ANDROID;
            if (ios) return PKG_POG_IOS;
        } else {
            if (android) return PKG_ESPORTS_ANDROID;
            if (ios) return PKG_ESPORTS_IOS;
        }
        throw new IllegalArgumentException("无法解析包名: appId=" + appId + ", platform=" + device.getPlatform());
    }

    /**
     * 先判断是否已安装：未安装则跳过；已安装才卸载。
     */
    public OpResult uninstallForOnlineRegression(OnlineRegressionJob job, Device device) {
        String pkg = resolvePackage(job.getAppId(), device);
        log.info("线上回归卸载检查: job={}, device={}, pkg={}", job.getJobKey(), device.getId(), pkg);
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
        // 确认卸载完成
        for (int i = 0; i < 10 && isInstalled(device, pkg); i++) {
            sleep(500);
        }
        if (isInstalled(device, pkg)) {
            return OpResult.fail("卸载后仍检测到包: " + pkg);
        }
        return OpResult.ok("已卸载: " + pkg);
    }

    /**
     * 安装前再次确认：若仍安装着则先卸，再打开商店安装。
     */
    public OpResult installFromStoreForOnlineRegression(OnlineRegressionJob job, Device device) {
        String appId = job.getAppId();
        String pkg = resolvePackage(appId, device);
        log.info("线上回归商店安装: job={}, device={}, pkg={}, appId={}", job.getJobKey(), device.getId(), pkg, appId);

        if (isInstalled(device, pkg)) {
            log.info("安装前发现仍已安装，先卸载: {}", pkg);
            OpResult uninstall = uninstallForOnlineRegression(job, device);
            if (!uninstall.success) {
                return OpResult.fail("安装前卸载失败: " + uninstall.message);
            }
        }

        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            if (!openAndroidStore(device.getId(), appId, pkg)) {
                return OpResult.fail("无法打开应用市场/Play，请确认设备联网且已装对应商店");
            }
            sleep(2000); // 等商店详情页渲染
            tapAndroidInstallOnce(device.getId());
            log.info("Android 商店安装冷静等待 {}s 后再检测包名: {}", storeGraceSeconds, pkg);
            sleep(Math.max(5, storeGraceSeconds) * 1000L);
            boolean installed = waitUntilInstalled(device, pkg, storeWaitSeconds, false);
            if (!installed) {
                return OpResult.fail("等待商店安装超时（冷静 " + storeGraceSeconds + "s + 轮询 "
                        + storeWaitSeconds + "s），包名仍未出现: " + pkg
                        + "。请在手机商店确认已点安装/登录账号后重试");
            }
            // 装完必须回桌面，避免停在应用市场；启动由后续 launch 步骤用 adb 完成
            deviceManager.pressAndroidHome(device.getId());
            sleep(800);
            return OpResult.ok("商店安装成功并已回桌面: " + pkg);
        }

        if (Device.usesIosAutomation(device.getPlatform())) {
            String expectedName = APP_POG.equals(appId) ? SEARCH_POG : SEARCH_ESPORTS;
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

    /** 本地 APK/IPA 安装到设备 */
    public boolean installLocalPackage(Device device, Path packageFile) {
        if (device == null || packageFile == null || !Files.isRegularFile(packageFile)) {
            return false;
        }
        String path = packageFile.toAbsolutePath().toString();
        if (Device.isAndroidOrPogAndroid(device.getPlatform())) {
            return deviceManager.installAndroidApp(device.getId(), path);
        }
        if (Device.usesIosAutomation(device.getPlatform())) {
            return deviceManager.installIOSApp(device.getId(), path);
        }
        log.warn("本地安装不支持平台: {}", device.getPlatform());
        return false;
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

    private boolean openAndroidStore(String deviceId, String appId, String packageName) {
        if (APP_POG.equals(appId)) {
            // 海外 PogPog → Google Play
            String play = "market://details?id=" + packageName;
            String playHttps = "https://play.google.com/store/apps/details?id=" + packageName;
            return deviceManager.openAndroidUri(deviceId, play) || deviceManager.openAndroidUri(deviceId, playHttps);
        }

        // 国内电竞：按厂商打开应用市场
        String mfr = deviceManager.getAndroidManufacturer(deviceId);
        List<String> candidates = domesticMarketUris(mfr, packageName, SEARCH_ESPORTS);
        for (String uri : candidates) {
            if (deviceManager.openAndroidUri(deviceId, uri)) {
                return true;
            }
        }
        return false;
    }

    private List<String> domesticMarketUris(String manufacturer, String packageName, String keyword) {
        String m = manufacturer != null ? manufacturer : "";
        String encKeyword;
        try {
            encKeyword = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            encKeyword = keyword;
        }
        // 各厂商详情页 / 搜索 / 通用 market
        if (m.contains("huawei") || m.contains("honor")) {
            return List.of(
                    "appmarket://details?id=" + packageName,
                    "market://details?id=" + packageName,
                    "https://appgallery.huawei.com/#/search/" + encKeyword
            );
        }
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            return List.of(
                    "mimarket://details?id=" + packageName,
                    "market://details?id=" + packageName
            );
        }
        if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) {
            return List.of(
                    "oppomarket://details?packagename=" + packageName,
                    "market://details?id=" + packageName
            );
        }
        if (m.contains("vivo") || m.contains("iqoo")) {
            return List.of(
                    "vivomarket://details?id=" + packageName,
                    "market://details?id=" + packageName
            );
        }
        if (m.contains("samsung")) {
            return List.of(
                    "samsungapps://ProductDetail/" + packageName,
                    "market://details?id=" + packageName
            );
        }
        return List.of(
                "market://details?id=" + packageName,
                "market://search?q=" + encKeyword
        );
    }

    private boolean openIosAppStore(Device device, String appId) {
        // 国内 → 完美世界电竞官方链接；海外 → PogPog
        // 关键不要用 Appium launchApp(AppStore)：每次会话都会重装/拉起 WebDriverAgent，手机上会看到「在装 WDA」
        String detailHttps = APP_POG.equals(appId)
                ? "https://apps.apple.com/us/app/id" + APPSTORE_ID_POG
                : APPSTORE_URL_ESPORTS;
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

    /** Android：按校准坐标只点一次「安装」 */
    private void tapAndroidInstallOnce(String deviceId) {
        log.info("Android 商店安装点击固定坐标一次: ({},{})", androidTapX, androidTapY);
        if (!deviceManager.adbTap(deviceId, androidTapX, androidTapY)) {
            log.warn("adb tap 安装坐标失败: ({},{})", androidTapX, androidTapY);
        }
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
            tapAndroidInstallOnce(device.getId());
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

        private OpResult(boolean success, String message, String screenshotPath) {
            this.success = success;
            this.message = message;
            this.screenshotPath = screenshotPath;
        }

        public static OpResult ok() {
            return new OpResult(true, null, null);
        }

        public static OpResult ok(String message) {
            return new OpResult(true, message, null);
        }

        public static OpResult ok(String message, String screenshotPath) {
            return new OpResult(true, message, screenshotPath);
        }

        public static OpResult fail(String message) {
            return new OpResult(false, message, null);
        }
    }
}
