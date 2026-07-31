package com.mobile.standalone.controller;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FunctionalTestExecutionResult;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.SettingsTestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 设置自动化测试接口（与 automation-service-standalone 的 AndroidSettingsTestController / IOSSettingsTestController 对齐）。
 * 直接调用即执行：先登录再进入设置页并截图，返回步骤列表。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SettingsTestController {

    private final DeviceMapper deviceMapper;
    private final ApplicationMapper applicationMapper;
    private final SettingsTestService settingsTestService;

    /**
     * 执行 Android 设置测试
     * 请求体：deviceId（必填）, applicationId（选填）, packageName（选填，与 applicationId 二选一）, appiumServerUrl（选填）
     */
    @PostMapping("/android-settings-test/execute")
    public ResponseEntity<Map<String, Object>> executeAndroidSettingsTest(@RequestBody Map<String, String> request) {
        String deviceId = request.getOrDefault("deviceId", "").trim();
        if (deviceId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "缺少 deviceId", "data", null));
        }
        return doExecuteSettingsTest(request, deviceId, Task.Platform.ANDROID, "packageName");
    }

    /**
     * 执行 iOS 设置测试
     * 请求体：deviceId（必填）, applicationId（选填）, bundleId（选填，与 applicationId 二选一）, appiumServerUrl（选填）
     */
    @PostMapping("/ios-settings-test/execute")
    public ResponseEntity<Map<String, Object>> executeIosSettingsTest(@RequestBody Map<String, String> request) {
        String deviceId = request.getOrDefault("deviceId", "").trim();
        if (deviceId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "缺少 deviceId", "data", null));
        }
        return doExecuteSettingsTest(request, deviceId, Task.Platform.IOS, "bundleId");
    }

    private ResponseEntity<Map<String, Object>> doExecuteSettingsTest(
            Map<String, String> request, String deviceId, Task.Platform platform, String packageKey) {
        Map<String, Object> response = new HashMap<>();
        try {
            Device device = deviceMapper.findById(deviceId);
            if (device == null) {
                response.put("code", 404);
                response.put("message", "设备不存在: " + deviceId);
                response.put("data", null);
                return ResponseEntity.status(404).body(response);
            }
            if (device.getPlatform() == null || !device.getPlatform().name().equals(platform.name())) {
                response.put("code", 400);
                response.put("message", "设备平台与接口不一致，当前设备: " + (device.getPlatform() != null ? device.getPlatform() : "未知"));
                response.put("data", null);
                return ResponseEntity.badRequest().body(response);
            }

            Application app = resolveApplication(request, deviceId, platform, packageKey);
            if (app == null) {
                response.put("code", 400);
                response.put("message", "请提供 applicationId 或 " + packageKey + "（且该应用已在该设备的应用列表中）");
                response.put("data", null);
                return ResponseEntity.badRequest().body(response);
            }

            Task task = new Task();
            task.setId("settings_test_" + System.currentTimeMillis());
            task.setName(platform == Task.Platform.ANDROID ? "Android设置测试" : "iOS设置测试");
            task.setDeviceId(deviceId);
            task.setAppId(app.getId());
            task.setPlatform(platform);
            task.setTaskType(Task.TaskType.FUNCTIONAL_QUALITY_TEST);
            task.setScenarioKey("settings");
            task.setStatus(Task.TaskStatus.PENDING);

            log.info("开始执行{}设置测试，设备ID: {}, 应用: {}", platform, deviceId, app.getPackageName());
            FunctionalTestExecutionResult result = settingsTestService.executeSettingsTest(task, device);

            List<Map<String, Object>> testSteps = toTestSteps(result.getExecutionLog());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", task.getId());
            data.put("deviceId", deviceId);
            data.put("testCaseName", task.getName());
            data.put("testSteps", testSteps);
            data.put("reportPath", result.getReportPath());

            response.put("code", 200);
            response.put("message", (platform == Task.Platform.ANDROID ? "Android" : "iOS") + "设置测试执行成功");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("设置测试执行失败", e);
            response.put("code", 500);
            response.put("message", "设置测试执行失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            response.put("data", null);
            return ResponseEntity.status(500).body(response);
        }
    }

    private Application resolveApplication(Map<String, String> request, String deviceId, Task.Platform platform, String packageKey) {
        String applicationId = request.getOrDefault("applicationId", "").trim();
        if (!applicationId.isEmpty()) {
            Application app = applicationMapper.findById(applicationId);
            if (app != null && deviceId.equals(app.getDeviceId())) return app;
        }
        String packageOrBundle = request.getOrDefault(packageKey, "").trim();
        if (packageOrBundle.isEmpty()) return null;
        List<Application> list = applicationMapper.findByDeviceId(deviceId);
        return list.stream()
                .filter(a -> packageOrBundle.equals(a.getPackageName()))
                .findFirst()
                .orElse(null);
    }

    private static List<Map<String, Object>> toTestSteps(List<ExecutionLogEntryDto> log) {
        if (log == null) return Collections.emptyList();
        return log.stream().map(entry -> {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepName", entry.getStep());
            step.put("status", "success".equals(entry.getStatus()) ? "PASSED" : "FAILED");
            step.put("errorMessage", "fail".equals(entry.getStatus()) ? entry.getMessage() : null);
            return step;
        }).collect(Collectors.toList());
    }

    @GetMapping("/android-settings-test/health")
    public ResponseEntity<Map<String, Object>> androidHealth() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "Android Settings Test Controller", "timestamp", System.currentTimeMillis()));
    }

    @GetMapping("/ios-settings-test/health")
    public ResponseEntity<Map<String, Object>> iosHealth() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "iOS Settings Test Controller", "timestamp", System.currentTimeMillis()));
    }
}
