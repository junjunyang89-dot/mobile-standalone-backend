package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 执行时选的蒲公英版本必须盖过流程里存的。
 * <p>
 * 一般参数是「流程里配好的为主、执行参数兜底」，版本是例外：人在执行对话框里挑了一个，
 * 那就是他这次的意图。搞反了会出现最难查的一类问题——界面上选了 A、实际装了 B，
 * 而日志显示「安装成功」。
 */
class InstallBuildKeyOverrideTest {

    private final InstallAppHandler handler = new InstallAppHandler();

    private KeywordContext ctx(Map<String, Object> stepParams, Map<String, String> execParams) {
        return KeywordContext.builder()
                .rawCode("INSTALL_APP")
                .stepParams(stepParams)
                .execParams(execParams)
                .build();
    }

    private String resolve(KeywordContext c) {
        return ReflectionTestUtils.invokeMethod(handler, "resolveBuildKey", c);
    }

    @Test
    void 执行时选的版本覆盖流程里存的() {
        Map<String, String> exec = new HashMap<>();
        exec.put("pgyerBuildKeyOverride", "picked-at-run");

        assertEquals("picked-at-run",
                resolve(ctx(Map.of("pgyerBuildKey", "stored-in-flow"), exec)));
    }

    @Test
    void 没选版本时沿用流程里存的() {
        assertEquals("stored-in-flow",
                resolve(ctx(Map.of("pgyerBuildKey", "stored-in-flow"), Map.of())));
    }

    /** 空串等于没选，不能把流程里配好的顶掉 */
    @Test
    void 空覆盖值不生效() {
        Map<String, String> exec = new HashMap<>();
        exec.put("pgyerBuildKeyOverride", "   ");

        assertEquals("stored-in-flow",
                resolve(ctx(Map.of("pgyerBuildKey", "stored-in-flow"), exec)));
    }

    @Test
    void 两边都没有时返回空() {
        assertNull(resolve(ctx(Map.of(), Map.of())));
    }
}
