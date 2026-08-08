package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.keyword.FlowOutcome;
import com.mobile.standalone.service.keyword.KeywordContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三个结束关键字。核心价值是「判定写在关键字名上、没有可漏配的参数」——
 * 原来一个 RETURN_MESSAGE 加一个 endStatus 默认值，把「成功登录XXX」记成了失败。
 */
class FinishHandlersTest {

    private final List<ExecutionLogEntryDto> logs = new ArrayList<>();

    @Test
    void 成功结束判通过() throws Exception {
        KeywordContext ctx = ctx(Map.of("message", "成功登录完美世界电竞APP"));

        new FinishSuccessHandler().execute(ctx);

        assertTrue(ctx.getState().earlyReturn);
        assertEquals(FlowOutcome.PASS, ctx.getState().returnOutcome);
        assertEquals("成功登录完美世界电竞APP", ctx.getState().returnMessage);
        assertTrue(lastLog().getStatus().equals("success"), "成功结束不该记成 fail 日志");
    }

    @Test
    void 失败结束判失败() throws Exception {
        KeywordContext ctx = ctx(Map.of("message", "侧栏入口不存在"));

        new FinishFailedHandler().execute(ctx);

        assertEquals(FlowOutcome.FAIL, ctx.getState().returnOutcome);
        assertEquals("fail", lastLog().getStatus());
    }

    @Test
    void 跳过结束判跳过() throws Exception {
        KeywordContext ctx = ctx(Map.of("message", "该设备未安装被测应用"));

        new FinishSkippedHandler().execute(ctx);

        assertEquals(FlowOutcome.SKIP, ctx.getState().returnOutcome);
        assertEquals("success", lastLog().getStatus());
    }

    /** 不填文案也要能在报告里看出发生了什么 */
    @Test
    void 未填文案时使用各自的默认结论() throws Exception {
        KeywordContext ok = ctx(Map.of());
        new FinishSuccessHandler().execute(ok);
        assertEquals("流程已达成目标", ok.getState().returnMessage);

        KeywordContext bad = ctx(Map.of("message", "   "));
        new FinishFailedHandler().execute(bad);
        assertEquals("流程无法继续", bad.getState().returnMessage);

        KeywordContext skip = ctx(Map.of());
        new FinishSkippedHandler().execute(skip);
        assertEquals("本次不适用，已跳过", skip.getState().returnMessage);
    }

    @Test
    void 文案支持变量替换() throws Exception {
        KeywordContext ctx = KeywordContext.builder()
                .rawCode("FINISH_SUCCESS")
                .stepParams(Map.of("message", "成功登录 ${phone}"))
                .execParams(new HashMap<>(Map.of("phone", "138****8000")))
                .logs(logs)
                .task(task())
                .pathNo("9.1")
                .state(new KeywordContext.RunState(false, null))
                .runtime(new FakeRuntime())
                .build();

        new FinishSuccessHandler().execute(ctx);

        assertEquals("成功登录 138****8000", ctx.getState().returnMessage);
    }

    /** 三个关键字的 code / 判定 / 排序互不相同，避免复制粘贴时漏改 */
    @Test
    void 三个关键字彼此区分() {
        FinishSuccessHandler ok = new FinishSuccessHandler();
        FinishFailedHandler bad = new FinishFailedHandler();
        FinishSkippedHandler skip = new FinishSkippedHandler();

        assertEquals(List.of("FINISH_SUCCESS", "FINISH_FAILED", "FINISH_SKIPPED"),
                List.of(ok.code(), bad.code(), skip.code()));
        assertNotEquals(ok.sortOrder(), bad.sortOrder());
        assertNotEquals(bad.sortOrder(), skip.sortOrder());
        for (var h : List.of(ok, bad, skip)) {
            assertEquals("FLOW", h.category());
            assertEquals(1, h.params().size(), h.code() + " 应当只有文案一个参数，不该再有判定参数");
            assertEquals("message", h.params().get(0).name());
        }
    }

    /** 旧关键字仍可执行，只是从面板首选位置挪走 */
    @Test
    void 旧的返回提示仍然可用且已标注() {
        ReturnMessageHandler legacy = new ReturnMessageHandler();

        assertTrue(legacy.nameZh().contains("旧"));
        assertTrue(legacy.sortOrder() > 100, "旧写法应排到面板最后");
        assertTrue(legacy.description().contains("成功结束"), "说明里应指向新关键字");
    }

    private KeywordContext ctx(Map<String, Object> stepParams) {
        return KeywordContext.builder()
                .rawCode("FINISH")
                .stepParams(stepParams)
                .execParams(new HashMap<>())
                .logs(logs)
                .task(task())
                .pathNo("9.1")
                .state(new KeywordContext.RunState(false, null))
                .runtime(new FakeRuntime())
                .build();
    }

    private static Task task() {
        Task t = new Task();
        t.setId("task-1");
        return t;
    }

    private ExecutionLogEntryDto lastLog() {
        assertFalse(logs.isEmpty(), "应当写入一条执行日志");
        return logs.get(logs.size() - 1);
    }
}
