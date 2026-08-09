package com.mobile.standalone.service.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * iOS {@code .ips} 崩溃日志解析。
 * <p>
 * 最要紧的是 <b>watchdog（0x8badf00d）</b>：iOS 没有 ANR，主线程卡死表现为被系统杀掉。
 * 认不出它就等于 iOS 侧完全测不到卡死——而卡死恰恰是稳定性测试最该抓的一类问题。
 * <p>
 * 其次是新旧两种格式都要认：iOS 15+ 是 JSON，更早是纯文本，设备池里新旧机型是混着的。
 */
class IosCrashParserTest {

    private static final String BUNDLE = "com.pwrd.steam.esports";

    /** iOS 15+ ：第一行 JSON 头，后面 JSON 体 */
    private static final String JSON_CRASH = """
            {"app_name":"完美世界电竞","bundleID":"com.pwrd.steam.esports","incident_id":"ABC-123"}
            {"exception":{"type":"EXC_BAD_ACCESS","signal":"SIGSEGV"},
             "termination":{"code":1,"namespace":"SIGNAL","indicator":"Segmentation fault: 11"},
             "usedImages":[{"name":"WMPVP"}],
             "threads":[{"triggered":true,"frames":[
               {"symbol":"-[LoginViewController viewDidLoad]"},
               {"symbol":"UIViewController loadViewIfRequired"}]}]}
            """;

    private static final String JSON_WATCHDOG = """
            {"app_name":"完美世界电竞","bundleID":"com.pwrd.steam.esports"}
            {"exception":{"type":"EXC_CRASH","signal":"SIGKILL"},
             "termination":{"code":2343432205,"namespace":"WATCHDOG","indicator":"0x8badf00d"},
             "threads":[{"triggered":true,"frames":[{"symbol":"mach_msg_trap"}]}]}
            """;

    private static final String LEGACY = """
            Incident Identifier: 1234-5678
            Process:         WMPVP [456]
            Identifier:      com.pwrd.steam.esports
            Exception Type:  EXC_BAD_ACCESS (SIGSEGV)
            Exception Codes: KERN_INVALID_ADDRESS at 0x0000000000000010

            Thread 0 Crashed:
            0   WMPVP                     0x0000000102a4c5d8 -[LoginVC onTap:] + 120
            1   UIKitCore                 0x00000001a5b2c1f4 -[UIControl sendAction:] + 96
            """;

    // ==================== JSON 格式 ====================

    @Test
    void 认出JSON格式的崩溃并带上异常类型() {
        CrashLogParser.Incident c = IosCrashParser.parse(JSON_CRASH, BUNDLE);

        assertNotNull(c);
        assertEquals("CRASH", c.getType());
        assertEquals(BUNDLE, c.getProcess());
        assertTrue(c.getTitle().contains("EXC_BAD_ACCESS"), c.getTitle());
        assertTrue(c.getTitle().contains("SIGSEGV"), c.getTitle());
    }

    @Test
    void JSON格式带出崩溃线程的栈() {
        CrashLogParser.Incident c = IosCrashParser.parse(JSON_CRASH, BUNDLE);

        assertTrue(c.getStack().contains("LoginViewController"), c.getStack());
    }

    /**
     * 本组核心。iOS 没有 ANR，主线程卡死是被 watchdog 杀掉、终止码 0x8badf00d。
     * 不单独认出来的话，它只会显示成一条普通的 SIGKILL 崩溃，
     * 没人会意识到「这其实是卡死」——而卡死和崩溃要查的方向完全不同。
     */
    @Test
    void 认出watchdog卡死而不是当成普通崩溃() {
        CrashLogParser.Incident c = IosCrashParser.parse(JSON_WATCHDOG, BUNDLE);

        assertNotNull(c);
        assertEquals("WATCHDOG", c.getType());
        assertTrue(c.getTitle().contains("卡死"), "要说人话，不能只丢一串 0x8badf00d：" + c.getTitle());
        assertTrue(c.getTitle().contains("ANR"), "点明它等价于 Android 的 ANR：" + c.getTitle());
    }

    // ==================== 老式纯文本 ====================

    @Test
    void 认出老式纯文本崩溃() {
        CrashLogParser.Incident c = IosCrashParser.parse(LEGACY, BUNDLE);

        assertNotNull(c);
        assertEquals("CRASH", c.getType());
        assertEquals(BUNDLE, c.getProcess());
        assertTrue(c.getTitle().contains("EXC_BAD_ACCESS"), c.getTitle());
    }

    @Test
    void 老式格式也带栈() {
        CrashLogParser.Incident c = IosCrashParser.parse(LEGACY, BUNDLE);

        assertTrue(c.getStack().contains("LoginVC"), c.getStack());
    }

    @Test
    void 老式格式的watchdog也认() {
        String s = LEGACY.replace("Exception Type:  EXC_BAD_ACCESS (SIGSEGV)",
                "Exception Type:  EXC_CRASH (SIGKILL)\nTermination Reason: WATCHDOG 0x8badf00d");

        assertEquals("WATCHDOG", IosCrashParser.parse(s, BUNDLE).getType());
    }

    // ==================== 过滤与边界 ====================

    /** 设备上躺着的崩溃来自所有 App，不筛就把别人的账算到自己头上 */
    @Test
    void 别的App的崩溃不算() {
        String other = JSON_CRASH.replace(BUNDLE, "com.tencent.xin");

        assertNull(IosCrashParser.parse(other, BUNDLE));
        assertNotNull(IosCrashParser.parse(other, null), "不传 bundleId 时全收");
    }

    @Test
    void 空输入和垃圾内容不抛() {
        assertNull(IosCrashParser.parse(null, BUNDLE));
        assertNull(IosCrashParser.parse("", BUNDLE));
        assertNull(IosCrashParser.parse("这不是崩溃日志", BUNDLE));
    }

    /** JSON 体损坏（导出被截断）时，至少还能从头部认出是哪个 App 崩了 */
    @Test
    void JSON体损坏时仍能认出应用() {
        String broken = "{\"bundleID\":\"" + BUNDLE + "\"}\n{不是合法 JSON";

        CrashLogParser.Incident c = IosCrashParser.parse(broken, BUNDLE);

        assertNotNull(c, "半截日志也比完全丢掉强——至少知道它崩过");
        assertEquals(BUNDLE, c.getProcess());
    }
}
