package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Context;
import com.mobile.standalone.dto.FailureDiagnosisDtos.ElementRef;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败归因的上下文组装与结论回写。
 * <p>
 * 这套管道最容易出错的地方不是「组装不出来」，而是「组装出一份看着完整、其实缺证据的上下文」——
 * 下游拿到之后照样会给出语气笃定的结论。所以缺口上报的用例和正常路径同样重要。
 */
class FailureDiagnosisServiceTest {

    private static final String TASK_ID = "task-1";
    private static final String FLOW_ID = "flow-login";
    private static final String UI_APP_ID = "app-demo";

    @TempDir
    Path tempRoot;

    private Path screenshotRoot;
    private Path taskDir;

    private FailureDiagnosisService service;
    private TaskMapper taskMapper;
    private TaskExecutionMapper executionMapper;
    private TaskFailureDiagnosisMapper diagnosisMapper;
    private UiFlowService uiFlowService;
    private UiElementService uiElementService;

    private final List<TaskFailureDiagnosis> saved = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        screenshotRoot = Files.createDirectories(tempRoot.resolve("screenshots"));
        taskDir = Files.createDirectories(screenshotRoot.resolve(TASK_ID));

        taskMapper = Mockito.mock(TaskMapper.class);
        executionMapper = Mockito.mock(TaskExecutionMapper.class);
        diagnosisMapper = Mockito.mock(TaskFailureDiagnosisMapper.class);
        uiFlowService = Mockito.mock(UiFlowService.class);
        uiElementService = Mockito.mock(UiElementService.class);

        Mockito.when(diagnosisMapper.insert(ArgumentMatchers.any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return 1;
        });
        Mockito.when(diagnosisMapper.findByTaskId(TASK_ID)).thenReturn(saved);

        service = new FailureDiagnosisService(taskMapper, executionMapper, diagnosisMapper,
                uiFlowService, uiElementService);
        service.setScreenshotDir(screenshotRoot.toString());

        givenTask();
        givenFlow();
    }

    // ==================== 装配 ====================

    private void givenTask() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setName("登录流程");
        task.setPlatform(Task.Platform.ANDROID);
        task.setStatus(Task.TaskStatus.FAILED);
        task.setTaskType(Task.TaskType.UI_FLOW_TEST);
        task.setDeviceId("emulator-5554");
        task.setConfig("{\"flowId\":\"" + FLOW_ID + "\"}");
        Mockito.when(taskMapper.findById(TASK_ID)).thenReturn(task);
    }

    private void givenFlow() {
        UiFlow flow = new UiFlow();
        flow.setId(FLOW_ID);
        flow.setName("登录");
        flow.setAppId(UI_APP_ID);
        Mockito.when(uiFlowService.get(FLOW_ID)).thenReturn(flow);
    }

    private void givenExecution(List<ExecutionLogEntryDto> logs) {
        TaskExecution exec = new TaskExecution();
        exec.setId("exec-1");
        exec.setTaskId(TASK_ID);
        exec.setStatus(TaskExecution.ExecutionStatus.FAILED);
        exec.setErrorMessage("点击登录按钮失败");
        try {
            exec.setExecutionLog(ExecutionLogJson.mapper().writeValueAsString(logs));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Mockito.when(executionMapper.findByTaskIdOrderByStartTimeDesc(TASK_ID)).thenReturn(List.of(exec));
    }

    private void givenElement(String key) {
        UiElement el = new UiElement();
        el.setId("el-1");
        el.setNameEn(key);
        el.setNameZh("登录按钮");
        el.setVisualHint("底部蓝色通栏按钮");
        el.setPlatform("ANDROID");
        el.setLocatorType("id");
        el.setLocatorValue("com.demo.app:id/login_btn");
        el.setLocatorFallbacks("[{\"locatorType\":\"xpath\",\"locatorValue\":\"//*[@text='登录']\"}]");
        el.setStatus("ACTIVE");
        Mockito.when(uiElementService.findActiveByKey(UI_APP_ID, "ANDROID", key)).thenReturn(el);
    }

    private static ExecutionLogEntryDto failWith(String step, String msg, String selector, String elementKey) {
        return ExecutionLogEntryDto.fail(step, msg, selector, elementKey);
    }

    // ==================== 失败步骤定位 ====================

    /**
     * 重试过程中的失败会被降级成 neutral，用户点停止也是 neutral。
     * 把它们当失败点会把结论钉在一个其实已经通过了的步骤上。
     */
    @Test
    void 只认真失败不把重试与停止的中立事件当失败点() {
        givenExecution(List.of(
                ExecutionLogEntryDto.success("1 · 启动应用", "已启动"),
                ExecutionLogEntryDto.neutral("2 · 准备重试", "第 1/2 次失败，1000ms 后重试"),
                ExecutionLogEntryDto.success("2 · 输入手机号", "已输入 138****8000"),
                failWith("3 · 点击登录", "未找到元素", "id=login_btn", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertNotNull(ctx.getFailedStep());
        assertEquals("3 · 点击登录", ctx.getFailedStep().getStep());
        assertEquals("login_submit_btn", ctx.getFailedStep().getElementKey());
    }

    @Test
    void 多次失败时取最后一次() {
        givenExecution(List.of(
                failWith("2 · 点击同意", "未找到", "id=agree", "agree_btn"),
                ExecutionLogEntryDto.success("3 · 输入手机号", "ok"),
                failWith("4 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertEquals("4 · 点击登录", ctx.getFailedStep().getStep());
    }

    @Test
    void 没有失败步骤时明确记入缺口而不是静默返回空() {
        givenExecution(List.of(
                ExecutionLogEntryDto.success("1 · 启动应用", "已启动"),
                ExecutionLogEntryDto.neutral("2 · 已停止", "用户请求停止")));

        Context ctx = service.buildContext(TASK_ID);

        assertNull(ctx.getFailedStep());
        assertTrue(ctx.getGaps().stream().anyMatch(g -> g.contains("没有失败步骤")), ctx.getGaps().toString());
    }

    @Test
    void 任务不存在直接报错() {
        Mockito.when(taskMapper.findById("nope")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.buildContext("nope"));
    }

    @Test
    void 没有执行记录时记入缺口() {
        Mockito.when(executionMapper.findByTaskIdOrderByStartTimeDesc(TASK_ID)).thenReturn(List.of());

        Context ctx = service.buildContext(TASK_ID);

        assertTrue(ctx.getGaps().stream().anyMatch(g -> g.contains("没有执行记录")), ctx.getGaps().toString());
    }

    // ==================== 日志窗口 ====================

    /**
     * 首条日志记的是流程名与执行参数。窗口滑走之后如果不单独补回来，
     * 下游就不知道这次跑的到底是哪条流程。
     */
    @Test
    void 日志窗口始终带上首条流程信息() {
        List<ExecutionLogEntryDto> logs = new ArrayList<>();
        logs.add(ExecutionLogEntryDto.success("开始执行流程", "登录 (flow-login)"));
        for (int i = 0; i < 40; i++) {
            logs.add(ExecutionLogEntryDto.success("步骤 " + i, "ok"));
        }
        logs.add(failWith("41 · 点击登录", "未找到", "id=login", "login_submit_btn"));
        givenExecution(logs);

        Context ctx = service.buildContext(TASK_ID);

        assertEquals("开始执行流程", ctx.getLogWindow().get(0).getStep());
        assertTrue(ctx.getLogWindow().size() < logs.size(), "窗口应远小于完整日志");
        assertTrue(ctx.getLogWindow().stream().anyMatch(l -> "41 · 点击登录".equals(l.getStep())));
    }

    /** 下标要指向完整日志中的真实位置，前端才能跳转过去 */
    @Test
    void 日志行保留在完整日志中的下标() {
        givenExecution(List.of(
                ExecutionLogEntryDto.success("a", "1"),
                ExecutionLogEntryDto.success("b", "2"),
                failWith("c", "boom", null, null)));

        Context ctx = service.buildContext(TASK_ID);

        assertEquals(2, ctx.getFailedStep().getIndex());
    }

    // ==================== 元素 ====================

    @Test
    void 取到失败步骤的元素定位与备用定位() {
        givenElement("login_submit_btn");
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertEquals(1, ctx.getElements().size());
        ElementRef ref = ctx.getElements().get(0);
        assertEquals("login_submit_btn", ref.getNameEn());
        assertEquals("com.demo.app:id/login_btn", ref.getLocatorValue());
        assertEquals(List.of("xpath=//*[@text='登录']"), ref.getLocatorFallbacks());
        assertEquals("底部蓝色通栏按钮", ref.getVisualHint());
        assertNull(ref.getMissingReason());
    }

    /**
     * 元素查不到本身就是结论：多半是被停用或改名了。
     * 这时候不能悄悄跳过，否则下游会以为这一步不涉及元素。
     */
    @Test
    void 元素查不到时给出原因而不是跳过() {
        Mockito.when(uiElementService.findActiveByKey(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(null);
        givenExecution(List.of(failWith("3 · 点击登录", "无 ACTIVE 元素", "login_submit_btn", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertEquals(1, ctx.getElements().size());
        assertNotNull(ctx.getElements().get(0).getMissingReason());
    }

    /** 存量元素还挂在 POG_ANDROID 下，按 ANDROID 查不到要再回退查一次 */
    @Test
    void 元素能按历史平台名回退查到() {
        UiElement legacy = new UiElement();
        legacy.setId("el-legacy");
        legacy.setNameEn("login_submit_btn");
        legacy.setPlatform("POG_ANDROID");
        legacy.setLocatorType("id");
        legacy.setLocatorValue("com.demo.app:id/login_btn");
        Mockito.when(uiElementService.findActiveByKey(UI_APP_ID, "ANDROID", "login_submit_btn")).thenReturn(null);
        Mockito.when(uiElementService.findActiveByKey(UI_APP_ID, "POG_ANDROID", "login_submit_btn"))
                .thenReturn(legacy);
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertEquals("el-legacy", ctx.getElements().get(0).getId());
        assertNull(ctx.getElements().get(0).getMissingReason());
    }

    /** 老任务的日志里没有 elementKey，得说明是「拿不到」而不是「这步没元素」 */
    @Test
    void 失败步骤没有元素key时记入缺口() {
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", null)));

        Context ctx = service.buildContext(TASK_ID);

        assertTrue(ctx.getElements().isEmpty());
        assertTrue(ctx.getGaps().stream().anyMatch(g -> g.contains("没有关联元素")), ctx.getGaps().toString());
    }

    // ==================== 现场文件 ====================

    @Test
    void 优先用日志里记录的截图路径() throws IOException {
        Path shot = taskDir.resolve("step-100.png");
        Files.write(shot, new byte[]{1, 2, 3});
        Path unrelated = taskDir.resolve("step-999.png");
        Files.write(unrelated, new byte[]{4});

        ExecutionLogEntryDto failed = failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn");
        failed.setScreenshotPath(shot.toString());
        failed.setScreenshotSettled(true);
        givenExecution(List.of(failed));

        Context ctx = service.buildContext(TASK_ID);

        assertEquals(1, ctx.getScreenshots().size());
        assertEquals("step-100.png", ctx.getScreenshots().get(0).getFileName());
        assertEquals(3, ctx.getScreenshots().get(0).getSizeBytes());
        assertEquals(Boolean.TRUE, ctx.getScreenshots().get(0).getSettled());
    }

    /** 老任务日志里没有 screenshotPath，还能靠扫目录兜住 */
    @Test
    void 日志没有截图路径时回退扫目录() throws IOException {
        Files.write(taskDir.resolve("step-100.png"), new byte[]{1});
        Files.write(taskDir.resolve("step-200.png"), new byte[]{2});
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertEquals(2, ctx.getScreenshots().size());
    }

    /** 截图被保留策略清掉是常态，必须让下游知道自己是在看无图的证据 */
    @Test
    void 没有截图时记入缺口() {
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertTrue(ctx.getScreenshots().isEmpty());
        assertTrue(ctx.getGaps().stream().anyMatch(g -> g.contains("没有找到截图")), ctx.getGaps().toString());
    }

    @Test
    void 取最新的控件树并压成摘要() throws IOException {
        Files.writeString(taskDir.resolve("pagesource-100.xml"),
                "<hierarchy><android.widget.TextView text=\"旧页面\"/></hierarchy>");
        Path newer = taskDir.resolve("pagesource-200.xml");
        Files.writeString(newer,
                "<hierarchy><android.widget.TextView text=\"验证码已发送\" resource-id=\"a:id/tip\"/></hierarchy>");
        newer.toFile().setLastModified(System.currentTimeMillis() + 5000);
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertNotNull(ctx.getPageSource());
        assertTrue(ctx.getPageSource().getOutline().contains("验证码已发送"));
        assertFalse(ctx.getPageSource().getOutline().contains("旧页面"));
        assertEquals(List.of("验证码已发送"), ctx.getPageSource().getVisibleTexts());
    }

    @Test
    void 没有控件树时记入缺口() {
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertNull(ctx.getPageSource());
        assertTrue(ctx.getGaps().stream().anyMatch(g -> g.contains("控件树")), ctx.getGaps().toString());
    }

    /**
     * 截图拍在动作开始前，控件树存在最终失败之后，中间隔着重试和逐个尝试备用定位——
     * 实测能差 31 秒。不点破的话，两份证据显示不同页面会被当成矛盾去解释。
     */
    @Test
    void 截图与控件树时间差过大时提醒() throws IOException {
        Path shot = taskDir.resolve("step-1.png");
        Files.write(shot, new byte[]{1});
        Path tree = taskDir.resolve("pagesource-1.xml");
        Files.writeString(tree, "<hierarchy><android.widget.TextView text=\"登录\"/></hierarchy>");
        long now = System.currentTimeMillis();
        shot.toFile().setLastModified(now - 31_000);
        tree.toFile().setLastModified(now);
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertTrue(ctx.getGaps().stream().anyMatch(g -> g.contains("秒") && g.contains("截图")),
                ctx.getGaps().toString());
    }

    /** 同一动作内几百毫秒的抖动不值得提醒，提多了就没人看了 */
    @Test
    void 时间差很小时不提醒() throws IOException {
        Path shot = taskDir.resolve("step-1.png");
        Files.write(shot, new byte[]{1});
        Path tree = taskDir.resolve("pagesource-1.xml");
        Files.writeString(tree, "<hierarchy><android.widget.TextView text=\"登录\"/></hierarchy>");
        long now = System.currentTimeMillis();
        shot.toFile().setLastModified(now - 500);
        tree.toFile().setLastModified(now);
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertFalse(ctx.getGaps().stream().anyMatch(g -> g.contains("拍摄于控件树之前")),
                ctx.getGaps().toString());
    }

    /** 文本体量要能被上层看到，否则控件树一大就会把上下文顶爆 */
    @Test
    void 统计文本证据体量() throws IOException {
        Files.writeString(taskDir.resolve("pagesource-1.xml"),
                "<hierarchy><android.widget.TextView text=\"登录\" resource-id=\"a:id/t\"/></hierarchy>");
        givenExecution(List.of(failWith("3 · 点击登录", "未找到元素，试过 2 个定位", "id=login", "login_submit_btn")));

        Context ctx = service.buildContext(TASK_ID);

        assertTrue(ctx.getTextChars() > 0);
    }

    // ==================== 结论回写 ====================

    @Test
    void 写回结论时由服务端回填失败步骤与元素key() {
        givenExecution(List.of(
                ExecutionLogEntryDto.success("1 · 启动应用", "ok"),
                failWith("3 · 点击登录", "未找到", "id=login", "login_submit_btn")));

        Verdict v = new Verdict();
        v.setCategory(Category.ELEMENT_BROKEN);
        v.setConfidence(80);
        v.setReason("控件树里有登录按钮，但 resource-id 已改名");
        v.setModel("claude-opus-5");

        TaskFailureDiagnosis row = service.save(TASK_ID, v);

        // 调用方传什么都不影响：结论必须钉在真实发生过的那一步上，事后才验得了准不准
        assertEquals("3 · 点击登录", row.getFailedStep());
        assertEquals("login_submit_btn", row.getElementKey());
        assertEquals("exec-1", row.getExecutionId());
        assertEquals("ELEMENT_BROKEN", row.getCategory());
        assertEquals("AI", row.getSource(), "source 缺省应为 AI");
        assertNotNull(row.getCreatedAt());
    }

    @Test
    void 缺少分类直接拒绝() {
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "k")));

        assertThrows(IllegalArgumentException.class, () -> service.save(TASK_ID, new Verdict()));
        assertThrows(IllegalArgumentException.class, () -> service.save(TASK_ID, null));
    }

    @Test
    void 把握度越界拒绝() {
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "k")));
        Verdict v = new Verdict();
        v.setCategory(Category.UNKNOWN);
        v.setConfidence(120);

        assertThrows(IllegalArgumentException.class, () -> service.save(TASK_ID, v));
    }

    /**
     * AI 归错了人会来纠正，纠正之后不能被下一次 AI 重跑覆盖回去。
     */
    @Test
    void 当前结论人工优先于AI() {
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "k")));

        TaskFailureDiagnosis ai = new TaskFailureDiagnosis();
        ai.setId("d-ai");
        ai.setSource("AI");
        ai.setCategory("ELEMENT_BROKEN");
        ai.setCreatedAt(LocalDateTime.now());
        TaskFailureDiagnosis human = new TaskFailureDiagnosis();
        human.setId("d-human");
        human.setSource("HUMAN");
        human.setCategory("FUNCTION_BROKEN");
        human.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        // mapper 按时间倒序返回：AI 那条更新，但人工的才是准
        saved.add(ai);
        saved.add(human);

        assertEquals("d-human", service.current(TASK_ID).getId());
    }

    @Test
    void 都是AI结论时取最新一条() {
        TaskFailureDiagnosis newer = new TaskFailureDiagnosis();
        newer.setId("d-new");
        newer.setSource("AI");
        TaskFailureDiagnosis older = new TaskFailureDiagnosis();
        older.setId("d-old");
        older.setSource("AI");
        saved.add(newer);
        saved.add(older);

        assertEquals("d-new", service.current(TASK_ID).getId());
    }

    @Test
    void 没有结论时返回null() {
        assertNull(service.current(TASK_ID));
    }

    @Test
    void 人工结论可显式指定来源() {
        givenExecution(List.of(failWith("3 · 点击登录", "未找到", "id=login", "k")));
        Verdict v = new Verdict();
        v.setCategory(Category.FUNCTION_BROKEN);
        v.setSource(Source.HUMAN);
        v.setCreatedBy("yangjun");

        TaskFailureDiagnosis row = service.save(TASK_ID, v);

        assertEquals("HUMAN", row.getSource());
        assertEquals("yangjun", row.getCreatedBy());
    }

    /** 分类推不出「接下来干什么」的话，归因就白做了 */
    @Test
    void 每个分类都配了中文名与处理动作() {
        for (Category c : Category.values()) {
            assertNotNull(c.label(), c.name() + " 缺中文名");
            assertNotNull(c.action(), c.name() + " 缺处理动作");
            assertFalse(c.label().isBlank());
            assertFalse(c.action().isBlank());
        }
    }
}
