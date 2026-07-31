package com.mobile.standalone.service;

import com.mobile.standalone.dto.ExecutionLogEntryDto;

import java.util.List;

/**
 * 功能测试执行日志统一格式：与「性能测试」类似的头部 + 结尾汇总。
 * 功能就是功能、性能就是性能，日志风格一致便于前端展示与汇总。
 */
public final class FunctionalTestLogHelper {

    private FunctionalTestLogHelper() {}

    /**
     * 在日志列表头部插入功能基础质量测试的通用头部（与性能测试日志风格一致）：
     * - 功能基础质量测试开始执行
     * - 测试场景: xxx（如 设置测试、设置-我的账号、登录测试、启动应用程序）
     * - 测试应用: 包名
     * - 测试设备: 设备ID
     */
    public static void prependHeader(List<ExecutionLogEntryDto> executionLog, String scenarioName, String appPackage, String deviceId) {
        if (executionLog == null) return;
        String pkg = appPackage != null ? appPackage : "-";
        String dev = deviceId != null ? deviceId : "-";
        executionLog.add(0, ExecutionLogEntryDto.success("开始测试", "功能基础质量测试开始执行"));
        executionLog.add(1, ExecutionLogEntryDto.success("测试场景", scenarioName != null ? scenarioName : "-"));
        executionLog.add(2, ExecutionLogEntryDto.success("测试应用", pkg));
        executionLog.add(3, ExecutionLogEntryDto.success("测试设备", dev));
    }

    /**
     * 在日志列表末尾追加一条汇总：共 N 步，通过 M 步，失败 K 步（或「全部通过」）。
     */
    public static void appendSummary(List<ExecutionLogEntryDto> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) return;
        int total = executionLog.size();
        int passed = 0;
        int failed = 0;
        for (ExecutionLogEntryDto entry : executionLog) {
            if ("success".equalsIgnoreCase(entry.getStatus())) {
                passed++;
            } else if ("fail".equalsIgnoreCase(entry.getStatus())) {
                failed++;
            }
        }
        String summary = failed == 0
                ? "执行完成，共 " + total + " 步，全部通过"
                : "执行完成，共 " + total + " 步，通过 " + passed + " 步，失败 " + failed + " 步";
        executionLog.add(ExecutionLogEntryDto.success("汇总", summary));
    }
}
