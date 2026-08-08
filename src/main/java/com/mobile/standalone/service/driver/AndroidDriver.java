package com.mobile.standalone.service.driver;

import com.mobile.standalone.service.AutomationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Android 平台驱动实现
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AndroidDriver implements PlatformDriver {
    
    private final AutomationEngine automationEngine;
    
    @Override
    public boolean launchApp(String deviceId, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            log.error("启动 Android 应用失败: packageName 为空");
            return false;
        }
        log.info("启动 Android 应用: deviceId={}, packageName={}", deviceId, packageName);
        try {
            // 方式1: monkey 启动 LAUNCHER
            Process process = new ProcessBuilder("adb", "-s", deviceId, "shell", "monkey",
                    "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Android 应用启动成功 (monkey)");
                return true;
            }
            log.warn("monkey 启动退出码 {}，尝试 am start", exitCode);

            // 方式2: 通过 resolve-activity 解析启动 Activity，再 am start（API 25+）
            String fullComponent = resolveLauncherComponent(deviceId, packageName);
            if (fullComponent != null && !fullComponent.isEmpty()) {
                Process am = new ProcessBuilder("adb", "-s", deviceId, "shell", "am", "start", "-n", fullComponent)
                        .redirectErrorStream(true)
                        .start();
                int amExit = am.waitFor();
                if (amExit == 0) {
                    log.info("Android 应用启动成功 (am start -n {})", fullComponent);
                    return true;
                }
            }

            // 方式3: am start 尝试常见主 Activity 名称
            String[] mainActivities = { ".MainActivity", ".SplashActivity", ".LauncherActivity", ".WelcomeActivity" };
            for (String activity : mainActivities) {
                fullComponent = packageName + "/" + (activity.startsWith(".") ? packageName + activity : activity);
                Process am = new ProcessBuilder("adb", "-s", deviceId, "shell", "am", "start", "-n", fullComponent)
                        .redirectErrorStream(true)
                        .start();
                int amExit = am.waitFor();
                if (amExit == 0) {
                    log.info("Android 应用启动成功 (am start -n {})", fullComponent);
                    return true;
                }
            }
            log.error("启动 Android 应用失败: monkey 与 am start 均未成功，请检查设备连接与包名");
            return false;
        } catch (Exception e) {
            log.error("启动 Android 应用异常: deviceId={}, packageName={}", deviceId, packageName, e);
            return false;
        }
    }
    
    @Override
    public boolean click(String deviceId, int x, int y) {
        return automationEngine.click(deviceId, com.mobile.standalone.entity.Device.Platform.ANDROID, x, y);
    }
    
    @Override
    public boolean clickElement(String deviceId, String selector) {
        return automationEngine.clickElement(deviceId, com.mobile.standalone.entity.Device.Platform.ANDROID, selector);
    }
    
    @Override
    public boolean swipe(String deviceId, int x1, int y1, int x2, int y2, int duration) {
        return automationEngine.swipe(deviceId, com.mobile.standalone.entity.Device.Platform.ANDROID, x1, y1, x2, y2, duration);
    }
    
    @Override
    public boolean inputText(String deviceId, String text) {
        return automationEngine.inputText(deviceId, com.mobile.standalone.entity.Device.Platform.ANDROID, text);
    }
    
    @Override
    public boolean inputTextToElement(String deviceId, String selector, String text) {
        // 先点击元素，再输入文本
        if (clickElement(deviceId, selector)) {
            try {
                Thread.sleep(500); // 等待输入框获得焦点
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
        return automationEngine.takeScreenshot(deviceId, com.mobile.standalone.entity.Device.Platform.ANDROID, savePath);
    }
    
    @Override
    public String getPageSource(String deviceId) {
        return automationEngine.getPageSource(deviceId, com.mobile.standalone.entity.Device.Platform.ANDROID);
    }

    @Override
    public String getCurrentActivity(String deviceId) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "dumpsys", "window", "windows")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            // mCurrentFocus=Window{... u0 package/package.ActivityName} 或 package/package.ActivityName
            int idx = out.indexOf("mCurrentFocus=");
            if (idx >= 0) {
                int start = out.indexOf(" ", idx) + 1;
                if (start > 0 && start < out.length()) {
                    int end = out.indexOf("}", start);
                    if (end > start) {
                        String s = out.substring(start, end).trim();
                        int slash = s.lastIndexOf("/");
                        if (slash >= 0 && slash < s.length() - 1) {
                            return s.substring(slash + 1).trim();
                        }
                        return s;
                    }
                }
            }
        } catch (Exception e) {
            log.trace("获取当前 Activity 失败: {}", e.getMessage());
        }
        return null;
    }
    
    @Override
    public boolean waitForElement(String deviceId, String selector, int timeoutSeconds) {
        return automationEngine.waitForElement(deviceId, com.mobile.standalone.entity.Device.Platform.ANDROID, selector, timeoutSeconds);
    }
    
    @Override
    public boolean isElementPresent(String deviceId, String selector) {
        return automationEngine.isElementPresent(deviceId, com.mobile.standalone.entity.Device.Platform.ANDROID, selector);
    }
    
    @Override
    public String getElementText(String deviceId, String selector) {
        // 从页面源中提取元素文本
        String pageSource = getPageSource(deviceId);
        if (pageSource == null) {
            return null;
        }
        
        // 根据选择器类型解析
        if (selector.startsWith("text:")) {
            String text = selector.substring(5);
            // 在页面源中查找包含该文本的元素
            if (pageSource.contains("text=\"" + text + "\"")) {
                return text;
            }
        } else if (selector.startsWith("resource-id:") || selector.startsWith("id:")) {
            String resourceId = selector.contains(":") ? selector.split(":", 2)[1] : selector;
            // 从页面源中提取该元素的文本
            // 这里需要更复杂的解析逻辑
            return extractTextByResourceId(pageSource, resourceId);
        }
        
        return null;
    }

    @Override
    public String getElementAttribute(String deviceId, String selector, String attribute) {
        String pageSource = getPageSource(deviceId);
        if (pageSource == null) {
            return null;
        }
        
        // 从页面源中提取元素属性
        // 这里需要实现 XML 解析逻辑
        return extractAttributeFromPageSource(pageSource, selector, attribute);
    }
    
    private String extractTextByResourceId(String pageSource, String resourceId) {
        // 实现从页面源中根据 resource-id 提取文本
        // 使用正则表达式或 XML 解析
        String pattern = "resource-id=\"" + resourceId + "\"[^>]*text=\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(pageSource);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
    
    private String extractAttributeFromPageSource(String pageSource, String selector, String attribute) {
        // 实现从页面源中提取元素属性
        // 这里需要根据选择器找到元素，然后提取属性
        return null; // TODO: 实现属性提取
    }

    /**
     * 通过 cmd package resolve-activity 解析应用的 LAUNCHER 组件（API 25+）
     * 返回完整 component 如 "com.example.app/.MainActivity"，失败返回 null
     */
    private String resolveLauncherComponent(String deviceId, String packageName) {
        try {
            Process p = new ProcessBuilder("adb", "-s", deviceId, "shell", "cmd", "package", "resolve-activity",
                    "--brief", "-c", "android.intent.category.LAUNCHER", packageName)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            // 输出可能多行（部分机型带 priority=...），需逐行找 component
            if (out != null && !out.isBlank()) {
                for (String raw : out.trim().split("\n")) {
                    String line = raw.trim();
                    if (line.contains("/") && !line.contains("null")) {
                        String component = line.contains(":") ? line.substring(line.indexOf(':') + 1).trim() : line;
                        if (component.matches("^[a-zA-Z0-9_.]+/[.a-zA-Z0-9_]+$")) {
                            return component;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("resolve-activity 解析失败: {}", e.getMessage());
        }
        return null;
    }
}
