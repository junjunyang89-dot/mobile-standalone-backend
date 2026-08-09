package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Artifact;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Context;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskFailureDiagnosis;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.service.ai.DiagnosisModelClient;
import com.mobile.standalone.util.ExecutionLogJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务失败后自动归因，并按结论驱动动作。
 * <p>
 * 只输出一段结论文字是不够的——那只是把「人排查失败」换成了「人读归因报告」。真正省人的是
 * 让结论直接推动下一步：环境问题自动重跑，元素失效备好候选定位等人一键采纳，
 * 剩下真正是缺陷的才需要人看。
 * <p>
 * 整条链路对主流程是<b>只读且静默</b>的：归因失败、模型超时、没配 key，都只记日志，
 * 绝不影响任务本身的状态。自动化能力不该让原本能跑的东西变得跑不了。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FailureDiagnosisRunner {

    /** 自动重跑的冷却期：同一任务短时间内只自动重跑一次，防止环境一直不好时无限循环 */
    private static final Duration RERUN_COOLDOWN = Duration.ofMinutes(30);
    /** 同签名结论的复用窗口。超过这个时间就重新判一次，避免沿用早已过时的结论 */
    private static final Duration REUSE_WINDOW = Duration.ofDays(7);

    private final TaskMapper taskMapper;
    private final FailureDiagnosisService diagnosisService;
    /**
     * 用 Provider 而不是直接注入：provider 配置写错字时两个实现都不会注册，
     * 直接注入会让整个应用起不来——归因不可用最多是少个能力，绝不该拖垮主流程。
     */
    private final ObjectProvider<DiagnosisModelClient> modelClientProvider;
    /** TaskService 反过来依赖本类，用 Provider 打破构造期的循环 */
    private final ObjectProvider<TaskService> taskServiceProvider;

    @Value("${diagnosis.ai.enabled:false}")
    private boolean enabled;

    /**
     * 归因时上传几张截图。
     * <p>
     * 默认 0：自动化以断言为主，判据主要在控件树与日志上——实测模型给出的依据也几乎全来自控件树。
     * 带图会让单次判断从几十秒涨到几分钟，收益却很有限。需要看图时调大即可。
     */
    @Value("${diagnosis.ai.max-images:0}")
    private int maxImages;

    /** 环境问题自动重跑。默认开——环境抖动占失败的很大一块，每次都要人来点重跑最没价值 */
    @Value("${diagnosis.auto-rerun.enabled:true}")
    private boolean autoRerunEnabled;

    /** 归因跑在自己的线程池里，绝不占用执行线程 */
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    /**
     * 启动时把生效的配置打出来。
     * <p>
     * 这条链路是「静默失败」的——没配 key、provider 写错字都只是不归因，任务照跑。
     * 不打日志的话，人要等好几天没看到任何结论才会想起来查配置。
     */
    @jakarta.annotation.PostConstruct
    void logConfig() {
        if (!enabled) {
            log.info("失败归因未启用（diagnosis.ai.enabled=false）");
            return;
        }
        DiagnosisModelClient client = modelClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("失败归因已启用，但没有可用的模型实现——请检查 diagnosis.ai.provider（qwen / gemini）");
            return;
        }
        log.info("失败归因已启用：client={}, model={}, 密钥已配置={}, 上传截图={}张, 环境问题自动重跑={}",
                client.getClass().getSimpleName(), client.modelId(), client.isConfigured(),
                maxImages, autoRerunEnabled);
        if (!client.isConfigured()) {
            log.warn("失败归因已启用但未配置 api-key，实际不会调用模型");
        }
    }

    /**
     * 任务执行线程收尾时调用。
     *
     * @param standalone 是否为独立运行的任务。线上回归编排驱动的任务不自动重跑——
     *                   编排那边已经按自己的状态机记录了步骤结果，背着它重跑会让两边对不上。
     */
    public void onTaskFinished(String taskId, boolean standalone) {
        if (!enabled || taskId == null) {
            return;
        }
        DiagnosisModelClient client = modelClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("归因已开启但没有可用的模型实现，请检查 diagnosis.ai.provider 取值（qwen / gemini）");
            return;
        }
        if (!client.isConfigured()) {
            return;
        }
        pool.submit(() -> {
            try {
                diagnoseAndAct(taskId, standalone, client);
            } catch (Exception e) {
                log.warn("自动归因失败 taskId={}: {}", taskId, e.getMessage());
            }
        });
    }

    private void diagnoseAndAct(String taskId, boolean standalone, DiagnosisModelClient client)
            throws Exception {
        Task task = taskMapper.findById(taskId);
        if (task == null || task.getStatus() != Task.TaskStatus.FAILED) {
            // 只归因真失败。停止和「本次不适用」不是失败，归因了反而制造噪声
            return;
        }

        Context ctx = diagnosisService.buildContext(taskId);

        // 同一个问题反复重现时复用近期结论：重判一次是同样的答案、几分钟、一次额度，
        // 更糟的是「归因覆盖率」会虚高——每次都有结论，实际一个问题都没解决
        // 失败原因参与签名：同一步换了个原因就是另一个问题，不能复用上次的结论
        String signature = diagnosisService.signatureOf(task,
                ctx.getFailedStep() == null ? null : ctx.getFailedStep().getStep(),
                ctx.getFailedStep() == null ? null : ctx.getFailedStep().getElementKey(),
                ctx.getFailedStep() == null ? null : ctx.getFailedStep().getMessage());
        Verdict verdict = reuseRecent(signature);
        if (verdict != null) {
            log.info("复用近期同签名归因，跳过模型调用 taskId={}, signature={}", taskId, signature);
        } else {
            boolean withImages = maxImages > 0;
            String prompt = FailureDiagnosisPromptRenderer.render(ctx, withImages);
            List<Path> shots = withImages ? screenshotPaths(ctx) : List.of();

            long startedAt = System.currentTimeMillis();
            verdict = client.diagnose(prompt, shots);
            log.info("自动归因完成 taskId={}, category={}, confidence={}, 耗时={}ms",
                    taskId, verdict.getCategory(), verdict.getConfidence(),
                    System.currentTimeMillis() - startedAt);
        }

        TaskFailureDiagnosis saved = diagnosisService.save(taskId, verdict);
        applyActions(task, saved, standalone);
    }

    /**
     * 近期同签名结论直接复制一份。
     * <p>
     * 仍然为当前任务写一条独立记录，而不是在旧记录上累加——每个任务的详情页都要能看到自己的
     * 归因结论；同签名的记录条数天然就成了「这个问题发生了多少次」。
     */
    private Verdict reuseRecent(String signature) {
        TaskFailureDiagnosis prev = diagnosisService.findReusable(signature, REUSE_WINDOW);
        if (prev == null) {
            return null;
        }
        Verdict v = new Verdict();
        try {
            v.setCategory(Category.valueOf(prev.getCategory()));
        } catch (Exception e) {
            return null;
        }
        v.setConfidence(prev.getConfidence());
        v.setReason(prev.getReason());
        v.setSuggestion(prev.getSuggestion());
        v.setSuggestedLocatorType(prev.getSuggestedLocatorType());
        v.setSuggestedLocatorValue(prev.getSuggestedLocatorValue());
        v.setSource(com.mobile.standalone.dto.FailureDiagnosisDtos.Source.AI);
        // 标出来是复用的：统计模型真实调用次数时不能把这些算进去
        v.setModel(prev.getModel() == null ? "复用" : prev.getModel() + " (复用)");
        return v;
    }

    /**
     * 截图取最靠后的几张。
     * <p>
     * 上下文里是时间正序，越靠后离失败越近。张数由客户端按配置再截一次，这里不重复限制。
     */
    private List<Path> screenshotPaths(Context ctx) {
        List<Artifact> shots = ctx.getScreenshots();
        List<Path> out = new ArrayList<>();
        for (int i = shots.size() - 1; i >= 0; i--) {
            String p = shots.get(i).getPath();
            if (p != null && !p.isBlank()) {
                out.add(Path.of(p));
            }
        }
        return out;
    }

    // ==================== 结论驱动动作 ====================

    private void applyActions(Task task, TaskFailureDiagnosis diagnosis, boolean standalone) {
        Category category = parseCategory(diagnosis.getCategory());
        if (category != Category.ENVIRONMENT) {
            // ELEMENT_BROKEN 的候选定位已经随结论存下了，等人一键采纳——
            // 定位是执行的根基，自动改掉万一 AI 判错，后面每条流程都会跟着错，代价太大
            return;
        }
        if (!autoRerunEnabled || !standalone) {
            return;
        }
        if (recentlyAutoRerun(task)) {
            log.info("跳过自动重跑（冷却期内已重跑过）taskId={}", task.getId());
            return;
        }
        try {
            markAutoRerun(task);
            boolean started = taskServiceProvider.getObject().runTask(task.getId());
            log.info("环境问题自动重跑 taskId={}, started={}", task.getId(), started);
        } catch (Exception e) {
            // 设备被占、任务已在跑都会走到这，属于正常情况，不该升级成错误
            log.info("自动重跑未执行 taskId={}: {}", task.getId(), e.getMessage());
        }
    }

    /**
     * 冷却标记写在 task.config 里。
     * <p>
     * 放内存里进程一重启就忘了，环境持续不好时会反复重跑；用执行次数判断又会把
     * 用户手动重跑的历史算进来。写进 config 既durable又只表达「自动重跑过」这一件事。
     */
    private boolean recentlyAutoRerun(Task task) {
        try {
            var node = ExecutionLogJson.mapper().readTree(
                    task.getConfig() == null || task.getConfig().isBlank() ? "{}" : task.getConfig());
            String at = node.path("autoRerunAt").asText(null);
            if (at == null || at.isBlank()) {
                return false;
            }
            return Duration.between(LocalDateTime.parse(at), LocalDateTime.now())
                    .compareTo(RERUN_COOLDOWN) < 0;
        } catch (Exception e) {
            // 解析不了就当没重跑过：宁可多重跑一次，也别因为读不懂配置就永远不重跑
            return false;
        }
    }

    private void markAutoRerun(Task task) {
        try {
            var mapper = ExecutionLogJson.mapper();
            var node = mapper.readTree(
                    task.getConfig() == null || task.getConfig().isBlank() ? "{}" : task.getConfig());
            if (!(node instanceof ObjectNode obj)) {
                return;
            }
            obj.put("autoRerunAt", LocalDateTime.now().toString());
            task.setConfig(mapper.writeValueAsString(obj));
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.update(task);
        } catch (Exception e) {
            log.warn("写入自动重跑标记失败 taskId={}: {}", task.getId(), e.getMessage());
        }
    }

    private static Category parseCategory(String raw) {
        try {
            return raw == null ? Category.UNKNOWN : Category.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return Category.UNKNOWN;
        }
    }
}
