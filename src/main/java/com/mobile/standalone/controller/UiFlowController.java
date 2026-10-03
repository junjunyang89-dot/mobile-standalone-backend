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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 拖拽流程/组件 CRUD + 执行（创建 UI_FLOW_TEST 任务并可立即 run）。
 */
@Slf4j
@RestController
@RequestMapping("/ui-flows")
@RequiredArgsConstructor
public class UiFlowController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UiFlowService uiFlowService;
    private final TaskService taskService;
    private final TaskAppResolver taskAppResolver;
    private final com.mobile.standalone.service.AiTestCaseService aiTestCaseService;
    private final com.mobile.standalone.service.compose.AiFlowComposer aiFlowComposer;

    /**
     * 说一句话，编一条流程草稿。
     *
     * <p>产物一律 <b>enabled=false</b>，落完直接跳流程编排页让人调——
     * 省掉的是「二十步一个个拖出来、填参数、选元素」这件苦力活，不是省掉调试。
     *
     * <p>校验没过的不落库、也不返回半成品：一份自己都验不过的草稿交给人，
     * 人得逐条当校验器用，比自己编还慢。
     */
    @PostMapping("/ai-compose")
    public ResponseEntity<?> aiCompose(@RequestBody AiComposeRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "请求体不能为空"));
        }
        try {
            // 按名字传，不按位置。这个参数表被追着加过三次字段，
            // 每次按位置传参的这里都会编不过，而报错长得像类型问题，看不出是有人加了字段
            var draft = aiFlowComposer.compose(
                    com.mobile.standalone.service.compose.AiFlowComposer.Request.builder()
                            .appId(body.getAppId())
                            .platform(body.getPlatform())
                            .pageIds(body.getPageIds())
                            .intent(body.getIntent())
                            .flowName(body.getFlowName())
                            .knownVars(body.getKnownVars() == null ? java.util.Set.of()
                                    : new java.util.LinkedHashSet<>(body.getKnownVars()))
                            .environment(body.getEnvironment())
                            .catalog(body.getCatalog())
                            .releaseStage(body.getReleaseStage())
                            .build());
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("flowId", draft.flowId());
            out.put("flowName", draft.flowName());
            out.put("steps", draft.steps());
            out.put("notes", draft.notes());
            out.put("message", "已生成流程草稿（停用中），去流程编排里调通再启用");
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(java.util.Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("[ai-compose] 失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(java.util.Map.of(
                    "message", e.getMessage() != null ? e.getMessage() : "AI 编排失败"));
        }
    }

    @lombok.Data
    public static class AiComposeRequest {
        private String appId;
        private String platform;
        /** 人勾的页面，按流程走的顺序 */
        private java.util.List<String> pageIds;
        /** 一段话：这条流程要干什么 */
        private String intent;
        private String flowName;
        /** 已经有值的变量名，用来判断元素定位里的 ${x} 缺不缺 */
        private java.util.List<String> knownVars;
        /** TEST / PROD，人选定，不交给模型 */
        private String environment;
        /** AUTOMATION / ONLINE / SHARED，人选定，不交给模型 */
        private String catalog;
        /** TESTING 本版新功能 / ONLINE 已上线，人选定，不交给模型 */
        private String releaseStage;
    }

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
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean coreOnly
    ) {
        if (Boolean.TRUE.equals(forRegression)) {
            return ResponseEntity.ok(uiFlowService.listForBatch(
                    appId, platform, environment, track, Boolean.TRUE.equals(releasedPackage)));
        }
        return ResponseEntity.ok(uiFlowService.list(appId, platform, catalog, componentOnly, environment, name, coreOnly));
    }

    /**
     * 一批流程各自最近一次执行。
     * <p>
     * 自动化目录页要在列表上标「通过 / 失败 / 未跑」，不能从「挂了哪些用例」倒推——
     * 没挂用例的流程照样会跑，倒推的结果是永远「未跑」。
     */
    @GetMapping("/last-runs")
    public ResponseEntity<Map<String, Map<String, Object>>> lastRuns(
            @RequestParam(required = false) String ids) {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        if (ids == null || ids.isBlank()) {
            return ResponseEntity.ok(out);
        }
        for (String raw : ids.split(",")) {
            String id = raw == null ? "" : raw.trim();
            if (id.isEmpty() || out.containsKey(id)) continue;
            Task t = taskService.findLatestByFlowId(id);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("lastRunStatus", t == null || t.getStatus() == null ? null : t.getStatus().name());
            m.put("lastRunAt", t == null ? null : (t.getUpdatedAt() != null ? t.getUpdatedAt() : t.getCreatedAt()));
            m.put("lastRunTaskId", t == null ? null : t.getId());
            // 试跑单独一份。把试跑从质量数据里摘出去是对的，但顺手也把它从「我刚跑的那次呢」
            // 里摘没了——人在编辑器里点了试跑，界面毫无反应，等于这个功能不存在
            Task d = taskService.findLatestDebugByFlowId(id);
            m.put("lastDebugStatus", d == null || d.getStatus() == null ? null : d.getStatus().name());
            m.put("lastDebugAt", d == null ? null : (d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt()));
            m.put("lastDebugTaskId", d == null ? null : d.getId());
            out.put(id, m);
        }
        return ResponseEntity.ok(out);
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

    /** 复制流程：步骤一起带走，名字加「副本」，打开后直接改。 */
    @PostMapping("/{id}/copy")
    public ResponseEntity<?> copy(@PathVariable String id) {
        try {
            UiFlow copied = uiFlowService.copy(id);
            return copied == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(copied);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    /**
     * 跨平台克隆：按一条流程生成另一平台的流程（如 Android → iOS），步骤原样带走，
     * platform 改为目标平台——运行时按「平台 + nameEn」解析各自 locator。
     * 同时返回元素覆盖报告（已就绪 / 待定位 / 缺失），一眼看出还要补哪些元素。
     * body: {platform: "IOS", environment?: "PROD"}
     */
    @PostMapping("/{id}/clone-to-platform")
    public ResponseEntity<?> cloneToPlatform(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String platform = body.get("platform") == null ? null : String.valueOf(body.get("platform"));
        String environment = body.get("environment") == null ? null : String.valueOf(body.get("environment"));
        try {
            UiFlowService.PlatformCloneResult r = uiFlowService.cloneToPlatform(id, platform, environment);
            return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
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
     * 执行时还缺的定位变量名（编排步骤里已经填过的不会出现）。
     * <p>
     * 前端据此在执行对话框里列出输入框——编排里写好的文案变量不该再逼人填一遍。
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
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "请求体不能为空"));
        }
        // 不给 deviceId＝交给设备池排班。
        //
        // 六台设备供全组用的时候，让人自己挑一台是行不通的：挑到的那台可能正被别人跑着，
        // 于是「设备正被占用」直接失败，而人的反应是换一台再点一次——
        // 几个人同时这么干就变成互相抢。交给平台排队，空出来一台就派下去。
        boolean autoAssign = body.getDeviceId() == null || body.getDeviceId().isBlank();
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
            return ResponseEntity.ok(Map.of(
                    "taskId", created.getId(),
                    "started", runNow && !autoAssign,
                    "queued", autoAssign,
                    "message", message
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
