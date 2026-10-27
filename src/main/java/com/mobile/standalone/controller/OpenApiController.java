package com.mobile.standalone.controller;

import com.mobile.standalone.auth.OpenApiAuth;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.UiApp;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.service.DeviceService;
import com.mobile.standalone.service.TaskService;
import com.mobile.standalone.service.UiAppService;
import com.mobile.standalone.service.UiFlowService;
import com.mobile.standalone.service.openapi.OpenRunService;
import com.mobile.standalone.service.openapi.OpenRunStatus;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对外开放接口：给其它管理平台查用例、下发 APP UI 自动化执行、查结果。
 * <p>
 * 鉴权在 {@link com.mobile.standalone.auth.AuthFilter} 里按 API Key 做；
 * 返回字段是对外契约，<b>只加不改</b>——对方平台按字段名解析，改名就是线上故障。
 */
@Slf4j
@RestController
@RequestMapping("/open/v1")
@RequiredArgsConstructor
public class OpenApiController {

    private final UiAppService uiAppService;
    private final UiFlowService uiFlowService;
    private final TaskService taskService;
    private final DeviceService deviceService;
    private final OpenRunService runService;

    @GetMapping("/apps")
    public ResponseEntity<?> apps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UiApp a : uiAppService.list(true)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("appId", a.getId());
            m.put("name", a.getNameZh());
            m.put("androidPackage", a.getAndroidPackage());
            m.put("iosBundleId", a.getIosBundleId());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 可下发的用例。只列「这个环境 + 轨道下能跑」的，和下发时的校验同一套规则，
     * 免得对方选了一条列表里有的、下发时却被拒。
     * <p>
     * 默认门槛（给 TestForge 展示用）：顶层步骤数 &gt; 1；不要求试跑通过。
     * 需要更严或全量时自行传 {@code trialPassed}/{@code minSteps}。
     */
    @GetMapping("/cases")
    public ResponseEntity<?> cases(@RequestParam(required = false) String appId,
                                   @RequestParam(required = false) String platform,
                                   @RequestParam(required = false) String environment,
                                   @RequestParam(required = false) String track,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false, defaultValue = "false") boolean trialPassed,
                                   @RequestParam(required = false, defaultValue = "1") int minSteps,
                                   @RequestParam(required = false, defaultValue = "1") int page,
                                   @RequestParam(required = false, defaultValue = "20") int size) {
        String env = TestEnvironment.normalize(environment);
        String tr = RunTrack.isAutomation(track) ? RunTrack.AUTOMATION : RunTrack.REGRESSION;
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int stepFloor = Math.max(0, minSteps);
        List<UiFlow> all = uiFlowService.listForBatch(appId, platform == null ? null : platform.trim().toUpperCase(Locale.ROOT), env, tr)
                .stream()
                .filter(f -> kw.isEmpty() || (f.getName() != null && f.getName().toLowerCase(Locale.ROOT).contains(kw)))
                .filter(f -> stepCount(f.getSteps()) > stepFloor)
                .filter(f -> !trialPassed || isTrialPassed(taskService.findLatestDebugByFlowId(f.getId())))
                .toList();
        int s = Math.max(1, Math.min(100, size));
        int p = Math.max(1, page);
        int from = Math.min(all.size(), (p - 1) * s);
        int to = Math.min(all.size(), from + s);
        List<Map<String, Object>> items = new ArrayList<>();
        for (UiFlow f : all.subList(from, to)) {
            items.add(caseVo(f, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("page", p);
        out.put("size", s);
        out.put("environment", env);
        out.put("track", tr);
        out.put("trialPassed", trialPassed);
        out.put("minSteps", stepFloor);
        out.put("items", items);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/cases/{id}")
    public ResponseEntity<?> caseDetail(@PathVariable String id) {
        UiFlow f = uiFlowService.get(id);
        if (f == null || Boolean.TRUE.equals(f.getIsComponent())) {
            return ResponseEntity.status(404).body(Map.of("message", "用例不存在：" + id));
        }
        return ResponseEntity.ok(caseVo(f, true));
    }

    /**
     * 设备池现状。TestForge 执行前应拉一次，让用户选可跑设备，再把 {@code deviceId} 传给 {@code POST /runs}。
     * <p>
     * {@code onlineOnly=true}（默认）只返回 {@code CONNECTED}；{@code platform} 与用例平台对齐（ANDROID/IOS/HARMONYOS）。
     * 不传 deviceId 时仍可由本平台设备池自动分配。
     */
    @GetMapping("/devices")
    public ResponseEntity<?> devices(@RequestParam(required = false) String platform,
                                     @RequestParam(required = false, defaultValue = "true") boolean onlineOnly,
                                     @RequestParam(required = false, defaultValue = "false") boolean includeEmulator) {
        String want = platform == null || platform.isBlank() ? null : platform.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Device d : deviceService.getAllDevices()) {
            if (Boolean.TRUE.equals(d.getDisabled())) continue;
            if (!includeEmulator && Boolean.TRUE.equals(d.getEmulator())) continue;
            String p = d.getPlatform() == null ? null : d.getPlatform().name();
            if (want != null && (p == null || !platformMatches(want, p))) continue;
            String st = d.getStatus() == null ? null : d.getStatus().name();
            if (onlineOnly && !"CONNECTED".equals(st)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", d.getId());
            m.put("name", d.getName());
            m.put("platform", p);
            m.put("osVersion", d.getVersion());
            m.put("status", st);
            m.put("emulator", Boolean.TRUE.equals(d.getEmulator()));
            m.put("selectable", "CONNECTED".equals(st));
            out.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("devices", out);
        body.put("queued", taskService.countQueued());
        body.put("platform", want);
        body.put("onlineOnly", onlineOnly);
        return ResponseEntity.ok(body);
    }

    /** 开放接口只认 ANDROID/IOS/HARMONYOS；库里若还有历史 POG_* 也归到对应端 */
    private static boolean platformMatches(String want, String devicePlatform) {
        if (want.equals(devicePlatform)) return true;
        if ("ANDROID".equals(want) && "POG_ANDROID".equals(devicePlatform)) return true;
        if ("IOS".equals(want) && "POG_IOS".equals(devicePlatform)) return true;
        return false;
    }

    @PostMapping("/runs")
    public ResponseEntity<?> createRun(HttpServletRequest request, @RequestBody OpenRunService.CreateRunRequest body) {
        try {
            OpenRunService.Created c = runService.create(client(request), body);
            Map<String, Object> out = new LinkedHashMap<>(c.run());
            // 同一个 externalId 再发一次：返回第一次建的那批，不重复下发
            out.put("duplicate", c.duplicate());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("[open-api] 下发失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("message", "下发失败：" + e.getMessage()));
        }
    }

    @GetMapping("/runs")
    public ResponseEntity<?> listRuns(HttpServletRequest request,
                                      @RequestParam(required = false) String externalId,
                                      @RequestParam(required = false, defaultValue = "20") int limit) {
        return ResponseEntity.ok(runService.list(client(request), externalId, limit));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> getRun(HttpServletRequest request, @PathVariable String runId) {
        Map<String, Object> d = runService.detail(client(request), runId);
        return d == null ? ResponseEntity.status(404).body(Map.of("message", "没有这次执行：" + runId))
                : ResponseEntity.ok(d);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<?> cancel(HttpServletRequest request, @PathVariable String runId) {
        Map<String, Object> d = runService.cancel(client(request), runId);
        return d == null ? ResponseEntity.status(404).body(Map.of("message", "没有这次执行：" + runId))
                : ResponseEntity.ok(d);
    }

    private Map<String, Object> caseVo(UiFlow f, boolean withDetail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", f.getId());
        m.put("name", f.getName());
        m.put("description", f.getDescription());
        m.put("appId", f.getAppId());
        m.put("platform", f.getPlatform());
        m.put("environment", f.getEnvironment());
        m.put("catalog", f.getCatalog());
        m.put("core", Boolean.TRUE.equals(f.getCore()));
        m.put("releaseStage", UiFlowService.normalizeReleaseStage(f.getReleaseStage()));
        m.put("enabled", !Boolean.FALSE.equals(f.getEnabled()));
        m.put("stepCount", stepCount(f.getSteps()));
        Task last = taskService.findLatestByFlowId(f.getId());
        m.put("lastRunStatus", last == null ? null : OpenRunStatus.ofTask(last));
        m.put("lastRunAt", last == null ? null : (last.getUpdatedAt() != null ? last.getUpdatedAt() : last.getCreatedAt()));
        Task debug = taskService.findLatestDebugByFlowId(f.getId());
        m.put("lastDebugStatus", debug == null ? null : OpenRunStatus.ofTask(debug));
        m.put("lastDebugAt", debug == null ? null : (debug.getUpdatedAt() != null ? debug.getUpdatedAt() : debug.getCreatedAt()));
        if (withDetail) {
            // 下发时可以通过 params 覆盖的变量
            m.put("variables", uiFlowService.collectLocatorVariables(f));
        }
        return m;
    }

    /** 顶层步骤数；解析失败按 0，宁可不给对方也不把坏数据放进可下发列表 */
    static int stepCount(String steps) {
        if (steps == null || steps.isBlank()) return 0;
        try {
            com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(steps);
            return n.isArray() ? n.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 试跑成功：最近一次 DEBUG 轨任务跑完且状态为通过 */
    static boolean isTrialPassed(Task debug) {
        return debug != null && debug.getStatus() == Task.TaskStatus.COMPLETED;
    }

    private static String client(HttpServletRequest request) {
        Object c = request.getAttribute(OpenApiAuth.ATTR_CLIENT);
        if (c == null) {
            // 走到这里说明鉴权没生效，宁可拒绝也不能当匿名放过去
            throw new IllegalStateException("开放接口鉴权未生效");
        }
        return c.toString();
    }
}
