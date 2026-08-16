package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口清单上那一列状态。
 * <p>
 * 它必须是<b>算出来的</b>。存一列人手维护的状态会跟「已实现自动化」犯同样的错：
 * 接口改了、用例删了，没人回来改这一列，清单越看越像真的，实际全是旧账。
 */
class ApiEndpointStatusTest {

    @Test
    void 没变没用例就是未覆盖() {
        assertEquals(ApiEndpointStatus.UNCOVERED, ApiEndpointStatus.of("ACTIVE", null, 0));
    }

    @Test
    void 没变有用例就是已覆盖() {
        assertEquals(ApiEndpointStatus.COVERED, ApiEndpointStatus.of("ACTIVE", null, 3));
    }

    @Test
    void 本版新增且没用例() {
        assertEquals(ApiEndpointStatus.NEW_UNCOVERED, ApiEndpointStatus.of("ACTIVE", "NEW", 0));
    }

    /**
     * 本组核心之一。接口改了但用例没跟着改——断言还在验旧字段，跑起来照样是绿的。
     * 这类比「没写用例」更容易漏，因为人会觉得「这接口早有用例了」。
     */
    @Test
    void 字段变了且有用例要复核而不是算已覆盖() {
        assertEquals(ApiEndpointStatus.CHANGED_NEEDS_REVIEW,
                ApiEndpointStatus.of("ACTIVE", "FIELD_CHANGED", 2));
    }

    @Test
    void 入参变了没用例要补() {
        assertEquals(ApiEndpointStatus.CHANGED_UNCOVERED,
                ApiEndpointStatus.of("ACTIVE", "PARAM_CHANGED", 0));
    }

    /**
     * 新增接口就算已经写了用例也不算「已覆盖」：
     * 那用例是刚写的，还没被任何一轮回归验证过，标成已覆盖会让人以为它已经稳了。
     */
    @Test
    void 新增接口写了用例也要复核() {
        assertEquals(ApiEndpointStatus.CHANGED_NEEDS_REVIEW, ApiEndpointStatus.of("ACTIVE", "NEW", 5));
    }

    /** 废弃优先级最高：接口都没了，其他判断没有意义 */
    @Test
    void 已废弃盖过一切() {
        assertEquals(ApiEndpointStatus.DEPRECATED, ApiEndpointStatus.of("DEPRECATED", "NEW", 10));
        assertEquals(ApiEndpointStatus.DEPRECATED, ApiEndpointStatus.of("DEPRECATED", null, 0));
    }

    /** 待办的才高亮——已覆盖和未覆盖是常态，全高亮等于没高亮 */
    @Test
    void 只有待办状态才高亮() {
        assertTrue(ApiEndpointStatus.NEW_UNCOVERED.actionable());
        assertTrue(ApiEndpointStatus.CHANGED_NEEDS_REVIEW.actionable());
        assertTrue(ApiEndpointStatus.CHANGED_UNCOVERED.actionable());
        assertTrue(ApiEndpointStatus.DEPRECATED.actionable());
        assertFalse(ApiEndpointStatus.COVERED.actionable());
        assertFalse(ApiEndpointStatus.UNCOVERED.actionable(),
                "「没写用例」是常态，不是每个接口都必须有——全标红人就不看了");
    }

    /** 每个状态都要有说明：光看「有变更·待复核」不知道为什么要复核 */
    @Test
    void 每个状态都有可读说明() {
        for (ApiEndpointStatus s : ApiEndpointStatus.values()) {
            assertFalse(s.label().isBlank(), s.name());
            assertFalse(s.hint().isBlank(), s.name());
        }
        assertTrue(ApiEndpointStatus.CHANGED_NEEDS_REVIEW.hint().contains("绿"),
                "要说清楚危害：断言过期了跑起来还是绿的");
    }

    @Test
    void 空值按未变处理() {
        assertEquals(ApiEndpointStatus.UNCOVERED, ApiEndpointStatus.of(null, null, 0));
        assertEquals(ApiEndpointStatus.COVERED, ApiEndpointStatus.of(null, "  ", 1));
    }
}
