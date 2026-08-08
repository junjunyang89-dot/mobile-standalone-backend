package com.mobile.standalone.service.perf;

/**
 * 性能指标采集引擎（ADB / SoloX）。
 * 会话型引擎（SoloX）在 {@link #startSession} / {@link #stopSession} 间读流式样本；
 * 点采型引擎（ADB）每次 {@link #sample} 拉一次。
 */
public interface PerformanceMetricCollector {

    /** ADB | SOLOX */
    String engineId();

    /** 本机/设备是否可用该引擎 */
    boolean available(String deviceId, String packageName);

    /**
     * 开始采集会话（SoloX 拉起 Python 子进程；ADB 可空实现）。
     */
    default void startSession(String deviceId, String packageName) {
        // no-op
    }

    /**
     * 采一点。会话型引擎可从缓冲取最新；点采型现场查 adb。
     */
    PerformanceMetricSample sample(String deviceId, String packageName);

    /**
     * 结束会话并释放资源。
     */
    default void stopSession() {
        // no-op
    }

    /** 按设备结束会话（SoloX 多设备并行时使用） */
    default void stopSession(String deviceId) {
        stopSession();
    }
}
