package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选 locator 的唯一优先重排——对齐 Appium Inspector：
 * 推荐首选必须是「当前树上只匹配到一个」的 locator，而不是生成顺序第一个。
 */
class DeviceInspectLocatorRankTest {

    private static DeviceInspectService.HitCandidate cand(String value, Boolean unique) {
        DeviceInspectService.HitCandidate c = new DeviceInspectService.HitCandidate();
        c.setLocatorType("xpath");
        c.setLocatorValue(value);
        c.setUnique(unique);
        return c;
    }

    private static List<String> order(List<DeviceInspectService.HitCandidate> list) {
        List<String> out = new ArrayList<>();
        for (DeviceInspectService.HitCandidate c : list) out.add(c.getLocatorValue());
        return out;
    }

    @Test
    void 唯一命中的顶到最前() {
        List<DeviceInspectService.HitCandidate> list = new ArrayList<>(List.of(
                cand("命中3个的裸id", false),
                cand("唯一的id加text", true),
                cand("命中2个的text", false),
                cand("唯一的绝对xpath", true)));

        DeviceInspectService.promoteUniqueFirst(list);

        // 两个唯一的排前面，且保持它们原来的相对顺序；非唯一的沉到后面
        assertThat(order(list)).containsExactly(
                "唯一的id加text", "唯一的绝对xpath", "命中3个的裸id", "命中2个的text");
    }

    @Test
    void 无法校验的排在唯一之后非唯一之前() {
        List<DeviceInspectService.HitCandidate> list = new ArrayList<>(List.of(
                cand("非唯一", false),
                cand("没法校验", null),
                cand("唯一", true)));

        DeviceInspectService.promoteUniqueFirst(list);

        assertThat(order(list)).containsExactly("唯一", "没法校验", "非唯一");
    }

    @Test
    void 重排后rank按新顺序重编() {
        List<DeviceInspectService.HitCandidate> list = new ArrayList<>(List.of(
                cand("非唯一", false),
                cand("唯一", true)));

        DeviceInspectService.promoteUniqueFirst(list);

        assertThat(list.get(0).getLocatorValue()).isEqualTo("唯一");
        assertThat(list.get(0).getRank()).isEqualTo(0);
        assertThat(list.get(1).getRank()).isEqualTo(1);
    }

    @Test
    void 同档内保持原序是稳定排序() {
        // 全部非唯一：顺序不该被打乱
        List<DeviceInspectService.HitCandidate> list = new ArrayList<>(List.of(
                cand("a", false), cand("b", false), cand("c", false)));

        DeviceInspectService.promoteUniqueFirst(list);

        assertThat(order(list)).containsExactly("a", "b", "c");
    }
}
