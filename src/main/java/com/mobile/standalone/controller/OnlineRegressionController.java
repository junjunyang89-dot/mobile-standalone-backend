package com.mobile.standalone.controller;

import com.mobile.standalone.entity.OnlineRegressionAccount;
import com.mobile.standalone.entity.OnlineRegressionRun;
import com.mobile.standalone.entity.OnlineRegressionStep;
import com.mobile.standalone.service.FailureCluster;
import com.mobile.standalone.service.OnlineRegressionOrchestrator;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.JobDetail;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.OnlineRegressionRunDetail;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.OrderedStepDto;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.RunSummary;
import com.mobile.standalone.service.OnlineRegressionOrchestrator.StartRunRequest;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.util.TestEnvironment;
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
    public ResponseEntity<List<AccountVo>> listAccounts(@RequestParam(required = false) String appId,
                                                        @RequestParam(required = false) String environment) {
        List<OnlineRegressionAccount> list = orchestrator.listAccounts(appId, environment);
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

    /**
     * 执行顺序预览。回归＝勾选顺序；自动化＝按前置依赖拓扑排序，并把没勾的前置补进来。
     * <p>
     * 依赖成环会在这里就报出环路（400），不用等到开跑。
     */
    @PostMapping("/preview-order")
    public ResponseEntity<?> previewOrder(@RequestBody PreviewOrderRequest body) {
        List<String> flowIds = body != null && body.getFlowIds() != null ? body.getFlowIds() : List.of();
        List<String> featureIds = body != null ? body.getFeatureIds() : null;
        String track = body != null ? body.getTrack() : null;
        String resetFlowId = body != null ? body.getResetFlowId() : null;
        try {
            return ResponseEntity.ok(orchestrator.previewOrder(
                    orchestrator.resolveFlowIds(flowIds, featureIds), track, resetFlowId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
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
            // 存量批次没有这个字段，一律按生产解读，历史列表才不会出现空标签
            item.put("environment", TestEnvironment.normalize(run.getEnvironment()));
            item.put("environmentLabel", TestEnvironment.label(run.getEnvironment()));
            item.put("track", RunTrack.normalize(run.getTrack()));
            item.put("trackLabel", RunTrack.label(run.getTrack()));
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

    /** 清空全部历史批次（模块保留，账号池保留） */
    @DeleteMapping("/runs")
    public ResponseEntity<Map<String, Object>> clearHistory() {
        int n = orchestrator.clearAllHistory();
        return ResponseEntity.ok(Map.of("success", true, "deleted", n));
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
        // 失败归类：报告顶部先给「其实是几个问题」，逐条查之前先收敛
        vo.put("failureSummary", detail.getFailureSummary());
        List<Map<String, Object>> clusters = new ArrayList<>();
        for (FailureCluster.Cluster c : detail.getFailureClusters() == null
                ? List.<FailureCluster.Cluster>of() : detail.getFailureClusters()) {
            Map<String, Object> cv = new HashMap<>();
            cv.put("reason", c.getReason());
            cv.put("count", c.getCount());
            cv.put("samples", c.getSamples());
            cv.put("rank", c.getRank());
            cv.put("hint", c.getHint());
            clusters.add(cv);
        }
        vo.put("failureClusters", clusters);
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
                // 有关联任务时给出截图 URL；接口会从执行日志/磁盘发现截图，前端 404 时自行清空
                String shotUrl = null;
                if (s.getTaskId() != null && !s.getTaskId().isBlank()) {
                    String status = s.getStatus() != null ? s.getStatus() : "";
                    boolean done = "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)
                            || "RUNNING".equalsIgnoreCase(status);
                    boolean hasShot = s.getScreenshotPath() != null && !s.getScreenshotPath().isBlank();
                    if (hasShot || done) {
                        shotUrl = "functional-test/tasks/" + s.getTaskId() + "/screenshot";
                    }
                }
                st.put("screenshotUrl", shotUrl);
                st.put("screenshot", null);
                if (shotUrl != null && ("SUCCESS".equalsIgnoreCase(s.getStatus())
                        || "UNSTABLE".equalsIgnoreCase(s.getStatus())
                        || "FAILED".equalsIgnoreCase(s.getStatus()))) {
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
            case "UNSTABLE" -> "unstable";
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
        private List<String> flowIds;
        /** @deprecated 兼容旧前端 */
        private List<String> featureIds;
        /** REGRESSION（默认）/ AUTOMATION */
        private String track;
        /** 复位流程：每条用例前跑一次 */
        private String resetFlowId;
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
