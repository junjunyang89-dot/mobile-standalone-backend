package com.mobile.standalone.service.keyword;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.AppPackageInstallService;
import com.mobile.standalone.service.AppiumServerManager;
import com.mobile.standalone.service.UiFlowExecutionException;
import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.driver.PlatformDriver;

import java.util.List;
import java.util.Map;

/**
 * 关键字执行时可回调的流程能力，由 {@code UiFlowExecutor} 实现。
 * <p>
 * 关键字实现只依赖这个接口，不反向依赖执行器本身——嵌套步骤、元素解析、截图、可取消等待
 * 这些跨关键字的公共能力仍然只有一份实现。
 */
public interface FlowRuntime {

    /** 主定位 + 备用定位，按顺序尝试；解析不到时记日志并抛出 */
    List<String> requireSelectors(String platform, String uiAppId, String elementKey,
                                  Map<String, String> execParams,
                                  List<ExecutionLogEntryDto> logs, String stepName)
            throws UiFlowExecutionException;

    /** 执行嵌套步骤（IF 分支、组件调用） */
    void runNestedSteps(List<Map<String, Object>> steps, KeywordContext ctx, int depth) throws Exception;

    /** 把任意 JSON / List 结构解析成步骤列表 */
    List<Map<String, Object>> asStepList(Object raw) throws Exception;

    /** 解析流程的 steps 字段 */
    List<Map<String, Object>> parseSteps(String stepsJson) throws Exception;

    /** 单张步骤截图 */
    void captureScreenshot(PlatformDriver driver, String deviceId, Task task,
                           List<ExecutionLogEntryDto> logs, String label);

    /**
     * 等页面稳定后拍一张最终态，供人工/AI 复核。
     * 步骤显式配了 screenshotRounds 时退回连拍。
     */
    void captureSettledScreenshot(PlatformDriver driver, String deviceId, Task task,
                                  List<ExecutionLogEntryDto> logs, Map<String, Object> stepParams,
                                  String label);

    /** 可被「停止」打断的等待 */
    void sleepCancellable(long totalMs, Task task, List<ExecutionLogEntryDto> logs) throws Exception;

    /** 用户已请求停止时立即中断 */
    void ensureNotCancelled(Task task, List<ExecutionLogEntryDto> logs) throws UiFlowExecutionException;

    /** 包名解析：步骤参数 → 执行参数 → 应用字典 */
    String resolvePackage(String uiAppId, String platform, Map<String, Object> stepParams,
                          Map<String, String> execParams);

    /** 按方向滑动（屏幕比例计算） */
    boolean directionalSwipe(PlatformDriver driver, String deviceId, String direction, int durationMs);

    /** ADB 直启，Appium 不可用时的 Android 回退 */
    boolean adbLaunchAndroid(String deviceId, String packageName, String activity);

    /** Appium 适配器，未启用 Appium 时为 null */
    AppiumDriverAdapter appiumDriverAdapter();

    AppiumServerManager appiumServerManager();

    AppPackageInstallService appPackageInstallService();
}
