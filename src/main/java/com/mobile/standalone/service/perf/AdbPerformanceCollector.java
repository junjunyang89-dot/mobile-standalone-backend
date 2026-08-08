package com.mobile.standalone.service.perf;

import com.mobile.standalone.service.AndroidPerformanceCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 方案二：Java + ADB（包装 {@link AndroidPerformanceCollector}）。
 */
@Component
@RequiredArgsConstructor
public class AdbPerformanceCollector implements PerformanceMetricCollector {

    private final AndroidPerformanceCollector androidPerformanceCollector;

    @Override
    public String engineId() {
        return "ADB";
    }

    @Override
    public boolean available(String deviceId, String packageName) {
        return deviceId != null && !deviceId.isBlank()
                && packageName != null && !packageName.isBlank();
    }

    @Override
    public PerformanceMetricSample sample(String deviceId, String packageName) {
        AndroidPerformanceCollector.Sample s = androidPerformanceCollector.sample(deviceId, packageName);
        if (s == null) {
            return new PerformanceMetricSample(null, null, null, null, null, null, null);
        }
        return new PerformanceMetricSample(
                s.getCpuPercent(),
                s.getMemoryPssKb(),
                s.getNetworkRxBytes(),
                s.getNetworkTxBytes(),
                s.getTemperatureTenthsC(),
                s.getFps(),
                s.getJankPercent()
        );
    }
}
