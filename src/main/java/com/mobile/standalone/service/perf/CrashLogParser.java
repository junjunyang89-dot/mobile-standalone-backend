package com.mobile.standalone.service.perf;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 logcat 里认出崩溃和 ANR。
 * <p>
 * 遍历器已经在真机上随机点了几十分钟，但<b>不抓崩溃的话，跑完只有一条资源曲线，
 * 崩了都不知道</b>——最该发现的问题恰恰是最容易被漏掉的那个。
 * <p>
 * 纯字符串解析，不碰进程也不碰设备，好测。
 */
public final class CrashLogParser {

    private CrashLogParser() {
    }

    /** 一次崩溃/ANR */
    @Data
    public static class Incident {
        /** CRASH | ANR | NATIVE_CRASH */
        private String type;
        /** 出问题的进程/包名 */
        private String process;
        /** 一行摘要，直接能贴进报告 */
        private String title;
        /** 堆栈前几行——定位到具体哪个类就够了，全量堆栈没人看 */
        private String stack;
        /** 原始日志里的行号，便于回查 */
        private int lineNo;
    }

    /** Java 崩溃：{@code FATAL EXCEPTION: main} 后面跟 Process: xxx，再往下是堆栈 */
    private static final Pattern FATAL = Pattern.compile("FATAL EXCEPTION");
    private static final Pattern PROCESS = Pattern.compile("Process:\\s*([\\w.:]+)");
    /** ANR：{@code ANR in com.xxx (com.xxx/.MainActivity)} */
    private static final Pattern ANR = Pattern.compile("ANR in ([\\w.:]+)");
    /** native 崩溃：tombstone 的特征行 */
    private static final Pattern NATIVE = Pattern.compile(
            "signal\\s+\\d+\\s+\\(SIG\\w+\\)|\\*\\*\\* \\*\\*\\* \\*\\*\\*");

    /** 堆栈最多留几行 */
    private static final int STACK_LINES = 8;

    /**
     * @param logcat    logcat 输出
     * @param packageName 只关心这个包的问题；为空则全收
     */
    public static List<Incident> parse(String logcat, String packageName) {
        List<Incident> out = new ArrayList<>();
        if (logcat == null || logcat.isBlank()) {
            return out;
        }
        String[] lines = logcat.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Incident hit = null;

            if (FATAL.matcher(line).find()) {
                hit = new Incident();
                hit.setType("CRASH");
                // Process: 在 FATAL EXCEPTION 的下一行，往后翻几行找
                hit.setProcess(lookupProcess(lines, i));
                hit.setTitle(firstCause(lines, i));
            } else if (NATIVE.matcher(line).find()) {
                hit = new Incident();
                hit.setType("NATIVE_CRASH");
                hit.setProcess(lookupProcess(lines, i));
                hit.setTitle("native 崩溃（tombstone）");
            } else {
                Matcher anr = ANR.matcher(line);
                if (anr.find()) {
                    hit = new Incident();
                    hit.setType("ANR");
                    hit.setProcess(anr.group(1));
                    hit.setTitle(trim(line));
                }
            }
            if (hit == null) {
                continue;
            }
            // 只留目标应用的。一次遍历里系统服务、输入法崩溃都很常见，
            // 全报出来会把自家的那条淹掉
            if (packageName != null && !packageName.isBlank()
                    && hit.getProcess() != null && !hit.getProcess().startsWith(packageName)) {
                continue;
            }
            hit.setLineNo(i + 1);
            hit.setStack(stackAfter(lines, i));
            out.add(hit);
        }
        return dedupe(out);
    }

    /** {@code Process: com.xxx, PID: 123} 通常在 FATAL EXCEPTION 后一两行 */
    private static String lookupProcess(String[] lines, int from) {
        for (int i = from; i < Math.min(lines.length, from + 4); i++) {
            Matcher m = PROCESS.matcher(lines[i]);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * 取第一条异常行作为标题，如 {@code java.lang.NullPointerException: ...}。
     * 这一行就是开发最需要看到的东西。
     */
    private static String firstCause(String[] lines, int from) {
        for (int i = from + 1; i < Math.min(lines.length, from + 6); i++) {
            String s = trim(lines[i]);
            if (s.matches("^(java|android|kotlin|com|org)\\.[\\w.$]+(Exception|Error)\\b.*")) {
                return s;
            }
        }
        return "应用崩溃";
    }

    private static String stackAfter(String[] lines, int from) {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = from + 1; i < lines.length && taken < STACK_LINES; i++) {
            String s = trim(lines[i]);
            if (s.isEmpty()) {
                continue;
            }
            sb.append(s).append('\n');
            taken++;
        }
        return sb.toString().trim();
    }

    private static final Pattern AM_KILL = Pattern.compile("am_kill\\s*:\\s*\\[\\d+,(\\d+),");
    private static final Pattern AM_PROC_DIED = Pattern.compile("am_proc_died\\s*:\\s*\\[\\d+,(\\d+),([^,\\]]+)");
    /** logcat 行首：日期 时间 pid tid 级别 tag: */
    private static final Pattern LINE_PID = Pattern.compile("^\\S+\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+\\w\\s+");

    /**
     * 主进程死了、却不是谁主动杀的。
     * <p>
     * 只认 FATAL EXCEPTION / tombstone 不够：应用集成的崩溃 SDK、WebView 的 crashpad 会把原生崩溃截走，
     * 系统里不留这两种痕迹，进程就这么没了。真机上实测过：WebView 渲染进程崩了没人处理，
     * 整个应用跟着退出，logcat 里只剩 {@code am_proc_died}。
     * <p>
     * force-stop / 系统按策略回收都会先打 {@code am_kill}，所以「死了但前面没有 am_kill」= 意外退出。
     *
     * @param events  events 缓冲区（必须按时间切过，events 不会被 logcat -c 清掉）
     * @param mainLog main 缓冲区，用来补一句死因
     */
    public static List<Incident> unexpectedDeaths(String events, String mainLog, String packageName) {
        List<Incident> out = new ArrayList<>();
        if (events == null || events.isBlank() || packageName == null || packageName.isBlank()) {
            return out;
        }
        java.util.Set<String> killed = new java.util.HashSet<>();
        Matcher k = AM_KILL.matcher(events);
        while (k.find()) {
            killed.add(k.group(1));
        }
        String[] lines = events.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher d = AM_PROC_DIED.matcher(lines[i]);
            if (!d.find() || !packageName.equals(d.group(2).trim()) || killed.contains(d.group(1))) {
                continue;
            }
            String pid = d.group(1);
            Incident in = new Incident();
            in.setType("PROCESS_DIED");
            in.setProcess(packageName);
            in.setLineNo(i + 1);
            if (webViewTookItDown(mainLog, pid)) {
                in.setTitle("WebView 渲染进程崩溃没人处理，连带整个应用退出（pid " + pid + "）");
                in.setStack("有 WebView 没处理 WebViewClient.onRenderProcessGone：渲染进程一崩（低内存被回收也算），"
                        + "系统就把整个应用杀掉。用户看到的是看网页时闪退。");
            } else {
                in.setTitle("应用进程意外退出（pid " + pid + "）");
                in.setStack("不是测试主动结束的（前面没有 am_kill）。可能是原生崩溃被应用内的崩溃 SDK 截走，"
                        + "也可能是被系统低内存回收。按时间去 logcat 里找 pid " + pid + " 附近的日志。");
            }
            out.add(in);
        }
        return out;
    }

    private static boolean webViewTookItDown(String mainLog, String pid) {
        if (mainLog == null) {
            return false;
        }
        for (String line : mainLog.split("\r?\n")) {
            if (line.contains("crash wasn't handled by all associated")) {
                Matcher m = LINE_PID.matcher(line);
                if (m.find() && m.group(1).equals(pid)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 同一次崩溃 logcat 里会重复出现（多个 buffer、系统再打一遍）。
     * 不去重的话一次崩溃报成五次，"崩溃次数"这个指标就没法用了——
     * 而它正是要拿去和上个版本比的。
     */
    private static List<Incident> dedupe(List<Incident> raw) {
        List<Incident> out = new ArrayList<>();
        for (Incident in : raw) {
            boolean dup = out.stream().anyMatch(x ->
                    x.getType().equals(in.getType())
                            && java.util.Objects.equals(x.getProcess(), in.getProcess())
                            && java.util.Objects.equals(x.getTitle(), in.getTitle()));
            if (!dup) {
                out.add(in);
            }
        }
        return out;
    }

    /** 去掉 logcat 的时间戳/tag 前缀，只留正文 */
    static String trim(String line) {
        if (line == null) {
            return "";
        }
        String s = line;
        // 08-08 17:41:20.123  1234  1234 E AndroidRuntime: 正文
        int idx = s.indexOf(": ");
        if (idx > 0 && idx < 80 && s.matches("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}.*")) {
            s = s.substring(idx + 2);
        }
        return s.trim();
    }
}
