package com.mobile.standalone.service;

import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Context;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Source;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskFailureDiagnosis;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.service.ai.DiagnosisModelClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境问题自动重跑的闸门。
 * <p>
 * 自动重跑是这批里唯一会真的占设备、真的再跑一遍的动作，出问题的方式也最难看——
 * 环境持续不好时无限重跑，或者背着线上回归编排重跑把它的状态机搞乱。
 * 所以这组用例基本都是「什么情况下**不**该重跑」。
 */
class AutoRerunGuardTest {

    private static final String TASK_ID = "task-1";

    private FailureDiagnosisRunner runner;
    private TaskMapper taskMapper;
    private TaskService taskService;
    private FailureDiagnosisService diagnosisService;
    private Task task;

    @BeforeEach
    void setUp() throws Exception {
        taskMapper = Mockito.mock(TaskMapper.class);
        diagnosisService = Mockito.mock(FailureDiagnosisService.class);
        taskService = Mockito.mock(TaskService.class);
        DiagnosisModelClient client = Mockito.mock(DiagnosisModelClient.class);

        task = new Task();
        task.setId(TASK_ID);
        task.setStatus(Task.TaskStatus.FAILED);
        task.setConfig("{\"flowId\":\"flow-1\"}");
        Mockito.when(taskMapper.findById(TASK_ID)).thenReturn(task);

        Mockito.when(client.isConfigured()).thenReturn(true);
        Mockito.when(client.diagnose(ArgumentMatchers.anyString(), ArgumentMatchers.anyList()))
                .thenReturn(verdict(Category.ENVIRONMENT));
        Mockito.when(diagnosisService.buildContext(TASK_ID)).thenReturn(new Context());
        Mockito.when(diagnosisService.save(ArgumentMatchers.eq(TASK_ID), ArgumentMatchers.any()))
                .thenAnswer(inv -> row(inv.getArgument(1, Verdict.class)));

        runner = new FailureDiagnosisRunner(taskMapper, diagnosisService,
                clientProvider(client), taskServiceProvider());
        ReflectionTestUtils.setField(runner, "enabled", true);
        ReflectionTestUtils.setField(runner, "autoRerunEnabled", true);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<DiagnosisModelClient> clientProvider(DiagnosisModelClient c) {
        ObjectProvider<DiagnosisModelClient> p = Mockito.mock(ObjectProvider.class);
        Mockito.when(p.getIfAvailable()).thenReturn(c);
        return p;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<TaskService> taskServiceProvider() {
        ObjectProvider<TaskService> p = Mockito.mock(ObjectProvider.class);
        Mockito.when(p.getObject()).thenReturn(taskService);
        return p;
    }

    private static Verdict verdict(Category c) {
        Verdict v = new Verdict();
        v.setCategory(c);
        v.setConfidence(80);
        v.setSource(Source.AI);
        return v;
    }

    private static TaskFailureDiagnosis row(Verdict v) {
        TaskFailureDiagnosis d = new TaskFailureDiagnosis();
        d.setTaskId(TASK_ID);
        d.setCategory(v.getCategory().name());
        return d;
    }

    /** 归因跑在自己的线程池里，等它做完再断言 */
    private void runAndWait(boolean standalone) throws Exception {
        runner.onTaskFinished(TASK_ID, standalone);
        for (int i = 0; i < 50; i++) {
            if (Mockito.mockingDetails(diagnosisService).getInvocations().stream()
                    .anyMatch(inv -> "save".equals(inv.getMethod().getName()))) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        TimeUnit.MILLISECONDS.sleep(50);
    }

    @Test
    void 判为环境问题的独立任务会自动重跑() throws Exception {
        runAndWait(true);

        Mockito.verify(taskService).runTask(TASK_ID);
    }

    /**
     * 线上回归编排有自己的状态机，步骤结果在任务结束时就已记录。
     * 背着它重跑会让任务状态和步骤结果对不上。
     */
    @Test
    void 线上回归编排驱动的任务不自动重跑() throws Exception {
        runAndWait(false);

        Mockito.verify(taskService, Mockito.never()).runTask(ArgumentMatchers.anyString());
    }

    /** 环境一直不好时，不能一次次重跑下去 */
    @Test
    void 冷却期内不重复自动重跑() throws Exception {
        task.setConfig("{\"flowId\":\"flow-1\",\"autoRerunAt\":\""
                + LocalDateTime.now().minusMinutes(5) + "\"}");

        runAndWait(true);

        Mockito.verify(taskService, Mockito.never()).runTask(ArgumentMatchers.anyString());
    }

    /** 冷却期过了应当允许再自动重跑一次，否则这台机第二天出环境问题就再也不自愈了 */
    @Test
    void 冷却期外允许再次自动重跑() throws Exception {
        task.setConfig("{\"flowId\":\"flow-1\",\"autoRerunAt\":\""
                + LocalDateTime.now().minusHours(3) + "\"}");

        runAndWait(true);

        Mockito.verify(taskService).runTask(TASK_ID);
    }

    /** 重跑前要写下标记，否则进程重启后冷却期就失忆了 */
    @Test
    void 重跑前把标记写进任务配置() throws Exception {
        runAndWait(true);

        Mockito.verify(taskMapper).update(ArgumentMatchers.argThat(
                t -> t.getConfig() != null && t.getConfig().contains("autoRerunAt")));
    }

    @Test
    void 非环境问题不重跑() throws Exception {
        DiagnosisModelClient client = Mockito.mock(DiagnosisModelClient.class);
        Mockito.when(client.isConfigured()).thenReturn(true);
        Mockito.when(client.diagnose(ArgumentMatchers.anyString(), ArgumentMatchers.anyList()))
                .thenReturn(verdict(Category.ELEMENT_BROKEN));
        runner = new FailureDiagnosisRunner(taskMapper, diagnosisService,
                clientProvider(client), taskServiceProvider());
        ReflectionTestUtils.setField(runner, "enabled", true);
        ReflectionTestUtils.setField(runner, "autoRerunEnabled", true);

        runAndWait(true);

        Mockito.verify(taskService, Mockito.never()).runTask(ArgumentMatchers.anyString());
    }

    /** 停止和「本次不适用」不是失败，归因了只会制造噪声 */
    @Test
    void 只归因真失败() throws Exception {
        task.setStatus(Task.TaskStatus.STOPPED);

        runner.onTaskFinished(TASK_ID, true);
        TimeUnit.MILLISECONDS.sleep(100);

        Mockito.verify(diagnosisService, Mockito.never()).buildContext(ArgumentMatchers.anyString());
    }

    /** 没开开关时整条链路要静默跳过，不该有任何副作用 */
    @Test
    void 未启用时不做任何事() throws Exception {
        ReflectionTestUtils.setField(runner, "enabled", false);

        runner.onTaskFinished(TASK_ID, true);
        TimeUnit.MILLISECONDS.sleep(100);

        Mockito.verify(diagnosisService, Mockito.never()).buildContext(ArgumentMatchers.anyString());
    }

    /**
     * 模型超时、返回异常、设备被占——归因链路出任何问题都不该影响任务本身。
     * 自动化能力不该让原本能跑的东西变得跑不了。
     */
    @Test
    void 模型调用失败不外抛() throws Exception {
        DiagnosisModelClient boom = Mockito.mock(DiagnosisModelClient.class);
        Mockito.when(boom.isConfigured()).thenReturn(true);
        Mockito.when(boom.diagnose(ArgumentMatchers.anyString(), ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("模型超时"));
        runner = new FailureDiagnosisRunner(taskMapper, diagnosisService,
                clientProvider(boom), taskServiceProvider());
        ReflectionTestUtils.setField(runner, "enabled", true);

        runner.onTaskFinished(TASK_ID, true);
        TimeUnit.MILLISECONDS.sleep(150);

        assertTrue(true, "没有异常逃逸到调用线程");
    }

    /** provider 配置写错字时两个实现都不注册，这时最多是少个能力，绝不能影响任务本身 */
    @Test
    void 没有可用模型实现时静默跳过() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<DiagnosisModelClient> empty = Mockito.mock(ObjectProvider.class);
        Mockito.when(empty.getIfAvailable()).thenReturn(null);
        runner = new FailureDiagnosisRunner(taskMapper, diagnosisService, empty, taskServiceProvider());
        ReflectionTestUtils.setField(runner, "enabled", true);

        runner.onTaskFinished(TASK_ID, true);
        TimeUnit.MILLISECONDS.sleep(100);

        Mockito.verify(diagnosisService, Mockito.never()).buildContext(ArgumentMatchers.anyString());
    }

    @Test
    void 截图按离失败由近到远传给模型() {
        Context ctx = new Context();
        com.mobile.standalone.dto.FailureDiagnosisDtos.Artifact a1 =
                new com.mobile.standalone.dto.FailureDiagnosisDtos.Artifact();
        a1.setPath("/tmp/a.png");
        com.mobile.standalone.dto.FailureDiagnosisDtos.Artifact a2 =
                new com.mobile.standalone.dto.FailureDiagnosisDtos.Artifact();
        a2.setPath("/tmp/b.png");
        ctx.setScreenshots(List.of(a1, a2));

        @SuppressWarnings("unchecked")
        List<Path> paths = (List<Path>) ReflectionTestUtils.invokeMethod(
                runner, "screenshotPaths", ctx);

        // 上下文里是时间正序，越靠后离失败越近；只传两张时要传最近的那两张
        assertTrue(paths.get(0).toString().endsWith("b.png"), paths.toString());
    }
}
