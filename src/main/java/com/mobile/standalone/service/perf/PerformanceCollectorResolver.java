package com.mobile.standalone.service.perf;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Device;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 按平台 + config.collectorEngine 选择采集实现。
 * <ul>
 *   <li>Android：AUTO|ADB|SOLOX — AUTO 优先 SoloX，否则 ADB</li>
 *   <li>iOS：AUTO|TIDEVICE|NONE — AUTO/TIDEVICE 用 Tidevice，不可用则 NONE（无 ADB 等价兜底）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PerformanceCollectorResolver {

    private final AdbPerformanceCollector adbPerformanceCollector;
    private final SoloxPerformanceCollector soloxPerformanceCollector;
    private final TidevicePerformanceCollector tidevicePerformanceCollector;
    private final NoOpPerformanceCollector noOpPerformanceCollector;

    public record ResolvedCollector(PerformanceMetricCollector collector, String engineUsed, boolean fellBack) {}

    public ResolvedCollector resolve(String collectorEngine, Device.Platform platform,
                                     String deviceId, String packageName,
                                     List<ExecutionLogEntryDto> executionLog) {
        String pref = collectorEngine == null || collectorEngine.isBlank()
                ? "AUTO"
                : collectorEngine.trim().toUpperCase(Locale.ROOT);

        if (Device.usesIosAutomation(platform)) {
            return resolveIos(pref, deviceId, packageName, executionLog);
        }
        return resolveAndroid(pref, deviceId, packageName, executionLog);
    }

    /** 兼容旧调用：默认按 Android 解析 */
    public ResolvedCollector resolve(String collectorEngine, String deviceId, String packageName,
                                     List<ExecutionLogEntryDto> executionLog) {
        return resolve(collectorEngine, Device.Platform.ANDROID, deviceId, packageName, executionLog);
    }

    private ResolvedCollector resolveAndroid(String pref, String deviceId, String packageName,
                                             List<ExecutionLogEntryDto> executionLog) {
        if ("TIDEVICE".equals(pref) || "NONE".equals(pref)) {
            logEngine(executionLog, "Android 不支持 " + pref + "，改用 AUTO 逻辑");
            pref = "AUTO";
        }
        if ("ADB".equals(pref)) {
            logEngine(executionLog, "使用 ADB 采集引擎");
            return new ResolvedCollector(adbPerformanceCollector, "ADB", false);
        }
        if ("SOLOX".equals(pref)) {
            if (soloxPerformanceCollector.available(deviceId, packageName)) {
                logEngine(executionLog, "使用 SoloX 采集引擎（强制）");
                return new ResolvedCollector(soloxPerformanceCollector, "SOLOX", false);
            }
            logEngine(executionLog, "SoloX 不可用，强制模式仍回退 ADB");
            return new ResolvedCollector(adbPerformanceCollector, "ADB", true);
        }
        if (soloxPerformanceCollector.available(deviceId, packageName)) {
            logEngine(executionLog, "AUTO：检测到 SoloX，使用 SoloX 采集");
            return new ResolvedCollector(soloxPerformanceCollector, "SOLOX", false);
        }
        logEngine(executionLog, "AUTO：SoloX 不可用，回退 ADB 采集");
        return new ResolvedCollector(adbPerformanceCollector, "ADB", true);
    }

    private ResolvedCollector resolveIos(String pref, String deviceId, String packageName,
                                         List<ExecutionLogEntryDto> executionLog) {
        if ("ADB".equals(pref) || "SOLOX".equals(pref)) {
            logEngine(executionLog, "iOS 不支持 " + pref + "，改用 Tidevice/AUTO");
            pref = "AUTO";
        }
        if ("NONE".equals(pref)) {
            logEngine(executionLog, "使用 NONE（仅跑 UI，不采性能）");
            return new ResolvedCollector(noOpPerformanceCollector, "NONE", false);
        }
        boolean wantTidevice = "AUTO".equals(pref) || "TIDEVICE".equals(pref);
        if (wantTidevice && tidevicePerformanceCollector.available(deviceId, packageName)) {
            String msg = "TIDEVICE".equals(pref)
                    ? "使用 Tidevice 采集引擎（强制）"
                    : "AUTO：检测到 Tidevice，使用 Tidevice 采集（iOS 17+ 可能不稳定）";
            logEngine(executionLog, msg);
            return new ResolvedCollector(tidevicePerformanceCollector, "TIDEVICE", false);
        }
        String reason = "TIDEVICE".equals(pref)
                ? "Tidevice 不可用（未 pip 安装或 --ping 失败），回退 NONE：流程仍可跑，无实时曲线"
                : "AUTO：Tidevice 不可用，回退 NONE（iOS 无 ADB 等价兜底）";
        logEngine(executionLog, reason);
        return new ResolvedCollector(noOpPerformanceCollector, "NONE", true);
    }

    private static void logEngine(List<ExecutionLogEntryDto> executionLog, String msg) {
        if (executionLog != null) {
            executionLog.add(ExecutionLogEntryDto.success("采集引擎", msg));
        }
        log.info("[perf-collector] {}", msg);
    }
}
