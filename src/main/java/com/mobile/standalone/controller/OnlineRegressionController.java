package com.mobile.standalone.controller;

import com.mobile.standalone.entity.OnlineRegressionAccount;
import com.mobile.standalone.entity.OnlineRegressionRun;
import com.mobile.standalone.entity.OnlineRegressionStep;
import com.mobile.standalone.service.OnlineRegressionOrchestrator;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.JobDetail;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.OnlineRegressionRunDetail;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.OrderedStepDto;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.RunSummary;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.StartRunRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * APP 线上回归：账号池 + 启动批次 + 进度查询。
 * 复用功能测试场景执行；批次状态落 online_regression_* 表。
 */
@RestController
@RequestMapping("/online-regression")
@RequiredArgsConstructor
public class OnlineRegressionController {

    private final OnlineRegressionOrchestrator orchestrator;

    /** 线上回归专用账号池（手机号+验证码），与专项测试默认号分离 */
    @GetMapping("/accounts")
    public ResponseEntity<List<AccountVo>> listAccounts(@RequestParam(required = false) String appId) {
        List<OnlineRegressionAccount> list = orchestrator.listAccounts(appId);
        List<AccountVo> vos = new ArrayList<>();
        for (OnlineRegressionAccount a : list) {
            AccountVo vo = new AccountVo();
            vo.setId(a.getId());
            vo.setAppId(a.getAppId());
            vo.setLabel(a.getLabel());
            vo.setPhoneNumber(a.getPhoneNumber());
            // 不下发明文验证码给非必要端时可再收紧；阶段一给前端下拉展示用
            vo.setVerificationCode(a.getVerificationCode());
            vos.add(vo);
        }
        return ResponseEntity.ok(vos);
    }

    /** 依赖智能排序预览：勾选功能 → 原子步骤顺序 */
    @PostMapping("/preview-order")
    public ResponseEntity<List<OrderedStepDto>> previewOrder(@RequestBody PreviewOrderRequest body) {
        List<String> features = body != null && body.getFeatureIds() != null ? body.getFeatureIds() : List.of();
        return ResponseEntity.ok(orchestrator.previewOrderedSteps(features));
    }

    /** 历史批次列表（摘要）；详情请用 GET /runs/{runId} */
    @GetMapping("/runs")
    public ResponseEntity<List<Map<String, Object>>> listRuns(
            @RequestParam(required = false, defaultValue = "20") int limit) {
        List<RunSummary> runs = orchestrator.listRecentRunSummaries(limit);
        List<Map<String, Object>> list = new ArrayList<>(runs.size());
        for (RunSummary summary : runs) {
            OnlineRegressionRun run = summary.getRun();
            Map<String, Object> item = new HashMap<>();
            item.put("id", run.getId());
            item.put("status", run.getStatus());
            item.put("passRate", run.getPassRate());
            item.put("errorMessage", run.getErrorMessage());
            item.put("startedAt", run.getStartedAt());
            item.put("finishedAt", run.getFinishedAt());
            item.put("createdAt", run.getCreatedAt());
            item.put("jobCount", summary.getJobCount());
            list.add(item);
        }
        return ResponseEntity.ok(list);
    }

    /** 创建并异步启动一次线上回归 */
    @PostMapping("/runs")
    public ResponseEntity<?> startRun(@RequestBody StartRunRequest request) {
        try {
            OnlineRegressionRunDetail detail = orchestrator.startRun(request);
            return ResponseEntity.ok(toRunVo(detail));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage() != null ? e.getMessage() : "启动失败"));
        }
    }

    /** 查询批次详情（供实时看板轮询 / 历史「查看」） */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> getRun(@PathVariable String runId) {
        OnlineRegressionRunDetail detail = orchestrator.getRunDetail(runId);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toRunVo(detail));
    }

    @PostMapping("/runs/{runId}/stop")
    public ResponseEntity<Map<String, Object>> stopRun(@PathVariable String runId) {
        orchestrator.stopRun(runId);
        return ResponseEntity.ok(Map.of("success", true, "runId", runId));
    }

    private Map<String, Object> toRunVo(OnlineRegressionRunDetail detail) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("id", detail.getRun().getId());
        vo.put("status", detail.getRun().getStatus());
        vo.put("passRate", detail.getRun().getPassRate());
        vo.put("enableOverseasProxy", detail.getRun().getEnableOverseasProxy());
        vo.put("startedAt", detail.getRun().getStartedAt());
        vo.put("finishedAt", detail.getRun().getFinishedAt());
        vo.put("errorMessage", detail.getRun().getErrorMessage());
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JobDetail jd : detail.getJobs()) {
            Map<String, Object> j = new HashMap<>();
            j.put("id", jd.getJob().getId());
            j.put("key", jd.getJob().getJobKey());
            j.put("appId", jd.getJob().getAppId());
            j.put("appName", jd.getJob().getAppName());
            j.put("deviceId", jd.getJob().getDeviceId());
            j.put("deviceName", jd.getJob().getDeviceName());
            j.put("devicePlatform", jd.getJob().getDevicePlatform());
            j.put("endpoint", jd.getJob().getEndpoint());
            j.put("status", normalizeJobStatus(jd.getJob().getStatus()));
            j.put("progress", jd.getJob().getProgress());
            j.put("currentStepLabel", jd.getJob().getCurrentStepLabel());
            j.put("accountPhone", jd.getJob().getAccountPhone());
            j.put("errorMessage", jd.getJob().getErrorMessage());
            List<Map<String, Object>> steps = new ArrayList<>();
            String latestShotUrl = null;
            for (OnlineRegressionStep s : jd.getSteps()) {
                Map<String, Object> st = new HashMap<>();
                st.put("id", s.getId());
                st.put("stepId", s.getStepId());
                st.put("label", s.getStepLabel());
                st.put("status", normalizeStepStatus(s.getStatus()));
                st.put("taskId", s.getTaskId());
                st.put("error", s.getErrorMessage());
                String shotUrl = null;
                if (s.getTaskId() != null && !s.getTaskId().isBlank()) {
                    shotUrl = "functional-test/tasks/" + s.getTaskId() + "/screenshot";
                }
                st.put("screenshotUrl", shotUrl);
                st.put("screenshot", null);
                if (shotUrl != null && ("SUCCESS".equalsIgnoreCase(s.getStatus()) || "FAILED".equalsIgnoreCase(s.getStatus()))) {
                    latestShotUrl = shotUrl;
                }
                steps.add(st);
            }
            j.put("steps", steps);
            j.put("latestScreenshotUrl", latestShotUrl);
            j.put("endpointLabel", "ios".equalsIgnoreCase(jd.getJob().getEndpoint()) ? "iOS" : "Android");
            jobs.add(j);
        }
        vo.put("jobs", jobs);
        return vo;
    }

    /** 前端 Live 看板用小写 status */
    private String normalizeStepStatus(String status) {
        if (status == null) return "pending";
        return switch (status.toUpperCase()) {
            case "RUNNING" -> "running";
            case "SUCCESS" -> "success";
            case "FAILED" -> "failed";
            case "SKIPPED" -> "skipped";
            default -> "pending";
        };
    }

    private String normalizeJobStatus(String status) {
        if (status == null) return "pending";
        return switch (status.toUpperCase()) {
            case "RUNNING" -> "running";
            case "SUCCESS" -> "success";
            case "FAILED" -> "failed";
            case "STOPPED" -> "stopped";
            default -> "pending";
        };
    }

    @Data
    public static class PreviewOrderRequest {
        private List<String> featureIds;
    }

    @Data
    public static class AccountVo {
        private String id;
        private String appId;
        private String label;
        private String phoneNumber;
        private String verificationCode;
    }
}
