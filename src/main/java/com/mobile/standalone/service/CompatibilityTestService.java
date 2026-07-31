package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 兼容性测试：使用 Appium 真实启动应用、截图，并收集设备/会话数据作为结构化结果。
 * 需 appium.enabled=true，以获取真实会话能力数据（设备型号、系统版本等）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompatibilityTestService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PlatformRouter platformRouter;
    private final ApplicationMapper applicationMapper;

    /**
     * 执行兼容性测试：Appium 启动应用、截图，并产出结构化结果数据。
     * @return 含截图路径与 resultData JSON 的 DTO，供 TaskService 写入 task_executions
     */
    public CompatibilityTestResult runCompatibilityTest(Task task, Device device) {
        log.info("兼容性测试开始(Appium): taskId={}, deviceId={}, appId={}, platform={}",
                task.getId(), device.getId(), task.getAppId(), device.getPlatform());

        Application app = applicationMapper.findById(task.getAppId());
        if (app == null) {
            throw new RuntimeException("应用不存在: " + task.getAppId() + "，请选择已安装的应用");
        }
        String packageOrBundle = app.getPackageName();
        if (packageOrBundle == null || packageOrBundle.isBlank()) {
            throw new RuntimeException("应用未配置 packageName/bundleId: " + task.getAppId());
        }

        PlatformDriver driver = platformRouter.getDriver(device);
        if (!driver.launchApp(device.getId(), packageOrBundle)) {
            throw new RuntimeException("启动应用失败: " + packageOrBundle);
        }

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("兼容性测试被中断", e);
        }

        String screenshotPath = "/tmp/screenshots/compatibility_" + task.getId() + "_" + System.currentTimeMillis() + ".png";
        String savedPath = driver.takeScreenshot(device.getId(), screenshotPath);
        if (savedPath != null) {
            log.info("兼容性测试截图已保存: {}", savedPath);
        }

        Map<String, Object> sessionInfo = driver.getSessionInfo(device.getId());
        String resultDataJson = buildResultData(task, device, app, true, savedPath, sessionInfo);
        return new CompatibilityTestResult(savedPath, resultDataJson);
    }

    private String buildResultData(Task task, Device device, Application app,
                                   boolean launchSuccess, String screenshotPath,
                                   Map<String, Object> sessionInfo) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", task.getId());
            data.put("completedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            data.put("deviceId", device.getId());
            data.put("deviceName", device.getName());
            data.put("platform", device.getPlatform() != null ? device.getPlatform().name() : null);
            data.put("deviceVersion", device.getVersion());
            data.put("appId", app.getId());
            data.put("appName", app.getName());
            data.put("packageName", app.getPackageName());
            data.put("appVersion", app.getVersion());
            data.put("launchSuccess", launchSuccess);
            data.put("screenshotPath", screenshotPath);
            if (sessionInfo != null && !sessionInfo.isEmpty()) {
                data.put("sessionInfo", sessionInfo);
            }
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("序列化兼容性结果失败", e);
            return "{}";
        }
    }

    @Data
    public static class CompatibilityTestResult {
        private final String reportPath;
        private final String resultData;
    }
}
