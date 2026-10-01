package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 在当前 App 内打开 H5 完整地址（可含 hash）。
 * <p>
 * 完美电竞的 {@code PWx5WebActivity} 未 exported，adb {@code am start -n} 会被系统拒绝
 * （应用在前台也一样）。应走已导出的主 Activity scheme：
 * {@code pwesports://global/openWebView?url=}。
 */
@Slf4j
@Component
public class OpenH5Handler implements KeywordHandler {

    static final String ESPORTS_PKG = "com.pwrd.steam.esports";
    static final String ESPORTS_OPEN_WEB = "pwesports://global/openWebView";

    @Override
    public String code() {
        return "OPEN_H5";
    }

    @Override
    public String nameZh() {
        return "H5打开页面";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "通用：用完整 H5 地址打开当前 App 内的一页（可含 #）。"
                + "换页只改地址。完美电竞可留空 scheme；其他 App 把打开 H5 的 scheme 填在参数里，不必改代码";
    }

    @Override
    public int sortOrder() {
        return 8;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.SINGLE;
    }

    @Override
    public String shortLabel() {
        return "H5打开";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.requiredString("url", "H5 地址")
                        .withHelp("完整 location.href，例如 https://news.wmpvp.com/gameMall.html#/index?tagId=3829"),
                ParamDef.string("scheme", "App 内打开 scheme")
                        .withHelp("完美电竞可留空（默认 pwesports://global/openWebView）。其他 App 填其打开 H5 的 scheme 前缀"),
                ParamDef.string("activity", "WebActivity（备选）")
                        .withHelp("仅当 App 把 WebActivity 设为 exported 时才有效；完美电竞这个填了也会被系统拒绝"),
                ParamDef.string("packageName", "包名 / BundleId")
                        .withHelp("留空则取应用字典"),
                ParamDef.number("waitAfterMs", "打开后等待毫秒", 1500)
                        .withHelp("等 WebView 首屏，可按页调大")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String url = KeywordContext.firstNonBlank(ctx.str("url"), ctx.str("pageUrl"));
        if (url != null) {
            url = url.trim();
        }
        if (url == null || url.isBlank()) {
            throw ctx.fail("H5打开页面", "请填写 H5 地址（完整 URL，可含 # 路由）", null);
        }
        if (!(url.startsWith("http://") || url.startsWith("https://")
                || url.contains("://"))) {
            throw ctx.fail("H5打开页面", "地址不像 URL：" + url, url);
        }

        String pkg = ctx.packageName();
        if (ctx.isHarmony()) {
            throw ctx.fail("H5打开页面", "鸿蒙暂不支持直接打开 H5 URL", url);
        }

        boolean ok;
        String how;
        if (ctx.isIos()) {
            AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
            if (appium == null) {
                throw ctx.fail("H5打开页面", "iOS 打开 H5 需要 Appium 会话", url);
            }
            ok = appium.openDeepLink(ctx.getDeviceId(), url, pkg);
            how = "iOS deepLink → " + pkg;
        } else {
            String launch = url;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                String scheme = inAppOpenScheme(pkg, ctx.str("scheme"), url);
                if (scheme != null) {
                    launch = scheme;
                }
            }
            ok = adbStartView(ctx.getDeviceId(), pkg, launch);
            how = "VIEW " + launch;
            if (!ok) {
                String activity = ctx.str("activity");
                if (activity != null && !activity.isBlank()) {
                    ok = adbStartH5(ctx.getDeviceId(), toComponent(pkg, activity.trim()), url);
                    how = "am start -n " + toComponent(pkg, activity.trim());
                }
            }
            if (!ok) {
                AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
                if (appium != null) {
                    ok = appium.openDeepLink(ctx.getDeviceId(), launch, pkg);
                    how = "Appium deepLink → " + launch;
                }
            }
        }
        if (!ok) {
            throw ctx.fail("H5打开页面",
                    "未能打开：" + url
                            + "。不是「应用没启动」：完美电竞 WebActivity 未导出，adb 直接 start 会被拒绝。"
                            + "已改走 pwesports://global/openWebView。请重启后端后再试；"
                            + "其他 App 需填它自己的打开 H5 scheme。",
                    url);
        }
        ctx.ok("H5打开页面", how + " | " + url);
        long waitMs = ctx.longParam("waitAfterMs", 1500);
        if (waitMs > 0) {
            ctx.sleep(Math.min(waitMs, 30_000));
        }
        ctx.screenshotWhenSettled("H5打开");
    }

    static String inAppOpenScheme(String pkg, String schemePrefix, String h5Url) {
        String prefix = schemePrefix != null ? schemePrefix.trim() : "";
        if (prefix.isBlank() && pkg != null && ESPORTS_PKG.equalsIgnoreCase(pkg.trim())) {
            prefix = ESPORTS_OPEN_WEB;
        }
        if (prefix.isBlank()) {
            return null;
        }
        String enc = URLEncoder.encode(h5Url, StandardCharsets.UTF_8);
        if (prefix.contains("url=")) {
            return prefix;
        }
        return prefix + (prefix.contains("?") ? "&" : "?") + "url=" + enc;
    }

    static String toComponent(String pkg, String activity) {
        String act = activity == null ? "" : activity.trim();
        if (act.contains("/")) {
            return act;
        }
        if (act.startsWith(".")) {
            return pkg + "/" + act;
        }
        return pkg + "/" + act;
    }

    /** 设备 shell 里 # 是注释，URL 必须单引号包起来。 */
    static String shSingleQuote(String raw) {
        if (raw == null) {
            return "''";
        }
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    static boolean adbStartH5(String deviceId, String component, String url) {
        String shell = "am start -n " + component
                + " -f 0x14000000 --es Url " + shSingleQuote(url);
        return adbShell(deviceId, shell, "component=" + component);
    }

    static boolean adbStartView(String deviceId, String pkg, String url) {
        String shell = "am start -a android.intent.action.VIEW -d "
                + shSingleQuote(url) + " -p " + pkg;
        return adbShell(deviceId, shell, "VIEW " + pkg);
    }

    private static boolean adbShell(String deviceId, String shell, String hint) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", shell)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("打开 H5 adb 超时: {}", hint);
                return false;
            }
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.exitValue();
            String lower = out.toLowerCase(Locale.ROOT);
            boolean denied = lower.contains("securityexception") || lower.contains("permission denial")
                    || lower.contains("not exported") || lower.contains("does not exist");
            boolean started = lower.contains("starting:") || lower.contains("delivered")
                    || lower.contains("cmp=");
            boolean looksOk = !denied && (code == 0 || started);
            if (looksOk) {
                log.info("打开 H5 {}: {}", hint, out.trim().replace('\n', ' '));
            } else {
                log.warn("打开 H5 失败 {} exit={} out={}", hint, code, out.trim());
            }
            return looksOk;
        } catch (Exception e) {
            log.warn("打开 H5 adb 异常 {}: {}", hint, e.getMessage());
            return false;
        }
    }
}
