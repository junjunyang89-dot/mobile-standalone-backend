package com.mobile.standalone.service.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 iOS 的 {@code .ips} 崩溃日志。
 * <p>
 * iOS 跟 Android 完全是两套东西：
 * <ul>
 *   <li>没有 logcat，崩溃以文件形式躺在设备上，要用 {@code idevicecrashreport} 导出</li>
 *   <li><b>没有 ANR 这个概念</b>。对应的是主线程卡死被 watchdog 杀掉，
 *       特征是终止码 {@code 0x8badf00d}（"ate bad food"）。这才是 iOS 上和 ANR 等价的东西，
 *       不认它就等于 iOS 侧完全测不到卡死</li>
 *   <li>iOS 15+ 是 JSON 格式（两段：头一行 JSON 头 + 后面 JSON 体），老版本是纯文本。
 *       两种都得认——设备池里新旧机型混着</li>
 * </ul>
 */
public final class IosCrashParser {

    private IosCrashParser() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 主线程卡死被系统杀掉，等价于 Android 的 ANR */
    private static final String WATCHDOG = "8badf00d";

    // 老式纯文本格式的字段
    private static final Pattern P_PROCESS = Pattern.compile("^Process:\\s+(\\S+)", Pattern.MULTILINE);
    private static final Pattern P_IDENTIFIER = Pattern.compile("^Identifier:\\s+(\\S+)", Pattern.MULTILINE);
    private static final Pattern P_EXC_TYPE = Pattern.compile("^Exception Type:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern P_TERMINATION = Pattern.compile("^Termination Reason:\\s+(.+)$", Pattern.MULTILINE);

    private static final int STACK_LINES = 8;

    /**
     * @param content     一个 .ips 文件的全部内容
     * @param bundleId    只关心这个 App 的；为空则全收
     * @return 认不出或不是目标 App 时返回 null
     */
    public static CrashLogParser.Incident parse(String content, String bundleId) {
        if (content == null || content.isBlank()) {
            return null;
        }
        CrashLogParser.Incident hit = content.trim().startsWith("{")
                ? parseJson(content)
                : parseLegacy(content);
        if (hit == null) {
            return null;
        }
        // 设备上躺着的崩溃来自所有 App（微信、系统进程都有），不筛就会把别人的账算到自己头上
        if (bundleId != null && !bundleId.isBlank()
                && hit.getProcess() != null && !hit.getProcess().contains(bundleId)) {
            return null;
        }
        return hit;
    }

    /** iOS 15+ ：第一行是 JSON 头（app 信息），剩下是 JSON 体（异常与线程） */
    private static CrashLogParser.Incident parseJson(String content) {
        try {
            int nl = content.indexOf('\n');
            String headLine = nl > 0 ? content.substring(0, nl) : content;
            String bodyText = nl > 0 ? content.substring(nl + 1) : "{}";
            JsonNode head = JSON.readTree(headLine);
            JsonNode body;
            try {
                body = JSON.readTree(bodyText);
            } catch (Exception e) {
                body = JSON.createObjectNode();
            }

            CrashLogParser.Incident out = new CrashLogParser.Incident();
            out.setProcess(text(head, "bundleID", text(head, "app_name", null)));

            String excType = text(body.path("exception"), "type", null);
            String termCode = text(body.path("termination"), "code", null);
            String termIndicator = text(body.path("termination"), "indicator", null);
            String namespace = text(body.path("termination"), "namespace", null);

            boolean watchdog = isWatchdog(termCode) || isWatchdog(termIndicator)
                    || (namespace != null && namespace.toLowerCase().contains("watchdog"));

            out.setType(watchdog ? "WATCHDOG" : "CRASH");
            out.setTitle(watchdog
                    ? "主线程卡死被系统杀掉（watchdog 0x8badf00d）——iOS 上等价于 Android 的 ANR"
                    : buildTitle(excType, text(body.path("exception"), "signal", null), termIndicator));
            out.setStack(topFrames(body));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** iOS 14 及更早：纯文本 */
    private static CrashLogParser.Incident parseLegacy(String content) {
        Matcher proc = P_IDENTIFIER.matcher(content);
        String process = proc.find() ? proc.group(1) : null;
        if (process == null) {
            Matcher p2 = P_PROCESS.matcher(content);
            process = p2.find() ? p2.group(1) : null;
        }
        Matcher excM = P_EXC_TYPE.matcher(content);
        String exc = excM.find() ? excM.group(1).trim() : null;
        Matcher termM = P_TERMINATION.matcher(content);
        String term = termM.find() ? termM.group(1).trim() : null;

        if (process == null && exc == null) {
            return null;
        }
        boolean watchdog = isWatchdog(term) || isWatchdog(content);

        CrashLogParser.Incident out = new CrashLogParser.Incident();
        out.setProcess(process);
        out.setType(watchdog ? "WATCHDOG" : "CRASH");
        out.setTitle(watchdog
                ? "主线程卡死被系统杀掉（watchdog 0x8badf00d）——iOS 上等价于 Android 的 ANR"
                : buildTitle(exc, null, term));
        out.setStack(legacyTopFrames(content));
        return out;
    }

    private static boolean isWatchdog(String s) {
        return s != null && s.toLowerCase().contains(WATCHDOG);
    }

    private static String buildTitle(String excType, String signal, String termination) {
        StringBuilder sb = new StringBuilder();
        if (excType != null && !excType.isBlank()) {
            sb.append(excType.trim());
        }
        if (signal != null && !signal.isBlank()) {
            sb.append(sb.length() > 0 ? " / " : "").append(signal.trim());
        }
        if (termination != null && !termination.isBlank()) {
            sb.append(sb.length() > 0 ? " · " : "").append(termination.trim());
        }
        return sb.length() == 0 ? "应用崩溃" : sb.toString();
    }

    /** 只取崩溃线程的前几帧——定位到是哪个模块就够了，全量线程栈没人看 */
    private static String topFrames(JsonNode body) {
        StringBuilder sb = new StringBuilder();
        JsonNode threads = body.path("threads");
        if (!threads.isArray()) {
            return "";
        }
        JsonNode target = null;
        for (JsonNode t : threads) {
            if (t.path("triggered").asBoolean(false)) {
                target = t;
                break;
            }
        }
        if (target == null && threads.size() > 0) {
            target = threads.get(0);
        }
        if (target == null) {
            return "";
        }
        JsonNode images = body.path("usedImages");
        int taken = 0;
        for (JsonNode f : target.path("frames")) {
            if (taken >= STACK_LINES) {
                break;
            }
            String symbol = f.path("symbol").asText("");
            if (symbol.isBlank()) {
                int idx = f.path("imageIndex").asInt(-1);
                if (idx >= 0 && images.isArray() && idx < images.size()) {
                    symbol = images.get(idx).path("name").asText("");
                }
            }
            if (!symbol.isBlank()) {
                sb.append(symbol).append('\n');
                taken++;
            }
        }
        return sb.toString().trim();
    }

    private static String legacyTopFrames(String content) {
        List<String> out = new ArrayList<>();
        boolean inCrashed = false;
        for (String line : content.split("\r?\n")) {
            if (line.contains("Crashed:") || line.matches("^Thread \\d+:.*")) {
                inCrashed = line.contains("Crashed:");
                continue;
            }
            if (inCrashed && line.matches("^\\d+\\s+\\S+.*")) {
                out.add(line.trim());
                if (out.size() >= STACK_LINES) {
                    break;
                }
            }
        }
        return String.join("\n", out);
    }

    private static String text(JsonNode n, String field, String fallback) {
        if (n == null) {
            return fallback;
        }
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }
}
