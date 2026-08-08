package com.mobile.standalone.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionCancelRegistryTest {

    private ExecutionCancelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExecutionCancelRegistry();
    }

    @Test
    void 未登记的执行取消返回false且不视为已取消() {
        assertFalse(registry.cancel("task-1"));
        assertFalse(registry.isCancelled("task-1"));
        assertFalse(registry.isActive("task-1"));
    }

    @Test
    void 登记后可取消并被查到() {
        registry.register("task-1");

        assertTrue(registry.isActive("task-1"));
        assertFalse(registry.isCancelled("task-1"));

        assertTrue(registry.cancel("task-1"));
        assertTrue(registry.isCancelled("task-1"));
    }

    @Test
    void 清理后取消标记不残留到下次执行() {
        registry.register("task-1");
        registry.cancel("task-1");
        registry.clear("task-1");

        assertFalse(registry.isActive("task-1"));
        assertFalse(registry.isCancelled("task-1"));

        // 同一个 taskId 重跑不应带着上次的取消标记
        registry.register("task-1");
        assertFalse(registry.isCancelled("task-1"));
    }

    @Test
    void 重复登记会重置取消标记() {
        registry.register("task-1");
        registry.cancel("task-1");

        registry.register("task-1");

        assertFalse(registry.isCancelled("task-1"));
    }

    @Test
    void isActive反映执行线程是否还在跑() {
        registry.register("task-1");
        assertTrue(registry.isActive("task-1"));

        // 收到停止请求时线程还没收尾，仍算在跑——回归等下一步必须以此为准
        registry.cancel("task-1");
        assertTrue(registry.isActive("task-1"));

        registry.clear("task-1");
        assertFalse(registry.isActive("task-1"));
    }

    @Test
    void 各执行互不干扰() {
        registry.register("task-1");
        registry.register("task-2");

        registry.cancel("task-1");

        assertTrue(registry.isCancelled("task-1"));
        assertFalse(registry.isCancelled("task-2"));
    }

    @Test
    void 空key全部安全返回() {
        registry.register(null);
        registry.register("  ");
        assertFalse(registry.cancel(null));
        assertFalse(registry.isCancelled(null));
        assertFalse(registry.isActive(""));
        registry.clear(null);
    }
}
