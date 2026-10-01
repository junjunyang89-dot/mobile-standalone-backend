package com.mobile.standalone.service.perf;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Device;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 流程用例跑的时候顺带盯崩溃。
 * <p>
 * 点击测试的判据只有「点得动、元素在」。App 在后台崩了一次、被系统拉起来，
 * 流程照样能往下点，报告全绿——这正是「看起来没事」的那一类。
 * 流程每天本来就在跑，每一次顺带收一遍，不用写任何新用例。
 * <p>
 * 崩溃判任务失败：它是缺陷，不是风险，跟步骤断言失败同一个性质。
 * ANR 只记红不判失败：它跟这台手机当时忙不忙有关，拿它翻状态会冒出一批时有时无的失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowCrashGuard {

    static final String STEP = "崩溃采集";
    public static final String CRASH_STEP = "应用崩溃";
    public static final String ANR_STEP = "ANR（应用无响应）";
    public static final String DIED_STEP = "应用进程意外退出";

    private final CrashMonitor crashMonitor;

    /**
     * @param skipReason 非空表示这次不采；收尾时会原样写进报告
     */
    public record Watch(String deviceId, String packageName, String since, String skipReason) {
    }

    public Watch begin(Device device, String platform, String packageName) {
        if (device == null || device.getId() == null) {
            return skip("没有设备信息");
        }
        String p = platform == null ? "" : platform.toUpperCase(Locale.ROOT);
        if (!p.contains("ANDROID")) {
            // iOS 的采集要先把设备上的崩溃文件搬走，一次最多 60 秒，挂在每条流程上太贵
            return skip((p.contains("IOS") ? "iOS" : p.contains("HARMONY") ? "鸿蒙" : p) + " 流程暂未接入");
        }
        if (packageName == null || packageName.isBlank()) {
            return skip("不知道被测包名");
        }
        String since = crashMonitor.mark(device.getId());
        if (since == null) {
            // 读不到设备时间只能退回清缓冲，否则会把上一轮的崩溃算到这一轮头上
            crashMonitor.start(device.getId());
        }
        return new Watch(device.getId(), packageName.trim(), since, null);
    }

    public List<CrashLogParser.Incident> finish(Watch watch, List<ExecutionLogEntryDto> logs) {
        if (watch == null) {
            return List.of();
        }
        if (watch.skipReason() != null) {
            // 必须写出来：报告里什么都没有，人会读成「没崩」
            logs.add(ExecutionLogEntryDto.success(STEP, "本次未采集：" + watch.skipReason() + "（不代表没有崩溃）"));
            return List.of();
        }
        try {
            List<CrashLogParser.Incident> incidents =
                    crashMonitor.collectSince(watch.deviceId(), watch.packageName(), watch.since());
            appendTo(logs, incidents);
            return incidents;
        } catch (Exception e) {
            log.warn("[flow-crash] 采集失败 device={}: {}", watch.deviceId(), e.getMessage());
            logs.add(ExecutionLogEntryDto.success(STEP, "本次未采集：读取 logcat 失败（不代表没有崩溃）"));
            return List.of();
        }
    }

    static void appendTo(List<ExecutionLogEntryDto> logs, List<CrashLogParser.Incident> incidents) {
        if (incidents.isEmpty()) {
            logs.add(ExecutionLogEntryDto.success(STEP, "运行期间未发现崩溃 / ANR"));
            return;
        }
        for (CrashLogParser.Incident i : incidents) {
            String what = switch (i.getType() == null ? "" : i.getType()) {
                case "ANR" -> ANR_STEP;
                case "PROCESS_DIED" -> DIED_STEP;
                default -> CRASH_STEP;
            };
            logs.add(ExecutionLogEntryDto.fail(what,
                    i.getTitle() + (i.getProcess() == null ? "" : "（" + i.getProcess() + "）"), null));
            if (i.getStack() != null && !i.getStack().isBlank()) {
                String s = i.getStack();
                logs.add(ExecutionLogEntryDto.success("堆栈", s.length() > 600 ? s.substring(0, 600) + "…" : s));
            }
        }
    }

    /** 有崩溃返回判失败的原因；只有 ANR 或什么都没有返回 null */
    public static String failureMessage(List<CrashLogParser.Incident> incidents) {
        if (incidents == null) {
            return null;
        }
        List<CrashLogParser.Incident> crashes = incidents.stream()
                .filter(i -> !"ANR".equals(i.getType()))
                .toList();
        if (crashes.isEmpty()) {
            return null;
        }
        return "流程步骤都走完了，但运行期间应用崩溃 / 意外退出 " + crashes.size() + " 次："
                + crashes.get(0).getTitle();
    }

    /**
     * 这条日志是收尾时追加的崩溃记录，不是某个步骤失败。
     * 归因要跳过它找真正失败的那一步，否则会围着日志末尾取截图和上下文。
     */
    public static boolean isCrashEntry(ExecutionLogEntryDto e) {
        return e != null && (CRASH_STEP.equals(e.getStep()) || ANR_STEP.equals(e.getStep())
                || DIED_STEP.equals(e.getStep()));
    }

    public static boolean appCrashed(List<ExecutionLogEntryDto> logs) {
        return logs != null && logs.stream().anyMatch(e -> e != null
                && (CRASH_STEP.equals(e.getStep()) || DIED_STEP.equals(e.getStep())));
    }

    private static Watch skip(String why) {
        return new Watch(null, null, null, why);
    }
}
