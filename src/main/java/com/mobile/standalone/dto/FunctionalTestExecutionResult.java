package com.mobile.standalone.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 功能体验测试执行结果：截图路径 + 执行日志列表 + 执行变量（脱敏）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FunctionalTestExecutionResult {
    /** 截图保存路径 */
    private String reportPath;
    /** 执行步骤日志（按顺序） */
    private List<ExecutionLogEntryDto> executionLog;
    /** 执行变量脱敏值，如 phoneNumberMasked、verificationCodeMasked，供前端在检测规则「详细日志」列展示 */
    private Map<String, String> executionVariables;
    /**
     * 流程提前返回时的判定：FAIL / PASS / SKIP；跑完全部步骤为 null。
     * <p>
     * 没有这个字段时，「元素没出现所以提前退出」和「全部步骤跑完」在外面看起来一模一样，
     * 第 2 步就退出的流程也会被记成通过。
     */
    private String earlyReturnOutcome;
    /** 提前返回的提示文案，用于任务/回归报告展示原因 */
    private String earlyReturnMessage;

    /** MEMORY_CHECKPOINT 打下的内存点（PSS KB），供内存泄漏分析用；没打点则为空 */
    private List<Long> memoryCheckpoints;

    public FunctionalTestExecutionResult(String reportPath, List<ExecutionLogEntryDto> executionLog,
                                         Map<String, String> executionVariables) {
        this.reportPath = reportPath;
        this.executionLog = executionLog;
        this.executionVariables = executionVariables;
    }
}
