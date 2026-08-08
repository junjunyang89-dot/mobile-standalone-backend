package com.mobile.standalone.service.perf;

/**
 * 统一性能采样点（ADB / SoloX 均映射到此结构，再写入 PerformanceTestResultDto.ResourceSample）。
 */
public record PerformanceMetricSample(
        Double cpuPercent,
        Long memoryPssKb,
        Long networkRxBytes,
        Long networkTxBytes,
        Integer temperatureTenthsC,
        Double fps,
        Double jankPercent
) {
    public boolean hasAnyMetric() {
        return cpuPercent != null || memoryPssKb != null
                || networkRxBytes != null || networkTxBytes != null
                || temperatureTenthsC != null || fps != null || jankPercent != null;
    }
}
