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
}
