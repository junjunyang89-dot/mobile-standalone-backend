package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Source;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.entity.TaskFailureDiagnosis;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.mapper.TaskFailureDiagnosisMapper;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.util.ExecutionLogJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归因去重签名与人工复核。
 * <p>
 * 这两件事都是为了让指标能用：签名决定「归因覆盖率」会不会因为反复重判而虚高，
 * 复核决定「人工改判率」能不能区分「AI 判对了」和「根本没人看」。
 */
class DiagnosisSignatureAndReviewTest {

    private static final String TASK_ID = "task-1";
    private static final String FLOW_ID = "flow-login";

    private FailureDiagnosisService service;
    private TaskFailureDiagnosisMapper diagnosisMapper;
    private TaskMapper taskMapper;
    private final List<TaskFailureDiagnosis> saved = new ArrayList<>();
    private Task task;

    @BeforeEach
    void setUp() {
        taskMapper = Mockito.mock(TaskMapper.class);
        TaskExecutionMapper executionMapper = Mockito.mock(TaskExecutionMapper.class);
        diagnosisMapper = Mockito.mock(TaskFailureDiagnosisMapper.class);
        UiFlowService uiFlowService = Mockito.mock(UiFlowService.class);
        UiElementService uiElementService = Mockito.mock(UiElementService.class);

        task = new Task();
        task.setId(TASK_ID);
        task.setPlatform(Task.Platform.IOS);
        task.setTaskType(Task.TaskType.UI_FLOW_TEST);
        task.setConfig("{\"flowId\":\"" + FLOW_ID + "\"}");
        Mockito.when(taskMapper.findById(TASK_ID)).thenReturn(task);

        TaskExecution exec = new TaskExecution();
        exec.setId("exec-1");
        try {
            exec.setExecutionLog(ExecutionLogJson.mapper().writeValueAsString(List.of(
                    ExecutionLogEntryDto.fail("2.1 · 点击", "无 ACTIVE 元素", "广场", "广场"))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Mockito.when(executionMapper.findByTaskIdOrderByStartTimeDesc(TASK_ID)).thenReturn(List.of(exec));

        Mockito.when(diagnosisMapper.insert(ArgumentMatchers.any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return 1;
        });
        Mockito.when(diagnosisMapper.findByTaskId(TASK_ID)).thenReturn(saved);

        service = new FailureDiagnosisService(taskMapper, executionMapper, diagnosisMapper,
                uiFlowService, uiElementService);
    }

    private Verdict verdict(Category c) {
        Verdict v = new Verdict();
        v.setCategory(c);
        v.setSource(Source.AI);
        return v;
    }

    // ==================== 签名 ====================

    @Test
    void 写入结论时带上签名() {
        TaskFailureDiagnosis row = service.save(TASK_ID, verdict(Category.FLOW_OUTDATED));

        assertNotNull(row.getSignature());
        assertTrue(row.getSignature().contains(FLOW_ID), row.getSignature());
        assertTrue(row.getSignature().contains("广场"), row.getSignature());
    }

    /**
     * 签名不能含 taskId。同一条流程每晚回归都会生成新任务，带上 taskId 就永远对不上，
     * 去重也就失效了——那正是「每晚重判一次同样的问题」这个浪费的来源。
     */
    @Test
    void 同流程同步骤跨任务签名一致() {
        String a = service.signatureOf(task, "2.1 · 点击", "广场");
        Task other = new Task();
        other.setId("task-999");
        other.setTaskType(Task.TaskType.UI_FLOW_TEST);
        other.setConfig("{\"flowId\":\"" + FLOW_ID + "\"}");

        assertEquals(a, service.signatureOf(other, "2.1 · 点击", "广场"));
    }

    @Test
    void 不同步骤或不同元素签名不同() {
        String base = service.signatureOf(task, "2.1 · 点击", "广场");

        assertNotEquals(base, service.signatureOf(task, "3.1 · 点击", "广场"));
        assertNotEquals(base, service.signatureOf(task, "2.1 · 点击", "登录"));
    }

    @Test
    void 无流程无步骤无元素时不生成签名() {
        Task bare = new Task();
        bare.setId("t");

        assertNull(service.signatureOf(bare, null, null));
        assertNull(service.signatureOf(null, "step", "key"));
    }

    /** 列宽 255，超了会写库失败 */
    @Test
    void 签名不超过列宽() {
        String longStep = "步".repeat(400);

        assertTrue(service.signatureOf(task, longStep, "广场").length() <= 255);
    }

    // ==================== 复用 ====================

    @Test
    void 能取到近期同签名的AI结论() {
        TaskFailureDiagnosis prev = new TaskFailureDiagnosis();
        prev.setId("d-1");
        Mockito.when(diagnosisMapper.findLatestBySignature(ArgumentMatchers.eq("sig"),
                ArgumentMatchers.any(LocalDateTime.class))).thenReturn(prev);

        assertEquals("d-1", service.findReusable("sig", Duration.ofDays(7)).getId());
    }

    @Test
    void 空签名不查复用() {
        assertNull(service.findReusable(null, Duration.ofDays(7)));
        assertNull(service.findReusable("  ", Duration.ofDays(7)));
        Mockito.verify(diagnosisMapper, Mockito.never())
                .findLatestBySignature(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    /** 一个问题挂了 30 次和挂了 1 次，值不值得先修完全不同 */
    @Test
    void 历史记录带上同类问题发生次数() {
        TaskFailureDiagnosis d = new TaskFailureDiagnosis();
        d.setId("d-1");
        d.setSignature("sig");
        saved.add(d);
        Mockito.when(diagnosisMapper.countBySignature("sig")).thenReturn(7);

        assertEquals(7, service.history(TASK_ID).get(0).getOccurrenceCount());
    }

    @Test
    void 没有签名时不查发生次数() {
        TaskFailureDiagnosis d = new TaskFailureDiagnosis();
        d.setId("d-1");
        saved.add(d);

        assertNull(service.history(TASK_ID).get(0).getOccurrenceCount());
        Mockito.verify(diagnosisMapper, Mockito.never()).countBySignature(ArgumentMatchers.anyString());
    }

    // ==================== 复核 ====================

    /**
     * 没有复核动作，「人工改判率」恒为 0——区分不了「AI 判对了」和「根本没人看」，
     * 那个指标也就没法用来判断归因准不准。
     */
    @Test
    void 复核打上时间与操作人() {
        TaskFailureDiagnosis d = new TaskFailureDiagnosis();
        d.setId("d-1");
        Mockito.when(diagnosisMapper.findById("d-1")).thenReturn(d);

        TaskFailureDiagnosis out = service.review("d-1", "yangjun");

        assertNotNull(out.getReviewedAt());
        assertEquals("yangjun", out.getReviewedBy());
        Mockito.verify(diagnosisMapper).markReviewed(ArgumentMatchers.eq("d-1"),
                ArgumentMatchers.any(LocalDateTime.class), ArgumentMatchers.eq("yangjun"));
    }

    @Test
    void 复核不存在的记录报错() {
        assertThrows(IllegalArgumentException.class, () -> service.review("nope", "yangjun"));
    }
}
