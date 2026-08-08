package com.mobile.standalone.service;

import com.mobile.standalone.entity.Task;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 非流程类场景注册（兼容性 / 安装卸载 / 界面适配）。
 * 原功能基础质量硬编码场景（login/search/settings/cs_*）已下线，统一走流程编排。
 */
@Service
public class FunctionalTestScenarioService {

    private static final List<ScenarioDefinition> SCENARIOS = List.of(
            new ScenarioDefinition("install_upgrade_android", "安装卸载升级测试(Android)", "ANDROID",
                    Task.TaskType.INSTALL_UNINSTALL_UPGRADE_TEST, "Android 应用安装、卸载与升级流程验证", null),
            new ScenarioDefinition("install_upgrade_ios", "安装卸载升级测试(iOS)", "IOS",
                    Task.TaskType.INSTALL_UNINSTALL_UPGRADE_TEST, "iOS 应用安装、卸载与升级流程验证", null),
            new ScenarioDefinition("compatibility_android", "兼容性测试(Android)", "ANDROID",
                    Task.TaskType.COMPATIBILITY_TEST, "Android 设备与应用兼容性检查", null),
            new ScenarioDefinition("compatibility_ios", "兼容性测试(iOS)", "IOS",
                    Task.TaskType.COMPATIBILITY_TEST, "iOS 设备与应用兼容性检查", null),
            new ScenarioDefinition("ui_adapt_android", "界面与适配测试(Android)", "ANDROID",
                    Task.TaskType.UI_ADAPTATION_TEST, "Android 界面展示与多分辨率适配检查", null),
            new ScenarioDefinition("ui_adapt_ios", "界面与适配测试(iOS)", "IOS",
                    Task.TaskType.UI_ADAPTATION_TEST, "iOS 界面展示与多分辨率适配检查", null)
    );

    public List<ScenarioDefinition> listScenarios() {
        return SCENARIOS;
    }

    /** 功能基础质量场景已清空（硬编码下线） */
    public List<ScenarioDefinition> listFunctionalQualityScenarios(String platform) {
        return List.of();
    }

    /** 场景化性能不再注册写死场景，统一 flowId */
    public List<ScenarioDefinition> listScenarioPerformanceScenarios(String platform) {
        return List.of();
    }

    public List<Map<String, Object>> listScenariosByPlatformModule() {
        return List.of();
    }

    public ScenarioDefinition getScenario(String id) {
        if (id == null || id.isBlank()) return null;
        return SCENARIOS.stream().filter(s -> id.equals(s.getId())).findFirst().orElse(null);
    }

    public String getScenarioDisplayName(String scenarioKey, Task.Platform platform) {
        if (scenarioKey == null || platform == null) return null;
        if ("ui_flow".equalsIgnoreCase(scenarioKey.trim())) {
            return "流程用例";
        }
        return scenarioKey + "(" + platform + ")";
    }

    public String getScenarioDisplayNameOrFallback(Task task) {
        if (task == null) return null;
        if (task.getTaskType() == Task.TaskType.UI_FLOW_TEST) {
            return "流程用例执行";
        }
        if (task.getTaskType() == Task.TaskType.FUNCTIONAL_QUALITY_TEST) {
            String key = task.getScenarioKey();
            String plat = task.getPlatform() != null ? task.getPlatform().name() : "";
            if (key != null && !key.isBlank()) {
                return key + "(" + plat + ")·已下线";
            }
            return "功能基础质量(已下线)";
        }
        Task.Platform platform = task.getPlatform();
        if (platform != null) {
            String byType = SCENARIOS.stream()
                    .filter(s -> s.getTaskType() == task.getTaskType() && platform.name().equals(s.getPlatform()))
                    .map(ScenarioDefinition::getName)
                    .findFirst()
                    .orElse(null);
            if (byType != null) return byType;
        }
        String scenarioKey = task.getScenarioKey();
        if (scenarioKey != null && !scenarioKey.isBlank() && platform != null) {
            return getScenarioDisplayName(scenarioKey.trim(), platform);
        }
        return task.getTaskType() != null ? task.getTaskType().getDisplayName() : null;
    }

    @Data
    public static class ScenarioDefinition {
        private final String id;
        private final String name;
        private final String platform;
        private final Task.TaskType taskType;
        private final String description;
        private final String scenarioKey;
    }
}
