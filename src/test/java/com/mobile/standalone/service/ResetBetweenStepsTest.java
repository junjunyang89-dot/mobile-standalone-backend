package com.mobile.standalone.service;

import com.mobile.standalone.service.OnlineRegressionOrchestrator.OrderedStepDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复位流程的插入规则。
 * <p>
 * 存在的理由：依赖图排得再对，页面状态被上一条用例带歪了照样一片红——
 * A=登录跑完停在首页，用例 C 跑完停在「我的·设置」，用例 D 也依赖 A 却以为自己从首页开始。
 * 报错会指向 D 的第一个元素找不到，查半天才发现是 C 没收尾。
 * <p>
 * 跟 {@code dependsOn} 的前置是两回事：前置一批里<b>只跑一次</b>，复位<b>每条都跑</b>。
 */
class ResetBetweenStepsTest {

    private static OrderedStepDto step(String id, String label) {
        OrderedStepDto d = new OrderedStepDto();
        d.setId(id);
        d.setLabel(label);
        return d;
    }

    private static List<String> labels(List<OrderedStepDto> steps) {
        List<String> out = new ArrayList<>();
        steps.forEach(s -> out.add(s.getLabel()));
        return out;
    }

    private final OrderedStepDto reset = step("uif-reset", "复位·回到首页");

    /**
     * 第一条前面不插：那时 APP 可能还没装，冷启动必挂。
     * 之后每条前面都插一次。
     */
    @Test
    void 除第一条外每条用例前都插复位() {
        List<OrderedStepDto> in = List.of(
                step("a", "安装启动"), step("b", "登录"), step("c", "我的页"));

        List<OrderedStepDto> out = OnlineRegressionOrchestrator.withResetBetween(in, reset);

        assertEquals(List.of("安装启动", "复位·回到首页", "登录", "复位·回到首页", "我的页"), labels(out));
    }

    /** n 条用例插 n-1 次复位 */
    @Test
    void 复位次数是用例数减一() {
        List<OrderedStepDto> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            in.add(step("c" + i, "用例" + i));
        }

        List<OrderedStepDto> out = OnlineRegressionOrchestrator.withResetBetween(in, reset);

        assertEquals(39, out.size());
        assertEquals(19, out.stream().filter(s -> "uif-reset".equals(s.getId())).count());
    }

    @Test
    void 只有一条用例时不插复位() {
        List<OrderedStepDto> in = List.of(step("a", "登录"));

        assertEquals(List.of("登录"), labels(OnlineRegressionOrchestrator.withResetBetween(in, reset)));
    }

    /** 没配复位就原样返回——这是默认路径，线上回归走的就是它 */
    @Test
    void 没配复位时原样返回() {
        List<OrderedStepDto> in = List.of(step("a", "A"), step("b", "B"));

        assertSame(in, OnlineRegressionOrchestrator.withResetBetween(in, null));
    }

    @Test
    void 空列表安全() {
        assertTrue(OnlineRegressionOrchestrator.withResetBetween(List.of(), reset).isEmpty());
        assertEquals(null, OnlineRegressionOrchestrator.withResetBetween(null, reset));
    }

    /** 复位步骤共用同一个 flowId：执行期靠它认出「这一步是复位」，不靠标签前缀 */
    @Test
    void 复位步骤复用同一个流程id() {
        List<OrderedStepDto> out = OnlineRegressionOrchestrator.withResetBetween(
                List.of(step("a", "A"), step("b", "B"), step("c", "C")), reset);

        assertEquals(2, out.stream().filter(s -> "uif-reset".equals(s.getId())).count());
        assertEquals(1, out.stream().filter(s -> "a".equals(s.getId())).count());
    }
}
