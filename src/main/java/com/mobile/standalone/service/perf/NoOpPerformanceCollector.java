package com.mobile.standalone.service.perf;

import org.springframework.stereotype.Component;

/**
 * 无可用采集引擎时的空实现（iOS Tidevice 不可用时仍允许跑 UI 流程，曲线为空）。
 */
@Component
public class NoOpPerformanceCollector implements PerformanceMetricCollector {

    @Override
    public String engineId() {
        return "NONE";
    }

    @Override
    public boolean available(String deviceId, String packageName) {
        return true;
    }

    @Override
    public PerformanceMetricSample sample(String deviceId, String packageName) {
        return new PerformanceMetricSample(null, null, null, null, null, null, null);
    }
}
