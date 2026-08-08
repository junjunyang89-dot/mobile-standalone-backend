package com.mobile.standalone.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceLockServiceTest {

    private static final String DEVICE = "emulator-5554";

    private DeviceLockService lock;

    @BeforeEach
    void setUp() {
        lock = new DeviceLockService();
    }

    @Test
    void 首次占用成功且能查到持有者() {
        assertNull(lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-1", "冷启动测试"));

        DeviceLockService.Holder holder = lock.current(DEVICE);
        assertNotNull(holder);
        assertEquals("task-1", holder.refId());
        assertEquals(DeviceLockService.Kind.TASK, holder.kind());
        assertTrue(lock.isHeldBy(DEVICE, "task-1"));
    }

    @Test
    void 他人占用时返回当前持有者且不覆盖() {
        lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-1", "冷启动测试");

        DeviceLockService.Holder blocker =
                lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-2", "性能测试");

        assertNotNull(blocker);
        assertEquals("task-1", blocker.refId());
        assertEquals("task-1", lock.current(DEVICE).refId());
    }

    @Test
    void 同一持有者重复占用幂等() {
        assertNull(lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-1", "冷启动测试"));
        assertNull(lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-1", "冷启动测试"));
        assertEquals("task-1", lock.current(DEVICE).refId());
    }

    @Test
    void 同一执行链的子级可重入且持有者保持父级() {
        lock.tryAcquire(DEVICE, DeviceLockService.Kind.REGRESSION, "run-1", "线上回归 run-1");

        DeviceLockService.Holder blocker = lock.tryAcquire(
                DEVICE, DeviceLockService.Kind.TASK, "task-1", "回归步骤", "run-1");

        assertNull(blocker);
        // 持有者仍是父级，子任务只是搭车
        assertEquals("run-1", lock.current(DEVICE).refId());
        assertEquals(DeviceLockService.Kind.REGRESSION, lock.current(DEVICE).kind());
    }

    @Test
    void 子级释放不会误放父锁() {
        lock.tryAcquire(DEVICE, DeviceLockService.Kind.REGRESSION, "run-1", "线上回归 run-1");
        lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-1", "回归步骤", "run-1");

        assertFalse(lock.release(DEVICE, "task-1"));
        assertEquals("run-1", lock.current(DEVICE).refId());

        assertTrue(lock.release(DEVICE, "run-1"));
        assertNull(lock.current(DEVICE));
    }

    @Test
    void 非持有者无法释放() {
        lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-1", "冷启动测试");

        assertFalse(lock.release(DEVICE, "task-2"));
        assertEquals("task-1", lock.current(DEVICE).refId());
    }

    @Test
    void 强制释放不校验持有者() {
        lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-1", "冷启动测试");

        DeviceLockService.Holder removed = lock.forceRelease(DEVICE);

        assertNotNull(removed);
        assertEquals("task-1", removed.refId());
        assertNull(lock.current(DEVICE));
    }

    @Test
    void 释放后可被他人占用() {
        lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-1", "冷启动测试");
        lock.release(DEVICE, "task-1");

        assertNull(lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-2", "性能测试"));
        assertEquals("task-2", lock.current(DEVICE).refId());
    }

    @Test
    void 不同设备互不影响() {
        lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-1", "冷启动测试");

        assertNull(lock.tryAcquire("other-device", DeviceLockService.Kind.TASK, "task-2", "性能测试"));
        assertEquals(2, lock.snapshot().size());
    }

    @Test
    void deviceId前后空格视为同一台设备() {
        lock.tryAcquire("  " + DEVICE + "  ", DeviceLockService.Kind.TASK, "task-1", "冷启动测试");

        assertNotNull(lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, "task-2", "性能测试"));
    }

    /** 并发提交时只能有一个拿到设备——这正是原先「先查后写」方案漏掉的场景 */
    @Test
    void 并发占用同一设备只有一个成功() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                String refId = "task-" + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (lock.tryAcquire(DEVICE, DeviceLockService.Kind.TASK, refId, refId) == null) {
                        acquired.incrementAndGet();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, acquired.get());
        assertNotNull(lock.current(DEVICE));
    }
}
