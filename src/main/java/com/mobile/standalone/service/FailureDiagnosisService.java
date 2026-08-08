package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Artifact;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Context;
import com.mobile.standalone.dto.FailureDiagnosisDtos.ElementRef;
import com.mobile.standalone.dto.FailureDiagnosisDtos.LogLine;
import com.mobile.standalone.dto.FailureDiagnosisDtos.PageSource;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Source;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.entity.TaskFailureDiagnosis;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.mapper.TaskFailureDiagnosisMapper;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.util.ExecutionLogJson;
import com.mobile.standalone.util.PageSourceDigest;
import com.mobile.standalone.util.PlatformNames;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 失败归因：把一次失败的现场证据组装成结构化上下文，以及把结论写回任务。
 * <p>
 * 这里刻意不含任何模型调用。上下文组装和结论存储是稳定的部分，模型接哪家、提示词怎么写
 * 都会反复改——混在一起的话每次调模型都要动数据管道。同一份上下文后面做截图语义判定也能直接复用。
 * <p>
 * 组装遵循一条原则：<b>缺什么要说出来</b>。截图被保留策略清掉了、老任务没有控件树留存，
 * 这些缺口如果不显式记进 {@code gaps}，下游（模型或人）看到的就是一份「看起来很完整」的证据，
 * 照样会给出语气笃定的结论——而那正是归因最该避免的失败方式。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FailureDiagnosisService {

    /** 失败点之前保留多少条日志：足够看清「走到这一步之前发生了什么」 */
    private static final int LOG_BEFORE = 15;
    /** 失败点之后保留多少条：收尾日志通常没几条 */
    private static final int LOG_AFTER = 5;
    /** 单条日志 message 的截断长度，控件树 dump 那类长文案会很长 */
    private static final int MESSAGE_MAX = 1000;
    /** 最多带几张截图：最靠近失败那张最关键，往前几张用来看是怎么走到这一步的 */
    private static final int MAX_SCREENSHOTS = 4;
    /** 截图与控件树差多久就值得提醒。3 秒以内属于同一动作内的正常抖动 */
    private static final int SKEW_WARN_SECONDS = 3;

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final TaskMapper taskMapper;
    private final TaskExecutionMapper taskExecutionMapper;
    private final TaskFailureDiagnosisMapper diagnosisMapper;
    private final UiFlowService uiFlowService;
    private final UiElementService uiElementService;

    @Value("${app.screenshot-dir:${user.home}/.mobile-standalone/screenshots}")
    private String screenshotDir;

    // ==================== 上下文组装 ====================

    public Context buildContext(String taskId) {
        Task task = taskMapper.findById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        Context ctx = new Context();
        ctx.setTaskId(task.getId());
        ctx.setTaskName(task.getName());
        ctx.setTaskStatus(task.getStatus() != null ? task.getStatus().name() : null);
        ctx.setTaskType(task.getTaskType() != null ? task.getTaskType().name() : null);
        ctx.setPlatform(PlatformNames.normalize(
                task.getPlatform() != null ? task.getPlatform().name() : null));
        ctx.setDeviceId(task.getDeviceId());
        ctx.setEnvironment(TestEnvironment.label(readEnvironment(task.getConfig())));

        UiFlow flow = resolveFlow(task, ctx);
        String uiAppId = flow != null ? flow.getAppId() : null;
        ctx.setUiAppId(uiAppId);

        TaskExecution execution = latestExecution(taskId);
        List<ExecutionLogEntryDto> logs = List.of();
        if (execution == null) {
            ctx.getGaps().add("该任务没有执行记录，无法归因");
        } else {
            ctx.setExecutionId(execution.getId());
            ctx.setExecutionStatus(execution.getStatus() != null ? execution.getStatus().name() : null);
            ctx.setErrorMessage(execution.getErrorMessage());
            List<ExecutionLogEntryDto> parsed = ExecutionLogJson.parse(execution.getExecutionLog());
            if (parsed == null) {
                ctx.getGaps().add("执行日志为空或无法解析");
            } else {
                logs = parsed;
            }
        }

        int failedIndex = lastFailedIndex(logs);
        if (failedIndex < 0) {
            ctx.getGaps().add("执行日志中没有失败步骤"
                    + "（任务可能是被停止、提前结束，或失败发生在流程之外）");
        } else {
            ctx.setFailedStep(toLine(logs.get(failedIndex), failedIndex));
        }
        ctx.setLogWindow(logWindow(logs, failedIndex));

        collectElements(ctx, logs, failedIndex, uiAppId);
        Path taskDir = taskArtifactDir(taskId);
        collectScreenshots(ctx, logs, failedIndex, taskDir);
        collectPageSource(ctx, taskDir);
        noteEvidenceSkew(ctx);

        ctx.setTextChars(estimateTextChars(ctx));
        return ctx;
    }

    private UiFlow resolveFlow(Task task, Context ctx) {
        String flowId = readFlowId(task.getConfig());
        if (flowId == null) {
            return null;
        }
        ctx.setFlowId(flowId);
        UiFlow flow = uiFlowService.get(flowId);
        if (flow == null) {
            ctx.getGaps().add("流程已被删除，无法回溯步骤定义: " + flowId);
            return null;
        }
        ctx.setFlowName(flow.getName());
        return flow;
    }

    private String readFlowId(String config) {
        if (config == null || config.isBlank()) {
            return null;
        }
        try {
            JsonNode node = ExecutionLogJson.mapper().readTree(config);
            JsonNode v = node.get("flowId");
            if (v == null || v.isNull()) {
                return null;
            }
            String s = v.asText(null);
            return s != null && !s.isBlank() ? s.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 存量任务没有这个字段，一律按生产 */
    private String readEnvironment(String config) {
        if (config == null || config.isBlank()) {
            return TestEnvironment.PROD;
        }
        try {
            return TestEnvironment.normalize(
                    ExecutionLogJson.mapper().readTree(config).path("environment").asText(null));
        } catch (Exception e) {
            return TestEnvironment.PROD;
        }
    }

    private TaskExecution latestExecution(String taskId) {
        List<TaskExecution> list = taskExecutionMapper.findByTaskIdOrderByStartTimeDesc(taskId);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /**
     * 找最后一条真失败。
     * <p>
     * 只认 status=fail：重试过程中的失败尝试和用户点停止都已被降级为 neutral，
     * 把它们当成失败点会指向一个其实已经通过了的步骤。
     */
    private int lastFailedIndex(List<ExecutionLogEntryDto> logs) {
        for (int i = logs.size() - 1; i >= 0; i--) {
            ExecutionLogEntryDto e = logs.get(i);
            if (e != null && "fail".equalsIgnoreCase(e.getStatus())) {
                return i;
            }
        }
        return -1;
    }

    private List<LogLine> logWindow(List<ExecutionLogEntryDto> logs, int failedIndex) {
        List<LogLine> out = new ArrayList<>();
        if (logs.isEmpty()) {
            return out;
        }
        int center = failedIndex >= 0 ? failedIndex : logs.size() - 1;
        int from = Math.max(0, center - LOG_BEFORE);
        int to = Math.min(logs.size() - 1, center + LOG_AFTER);
        // 首条记录的是流程名与执行参数，是判断「跑的是哪条流程」的锚点，窗口外也要带上
        if (from > 0) {
            out.add(toLine(logs.get(0), 0));
        }
        for (int i = from; i <= to; i++) {
            out.add(toLine(logs.get(i), i));
        }
        return out;
    }

    private LogLine toLine(ExecutionLogEntryDto e, int index) {
        LogLine l = new LogLine();
        l.setIndex(index);
        l.setStep(e.getStep());
        l.setStatus(e.getStatus());
        l.setLevel(e.getLevel());
        l.setMessage(clip(e.getMessage(), MESSAGE_MAX));
        l.setSelector(clip(e.getSelector(), 300));
        l.setElementKey(e.getElementKey());
        l.setScreenshotSettled(e.getScreenshotSettled());
        l.setTimestamp(e.getTimestamp() != null ? e.getTimestamp().format(TS) : null);
        return l;
    }

    // ==================== 元素 ====================

    /**
     * 取失败步骤引用的元素行。
     * <p>
     * 归因判 ELEMENT_BROKEN 的依据，就是把这里的定位串和控件树对一遍：
     * 树里有对应控件但定位串对不上 → 元素坏了；树里压根没有 → 多半是没走到这个页面。
     */
    private void collectElements(Context ctx, List<ExecutionLogEntryDto> logs, int failedIndex, String uiAppId) {
        Set<String> keys = new LinkedHashSet<>();
        if (failedIndex >= 0) {
            addKey(keys, logs.get(failedIndex));
            // 失败前那几条常是同一元素的重试/回退尝试，一并带上便于看出试过哪些定位
            for (int i = Math.max(0, failedIndex - LOG_BEFORE); i < failedIndex; i++) {
                addKey(keys, logs.get(i));
            }
        }
        if (keys.isEmpty()) {
            if (failedIndex >= 0) {
                ctx.getGaps().add("失败步骤没有关联元素"
                        + "（可能是启动/等待类步骤，也可能是本次归因前执行的老任务还没记录 elementKey）");
            }
            return;
        }
        if (uiAppId == null) {
            ctx.getGaps().add("流程未绑定应用字典，无法回表取元素定位");
            return;
        }
        for (String key : keys) {
            ctx.getElements().add(toElementRef(key, uiAppId, ctx.getPlatform()));
        }
    }

    private void addKey(Set<String> keys, ExecutionLogEntryDto e) {
        if (e != null && e.getElementKey() != null && !e.getElementKey().isBlank()) {
            keys.add(e.getElementKey().trim());
        }
    }

    private ElementRef toElementRef(String key, String uiAppId, String platform) {
        ElementRef ref = new ElementRef();
        ref.setNameEn(key);
        UiElement el = findAcrossLegacyPlatforms(uiAppId, platform, key);
        if (el == null) {
            // 「元素查不到」本身就是有力证据：多半是被停用或改名了
            ref.setMissingReason("元素表中无 ACTIVE 记录: appId=" + uiAppId + ", platform=" + platform);
            return ref;
        }
        ref.setId(el.getId());
        ref.setNameZh(el.getNameZh());
        ref.setVisualHint(el.getVisualHint());
        ref.setPageId(el.getPageId());
        ref.setPlatform(el.getPlatform());
        ref.setLocatorType(el.getLocatorType());
        ref.setLocatorValue(el.getLocatorValue());
        ref.setStatus(el.getStatus());
        ref.setSource(el.getSource());
        ref.setLastVerifiedAt(el.getLastVerifiedAt() != null ? el.getLastVerifiedAt().format(TS) : null);
        ref.setLocatorFallbacks(parseFallbacks(el.getLocatorFallbacks()));
        return ref;
    }

    private UiElement findAcrossLegacyPlatforms(String uiAppId, String platform, String key) {
        String normalized = PlatformNames.normalize(platform);
        UiElement el = uiElementService.findActiveByKey(uiAppId, normalized, key);
        if (el != null) {
            return el;
        }
        for (String legacy : PlatformNames.legacyAliasesOf(normalized)) {
            el = uiElementService.findActiveByKey(uiAppId, legacy, key);
            if (el != null) {
                return el;
            }
        }
        return null;
    }

    private List<String> parseFallbacks(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode arr = ExecutionLogJson.mapper().readTree(json);
            if (arr == null || !arr.isArray()) {
                return out;
            }
            for (JsonNode n : arr) {
                String type = n.path("locatorType").asText(null);
                String value = n.path("locatorValue").asText(null);
                if (value != null && !value.isBlank()) {
                    out.add((type != null ? type + "=" : "") + value);
                }
            }
        } catch (Exception ignored) {
            // 备用定位解析不了不影响主流程，主定位仍在
        }
        return out;
    }

    // ==================== 现场文件 ====================

    private Path taskArtifactDir(String taskId) {
        return Path.of(screenshotDir, taskId).normalize().toAbsolutePath();
    }

    /**
     * 挑现场截图。
     * <p>
     * 优先用日志里 screenshotPath 指向的文件——那是执行器当时确定写下的，
     * 比按目录时间猜可靠。日志里没有（老任务）才回退去扫目录取最新几张。
     */
    private void collectScreenshots(Context ctx, List<ExecutionLogEntryDto> logs, int failedIndex, Path taskDir) {
        List<Artifact> picked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int center = failedIndex >= 0 ? failedIndex : logs.size() - 1;
        for (int i = center; i >= 0 && picked.size() < MAX_SCREENSHOTS; i--) {
            ExecutionLogEntryDto e = logs.get(i);
            if (e == null || e.getScreenshotPath() == null || e.getScreenshotPath().isBlank()) {
                continue;
            }
            Path p = Path.of(e.getScreenshotPath()).normalize().toAbsolutePath();
            if (!seen.add(p.toString()) || !Files.isRegularFile(p)) {
                continue;
            }
            Artifact a = toArtifact(p);
            a.setStep(e.getStep());
            a.setSettled(e.getScreenshotSettled());
            picked.add(a);
        }
        if (picked.isEmpty()) {
            picked.addAll(scanRecentScreenshots(taskDir));
        }
        // 上面是从失败点往回找的，反转成时间正序，读起来才是「怎么一步步走到失败」
        java.util.Collections.reverse(picked);
        ctx.setScreenshots(picked);

        if (picked.isEmpty()) {
            ctx.getGaps().add("没有找到截图"
                    + "（可能已被保留策略清理，或该任务执行时关闭了截图）");
        }
    }

    private List<Artifact> scanRecentScreenshots(Path taskDir) {
        if (!Files.isDirectory(taskDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(taskDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .limit(MAX_SCREENSHOTS)
                    .map(this::toArtifact)
                    .toList();
        } catch (IOException e) {
            log.warn("扫描任务截图目录失败 {}: {}", taskDir, e.getMessage());
            return List.of();
        }
    }

    private Artifact toArtifact(Path p) {
        Artifact a = new Artifact();
        a.setPath(p.toString());
        a.setFileName(p.getFileName().toString());
        try {
            a.setSizeBytes(Files.size(p));
            a.setModifiedAt(LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault()).format(TS));
        } catch (IOException ignored) {
            // 文件刚好被清理线程删掉：路径信息仍有价值，大小留 0
        }
        return a;
    }

    /**
     * 取失败瞬间的控件树。
     * <p>
     * 截图只能看出「页面长这样」，分不清元素是没渲染还是渲染了但被弹窗盖住、滚出了屏幕——
     * 而这两种的修法完全不同。控件树能直接回答。
     */
    private void collectPageSource(Context ctx, Path taskDir) {
        Path newest = newestPageSource(taskDir);
        if (newest == null) {
            ctx.getGaps().add("没有失败现场控件树"
                    + "（只有失败步骤才会留存；该任务可能执行于此功能上线前，或已被清理）");
            return;
        }
        String xml;
        try {
            xml = Files.readString(newest);
        } catch (IOException e) {
            ctx.getGaps().add("控件树读取失败: " + e.getMessage());
            return;
        }
        PageSourceDigest.Result r = PageSourceDigest.of(xml);
        PageSource ps = new PageSource();
        ps.setPath(newest.toString());
        ps.setOutline(r.outline());
        ps.setTotalNodes(r.totalNodes());
        ps.setKeptNodes(r.keptNodes());
        ps.setTruncated(r.truncated());
        ps.setRootTag(r.rootTag());
        ps.setVisibleTexts(PageSourceDigest.visibleTexts(xml, 40));
        try {
            ps.setCapturedAt(LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(newest).toInstant(), ZoneId.systemDefault()).format(TS));
        } catch (IOException ignored) {
            // 时间戳拿不到不影响判读
        }
        ctx.setPageSource(ps);
        if (r.truncated()) {
            ctx.getGaps().add("控件树过大已截断，保留 " + r.keptNodes() + "/" + r.totalNodes()
                    + " 个节点，页面下半部分的控件可能不在其中");
        }
    }

    /**
     * 截图与控件树不是同一时刻拍的，差得多了要说出来。
     * <p>
     * 步骤截图拍在动作**开始前**，控件树存在最终失败**之后**。中间隔着重试和逐个尝试备用定位——
     * 实测一次点击失败能隔 31 秒。这两份证据要是显示的不是同一个页面，判读的人会当成矛盾去解释，
     * 而实际上只是时间差。不点破的话，这个坑每次都要重踩。
     */
    private void noteEvidenceSkew(Context ctx) {
        if (ctx.getPageSource() == null || ctx.getPageSource().getCapturedAt() == null
                || ctx.getScreenshots().isEmpty()) {
            return;
        }
        Artifact last = ctx.getScreenshots().get(ctx.getScreenshots().size() - 1);
        if (last.getModifiedAt() == null) {
            return;
        }
        try {
            long seconds = Duration.between(
                    LocalDateTime.parse(last.getModifiedAt(), TS),
                    LocalDateTime.parse(ctx.getPageSource().getCapturedAt(), TS)).getSeconds();
            if (seconds >= SKEW_WARN_SECONDS) {
                ctx.getGaps().add("最后一张截图拍摄于控件树之前 " + seconds
                        + " 秒（点击重试、逐个尝试备用定位都会拉开这段时间）。"
                        + "截图是动作开始前的画面，控件树才是失败当时的状态；两者显示的页面不一致属于正常。");
            }
        } catch (Exception ignored) {
            // 时间解析不了就不提示，不值得为此让整个上下文失败
        }
    }

    private Path newestPageSource(Path taskDir) {
        if (!Files.isDirectory(taskDir)) {
            return null;
        }
        try (Stream<Path> s = Files.list(taskDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("pagesource-") && n.endsWith(".xml");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            log.warn("扫描控件树失败 {}: {}", taskDir, e.getMessage());
            return null;
        }
    }

    /** 粗算文本证据体量，调用方据此决定要不要再压缩 */
    private int estimateTextChars(Context ctx) {
        int n = 0;
        for (LogLine l : ctx.getLogWindow()) {
            n += len(l.getStep()) + len(l.getMessage()) + len(l.getSelector());
        }
        for (ElementRef e : ctx.getElements()) {
            n += len(e.getLocatorValue()) + len(e.getVisualHint()) + len(e.getMissingReason());
            for (String fb : e.getLocatorFallbacks()) {
                n += len(fb);
            }
        }
        if (ctx.getPageSource() != null) {
            n += len(ctx.getPageSource().getOutline());
        }
        return n;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /** 给人看的截断：会附加说明，长度可能略超 max */
    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }

    /** 落库用的硬截断：附加说明会把长度顶过列宽，这里保证不超 */
    private static String fit(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    // ==================== 结论回写 ====================

    public TaskFailureDiagnosis save(String taskId, Verdict verdict) {
        if (verdict == null || verdict.getCategory() == null) {
            throw new IllegalArgumentException("归因结论必须给出 category");
        }
        Task task = taskMapper.findById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        Integer confidence = verdict.getConfidence();
        if (confidence != null && (confidence < 0 || confidence > 100)) {
            throw new IllegalArgumentException("confidence 需在 0-100 之间: " + confidence);
        }

        TaskFailureDiagnosis row = new TaskFailureDiagnosis();
        row.setId(UUID.randomUUID().toString().replace("-", ""));
        row.setTaskId(taskId);
        row.setCategory(verdict.getCategory().name());
        row.setConfidence(confidence);
        row.setReason(verdict.getReason());
        row.setSuggestion(verdict.getSuggestion());
        row.setSuggestedLocatorType(fit(verdict.getSuggestedLocatorType(), 32));
        row.setSuggestedLocatorValue(fit(verdict.getSuggestedLocatorValue(), 1000));
        row.setSource((verdict.getSource() != null ? verdict.getSource() : Source.AI).name());
        row.setModel(verdict.getModel());
        row.setCreatedBy(verdict.getCreatedBy());
        row.setCreatedAt(LocalDateTime.now());

        // 失败步骤与元素 key 由服务端从执行记录回填，不接受调用方传入——
        // 结论必须钉在真实发生过的那一步上，否则事后没法验证归因准不准
        TaskExecution execution = latestExecution(taskId);
        row.setExecutionId(execution != null ? execution.getId() : null);
        if (execution != null) {
            List<ExecutionLogEntryDto> logs = ExecutionLogJson.parse(execution.getExecutionLog());
            if (logs != null) {
                int idx = lastFailedIndex(logs);
                if (idx >= 0) {
                    ExecutionLogEntryDto failed = logs.get(idx);
                    row.setFailedStep(fit(failed.getStep(), 255));
                    row.setElementKey(fit(failed.getElementKey(), 128));
                }
            }
        }

        row.setSignature(signatureOf(task, row.getFailedStep(), row.getElementKey()));
        diagnosisMapper.insert(row);
        log.info("写入失败归因: taskId={}, category={}, source={}, signature={}",
                taskId, row.getCategory(), row.getSource(), row.getSignature());
        return row;
    }

    public List<TaskFailureDiagnosis> history(String taskId) {
        List<TaskFailureDiagnosis> list = diagnosisMapper.findByTaskId(taskId);
        if (list == null) {
            return List.of();
        }
        list.forEach(this::fillOccurrence);
        return list;
    }

    /**
     * 去重签名：流程 + 失败步骤 + 元素 key。
     * <p>
     * 不含 taskId：同一条流程每晚回归都会生成新任务，带上 taskId 就永远对不上，去重也就失效了。
     */
    String signatureOf(Task task, String failedStep, String elementKey) {
        if (task == null) {
            return null;
        }
        String flowId = readFlowId(task.getConfig());
        String step = failedStep == null ? "" : failedStep.trim();
        String key = elementKey == null ? "" : elementKey.trim();
        if (flowId == null && step.isEmpty() && key.isEmpty()) {
            return null;
        }
        // 带上环境：同一个问题在测试环境和生产环境是两个独立问题——测试环境挂了不代表
        // 生产也挂。签名不区分环境会互相复用结论，把「测试服数据不同导致的失败」
        // 直接安到生产头上
        String env = readEnvironment(task.getConfig());
        return fit(env + "|" + (flowId == null ? task.getTaskType() : flowId)
                + "|" + step + "|" + key, 255);
    }

    /** 同签名累计发生次数：一个问题挂了 30 次和挂了 1 次，值不值得先修完全不同 */
    private void fillOccurrence(TaskFailureDiagnosis d) {
        if (d != null && d.getSignature() != null && !d.getSignature().isBlank()) {
            d.setOccurrenceCount(diagnosisMapper.countBySignature(d.getSignature()));
        }
    }

    /**
     * 近期同签名的 AI 结论，供复用而不是重复调模型。
     * <p>
     * 同一个问题每晚回归都重现，重判一次就是同样的结论、几分钟、一次额度。更糟的是
     * 「归因覆盖率」会因此虚高：每次都有结论，但一个问题都没被解决。
     */
    public TaskFailureDiagnosis findReusable(String signature, java.time.Duration within) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        return diagnosisMapper.findLatestBySignature(signature, LocalDateTime.now().minus(within));
    }

    /**
     * 人工复核：认可或改判都算看过。
     * <p>
     * 没有这个动作，「人工改判率」恒为 0——区分不了「AI 判对了」和「根本没人看」，
     * 那个指标也就没法用来判断归因到底准不准。
     */
    public TaskFailureDiagnosis review(String diagnosisId, String operator) {
        TaskFailureDiagnosis d = diagnosisMapper.findById(diagnosisId);
        if (d == null) {
            throw new IllegalArgumentException("归因记录不存在: " + diagnosisId);
        }
        LocalDateTime now = LocalDateTime.now();
        diagnosisMapper.markReviewed(diagnosisId, now, operator);
        d.setReviewedAt(now);
        d.setReviewedBy(operator);
        return d;
    }

    /**
     * 当前结论：人工优先。
     * <p>
     * AI 归错了人会来纠正，纠正之后就该以人的判断为准，不能被下一次 AI 重跑覆盖回去。
     */
    public TaskFailureDiagnosis current(String taskId) {
        List<TaskFailureDiagnosis> list = history(taskId);
        return list.stream()
                .filter(d -> Source.HUMAN.name().equalsIgnoreCase(d.getSource()))
                .findFirst()
                .orElseGet(() -> list.isEmpty() ? null : list.get(0));
    }

    public void delete(String id) {
        diagnosisMapper.deleteById(id);
    }

    /**
     * 采纳 AI 给出的候选定位，写回元素行。
     * <p>
     * <b>原定位不会丢</b>：它被降级进备用定位列表的第一位。AI 判错时执行仍会回退到旧定位试一次，
     * 人回头也能看出改之前是什么。定位是执行的根基，一旦覆盖没了，一次误判会让后面每条
     * 引用这个元素的流程全部跟着错——这个代价远大于多留一条备用定位。
     * <p>
     * 同样的理由，这一步只做在人点了「采纳」之后，不跟着归因自动发生。
     */
    public UiElement applySuggestedLocator(String diagnosisId, String operator) {
        TaskFailureDiagnosis d = diagnosisMapper.findById(diagnosisId);
        if (d == null) {
            throw new IllegalArgumentException("归因记录不存在: " + diagnosisId);
        }
        if (d.getAppliedAt() != null) {
            throw new IllegalStateException("该候选定位已采纳过，不重复写入");
        }
        String value = d.getSuggestedLocatorValue();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("该归因没有给出候选定位");
        }
        if (d.getElementKey() == null || d.getElementKey().isBlank()) {
            throw new IllegalStateException("该归因没有关联元素，无法确定要改哪一行");
        }

        Task task = taskMapper.findById(d.getTaskId());
        if (task == null) {
            throw new IllegalStateException("任务已被删除，无法定位应用字典: " + d.getTaskId());
        }
        UiFlow flow = uiFlowService.get(readFlowId(task.getConfig()));
        String uiAppId = flow != null ? flow.getAppId() : null;
        if (uiAppId == null) {
            throw new IllegalStateException("流程未绑定应用字典，无法确定要改哪一行元素");
        }
        String platform = PlatformNames.normalize(
                task.getPlatform() != null ? task.getPlatform().name() : null);
        UiElement el = findAcrossLegacyPlatforms(uiAppId, platform, d.getElementKey());
        if (el == null) {
            throw new IllegalStateException("元素表中查不到 " + d.getElementKey() + "，可能已被停用或改名");
        }

        String oldType = el.getLocatorType();
        String oldValue = el.getLocatorValue();

        UiElement patch = new UiElement();
        patch.setLocatorType(firstNonBlank(d.getSuggestedLocatorType(), oldType, "xpath"));
        patch.setLocatorValue(value);
        patch.setLocatorFallbacks(prependFallback(el.getLocatorFallbacks(), oldType, oldValue));
        UiElement updated = uiElementService.update(el.getId(), patch);

        diagnosisMapper.markApplied(diagnosisId, LocalDateTime.now());
        log.info("采纳候选定位: element={}, {} → {}, 操作人={}",
                d.getElementKey(), oldValue, value, operator);
        return updated;
    }

    /** 旧定位插到备用列表最前面：它刚刚还是主定位，是最有可能仍然管用的那一条 */
    private String prependFallback(String fallbacksJson, String oldType, String oldValue) {
        if (oldValue == null || oldValue.isBlank()) {
            return fallbacksJson;
        }
        try {
            ArrayNode arr = ExecutionLogJson.mapper().createArrayNode();
            ObjectNode demoted = arr.addObject();
            demoted.put("locatorType", oldType != null ? oldType : "xpath");
            demoted.put("locatorValue", oldValue);
            if (fallbacksJson != null && !fallbacksJson.isBlank()) {
                JsonNode existing = ExecutionLogJson.mapper().readTree(fallbacksJson);
                if (existing != null && existing.isArray()) {
                    for (JsonNode n : existing) {
                        // 新主定位如果本来就在备用里，留着就成了自己回退到自己
                        if (!oldValue.equals(n.path("locatorValue").asText(null))) {
                            arr.add(n);
                        }
                    }
                }
            }
            return ExecutionLogJson.mapper().writeValueAsString(arr);
        } catch (Exception e) {
            log.warn("合并备用定位失败，保留原备用列表: {}", e.getMessage());
            return fallbacksJson;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 仅用于测试注入截图根目录 */
    void setScreenshotDir(String dir) {
        this.screenshotDir = dir;
    }
}
