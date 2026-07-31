package com.mobile.standalone.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    @Value("${appium.enabled:false}")
    private boolean appiumEnabled;

    @Value("${appium.server-url:http://127.0.0.1:4723}")
    private String appiumServerUrl;

    @Autowired(required = false)
    private com.mobile.standalone.service.driver.AppiumDriverAdapter appiumDriverAdapter;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        Map<String, String> response = new HashMap<>();
        response.put("version", "1.0.0");
        return ResponseEntity.ok(response);
    }

    /**
     * Appium 配置与连通性排查
     * 返回：appium.enabled、server-url、驱动是否注册、Appium Server 是否可达、截图将使用的驱动
     */
    @GetMapping("/appium")
    public ResponseEntity<Map<String, Object>> appiumDiagnostic() {
        Map<String, Object> result = new HashMap<>();
        result.put("appiumEnabled", appiumEnabled);
        result.put("appiumServerUrl", appiumServerUrl);
        result.put("appiumDriverRegistered", appiumDriverAdapter != null);
        result.put("driverMode", appiumEnabled && appiumDriverAdapter != null ? "Appium" : "ADB/idevice");
        result.put("screenshotDriver", appiumEnabled && appiumDriverAdapter != null
                ? "Appium（需 launchApp 后建立会话，截图来自 WebDriver）"
                : "ADB/idevice（adb exec-out screencap -p 或 idevicescreenshot）");

        boolean serverReachable = false;
        String serverError = null;
        if (appiumEnabled) {
            try {
                URL url = new URL(appiumServerUrl.replaceFirst("/$", "") + "/status");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                int code = conn.getResponseCode();
                serverReachable = (code >= 200 && code < 400);
                if (!serverReachable) {
                    serverError = "HTTP " + code;
                }
            } catch (Exception e) {
                serverError = e.getMessage();
            }
        }
        result.put("appiumServerReachable", serverReachable);
        if (serverError != null) {
            result.put("appiumServerError", serverError);
        }

        result.put("summary", buildSummary(appiumEnabled, appiumDriverAdapter != null, serverReachable));
        return ResponseEntity.ok(result);
    }

    private static String buildSummary(boolean enabled, boolean driverRegistered, boolean serverReachable) {
        if (!enabled) {
            return "appium.enabled=false，使用 ADB/idevice 驱动。截图由 adb screencap 或 idevicescreenshot 完成，需设备已连接。";
        }
        if (!driverRegistered) {
            return "appium.enabled 可能被覆盖为 false，或 AppiumDriverAdapter 未加载。请检查 application.yml 与启动日志。";
        }
        if (!serverReachable) {
            return "Appium Server 不可达。请启动 Appium（如 appium -a 127.0.0.1 -p 4723），并确认 server-url 正确。";
        }
        return "Appium 已启用且 Server 可达。登录/设置测试将使用 Appium 驱动，截图来自 WebDriver（需 launchApp 建立会话后有效）。";
    }
}
