package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 中立事件的日志形态。
 * <p>
 * 用户点停止、重试前的那次失败尝试，都不该算进「不通过」——
 * 否则人为中断会拉低达标率（实测把 100% 拉成了 75%），
 * 重试成功的步骤也会同时留下一条红，让人分不清哪些是真问题。
 */
class NeutralLogResultTest {

    @Test
    void 中立事件的状态与级别区别于成功和失败() {
        ExecutionLogEntryDto neutral = ExecutionLogEntryDto.neutral("已停止", "用户请求停止");

        assertEquals("neutral", neutral.getStatus());
        assertEquals("Warn", neutral.getLevel());
        assertEquals("已停止", neutral.getStep());
        assertEquals("用户请求停止", neutral.getMessage());
    }

    @Test
    void 成功与失败的状态保持不变() {
        ExecutionLogEntryDto ok = ExecutionLogEntryDto.success("点击", "登录按钮");
        ExecutionLogEntryDto bad = ExecutionLogEntryDto.fail("点击", "点不到", "xpath://x");

        assertEquals("success", ok.getStatus());
        assertEquals("Info", ok.getLevel());
        assertEquals("fail", bad.getStatus());
        assertEquals("Error", bad.getLevel());
        assertEquals("xpath://x", bad.getSelector());
    }

    @Test
    void 中立事件不带定位符与截图() {
        ExecutionLogEntryDto neutral = ExecutionLogEntryDto.neutral("准备重试", "第 1/2 次失败");

        assertNull(neutral.getSelector());
        assertNull(neutral.getScreenshotPath());
        assertNull(neutral.getScreenshotSettled());
    }

    /**
     * 重试最终通过后，前几次尝试留下的红要降级成告警。
     * 否则一个已经通过的步骤仍然挂着「不通过」，红色就不再只代表真问题。
     */
    @Test
    void 重试通过后失败日志降级为中立态() throws Exception {
        java.util.List<ExecutionLogEntryDto> logs = new java.util.ArrayList<>();
        logs.add(ExecutionLogEntryDto.success("1 · 点击", "之前的步骤，不该被动"));
        int mark = logs.size();
        logs.add(ExecutionLogEntryDto.fail("2 · 点击", "点击失败: el_x", "xpath://x"));
        logs.add(ExecutionLogEntryDto.neutral("2 · 准备重试", "第 1/2 次失败"));

        java.lang.reflect.Method m = UiFlowExecutor.class.getDeclaredMethod(
                "downgradeFailedAttempts", java.util.List.class, int.class);
        m.setAccessible(true);
        m.invoke(null, logs, mark);

        // mark 之前的不动
        assertEquals("success", logs.get(0).getStatus());
        // 失败尝试降级为中立，并标注原因
        assertEquals("neutral", logs.get(1).getStatus());
        assertEquals("Warn", logs.get(1).getLevel());
        assertEquals(true, logs.get(1).getMessage().startsWith("[重试后已通过]"));
        // 本来就是中立的保持不变
        assertEquals("neutral", logs.get(2).getStatus());
    }

    /** 从数据库读回执行日志时，中立态不能退化成成功或失败 */
    @Test
    void 中立态能从库里原样读回() {
        String json = """
                [{"step":"已停止","level":"Warn","status":"neutral","message":"用户请求停止"},
                 {"step":"点击","level":"Error","status":"fail","message":"点不到","selector":"xpath://x"},
                 {"step":"输入","level":"Info","status":"success","message":"ok"}]
                """;

        var list = com.mobile.standalone.util.ExecutionLogJson.parse(json);

        assertEquals(3, list.size());
        assertEquals("neutral", list.get(0).getStatus());
        assertEquals("Warn", list.get(0).getLevel());
        assertEquals("fail", list.get(1).getStatus());
        assertEquals("success", list.get(2).getStatus());
    }
}
