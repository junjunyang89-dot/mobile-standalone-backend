package com.mobile.standalone.service.perf;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析 am start -W。
 *
 * <p>这组测试守的不是「能不能解析出数字」，而是<b>什么时候必须拒绝出数字</b>。
 * 一个缺失的指标是显眼的；一个看起来正常、实际来路不对的数字会被当真，
 * 然后驱动一次错误的结论——比如「这版启动快了 30%」，其实只是 force-stop 没生效。
 */
class ColdStartParserTest {

    private static final String OK = """
            Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] cmp=com.pwrd.steam.esports/.ui.activity.launcher.LauncherActivity }
            Status: ok
            LaunchState: COLD
            Activity: com.pwrd.steam.esports/.ui.activity.launcher.LauncherActivity
            TotalTime: 1842
            WaitTime: 1907
            Complete
            """;

    /** 老设备（Android 9 及更早）没有 LaunchState，但 ThisTime/TotalTime/WaitTime 都在 */
    private static final String OLD_FORMAT = """
            Starting: Intent { cmp=com.pwrd.steam.esports/.ui.activity.launcher.LauncherActivity }
            Status: ok
            Activity: com.pwrd.steam.esports/.ui.activity.launcher.LauncherActivity
            ThisTime: 1520
            TotalTime: 1655
            WaitTime: 1701
            """;

    /** 应用本来就活着，am start 只是把它切到前台 */
    private static final String BROUGHT_TO_FRONT = """
            Starting: Intent { cmp=com.pwrd.steam.esports/.ui.activity.launcher.LauncherActivity }
            Warning: Activity not started, its current task has been brought to the front
            Status: ok
            LaunchState: HOT
            TotalTime: 63
            WaitTime: 71
            """;

    @Test
    void 正常冷启动取TotalTime() {
        ColdStartParser.Result r = ColdStartParser.parse(OK);
        assertTrue(r.usable(), String.valueOf(r.problem()));
        assertEquals(1842, r.totalTimeMs());
        assertEquals("COLD", r.launchState());
    }

    /**
     * 取 TotalTime 不取 ThisTime。
     *
     * <p>这个包有一个 LauncherActivity 闪屏再进主页。ThisTime 只算链路上最后一个 Activity，
     * 会把闪屏那段漏掉——数字好看，但不是用户等的那段时间。
     */
    @Test
    void 有闪屏时不能用ThisTime() {
        ColdStartParser.Result r = ColdStartParser.parse(OLD_FORMAT);
        assertTrue(r.usable());
        assertEquals(1655, r.totalTimeMs(), "取成了 ThisTime，会系统性低估启动耗时");
        assertEquals(1520, r.thisTimeMs());
    }

    /**
     * <b>这条是整个类里最要紧的。</b>
     *
     * <p>应用没被杀干净时，am start 只是把它切到前台，TotalTime 只有几十毫秒。
     * 混进中位数会把整体数字拉低，于是报出「启动变快了」——
     * 而<b>假的好消息没有人会去核</b>，它会一直留在报告里。
     */
    @Test
    void 切到前台不算冷启动() {
        ColdStartParser.Result r = ColdStartParser.parse(BROUGHT_TO_FRONT);
        assertFalse(r.usable(), "63ms 被当成了一次冷启动");
        assertTrue(r.broughtToFront());
        assertTrue(r.problem().contains("force-stop"), r.problem());
    }

    /** 系统明说是热启动，就别当冷启动用 */
    @Test
    void 系统报告非COLD时拒绝() {
        ColdStartParser.Result r = ColdStartParser.parse("""
                Status: ok
                LaunchState: WARM
                TotalTime: 420
                """);
        assertFalse(r.usable());
        assertTrue(r.problem().contains("WARM"), r.problem());
    }

    /** 没有 LaunchState 的老设备不能因此被拒——那等于老机器永远测不了 */
    @Test
    void 老格式没有LaunchState也能用() {
        ColdStartParser.Result r = ColdStartParser.parse(OLD_FORMAT);
        assertTrue(r.usable(), String.valueOf(r.problem()));
        assertNull(r.launchState());
    }

    @Test
    void Activity写错要拒绝并说清楚() {
        ColdStartParser.Result r = ColdStartParser.parse(
                "Starting: Intent { cmp=com.x/.Wrong }\nError: Activity class {com.x/.Wrong} does not exist.\n");
        assertFalse(r.usable());
        assertTrue(r.problem().contains("does not exist"), r.problem());
    }

    @Test
    void Status非ok要拒绝() {
        ColdStartParser.Result r = ColdStartParser.parse("Status: timeout\nTotalTime: 9999\n");
        assertFalse(r.usable());
        assertTrue(r.problem().contains("timeout"), r.problem());
    }

    /** 命令被掐了/设备掉线：空输出不能变成 0ms */
    @Test
    void 空输出不能出数() {
        for (String s : Arrays.asList(null, "", "   ")) {
            ColdStartParser.Result r = ColdStartParser.parse(s);
            assertFalse(r.usable());
            assertNull(r.totalTimeMs());
        }
    }

    /** 格式不认识时宁可不出数，也不要蒙一个 */
    @Test
    void 没有TotalTime就拒绝() {
        ColdStartParser.Result r = ColdStartParser.parse("Status: ok\nWaitTime: 1200\n");
        assertFalse(r.usable());
        assertNull(r.totalTimeMs());
    }

    // ---------- 中位数 ----------

    /**
     * 中位数不是平均数。
     *
     * <p>启动耗时右偏：大多数落在窄带里，偶尔一次 GC 或后台任务拖到两三倍。
     * 平均会被那一轮拽走，于是「这版慢了 20%」可能只是当时手机在下载东西。
     */
    @Test
    void 离群的一轮拽不动中位数() {
        List<Integer> v = List.of(1800, 1850, 1820, 1790, 5200);
        assertEquals(1820.0, ColdStartParser.median(v));
        double avg = v.stream().mapToInt(Integer::intValue).average().orElse(0);
        assertTrue(avg > 2400, "平均数被拽到了 " + avg + "，这正是不用它的理由");
    }

    @Test
    void 偶数个取中间两个() {
        assertEquals(1825.0, ColdStartParser.median(List.of(1800, 1850, 1820, 1830)));
    }

    @Test
    void 一个样本和空列表() {
        assertEquals(1800.0, ColdStartParser.median(List.of(1800)));
        assertNull(ColdStartParser.median(List.of()));
        assertNull(ColdStartParser.median(null));
    }

    @Test
    void 列表里混了null不炸() {
        assertEquals(1800.0, ColdStartParser.median(Arrays.asList(1800, null)));
        assertNull(ColdStartParser.median(Arrays.asList((Integer) null, null)));
    }
}
