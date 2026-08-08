package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.AppPackageInstallService;
import com.mobile.standalone.service.AppiumServerManager;
import com.mobile.standalone.service.UiFlowCancelledException;
import com.mobile.standalone.service.UiFlowExecutionException;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.keyword.FlowRuntime;
import com.mobile.standalone.service.keyword.KeywordContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试用流程运行时：记录嵌套执行了哪些关键字，可模拟元素缺失与执行中被停止。
 */
class FakeRuntime implements FlowRuntime {

    /** 嵌套步骤实际执行到的 keywordCode，按顺序 */
    final List<String> executedCodes = new ArrayList<>();
    /** 这些 elementKey 视为元素库里不存在 */
    final Set<String> unknownElements = new HashSet<>();
    /** 大于 0 时，第 N 次取消检查后开始返回「已取消」 */
    int cancelAfterChecks = -1;
    /** 默认 xpath:；需要走「按 resource-id 从 pageSource 找节点」那条路的用例改成 "id:" */
    String selectorPrefix = "xpath:";

    private int cancelChecks = 0;

    @Override
    public List<String> requireSelectors(String platform, String uiAppId, String elementKey,
                                         java.util.Map<String, String> execParams,
                                         List<ExecutionLogEntryDto> logs, String stepName)
            throws UiFlowExecutionException {
        if (unknownElements.contains(elementKey)) {
            logs.add(ExecutionLogEntryDto.fail(stepName, "无 ACTIVE 元素: " + elementKey, elementKey));
            throw new UiFlowExecutionException("无 ACTIVE 元素: " + elementKey, logs);
        }
        return List.of(selectorPrefix + elementKey);
    }

    @Override
    public void runNestedSteps(List<Map<String, Object>> steps, KeywordContext ctx, int depth) {
        if (steps == null) {
            return;
        }
        for (Map<String, Object> step : steps) {
            Object code = step.get("keywordCode");
            if (code != null) {
                executedCodes.add(String.valueOf(code));
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> asStepList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    @Override
    public List<Map<String, Object>> parseSteps(String stepsJson) {
        return List.of();
    }

    @Override
    public void captureScreenshot(PlatformDriver driver, String deviceId, Task task,
                                  List<ExecutionLogEntryDto> logs, String label) {
        // 测试不落盘
    }

    @Override
    public void captureSettledScreenshot(PlatformDriver driver, String deviceId, Task task,
                                         List<ExecutionLogEntryDto> logs, Map<String, Object> stepParams,
                                         String label) {
        // 测试不落盘
    }

    @Override
    public void sleepCancellable(long totalMs, Task task, List<ExecutionLogEntryDto> logs)
            throws InterruptedException, UiFlowCancelledException {
        ensureNotCancelled(task, logs);
        Thread.sleep(Math.min(totalMs, 50));
    }

    @Override
    public void ensureNotCancelled(Task task, List<ExecutionLogEntryDto> logs) throws UiFlowCancelledException {
        if (cancelAfterChecks < 0) {
            return;
        }
        if (++cancelChecks > cancelAfterChecks) {
            throw new UiFlowCancelledException("流程已被用户停止", logs);
        }
    }

    @Override
    public String resolvePackage(String uiAppId, String platform, Map<String, Object> stepParams,
                                 Map<String, String> execParams) {
        return "com.example.app";
    }

    @Override
    public boolean directionalSwipe(PlatformDriver driver, String deviceId, String direction, int durationMs) {
        return true;
    }

    @Override
    public boolean adbLaunchAndroid(String deviceId, String packageName, String activity) {
        return true;
    }

    @Override
    public AppiumDriverAdapter appiumDriverAdapter() {
        return null;
    }

    @Override
    public AppiumServerManager appiumServerManager() {
        return null;
    }

    @Override
    public AppPackageInstallService appPackageInstallService() {
        return null;
    }
}
