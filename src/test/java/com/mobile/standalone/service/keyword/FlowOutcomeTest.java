package com.mobile.standalone.service.keyword;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowOutcomeTest {

    /**
     * 关键的一条：没写判定时必须回落到 FAIL。
     * 存量流程的 IF 步骤里都没有 endStatus，如果默认成 PASS，
     * 「元素没出现就退出」还是会继续被记成通过——这正是要修的问题。
     */
    @Test
    void 缺省与非法值一律判失败() {
        assertEquals(FlowOutcome.FAIL, FlowOutcome.from(null));
        assertEquals(FlowOutcome.FAIL, FlowOutcome.from(""));
        assertEquals(FlowOutcome.FAIL, FlowOutcome.from("   "));
        assertEquals(FlowOutcome.FAIL, FlowOutcome.from("не_существует"));
        assertEquals(FlowOutcome.FAIL, FlowOutcome.from("COMPLETED"));
    }

    @Test
    void 显式取值可解析且大小写空格不敏感() {
        assertEquals(FlowOutcome.PASS, FlowOutcome.from("PASS"));
        assertEquals(FlowOutcome.PASS, FlowOutcome.from("pass"));
        assertEquals(FlowOutcome.PASS, FlowOutcome.from("  Pass  "));
        assertEquals(FlowOutcome.SKIP, FlowOutcome.from("skip"));
        assertEquals(FlowOutcome.FAIL, FlowOutcome.from("fail"));
    }

    @Test
    void 下拉选项以FAIL打头() {
        assertEquals(3, FlowOutcome.optionNames().size());
        assertEquals("FAIL", FlowOutcome.optionNames().get(0));
        assertTrue(FlowOutcome.optionNames().containsAll(java.util.List.of("FAIL", "PASS", "SKIP")));
    }

    @Test
    void 每个判定都有中文展示名() {
        for (FlowOutcome outcome : FlowOutcome.values()) {
            assertTrue(outcome.getDisplayName() != null && !outcome.getDisplayName().isBlank(),
                    outcome + " 缺少展示名");
        }
    }

    @Test
    void 提前返回会记录判定与文案() {
        KeywordContext.RunState state = new KeywordContext.RunState(false, null);

        state.returnEarly("侧栏入口不存在，结束流程", FlowOutcome.FAIL);

        assertTrue(state.earlyReturn);
        assertEquals("侧栏入口不存在，结束流程", state.returnMessage);
        assertEquals(FlowOutcome.FAIL, state.returnOutcome);
    }

    @Test
    void 提前返回未给判定时按失败处理() {
        KeywordContext.RunState state = new KeywordContext.RunState(false, null);

        state.returnEarly(null, null);

        assertTrue(state.earlyReturn);
        assertEquals("", state.returnMessage);
        assertEquals(FlowOutcome.FAIL, state.returnOutcome);
    }
}
