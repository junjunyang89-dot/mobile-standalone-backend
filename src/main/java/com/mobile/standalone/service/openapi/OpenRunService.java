package com.mobile.standalone.service.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.auth.OpenApiAuth;
import com.mobile.standalone.config.OpenApiProperties;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.service.TaskService;
import com.mobile.standalone.service.UiFlowExecuteService;
import com.mobile.standalone.service.UiFlowService;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 外部平台下发的一次执行（单条 = 1 条用例的批次）。
 * <p>
 * 底下每条用例就是一个普通的流程任务，进设备池排队——和页面上点「执行」完全同一条路，
 * 这里只多记一层「这些任务属于外部的哪一次下发」，用来汇总状态、防重复下发、回调。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenRunService {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    static final String CB_NONE = "NONE";
    static final String CB_PENDING = "PENDING";
    static final String CB_DONE = "DONE";
    static final String CB_FAILED = "FAILED";

    private final JdbcTemplate jdbc;
    private final UiFlowService uiFlowService;
    private final UiFlowExecuteService executeService;
    private final TaskService taskService;
    private final TaskExecutionMapper executionMapper;
    private final OpenApiProperties props;
    private final OpenApiAuth openApiAuth;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS open_api_runs (
                        id VARCHAR(64) PRIMARY KEY,
                        client VARCHAR(64) NOT NULL COMMENT '下发方（open-api.clients.name）',
                        external_id VARCHAR(128) DEFAULT NULL COMMENT '下发方自己的单号，用于防重复下发',
                        operator VARCHAR(128) DEFAULT NULL COMMENT '下发方的操作人',
                        title VARCHAR(255) DEFAULT NULL,
                        environment VARCHAR(16) NOT NULL DEFAULT 'PROD',
                        track VARCHAR(32) NOT NULL DEFAULT 'REGRESSION',
                        status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
                        cancelled TINYINT(1) NOT NULL DEFAULT 0,
                        callback_url VARCHAR(1024) DEFAULT NULL,
                        callback_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
                        callback_attempts INT NOT NULL DEFAULT 0,
                        callback_next_at DATETIME DEFAULT NULL,
                        callback_last_error VARCHAR(512) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        finished_at DATETIME DEFAULT NULL,
                        UNIQUE KEY uk_client_external (client, external_id),
                        INDEX idx_status (status),
                        INDEX idx_callback (callback_state, callback_next_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS open_api_run_items (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        run_id VARCHAR(64) NOT NULL,
                        seq INT NOT NULL,
                        flow_id VARCHAR(64) NOT NULL,
                        flow_name VARCHAR(255) DEFAULT NULL,
                        task_id VARCHAR(64) DEFAULT NULL,
                        submit_error VARCHAR(512) DEFAULT NULL COMMENT '没建成任务的原因',
                        INDEX idx_run (run_id),
                        INDEX idx_task (task_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[open-api] 表已就绪");
        } catch (Exception e) {
            log.warn("[open-api] 建表失败: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 下发

    @Data
    public static class CreateRunRequest {
        /** 要跑的用例（流程）id，1 条即单条执行，多条即批量 */
        private List<String> caseIds;
        /** PROD（默认）/ TEST */
        private String environment;
        /** REGRESSION（默认）/ AUTOMATION，决定能选哪批用例 */
        private String track;
        /** 应用字典 id；不传用用例自己绑定的应用 */
        private String appId;
        /** 指定设备；不传由设备池分配（推荐） */
        private String deviceId;
        /** 本次覆盖的流程变量 */
        private Map<String, String> params;
        /** 下发方单号：同一调用方同一单号只建一次，重试安全 */
        private String externalId;
        private String operator;
        private String title;
        /** 整批结束后 POST 结果到这里 */
        private String callbackUrl;
    }

    public record Created(Map<String, Object> run, boolean duplicate) {
    }

    public Created create(String client, CreateRunRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (req.getCaseIds() != null) {
            for (String id : req.getCaseIds()) {
                if (id != null && !id.isBlank()) ids.add(id.trim());
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("caseIds 不能为空");
        }
        if (ids.size() > props.getMaxCasesPerRun()) {
            throw new IllegalArgumentException("一次最多下发 " + props.getMaxCasesPerRun() + " 条用例，当前 " + ids.size() + " 条");
        }
        String externalId = blankToNull(req.getExternalId());
        if (externalId != null) {
            String existing = findIdByExternal(client, externalId);
            if (existing != null) {
                return new Created(detail(client, existing), true);
            }
        }
        String callbackUrl = blankToNull(req.getCallbackUrl());
        if (callbackUrl != null && !(callbackUrl.startsWith("http://") || callbackUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("callbackUrl 必须是 http(s) 地址");
        }
        String environment = TestEnvironment.normalize(req.getEnvironment());
        String track = RunTrack.isAutomation(req.getTrack()) ? RunTrack.AUTOMATION : RunTrack.REGRESSION;

        // 先整体校验再建任务：校验到第 30 条才发现不对的话，前 29 条已经在设备上跑了
        List<UiFlow> flows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String id : ids) {
            UiFlow f = uiFlowService.get(id);
            if (f == null) {
                problems.add(id + "：用例不存在");
            } else if (!uiFlowService.isBatchEligible(f, environment, track)) {
                problems.add(id + "（" + f.getName() + "）：不能用于" + TestEnvironment.label(environment)
                        + RunTrack.label(track) + "，可能未启用、是组件、或环境 / 用途不匹配");
            } else {
                flows.add(f);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("以下用例不能执行：" + String.join("；", problems));
        }

        String runId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO open_api_runs (id, client, external_id, operator, title, environment, track,
                        status, callback_url, callback_state)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, runId, client, externalId, blankToNull(req.getOperator()), blankToNull(req.getTitle()),
                    environment, track, OpenRunStatus.QUEUED, callbackUrl, callbackUrl == null ? CB_NONE : CB_PENDING);
        } catch (DuplicateKeyException e) {
            // 两个重试请求并发打进来，另一个先建成了
            String existing = findIdByExternal(client, externalId);
            if (existing != null) {
                return new Created(detail(client, existing), true);
            }
            throw e;
        }

        int seq = 0;
        for (UiFlow f : flows) {
            seq++;
            String taskId = null;
            String error = null;
            try {
                UiFlowExecuteService.ExecuteFlowRequest body = new UiFlowExecuteService.ExecuteFlowRequest();
                body.setAppId(blankToNull(req.getAppId()));
                body.setDeviceId(blankToNull(req.getDeviceId()));
                body.setEnvironment(environment);
                body.setParams(req.getParams());
                body.setTaskName("流程" + TestEnvironment.tag(environment) + "-"
                        + (f.getName() == null ? f.getId() : f.getName()) + "（" + client + "）");
                taskId = executeService.submit(f, body).taskId();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("[open-api] 下发失败 run={} flow={}: {}", runId, f.getId(), error);
            }
            jdbc.update("INSERT INTO open_api_run_items (run_id, seq, flow_id, flow_name, task_id, submit_error) "
                    + "VALUES (?,?,?,?,?,?)", runId, seq, f.getId(), f.getName(), taskId, truncate(error, 500));
        }
        log.info("[open-api] {} 下发 run={} externalId={} 用例 {} 条 环境 {}",
                client, runId, externalId, flows.size(), environment);
        refresh(runId);
        return new Created(detail(client, runId), false);
    }

    // ------------------------------------------------------------------ 查询

    /** @return null＝不存在或不属于这个调用方（不区分，免得能探测别人的 runId） */
    public Map<String, Object> detail(String client, String runId) {
        Map<String, Object> run = row(runId);
        if (run == null || !client.equals(run.get("client"))) {
            return null;
        }
        List<Map<String, Object>> items = items(runId);
        List<String> statuses = items.stream().map(i -> (String) i.get("status")).toList();
        String status = OpenRunStatus.ofRun(statuses, truthy(run.get("cancelled")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("externalId", run.get("external_id"));
        out.put("title", run.get("title"));
        out.put("operator", run.get("operator"));
        out.put("environment", run.get("environment"));
        out.put("track", run.get("track"));
        out.put("status", status);
        out.put("finished", OpenRunStatus.isTerminal(status));
        out.put("summary", OpenRunStatus.summary(statuses));
        out.put("createdAt", run.get("created_at"));
        out.put("finishedAt", run.get("finished_at"));
        out.put("callback", Map.of(
                "url", run.get("callback_url") == null ? "" : run.get("callback_url"),
                "state", run.get("callback_state"),
                "attempts", run.get("callback_attempts")));
        out.put("items", items);
        return out;
    }

    public List<Map<String, Object>> list(String client, String externalId, int limit) {
        int n = Math.max(1, Math.min(100, limit));
        List<String> ids = externalId == null || externalId.isBlank()
                ? jdbc.queryForList("SELECT id FROM open_api_runs WHERE client = ? ORDER BY created_at DESC LIMIT " + n,
                String.class, client)
                : jdbc.queryForList("SELECT id FROM open_api_runs WHERE client = ? AND external_id = ?",
                String.class, client, externalId.trim());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> d = detail(client, id);
            if (d != null) {
                d.remove("items");
                out.add(d);
            }
        }
        return out;
    }

    private List<Map<String, Object>> items(String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT seq, flow_id, flow_name, task_id, submit_error FROM open_api_run_items WHERE run_id = ? ORDER BY seq",
                runId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String taskId = (String) r.get("task_id");
            Task task = taskId == null ? null : taskService.getTaskById(taskId);
            String status = taskId == null ? OpenRunStatus.ERROR : OpenRunStatus.ofTask(task);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", r.get("seq"));
            m.put("caseId", r.get("flow_id"));
            m.put("caseName", r.get("flow_name"));
            m.put("taskId", taskId);
            m.put("status", status);
            m.put("deviceId", task == null ? null : task.getDeviceId());
            String error = (String) r.get("submit_error");
            if (error == null && task != null && (OpenRunStatus.FAILED.equals(status) || OpenRunStatus.BLOCKED.equals(status))) {
                error = lastError(taskId);
            }
            m.put("error", error);
            m.put("updatedAt", task == null ? null : task.getUpdatedAt());
            if (taskId != null) {
                // 截图路径本身免登录（写进 <img src> 的），对方直接拼到我们的后端地址前面即可
                m.put("screenshotPath", "/functional-test/tasks/" + taskId + "/screenshot");
                m.put("reportUrl", reportUrl(taskId));
            }
            out.add(m);
        }
        return out;
    }

    private String lastError(String taskId) {
        try {
            List<TaskExecution> list = executionMapper.findByTaskIdOrderByStartTimeDesc(taskId);
            return list == null || list.isEmpty() ? null : truncate(list.get(0).getErrorMessage(), 1000);
        } catch (Exception e) {
            return null;
        }
    }

    private String reportUrl(String taskId) {
        String base = props.getReportBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        String b = base.trim();
        if (b.contains("#")) {
            b = b.substring(0, b.indexOf('#'));
        }
        return b + "#/tasks/" + taskId + "/overview";
    }

    // ------------------------------------------------------------------ 取消

    public Map<String, Object> cancel(String client, String runId) {
        Map<String, Object> run = row(runId);
        if (run == null || !client.equals(run.get("client"))) {
            return null;
        }
        jdbc.update("UPDATE open_api_runs SET cancelled = 1 WHERE id = ?", runId);
        for (String taskId : jdbc.queryForList(
                "SELECT task_id FROM open_api_run_items WHERE run_id = ? AND task_id IS NOT NULL", String.class, runId)) {
            Task t = taskService.getTaskById(taskId);
            if (t == null || OpenRunStatus.isTerminal(OpenRunStatus.ofTask(t))) {
                continue;
            }
            taskService.stopTask(taskId);
            // 排队中的任务 stopTask 不改状态（它只停在跑的），不改的话调度器照样会把它派出去
            if (t.getStatus() == Task.TaskStatus.PENDING) {
                Task patch = new Task();
                patch.setStatus(Task.TaskStatus.STOPPED);
                taskService.updateTask(taskId, patch);
            }
        }
        log.info("[open-api] {} 取消 run={}", client, runId);
        refresh(runId);
        return detail(client, runId);
    }

    // ------------------------------------------------------------------ 状态刷新 + 回调

    /** 把汇总状态落库；整批结束时记结束时间，有回调地址的排上回调 */
    void refresh(String runId) {
        Map<String, Object> run = row(runId);
        if (run == null) return;
        List<String> statuses = items(runId).stream().map(i -> (String) i.get("status")).toList();
        String status = OpenRunStatus.ofRun(statuses, truthy(run.get("cancelled")));
        boolean done = OpenRunStatus.isTerminal(status);
        jdbc.update("""
                UPDATE open_api_runs SET status = ?,
                    finished_at = CASE WHEN ? AND finished_at IS NULL THEN NOW() ELSE finished_at END,
                    callback_next_at = CASE WHEN ? AND callback_state = 'PENDING' AND callback_next_at IS NULL
                        THEN NOW() ELSE callback_next_at END
                WHERE id = ?
                """, status, done, done, runId);
    }

    @Scheduled(fixedDelayString = "${open-api.poll-ms:15000}", initialDelay = 30000)
    public void tick() {
        try {
            for (String id : jdbc.queryForList(
                    "SELECT id FROM open_api_runs WHERE finished_at IS NULL ORDER BY created_at LIMIT 200", String.class)) {
                refresh(id);
            }
            for (String id : jdbc.queryForList("""
                    SELECT id FROM open_api_runs
                    WHERE callback_state = 'PENDING' AND finished_at IS NOT NULL
                      AND callback_next_at IS NOT NULL AND callback_next_at <= NOW()
                    ORDER BY callback_next_at LIMIT 20
                    """, String.class)) {
                sendCallback(id);
            }
        } catch (Exception e) {
            log.warn("[open-api] 轮询失败: {}", e.getMessage());
        }
    }

    void sendCallback(String runId) {
        Map<String, Object> run = row(runId);
        if (run == null) return;
        String client = (String) run.get("client");
        String url = (String) run.get("callback_url");
        int attempts = ((Number) run.get("callback_attempts")).intValue() + 1;
        String error;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "run.finished");
            payload.put("run", detail(client, runId));
            String body = JSON.writeValueAsString(payload);
            long ts = System.currentTimeMillis() / 1000;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Open-Timestamp", String.valueOf(ts))
                    .header("X-Open-Signature", "sha256=" + sign(openApiAuth.keyOf(client), ts + "." + body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                jdbc.update("UPDATE open_api_runs SET callback_state = ?, callback_attempts = ?, "
                        + "callback_last_error = NULL WHERE id = ?", CB_DONE, attempts, runId);
                log.info("[open-api] 回调成功 run={} → {}", runId, url);
                return;
            }
            error = "HTTP " + resp.statusCode();
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        boolean giveUp = attempts >= props.getCallbackMaxAttempts();
        // 间隔 1、2、4、8… 分钟：对方服务在发版重启的话，几分钟后就能接上
        LocalDateTime next = giveUp ? null : LocalDateTime.now().plusMinutes(1L << Math.min(attempts - 1, 6));
        jdbc.update("UPDATE open_api_runs SET callback_state = ?, callback_attempts = ?, callback_next_at = ?, "
                        + "callback_last_error = ? WHERE id = ?",
                giveUp ? CB_FAILED : CB_PENDING, attempts, next, truncate(error, 500), runId);
        log.warn("[open-api] 回调失败 run={} 第 {} 次 → {}: {}{}", runId, attempts, url, error, giveUp ? "（不再重试）" : "");
    }

    /** HMAC-SHA256(key, "{timestamp}.{body}")，十六进制小写 */
    static String sign(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((key == null ? "" : key).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ 工具

    private Map<String, Object> row(String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM open_api_runs WHERE id = ?", runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String findIdByExternal(String client, String externalId) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM open_api_runs WHERE client = ? AND external_id = ?", String.class, client, externalId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static boolean truthy(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        return false;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
