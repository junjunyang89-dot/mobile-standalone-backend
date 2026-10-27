package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.util.TestEnvironment;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 执行一条流程：建 UI_FLOW_TEST 任务，指定了设备就直接起跑，没指定就交给设备池排队。
 * <p>
 * 页面上的「执行」和对外开放接口都走这里——两边各写一份的话，
 * 迟早出现「页面上点能跑、外部下发的跑不起来」这种只在一边复现的问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UiFlowExecuteService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskService taskService;
    private final TaskAppResolver taskAppResolver;

    public record Submitted(String taskId, boolean started, boolean queued, String message) {
    }

    /**
     * @throws IllegalArgumentException 入参不完整（缺应用 / 包名等），调用方按 400 处理
     */
    public Submitted submit(UiFlow flow, ExecuteFlowRequest body) throws Exception {
        String id = flow.getId();
        // 不给 deviceId＝交给设备池排班。
        //
        // 六台设备供全组用的时候，让人自己挑一台是行不通的：挑到的那台可能正被别人跑着，
        // 于是「设备正被占用」直接失败，而人的反应是换一台再点一次——
        // 几个人同时这么干就变成互相抢。交给平台排队，空出来一台就派下去。
        boolean autoAssign = body.getDeviceId() == null || body.getDeviceId().isBlank();
        Task.Platform platform = body.getPlatform();
        if (platform == null) {
            if (flow.getPlatform() != null && !flow.getPlatform().isBlank()) {
                platform = Task.Platform.valueOf(flow.getPlatform().trim().toUpperCase());
            } else {
                platform = Task.Platform.ANDROID;
            }
        }

        String uiAppId = blankToNull(body.getAppId());
        if (uiAppId == null) {
            uiAppId = blankToNull(flow.getAppId());
        }
        String packageName = blankToNull(body.getPackageName());
        String activity = blankToNull(body.getActivity());
        if (uiAppId == null && packageName == null) {
            throw new IllegalArgumentException("请选择应用字典，或填写要启动的包名/BundleId");
        }

        // 排班的任务这会儿还不知道会落到哪台设备上，而应用是按设备记录的，
        // 所以解析推迟到 DeviceTaskScheduler 派到设备之后
        Application installed = autoAssign ? null : taskAppResolver.resolveForTask(
                uiAppId, body.getDeviceId().trim(), platform, packageName);

        ObjectNode config = JSON.createObjectNode();
        config.put("flowId", id);
        // 轨道由调用方声明：编辑器里的「试跑」传 DEBUG，正式单跑不传（默认自动化编排轨）。
        // 试跑的结果不进「最近结果」、不算通过率、不告警——调试的失败不是质量数据。
        // 用例的「最近执行状态」只认自动化轨，回归批次跑的是生产包，结果不能贴给提测用例
        config.put("runTrack", RunTrack.isDebug(body.getTrack())
                ? RunTrack.DEBUG : RunTrack.AUTOMATION);
        if (uiAppId != null) {
            config.put("uiAppId", uiAppId);
        }
        if (packageName != null) {
            config.put("packageName", packageName);
        }
        if (activity != null) {
            config.put("activity", activity);
        }
        if (body.getParams() != null && !body.getParams().isEmpty()) {
            config.set("params", JSON.valueToTree(body.getParams()));
        }
        String environment = TestEnvironment.normalize(body.getEnvironment());
        config.put("environment", environment);

        if (autoAssign) {
            // 派给谁的条件写进 config，调度器据此挑设备
            ObjectNode pool = config.putObject("devicePool");
            pool.put("platform", platform.name());
            // 模拟器默认只接试跑、且只在测试环境。
            // 这个默认值刚好对上实际用法：调试拿模拟器凑合，正式跑要真机结果才作数。
            // 前端可以显式覆盖，但生产环境那一条在调度器里还会再兜一次
            boolean debugRun = RunTrack.isDebug(body.getTrack());
            boolean allowEmulator = body.getAllowEmulator() != null
                    ? body.getAllowEmulator()
                    : (debugRun && TestEnvironment.isTest(environment));
            pool.put("allowEmulator", allowEmulator && TestEnvironment.isTest(environment));
        }

        Task task = new Task();
        String name = body.getTaskName();
        if (name == null || name.isBlank()) {
            // 环境写进任务名：两个环境的报告混在一列时，不点开也能分清
            name = "流程" + TestEnvironment.tag(environment) + "-"
                    + (flow.getName() != null ? flow.getName() : id);
        }
        task.setName(name);
        task.setDescription(flow.getDescription());
        task.setTaskType(Task.TaskType.UI_FLOW_TEST);
        task.setPlatform(platform);
        task.setDeviceId(autoAssign ? null : body.getDeviceId().trim());
        task.setAppId(installed != null ? installed.getId() : null);
        task.setConfig(JSON.writeValueAsString(config));
        task.setScenarioKey("ui_flow");
        task.setStatus(Task.TaskStatus.PENDING);

        long t0 = System.currentTimeMillis();
        Task created = taskService.createTask(task);
        boolean runNow = body.getRunNow() == null || Boolean.TRUE.equals(body.getRunNow());
        // 排班的任务不在这里起跑：设备还没定，由 DeviceTaskScheduler 挑到设备后再拉起
        if (runNow && !autoAssign) {
            taskService.enqueueRun(created.getId());
        }
        log.info("执行流程已受理: flowId={}, taskId={}, autoAssign={}, runNow={}, {}ms",
                id, created.getId(), autoAssign, runNow, System.currentTimeMillis() - t0);
        String message;
        if (autoAssign) {
            message = "已排队，等到有空闲设备就自动开跑";
        } else {
            message = runNow ? "已创建任务并开始执行" : "已创建任务（未自动执行）";
        }
        return new Submitted(created.getId(), runNow && !autoAssign, autoAssign, message);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }

    @Data
    public static class ExecuteFlowRequest {
        private String deviceId;
        private Task.Platform platform;
        /** 应用字典 id；可与 packageName 二选一或同时传（包名优先生效于启动） */
        private String appId;
        /** 自定义包名 / BundleId，不在字典里的应用可直接填 */
        private String packageName;
        /** Android 可选启动 Activity */
        private String activity;
        private String taskName;
        private Map<String, String> params;
        private Boolean runNow;
        /** 排班时允不允许派给模拟器；不填按「试跑+测试环境才允许」 */
        private Boolean allowEmulator;
        /**
         * 传 {@code DEBUG} 表示这是编辑器里的试跑：结果不进「最近结果」、不算通过率、不告警。
         * 不传＝正式单跑，按自动化编排轨记。
         */
        private String track;
        /**
         * 执行环境：TEST / PROD，缺省 PROD。
         * <p>
         * 包名两个环境一样、元素定位也通用，环境只决定「装的是哪个构建、用哪套验证码」，
         * 以及报告和归因上怎么标——所以它是执行参数，不是流程属性，流程不用分两份维护。
         */
        private String environment;
    }
}
