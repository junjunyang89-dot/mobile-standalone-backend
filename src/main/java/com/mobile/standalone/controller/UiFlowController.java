package com.mobile.standalone.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.util.TestEnvironment;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.service.FlowDependencyResolver;
import com.mobile.standalone.service.TaskAppResolver;
import com.mobile.standalone.service.TaskService;
import com.mobile.standalone.service.UiFlowService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 拖拽流程/组件 CRUD + 执行（创建 UI_FLOW_TEST 任务并可立即 run）。
 */
@RestController
@RequestMapping("/ui-flows")
@RequiredArgsConstructor
public class UiFlowController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UiFlowService uiFlowService;
    private final TaskService taskService;
    private final TaskAppResolver taskAppResolver;
    private final com.mobile.standalone.service.AiTestCaseService aiTestCaseService;

    /**
     * 勾了这批流程，还缺哪些前置。
     * <p>
     * 回归轨<b>刻意不自动补</b>：批次有人盯着、顺序是刻意排的，静默塞进两条流程会让人
     * 发现「我勾了 12 条怎么跑了 14 条」。但也不能不管——忘勾「切环境」这种前置，
     * 后面十几条会全部失败，而失败现象看着像功能 bug（数据不对、找不到元素），
     * 没人会联想到是少跑了第一步。
     * <p>
     * 所以：当场告诉人缺什么，补不补由人定。
     */
    @PostMapping("/resolve-dependencies")
    public ResponseEntity<?> resolveDependencies(@RequestBody Map<String, List<String>> body) {
        List<String> ids = body == null ? List.of() : body.getOrDefault("flowIds", List.of());
        try {
            FlowDependencyResolver.Result r = FlowDependencyResolver.resolve(ids, uiFlowService::get);
            List<Map<String, String>> missing = new java.util.ArrayList<>();
            for (String id : r.autoAdded()) {
                UiFlow f = uiFlowService.get(id);
                missing.add(Map.of("id", id, "name", f == null || f.getName() == null ? id : f.getName()));
            }
            return ResponseEntity.ok(Map.of(
                    "orderedIds", r.orderedIds(),
                    "missing", missing));
        } catch (FlowDependencyResolver.CycleException e) {
            // 成环时把完整环路给出来——只说「依赖成环」人得自己去一条条翻
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ---------- 这条流程覆盖了哪些用例 ----------
    //
    // 真相存在用例上（ai_test_cases.flow_id），这里只是换了个编辑入口：
    // 写完用例去建流程时顺手勾选，比建完再回用例列表一条条挂上去顺手得多。
    // 不在 ui_flows 上再存一份，那样两边就能不一致。

    @GetMapping("/{id}/cases")
    public ResponseEntity<?> casesOfFlow(@PathVariable String id) {
        return ResponseEntity.ok(aiTestCaseService.casesOfFlow(id));
    }

    /** 候选用例：同平台、且没被别的流程占用 */
    @GetMapping("/{id}/linkable-cases")
    public ResponseEntity<?> linkableCases(@PathVariable String id,
                                           @RequestParam(required = false) String platform,
                                           @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(aiTestCaseService.linkableCases(id, platform, keyword));
    }

    @PutMapping("/{id}/cases")
    public ResponseEntity<?> setFlowCases(@PathVariable String id,
                                          @RequestBody Map<String, List<Long>> body) {
        try {
            return ResponseEntity.ok(aiTestCaseService.setFlowCases(id, body.get("caseIds")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<UiFlow>> list(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) Boolean componentOnly,
            @RequestParam(required = false) Boolean forRegression,
            /**
             * 环境（PROD / TEST）。回归勾选列表缺省按生产，存量前端行为不变；
             * 普通列表缺省不过滤，等于「全部环境」。
             */
            @RequestParam(required = false) String environment,
            /** 批量执行候选：REGRESSION（默认）/ AUTOMATION，与 forRegression 配合用 */
            @RequestParam(required = false) String track,
            /**
             * true＝跑已发布的线上包（上个版本），只列「已上线」的流程。
             * 本版新功能的流程在那个包里没有对应界面，跑上去必然失败——
             * 那种红跟质量无关，只会把真问题淹掉。
             */
            @RequestParam(required = false) Boolean releasedPackage,
            /** 流程名称模糊搜索 */
            @RequestParam(required = false) String name
    ) {
        if (Boolean.TRUE.equals(forRegression)) {
            return ResponseEntity.ok(uiFlowService.listForBatch(
                    appId, platform, environment, track, Boolean.TRUE.equals(releasedPackage)));
        }
        return ResponseEntity.ok(uiFlowService.list(appId, platform, catalog, componentOnly, environment, name));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiFlow> get(@PathVariable String id) {
        UiFlow f = uiFlowService.get(id);
        return f == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(f);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UiFlow body) {
        try {
            return ResponseEntity.ok(uiFlowService.create(body));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UiFlow body) {
        try {
            UiFlow updated = uiFlowService.update(id, body);
            return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return uiFlowService.delete(id) ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.notFound().build();
    }

    /**
     * 这条流程用到的定位变量名。
     * <p>
     * 前端据此在执行对话框里自动列出输入框——否则人得记住哪条流程要填什么，
     * 忘了就是一次「元素未找到」的白跑。
     */
    @GetMapping("/{id}/locator-variables")
    public ResponseEntity<List<String>> locatorVariables(@PathVariable String id) {
        UiFlow flow = uiFlowService.get(id);
        if (flow == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(uiFlowService.collectLocatorVariables(flow));
    }

    /**
     * 检查跨环境可用性：扫出步骤里写死的手机号 / 赛事 ID / 按文案定位。
     * <p>
     * 只报不改——参数里那串数字是手机号还是订单号只有人知道。
     */
    @GetMapping("/{id}/env-data-scan")
    public ResponseEntity<?> envDataScan(@PathVariable String id) {
        UiFlow flow = uiFlowService.get(id);
        if (flow == null) {
            return ResponseEntity.notFound().build();
        }
        List<com.mobile.standalone.service.FlowDataScanner.Hit> hits = uiFlowService.scanEnvData(id);
        return ResponseEntity.ok(Map.of(
                "flowId", id,
                "flowName", flow.getName() == null ? "" : flow.getName(),
                "hits", hits,
                "summary", hits.isEmpty()
                        ? "没扫到写死的环境数据，这条流程换个环境可以直接跑"
                        : "扫到 " + hits.size() + " 处写死的环境数据，换环境跑之前先改掉"));
    }

    /**
     * 执行流程：创建 UI_FLOW_TEST 任务（写入测试任务列表），默认立即 run。
     * 应用来源（勿写死）：请求 appId / packageName → 流程绑定 appId；包名可执行时覆盖。
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<?> execute(@PathVariable String id, @RequestBody ExecuteFlowRequest body) {
        UiFlow flow = uiFlowService.get(id);
        if (flow == null) {
            return ResponseEntity.notFound().build();
        }
        if (body == null || body.getDeviceId() == null || body.getDeviceId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "deviceId 必填"));
        }
        try {
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
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "请选择应用字典，或填写要启动的包名/BundleId"));
            }

            Application installed = taskAppResolver.resolveForTask(
                    uiAppId, body.getDeviceId().trim(), platform, packageName);

            ObjectNode config = JSON.createObjectNode();
            config.put("flowId", id);
            // 本页单跑＝自动化编排轨。用例的「最近执行状态」只认这条轨，
            // 回归批次跑的是生产包，结果不能贴给提测用例
            config.put("runTrack", RunTrack.AUTOMATION);
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
            task.setDeviceId(body.getDeviceId().trim());
            task.setAppId(installed.getId());
            task.setConfig(JSON.writeValueAsString(config));
            task.setScenarioKey("ui_flow");
            task.setStatus(Task.TaskStatus.PENDING);

            Task created = taskService.createTask(task);
            boolean runNow = body.getRunNow() == null || Boolean.TRUE.equals(body.getRunNow());
            boolean started = false;
            if (runNow) {
                started = taskService.runTask(created.getId());
            }
            return ResponseEntity.ok(Map.of(
                    "taskId", created.getId(),
                    "task", created,
                    "started", started,
                    "message", started ? "已创建任务并开始执行" : "已创建任务（未自动执行）"
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "执行失败"));
        }
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
        /**
         * 执行环境：TEST / PROD，缺省 PROD。
         * <p>
         * 包名两个环境一样、元素定位也通用，环境只决定「装的是哪个构建、用哪套验证码」，
         * 以及报告和归因上怎么标——所以它是执行参数，不是流程属性，流程不用分两份维护。
         */
        private String environment;
    }
}
