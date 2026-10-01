package com.mobile.standalone.service.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 {@code adb shell am start -W} 的输出。
 *
 * <p><b>为什么冷启动值得单独做一个指标</b>：CPU、内存、FPS 这些人感觉不到，
 * 但「点开 App 多久能用」是用户唯一天天在感受的性能。
 * 而它恰好是最<b>不需要埋点</b>的一个——系统自己就把耗时算好了。
 *
 * <p>平台里 {@code MetricComparator} 一直认识 {@code coldStartMs}（有中文名、有单位、
 * 方向也判对了），但从来没有采集器产生过它。这个类补的就是那一端。
 *
 * <p><b>纯解析，不执行命令</b>，这样各种边界输出都能用单测钉住——
 * 而这些边界恰好是最容易把假数据放进报告的地方。
 */
public final class ColdStartParser {

    private ColdStartParser() {
    }

    private static final Pattern TOTAL = Pattern.compile("(?m)^\\s*TotalTime:\\s*(\\d+)");
    private static final Pattern THIS = Pattern.compile("(?m)^\\s*ThisTime:\\s*(\\d+)");
    private static final Pattern WAIT = Pattern.compile("(?m)^\\s*WaitTime:\\s*(\\d+)");
    private static final Pattern STATUS = Pattern.compile("(?m)^\\s*Status:\\s*(\\S+)");
    private static final Pattern LAUNCH_STATE = Pattern.compile("(?m)^\\s*LaunchState:\\s*(\\S+)");
    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^\\s*Error:\\s*(.+)$");

    /**
     * @param usable          这一轮能不能进中位数。<b>不能的原因一定写在 problem 里</b>
     * @param totalTimeMs     应用自己的启动耗时（整条 Activity 链路），取它当 coldStartMs
     * @param thisTimeMs      只有最后一个 Activity 的耗时
     * @param waitTimeMs      系统总耗时，含 AMS 调度开销
     * @param broughtToFront  应用本来就在运行，这次只是被切到前台——<b>这不是冷启动</b>
     */
    public record Result(boolean usable, Integer totalTimeMs, Integer thisTimeMs, Integer waitTimeMs,
                         String status, String launchState, boolean broughtToFront, String problem) {
    }

    /**
     * <p><b>为什么取 TotalTime 而不是 ThisTime</b>：你们的 Android 包有一个
     * {@code LauncherActivity} 闪屏页再进主页。{@code ThisTime} 只算链路上最后一个 Activity，
     * 会把闪屏那段漏掉，数字好看但不是用户等的那段时间。
     * {@code TotalTime} 算整条链路，且不含 AMS 调度开销，是三个里最贴近「应用自己有多慢」的。
     *
     * <p><b>这个数字量到哪为止，要说清楚</b>：它量到<b>第一帧画出来</b>，
     * 不是「首页数据加载完、能用了」。一个先画骨架屏再请求数据的版本，
     * 在这个指标上会显得很快。要量「真正能用」只能在代码里埋点——那是下一步的事，
     * 但别把这个数字当成那个数字。
     */
    public static Result parse(String output) {
        if (output == null || output.isBlank()) {
            return new Result(false, null, null, null, null, null, false,
                    "am start -W 没有任何输出（设备没连上或命令被掐了）");
        }

        Matcher err = ERROR_LINE.matcher(output);
        if (err.find()) {
            return new Result(false, null, null, null, null, null, false,
                    "am start 报错：" + err.group(1).trim());
        }

        // 应用本来就活着，am start 只是把它切到前台。
        //
        // 这一条是这个类里最要紧的判断：这种情况下 TotalTime 往往只有几十毫秒，
        // 看起来是一次「特别快的冷启动」。混进中位数会把整体数字拉低，
        // 于是「启动变快了」这个结论是假的 —— 而假的好消息没人会去核。
        boolean brought = output.contains("brought to the front")
                || output.contains("Activity not started, its current task has been brought to the front");

        String status = find(STATUS, output);
        String launchState = find(LAUNCH_STATE, output);
        Integer total = findInt(TOTAL, output);
        Integer thisT = findInt(THIS, output);
        Integer wait = findInt(WAIT, output);

        if (brought) {
            return new Result(false, total, thisT, wait, status, launchState, true,
                    "应用本来就在运行，这次只是切到前台，不是冷启动（force-stop 没生效）");
        }
        if (status != null && !"ok".equalsIgnoreCase(status)) {
            return new Result(false, total, thisT, wait, status, launchState, false,
                    "am start 返回 Status: " + status);
        }
        if (total == null) {
            return new Result(false, null, thisT, wait, status, launchState, false,
                    "输出里没有 TotalTime，这台设备的 am start -W 格式不认识");
        }
        // LaunchState 是 Android 10+ 才有的，没有不代表有问题；
        // 但如果它明说不是 COLD，那就是真不是，别当冷启动用
        if (launchState != null && !"COLD".equalsIgnoreCase(launchState)
                && !"UNKNOWN".equalsIgnoreCase(launchState)) {
            return new Result(false, total, thisT, wait, status, launchState, false,
                    "系统报告这次是 " + launchState + " 启动，不是冷启动");
        }
        return new Result(true, total, thisT, wait, status, launchState, false, null);
    }

    /**
     * 中位数，不是平均数。
     *
     * <p>启动耗时的分布是<b>右偏</b>的：绝大多数落在一个窄带里，偶尔来一次
     * GC 或后台任务把某一轮拖到两三倍。平均数会被那一轮拽走，
     * 于是「这版慢了 20%」可能只是当时手机在下载东西。中位数不受这种影响。
     *
     * <p>偶数个取中间两个的平均——反正到这一步离群值已经被挤到两端了。
     */
    public static Double median(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.removeIf(java.util.Objects::isNull);
        if (sorted.isEmpty()) {
            return null;
        }
        sorted.sort(Integer::compareTo);
        int n = sorted.size();
        if (n % 2 == 1) {
            return (double) sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }

    private static Integer findInt(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
