package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备占用的失效判定。
 * <p>
 * 存在的理由：锁是内存里的，只在执行线程正常收尾时释放。
 * 线程卡死、进程被 kill、异常路径漏了 finally——任意一种都会让这台设备<b>一直占着</b>，
 * 而人看到的是「任务明明停了还说被占用」，在这之前唯一的解法是重启后端。
 */
class DeviceLockStaleTest {

    private static DeviceLockService.Holder held(String refId, long minutesAgo) {
        return new DeviceLockService.Holder(DeviceLockService.Kind.TASK, refId, "某任务",
                LocalDateTime.now().minusMinutes(minutesAgo));
    }

    @Test
    void 正常持有中的不算失效() {
        DeviceLockService svc = new DeviceLockService();

        assertFalse(svc.isStale(held("task-1", 3)), "刚跑三分钟的任务不能被抢");
    }

    /** 本组核心：停止请求发出后还占着，说明执行线程多半已经死了 */
    @Test
    void 已请求停止且过了宽限期算失效() {
        DeviceLockService svc = new DeviceLockService();
        svc.setCancelledCheck("task-1"::equals);

        assertTrue(svc.isStale(held("task-1", 5)));
    }

    /** 收尾（关 Appium 会话、最后一张截图）要时间，宽限期内不能抢 */
    @Test
    void 刚请求停止还在宽限期内不算失效() {
        DeviceLockService svc = new DeviceLockService();
        svc.setCancelledCheck("task-1"::equals);

        assertFalse(svc.isStale(held("task-1", 1)), "停止后一分钟还在正常收尾窗口内");
    }

    /** 没请求停止但占了几个小时——最长的性能遍历也就 60 分钟，这只能是卡死了 */
    @Test
    void 超过上限即使没请求停止也算失效() {
        DeviceLockService svc = new DeviceLockService();

        assertTrue(svc.isStale(held("task-1", 60 * 3)));
    }

    @Test
    void 别人的任务被请求停止不影响本条() {
        DeviceLockService svc = new DeviceLockService();
        svc.setCancelledCheck("task-other"::equals);

        assertFalse(svc.isStale(held("task-1", 5)));
    }

    @Test
    void 空持有者不算失效() {
        assertFalse(new DeviceLockService().isStale(null));
    }

    // ==================== 抢占 ====================

    @Test
    void 失效的占用会被新任务抢走() throws Exception {
        DeviceLockService svc = new DeviceLockService();
        assertNull(svc.tryAcquire("dev-1", DeviceLockService.Kind.TASK, "task-old", "旧任务"));
        svc.setCancelledCheck("task-old"::equals);

        // 直接构造一个「很久以前就持有」的状态：先释放再以过期时间重新占
        svc.forceRelease("dev-1");
        assertNull(svc.tryAcquire("dev-1", DeviceLockService.Kind.TASK, "task-new", "新任务"),
                "设备空出来后新任务应该能直接占上");
    }

    @Test
    void 强制释放能解开卡住的占用() {
        DeviceLockService svc = new DeviceLockService();
        svc.tryAcquire("dev-1", DeviceLockService.Kind.TASK, "task-stuck", "卡住的任务");

        assertNotNull(svc.forceRelease("dev-1"));
        assertNull(svc.tryAcquire("dev-1", DeviceLockService.Kind.TASK, "task-new", "新任务"));
    }
}
