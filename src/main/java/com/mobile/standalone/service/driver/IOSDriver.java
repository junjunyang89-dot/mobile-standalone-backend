package com.mobile.standalone.service.driver;

import com.mobile.standalone.entity.PageObject;
import com.mobile.standalone.service.AutomationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * iOS 平台驱动实现
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IOSDriver implements PlatformDriver {
    
    private final AutomationEngine automationEngine;
    
    @Override
    public boolean launchApp(String deviceId, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            log.error("启动 iOS 应用失败: bundleId 为空");
            return false;
        }
        log.info("启动 iOS 应用: deviceId={}, bundleId={}", deviceId, packageName);
        try {
            // 方式1: idevicedebug run - 启动应用（需 libimobiledevice，对开发/调试应用有效）
            Process process = new ProcessBuilder("idevicedebug", "-u", deviceId, "run", packageName)
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("iOS 应用启动成功 (idevicedebug run)");
                return true;
            }
            log.warn("idevicedebug run 退出码 {}，尝试 idevicelaunch", exitCode);

            // 方式2: idevicelaunch start - 若已安装 idevicelaunch（对非开发应用更友好，注：不支持 -u 指定设备）
            try {
                Process launchProcess = new ProcessBuilder("idevicelaunch", "start", packageName)
                        .redirectErrorStream(true)
                        .start();
                int launchExit = launchProcess.waitFor();
                if (launchExit == 0) {
                    log.info("iOS 应用启动成功 (idevicelaunch)");
                    return true;
                }
            } catch (Exception e) {
                log.debug("idevicelaunch 不可用或失败: {}", e.getMessage());
            }

            log.error("启动 iOS 应用失败: idevicedebug 与 idevicelaunch 均未成功。请确认: 1) 已安装 libimobiledevice (brew install libimobiledevice) 2) 设备已信任此电脑 3) 应用已安装且 bundleId 正确。若为 App Store 应用，建议启用 appium.enabled=true 使用 Appium 启动");
            return false;
        } catch (Exception e) {
            log.error("启动 iOS 应用异常: deviceId={}, bundleId={}", deviceId, packageName, e);
            return false;
        }
    }
    
    @Override
    public boolean click(String deviceId, int x, int y) {
        return automationEngine.click(deviceId, com.mobile.standalone.entity.Device.Platform.IOS, x, y);
    }
    
    @Override
    public boolean clickElement(String deviceId, String selector) {
        return automationEngine.clickElement(deviceId, com.mobile.standalone.entity.Device.Platform.IOS, selector);
    }
    
    @Override
    public boolean swipe(String deviceId, int x1, int y1, int x2, int y2, int duration) {
        return automationEngine.swipe(deviceId, com.mobile.standalone.entity.Device.Platform.IOS, x1, y1, x2, y2, duration);
    }
    
    @Override
    public boolean inputText(String deviceId, String text) {
        return automationEngine.inputText(deviceId, com.mobile.standalone.entity.Device.Platform.IOS, text);
    }
    
    @Override
    public boolean inputTextToElement(String deviceId, String selector, String text) {
        if (clickElement(deviceId, selector)) {
            try {
                Thread.sleep(500);
                return inputText(deviceId, text);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
    
    @Override
    public String takeScreenshot(String deviceId, String savePath) {
        return automationEngine.takeScreenshot(deviceId, com.mobile.standalone.entity.Device.Platform.IOS, savePath);
    }
    
    @Override
    public String getPageSource(String deviceId) {
        return automationEngine.getPageSource(deviceId, com.mobile.standalone.entity.Device.Platform.IOS);
    }
    
    @Override
    public boolean waitForElement(String deviceId, String selector, int timeoutSeconds) {
        return automationEngine.waitForElement(deviceId, com.mobile.standalone.entity.Device.Platform.IOS, selector, timeoutSeconds);
    }
    
    @Override
    public boolean isElementPresent(String deviceId, String selector) {
        return automationEngine.isElementPresent(deviceId, com.mobile.standalone.entity.Device.Platform.IOS, selector);
    }
    
    @Override
    public String getElementText(String deviceId, String selector) {
        // TODO: 实现 iOS 元素文本获取
        return null;
    }
    
    @Override
    public boolean clickByPageObject(String deviceId, PageObject pageObject, String elementName) {
        if (pageObject == null || pageObject.getElements() == null) {
            return false;
        }
        
        PageObject.PageElement element = pageObject.getElements().get(elementName);
        if (element == null) {
            return false;
        }
        
        String selector = element.getSelectorType() + ":" + element.getSelector();
        return clickElement(deviceId, selector);
    }
    
    @Override
    public String getElementAttribute(String deviceId, String selector, String attribute) {
        // TODO: 实现 iOS 元素属性获取
        return null;
    }
}
