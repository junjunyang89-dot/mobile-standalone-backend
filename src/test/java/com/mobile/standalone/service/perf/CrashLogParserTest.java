package com.mobile.standalone.service.perf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从 logcat 认崩溃 / ANR。
 * <p>
 * 两条最要紧：<b>去重</b>和<b>只留自家包</b>。
 * 一次崩溃报成五次，「崩溃次数」这个指标就没法拿去跟上个版本比；
 * 把输入法、系统服务的崩溃也算进来，自家那条会被淹掉。
 */
class CrashLogParserTest {

    private static final String PKG = "com.perfectworld.esports";

    private static final String JAVA_CRASH = """
            08-08 17:41:20.100  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
            08-08 17:41:20.100  1234  1234 E AndroidRuntime: Process: com.perfectworld.esports, PID: 1234
            08-08 17:41:20.100  1234  1234 E AndroidRuntime: java.lang.NullPointerException: Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference
            08-08 17:41:20.100  1234  1234 E AndroidRuntime: \tat com.perfectworld.esports.login.LoginActivity.onCreate(LoginActivity.java:88)
            08-08 17:41:20.100  1234  1234 E AndroidRuntime: \tat android.app.Activity.performCreate(Activity.java:8000)
            """;

    @Test
    void 认出Java崩溃并带上异常类型() {
        List<CrashLogParser.Incident> out = CrashLogParser.parse(JAVA_CRASH, PKG);

        assertEquals(1, out.size());
        CrashLogParser.Incident c = out.get(0);
        assertEquals("CRASH", c.getType());
        assertEquals(PKG, c.getProcess());
        assertTrue(c.getTitle().startsWith("java.lang.NullPointerException"),
                "标题要是那条异常——开发要看的就是它：" + c.getTitle());
    }

    @Test
    void 堆栈里带上出问题的类() {
        CrashLogParser.Incident c = CrashLogParser.parse(JAVA_CRASH, PKG).get(0);

        assertTrue(c.getStack().contains("LoginActivity.java:88"),
                "定位到具体哪一行才有用：" + c.getStack());
    }

    @Test
    void 认出ANR() {
        String log = "08-08 17:42:00.000  900  900 E ActivityManager: ANR in com.perfectworld.esports "
                + "(com.perfectworld.esports/.MainActivity)";

        List<CrashLogParser.Incident> out = CrashLogParser.parse(log, PKG);

        assertEquals(1, out.size());
        assertEquals("ANR", out.get(0).getType());
        assertEquals(PKG, out.get(0).getProcess());
    }

    @Test
    void 认出native崩溃() {
        String log = """
                08-08 17:43:00.000  1234 1234 F DEBUG   : *** *** *** *** *** *** ***
                08-08 17:43:00.000  1234 1234 F DEBUG   : Process: com.perfectworld.esports
                08-08 17:43:00.000  1234 1234 F DEBUG   : signal 11 (SIGSEGV)
                """;

        List<CrashLogParser.Incident> out = CrashLogParser.parse(log, PKG);

        assertTrue(out.stream().anyMatch(i -> "NATIVE_CRASH".equals(i.getType())), out.toString());
    }

    /**
     * 本组核心之一。同一次崩溃 logcat 里会重复出现（多个 buffer、系统再打一遍），
     * 不去重的话一次崩溃报成五次，「崩溃次数」这个指标就废了。
     */
    @Test
    void 同一次崩溃只算一次() {
        List<CrashLogParser.Incident> out = CrashLogParser.parse(JAVA_CRASH + JAVA_CRASH + JAVA_CRASH, PKG);

        assertEquals(1, out.size(), "重复日志算成三次崩溃，就没法跟上个版本比了");
    }

    /**
     * 本组核心之二。一次遍历里输入法、系统服务崩溃很常见，
     * 全报出来会把自家那条淹掉。
     */
    @Test
    void 只留目标应用的崩溃() {
        String other = """
                08-08 17:44:00.000  555  555 E AndroidRuntime: FATAL EXCEPTION: main
                08-08 17:44:00.000  555  555 E AndroidRuntime: Process: com.android.inputmethod, PID: 555
                08-08 17:44:00.000  555  555 E AndroidRuntime: java.lang.IllegalStateException: boom
                """;

        assertTrue(CrashLogParser.parse(other, PKG).isEmpty(), "输入法崩溃不是本次要测的东西");
        assertEquals(1, CrashLogParser.parse(other + JAVA_CRASH, PKG).size());
    }

    /** 不传包名时全收——单独看某台设备整体状况时会这么用 */
    @Test
    void 不传包名时全收() {
        assertEquals(1, CrashLogParser.parse(JAVA_CRASH, null).size());
    }

    /** 同一个包的不同崩溃要分别记，别被去重误伤 */
    @Test
    void 不同异常算两次() {
        String second = JAVA_CRASH.replace("NullPointerException", "IllegalArgumentException");

        assertEquals(2, CrashLogParser.parse(JAVA_CRASH + second, PKG).size());
    }

    @Test
    void 空输入不抛() {
        assertTrue(CrashLogParser.parse(null, PKG).isEmpty());
        assertTrue(CrashLogParser.parse("", PKG).isEmpty());
        assertTrue(CrashLogParser.parse("一切正常\n没有崩溃", PKG).isEmpty());
    }

    /** 下面三段取自小米 10 真机：am crash 打到 WebView 渲染进程后，整个应用跟着退出 */
    private static final String STEAM = "com.pwrd.steam.esports";
    private static final String EVENTS_WEBVIEW_DEATH = """
            09-29 19:21:22.008  2068  4411 I am_proc_died: [0,29282,com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0,0,12]
            09-29 19:21:26.201  2068  4903 I am_proc_died: [0,28817,com.pwrd.steam.esports,0,12]
            """;
    private static final String MAIN_WEBVIEW_DEATH = """
            09-29 19:21:22.102 28817 28817 F chromium: [FATAL:crashpad_client_linux.cc(732)] Render process (29282)'s crash wasn't handled by all associated  webviews, triggering application crash.
            """;
    private static final String EVENTS_FORCE_STOP = """
            09-29 19:22:16.605  2068  2621 I am_kill : [0,10203,com.pwrd.steam.esports,0,stop com.pwrd.steam.esports due to from process:11078]
            09-29 19:22:16.700  2068  2621 I am_proc_died: [0,10203,com.pwrd.steam.esports,0,12]
            """;

    @Test
    void 崩溃被SDK截走时_靠进程死亡认出来_并点明是WebView连带() {
        List<CrashLogParser.Incident> out =
                CrashLogParser.unexpectedDeaths(EVENTS_WEBVIEW_DEATH, MAIN_WEBVIEW_DEATH, STEAM);

        assertEquals(1, out.size(), "渲染进程自己的死亡不算，只算主进程");
        assertEquals("PROCESS_DIED", out.get(0).getType());
        assertTrue(out.get(0).getTitle().contains("WebView 渲染进程崩溃没人处理"), out.get(0).getTitle());
        assertTrue(out.get(0).getTitle().contains("28817"));
    }

    @Test
    void 没有WebView线索时只说意外退出() {
        List<CrashLogParser.Incident> out = CrashLogParser.unexpectedDeaths(EVENTS_WEBVIEW_DEATH, "", STEAM);

        assertTrue(out.get(0).getTitle().startsWith("应用进程意外退出"), out.get(0).getTitle());
    }

    @Test
    void 测试主动force_stop的不算意外退出() {
        assertTrue(CrashLogParser.unexpectedDeaths(EVENTS_FORCE_STOP, "", STEAM).isEmpty());
    }

    @Test
    void 子进程死亡不算主进程退出() {
        String sub = "09-29 19:21:26.201  2068  4903 I am_proc_died: [0,5555,com.pwrd.steam.esports:push,0,12]";

        assertTrue(CrashLogParser.unexpectedDeaths(sub, "", STEAM).isEmpty());
    }

    @Test
    void 去掉logcat前缀只留正文() {
        assertEquals("FATAL EXCEPTION: main",
                CrashLogParser.trim("08-08 17:41:20.100  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main"));
        assertEquals("已经是正文", CrashLogParser.trim("已经是正文"));
    }
}
