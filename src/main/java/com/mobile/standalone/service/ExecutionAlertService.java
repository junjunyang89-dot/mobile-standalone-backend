package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.entity.TaskFailureDiagnosis;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 执行失败告警。
 *
 * <p><b>为什么要有这个类</b>：告警原来只存在于 {@code OnlineRegressionOrchestrator} 的批次收尾里，
 * 也就是说——<b>只有以「线上回归批次」形式发起的执行失败了才有人知道</b>。
 * 而生产巡检走的是另一条轨道（单条流程执行），跑挂了没有任何人会收到消息。
 * 一个没人知道结果的巡检等于没跑。
 *
 * <p>所以告警必须挂在<b>执行层</b>：不管从哪个入口发起，一次执行结束就走同一条判定。
 *
 * <p>三条刻意的规则：
 * <ul>
 *   <li><b>编排驱动的任务不单独发。</b>否则一批 20 条流程失败会推 20 条消息，
 *       而批次收尾那条摘要才是人真正想看的。收到 20 条的结果是把通知静音。</li>
 *   <li><b>默认只有生产环境失败才发。</b>测试环境是你自己盯着在跑，不需要打扰；
 *       生产失败则可能是线上问题，必须有人知道。可以按流程显式覆盖。</li>
 *   <li><b>同一条流程有冷却期。</b>元素失效会让同一条流程连续失败，
 *       不设冷却就是一晚上几十条同样的消息。</li>
 * </ul>
 *
 * <p>整条链路对主流程<b>只读且静默</b>：告警发不出去只记日志，绝不影响任务本身的状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionAlertService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 归因是异步跑的，等它一会儿再发，消息里就能带上「该修元素还是该提单」 */
    private static final Duration DIAGNOSIS_WAIT = Duration.ofSeconds(60);
    private static final Duration DIAGNOSIS_POLL = Duration.ofSeconds(3);

    private final TaskMapper taskMapper;
    private final TaskExecutionMapper executionMapper;
    private final DeviceMapper deviceMapper;
    private final PushPlusNotifyService pushPlusNotifyService;
    /** 用 Provider 取，避免和归因链路互相依赖时在构造期成环 */
    private final ObjectProvider<FailureDiagnosisService> diagnosisServiceProvider;
    private final ObjectProvider<FailureDiagnosisRunner> diagnosisRunnerProvider;
    private final ObjectProvider<UiFlowService> uiFlowServiceProvider;

    @Value("${app.execution-alert.enabled:true}")
    private boolean enabled;

    /** 归因没开时不必干等 60 秒。不调 runner.isEnabled()：那边若没编进这个方法会直接编译失败 */
    @Value("${diagnosis.ai.enabled:false}")
    private boolean diagnosisEnabled;

    /**
     * 哪些环境失败了要告警，逗号分隔。默认只有生产。
     * <p>
     * 想全都告警就填 {@code PROD,TEST}；想全关就把 enabled 置 false。
     */
    @Value("${app.execution-alert.environments:PROD}")
    private String alertEnvironments;

    /** 同一条流程的告警冷却期（分钟） */
    @Value("${app.execution-alert.cooldown-minutes:30}")
    private int cooldownMinutes;

    /** 详情链接模板，含 {taskId} 占位符 */
    @Value("${app.execution-alert.detail-url-template:}")
    private String detailUrlTemplate;

    /**
     * 冷却记录。进程内，重启即清空——这是刻意接受的：
     * 重启后多发一条告警，比为了这点事再加一张表划算。
     */
    private final Map<String, Instant> lastAlertByFlow = new ConcurrentHashMap<>();

    /** 告警不能占执行线程：PushPlus 一次调用最长 15 秒，再加上等归因 60 秒 */
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "execution-alert");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 任务执行线程收尾时调用，和归因同一个位置。
     *
     * @param standalone 是否独立运行。false＝线上回归编排在驱动，
     *                   批次收尾那边会发一条摘要，这里不重复发。
     */
    public void onTaskFinished(String taskId, boolean standalone) {
        if (!enabled || taskId == null || taskId.isBlank()) {
            return;
        }
        if (!standalone) {
            log.debug("[alert] 编排驱动的任务不单独告警，交给批次收尾: taskId={}", taskId);
            return;
        }
        if (!pushPlusNotifyService.isReady()) {
            log.debug("[alert] 推送通道未就绪，跳过: taskId={}", taskId);
            return;
        }
        try {
            pool.submit(() -> safeAlert(taskId));
        } catch (Exception e) {
            log.warn("[alert] 提交告警任务失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    private void safeAlert(String taskId) {
        try {
            alert(taskId);
        } catch (Exception e) {
            log.warn("[alert] 组装/发送告警异常 taskId={}: {}", taskId, e.getMessage());
        }
    }

    private void alert(String taskId) {
        Task task = taskMapper.findById(taskId);
        if (task == null) {
            return;
        }
        if (task.getStatus() != Task.TaskStatus.FAILED) {
            return;
        }
        // 试跑失败不告警：那是你自己在编辑器里调，本来就盯着屏幕，
        // 再推一条微信只会让人把通知静音
        if (RunTrack.isDebug(task.getRunTrack())) {
            log.debug("[alert] 试跑失败不告警: taskId={}", taskId);
            return;
        }

        String environment = resolveEnvironment(task);
        Boolean explicit = readNotifyFlag(task.getConfig());
        if (Boolean.FALSE.equals(explicit)) {
            log.debug("[alert] 流程显式关闭了告警: taskId={}", taskId);
            return;
        }
        if (explicit == null && !environmentWantsAlert(environment)) {
            log.debug("[alert] {} 环境默认不告警: taskId={}", environment, taskId);
            return;
        }

        String flowId = task.getFlowId() != null ? task.getFlowId() : ("task:" + taskId);
        if (!passCooldown(flowId)) {
            log.info("[alert] 同一流程在冷却期内，本次不发: flowId={} taskId={}", flowId, taskId);
            return;
        }

        // 等一会儿归因结论。归因没开就别白等——那样每条告警都要多挂 60 秒
        TaskFailureDiagnosis diagnosis = waitForDiagnosis(taskId);

        String title = "自动化失败 · " + environment + " · " + safeName(task);
        String body = buildBody(task, environment, diagnosis);
        boolean sent = pushPlusNotifyService.sendText(title, body);
        log.info("[alert] 执行失败告警 taskId={} env={} 归因={} 发送={}",
                taskId, environment, diagnosis == null ? "无" : diagnosis.getCategory(), sent);
    }

    /** 任务 config 里的 environment > 设备绑定的 environment > 默认。与 UiFlowExecutor 的优先级保持一致 */
    private String resolveEnvironment(Task task) {
        String raw = readText(task.getConfig(), "environment");
        if ((raw == null || raw.isBlank()) && task.getDeviceId() != null) {
            Device device = deviceMapper.findById(task.getDeviceId());
            if (device != null) {
                raw = device.getEnvironment();
            }
        }
        return TestEnvironment.normalize(raw);
    }

    private boolean environmentWantsAlert(String environment) {
        if (alertEnvironments == null || alertEnvironments.isBlank()) {
            return false;
        }
        for (String part : alertEnvironments.split(",")) {
            if (part.trim().equalsIgnoreCase(environment)) {
                return true;
            }
        }
        return false;
    }

    private boolean passCooldown(String flowId) {
        if (cooldownMinutes <= 0) {
            return true;
        }
        Instant now = Instant.now();
        Instant last = lastAlertByFlow.get(flowId);
        if (last != null && last.isAfter(now.minus(Duration.ofMinutes(cooldownMinutes)))) {
            return false;
        }
        lastAlertByFlow.put(flowId, now);
        return true;
    }

    private TaskFailureDiagnosis waitForDiagnosis(String taskId) {
        FailureDiagnosisService service = diagnosisServiceProvider.getIfAvailable();
        if (service == null) {
            return null;
        }
        boolean diagnosisOn = diagnosisEnabled && diagnosisRunnerProvider.getIfAvailable() != null;
        try {
            TaskFailureDiagnosis first = service.current(taskId);
            if (first != null || !diagnosisOn) {
                return first;
            }
            long deadline = System.currentTimeMillis() + DIAGNOSIS_WAIT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(DIAGNOSIS_POLL.toMillis());
                TaskFailureDiagnosis d = service.current(taskId);
                if (d != null) {
                    return d;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("[alert] 取归因结论失败 taskId={}: {}", taskId, e.getMessage());
        }
        return null;
    }

    private String buildBody(Task task, String environment, TaskFailureDiagnosis diagnosis) {
        StringBuilder b = new StringBuilder();
        b.append("流程：").append(safeName(task)).append('\n');
        b.append("环境：").append(environment).append('\n');
        if (task.getDeviceId() != null) {
            b.append("设备：").append(task.getDeviceId()).append('\n');
        }
        if (task.getUpdatedAt() != null) {
            b.append("时间：").append(task.getUpdatedAt().format(TS)).append('\n');
        }

        TaskExecution execution = latestExecution(task.getId());
        if (execution != null && execution.getErrorMessage() != null
                && !execution.getErrorMessage().isBlank()) {
            b.append("\n失败原因：\n").append(truncate(execution.getErrorMessage(), 400)).append('\n');
        }

        if (diagnosis != null) {
            b.append("\n归因：").append(categoryLabel(diagnosis.getCategory()));
            if (diagnosis.getConfidence() != null) {
                b.append("（置信度 ").append(diagnosis.getConfidence()).append("%）");
            }
            b.append('\n');
            if (diagnosis.getFailedStep() != null && !diagnosis.getFailedStep().isBlank()) {
                b.append("失败步骤：").append(truncate(diagnosis.getFailedStep(), 120)).append('\n');
            }
            if (diagnosis.getElementKey() != null && !diagnosis.getElementKey().isBlank()) {
                b.append("涉及元素：").append(diagnosis.getElementKey()).append('\n');
            }
        }

        String tpl = detailUrlTemplate == null ? "" : detailUrlTemplate.trim();
        if (!tpl.isEmpty()) {
            b.append("\n详情：").append(tpl.replace("{taskId}", task.getId()));
        }
        return b.toString().trim();
    }

    private TaskExecution latestExecution(String taskId) {
        try {
            List<TaskExecution> list = executionMapper.findByTaskIdOrderByStartTimeDesc(taskId);
            return list == null || list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 归因分类翻成一句「该谁去做什么」，值班的人扫一眼就知道要不要立刻处理 */
    static String categoryLabel(String category) {
        if (category == null) {
            return "未知";
        }
        switch (category.trim().toUpperCase(Locale.ROOT)) {
            case "ELEMENT_BROKEN":
                return "元素定位失效（改用例，不是产品问题）";
            case "FLOW_OUTDATED":
                return "流程与现状不符（改编排）";
            case "FUNCTION_BROKEN":
                return "功能确实坏了（需要提单）";
            case "ENVIRONMENT":
                return "环境问题（已安排重跑）";
            case "FLAKY":
                return "偶发抖动（先观察）";
            default:
                return "需人工确认";
        }
    }

    private String safeName(Task task) {
        if (task.getName() != null && !task.getName().isBlank()) {
            return task.getName();
        }
        UiFlowService flowService = uiFlowServiceProvider.getIfAvailable();
        if (flowService != null && task.getFlowId() != null) {
            try {
                var flow = flowService.get(task.getFlowId());
                if (flow != null && flow.getName() != null) {
                    return flow.getName();
                }
            } catch (Exception ignored) {
                // 取不到名字不该让告警发不出去
            }
        }
        return task.getId();
    }

    private static String readText(String configJson, String field) {
        JsonNode node = parse(configJson);
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 流程可以显式声明「这条一定要告警 / 一定不要告警」，压过环境默认值 */
    private static Boolean readNotifyFlag(String configJson) {
        JsonNode node = parse(configJson);
        if (node == null) {
            return null;
        }
        JsonNode v = node.get("notifyOnFailure");
        return v == null || v.isNull() ? null : v.asBoolean();
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
