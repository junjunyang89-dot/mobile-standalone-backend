package com.mobile.standalone.service.driver;

import com.mobile.standalone.service.harmony.HdcClient;
import com.mobile.standalone.service.harmony.HmdriverBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 纯鸿蒙 NEXT 驱动：hdc + hmdriver2，不走 adb / Appium Android。
 * packageName 可带 ability：bundleName/AbilityName 或 bundleName:AbilityName
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HarmonyOSDriver implements PlatformDriver {

    private final HmdriverBridge bridge;
    private final HdcClient hdcClient;

    @Override
    public boolean launchApp(String deviceId, String packageName) {
        log.info("启动 HarmonyOS 应用: deviceId={}, packageName={}", deviceId, packageName);
        String bundle = packageName;
        String ability = null;
        if (packageName != null) {
            if (packageName.contains("/")) {
                String[] p = packageName.split("/", 2);
                bundle = p[0];
                ability = p[1];
            } else if (packageName.contains(":")) {
                String[] p = packageName.split(":", 2);
                // 避免把 com.xxx:xxx 误切；仅当第二段像 Ability 名（含 Ability 或首字母大写）
                if (p[1].contains("Ability") || Character.isUpperCase(p[1].charAt(0))) {
                    bundle = p[0];
                    ability = p[1];
                }
            }
        }
        if (bridge.available()) {
            return bridge.startApp(deviceId, bundle, ability);
        }
        if (!hdcClient.available()) {
            log.error("hdc / hmdriver 均不可用，无法启动应用");
            return false;
        }
        String cmd = ability != null && !ability.isBlank()
                ? "aa start -a " + ability + " -b " + bundle
                : "aa start -b " + bundle;
        HdcClient.ExecResult r = hdcClient.exec(60, "-t", deviceId, "shell", cmd);
        return r.ok();
    }

    @Override
    public boolean click(String deviceId, int x, int y) {
        if (bridge.available()) {
            return bridge.click(deviceId, x, y);
        }
        // 兜底：uitest uiInput
        if (hdcClient.available()) {
            String out = hdcClient.shell(deviceId, "uitest uiInput click " + x + " " + y);
            return out == null || !out.toLowerCase().contains("error");
        }
        return false;
    }

    @Override
    public boolean clickElement(String deviceId, String selector) {
        if (selector != null) {
            String s = selector.trim();
            if (s.startsWith("coordinate:") || s.startsWith("tap:")) {
                String coord = s.substring(s.indexOf(':') + 1).trim();
                String[] xy = coord.split("[,\\s]+");
                if (xy.length >= 2) {
                    try {
                        return click(deviceId, Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()));
                    } catch (NumberFormatException e) {
                        log.warn("鸿蒙坐标点击解析失败: {}", selector);
                        return false;
                    }
                }
            }
        }
        return bridge.available() && bridge.clickElement(deviceId, selector);
    }

    @Override
    public boolean swipe(String deviceId, int x1, int y1, int x2, int y2, int duration) {
        if (bridge.available()) {
            return bridge.swipe(deviceId, x1, y1, x2, y2, duration);
        }
        if (hdcClient.available()) {
            hdcClient.shell(deviceId, String.format(
                    "uitest uiInput swipe %d %d %d %d %d", x1, y1, x2, y2, Math.max(duration, 100)));
            return true;
        }
        return false;
    }

    @Override
    public boolean inputText(String deviceId, String text) {
        return bridge.available() && bridge.inputText(deviceId, text);
    }

    @Override
    public boolean inputTextToElement(String deviceId, String selector, String text) {
        if (bridge.available()) {
            return bridge.inputTextToElement(deviceId, selector, text);
        }
        return false;
    }

    @Override
    public String takeScreenshot(String deviceId, String savePath) {
        if (bridge.available()) {
            return bridge.screenshot(deviceId, savePath);
        }
        if (hdcClient.available()) {
            // 设备侧截图再拉回
            String remote = "/data/local/tmp/msb_shot.jpeg";
            hdcClient.shell(deviceId, "snapshot_display -f " + remote);
            HdcClient.ExecResult r = hdcClient.exec(60, "-t", deviceId, "file", "recv", remote, savePath);
            return r.ok() ? savePath : null;
        }
        return null;
    }

    @Override
    public String getPageSource(String deviceId) {
        return bridge.available() ? bridge.dumpHierarchy(deviceId) : null;
    }

    @Override
    public boolean waitForElement(String deviceId, String selector, int timeoutSeconds) {
        return bridge.available() && bridge.waitForElement(deviceId, selector, timeoutSeconds);
    }

    @Override
    public boolean isElementPresent(String deviceId, String selector) {
        return bridge.available() && bridge.isElementPresent(deviceId, selector);
    }

    @Override
    public String getElementText(String deviceId, String selector) {
        String pageSource = getPageSource(deviceId);
        if (pageSource == null) {
            return null;
        }
        if (selector != null && selector.startsWith("text:")) {
            String text = selector.substring(5);
            if (pageSource.contains(text)) {
                return text;
            }
        }
        return null;
    }

    @Override
    public String getElementAttribute(String deviceId, String selector, String attribute) {
        return null;
    }
}
