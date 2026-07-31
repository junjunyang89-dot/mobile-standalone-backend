package com.mobile.standalone.service.driver;

import com.mobile.standalone.entity.PageObject;
import com.mobile.standalone.service.AutomationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HarmonyOS 平台驱动实现
 * HarmonyOS 使用与 Android 类似的 ADB 命令
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HarmonyOSDriver implements PlatformDriver {
    
    private final AutomationEngine automationEngine;
    
    @Override
    public boolean launchApp(String deviceId, String packageName) {
        log.info("启动 HarmonyOS 应用: deviceId={}, packageName={}", deviceId, packageName);
        try {
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "monkey", 
                    "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.error("启动 HarmonyOS 应用失败", e);
            return false;
        }
    }
    
    @Override
    public boolean click(String deviceId, int x, int y) {
        return automationEngine.click(deviceId, com.mobile.standalone.entity.Device.Platform.HARMONYOS, x, y);
    }
    
    @Override
    public boolean clickElement(String deviceId, String selector) {
        return automationEngine.clickElement(deviceId, com.mobile.standalone.entity.Device.Platform.HARMONYOS, selector);
    }
    
    @Override
    public boolean swipe(String deviceId, int x1, int y1, int x2, int y2, int duration) {
        return automationEngine.swipe(deviceId, com.mobile.standalone.entity.Device.Platform.HARMONYOS, x1, y1, x2, y2, duration);
    }
    
    @Override
    public boolean inputText(String deviceId, String text) {
        return automationEngine.inputText(deviceId, com.mobile.standalone.entity.Device.Platform.HARMONYOS, text);
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
        return automationEngine.takeScreenshot(deviceId, com.mobile.standalone.entity.Device.Platform.HARMONYOS, savePath);
    }
    
    @Override
    public String getPageSource(String deviceId) {
        return automationEngine.getPageSource(deviceId, com.mobile.standalone.entity.Device.Platform.HARMONYOS);
    }
    
    @Override
    public boolean waitForElement(String deviceId, String selector, int timeoutSeconds) {
        return automationEngine.waitForElement(deviceId, com.mobile.standalone.entity.Device.Platform.HARMONYOS, selector, timeoutSeconds);
    }
    
    @Override
    public boolean isElementPresent(String deviceId, String selector) {
        return automationEngine.isElementPresent(deviceId, com.mobile.standalone.entity.Device.Platform.HARMONYOS, selector);
    }
    
    @Override
    public String getElementText(String deviceId, String selector) {
        String pageSource = getPageSource(deviceId);
        if (pageSource == null) {
            return null;
        }
        
        // 类似 Android 的实现
        if (selector.startsWith("text:")) {
            String text = selector.substring(5);
            if (pageSource.contains("text=\"" + text + "\"")) {
                return text;
            }
        }
        
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
        // TODO: 实现 HarmonyOS 元素属性获取
        return null;
    }
}
