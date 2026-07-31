package com.mobile.standalone.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单条执行日志：步骤名、级别、状态、说明、未定位到的元素、时间。
 * 用于前端「执行日志」展示，格式参考：时间 + Level(Info/Error) + 消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecutionLogEntryDto {
    /** 步骤名称，如「启动应用程序」「输入手机号」 */
    private String step;
    /** 日志级别：Info / Error，便于前端筛选与展示 */
    private String level;
    /** 状态：success / fail */
    private String status;
    /** 说明信息（可含变量脱敏值，如 应用包名:xxx、已输入:138****8000） */
    private String message;
    /** 未定位到时的元素定位符，便于排查 */
    private String selector;
    /** 时间（ISO 格式） */
    private LocalDateTime timestamp;
    /** 该步骤对应的截图文件路径（服务端保存用） */
    private String screenshotPath;
    /** 前端展示用：该步骤截图的请求地址（由接口层按 stepIndex 填充，不持久化） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String screenshotUrl;

    public static ExecutionLogEntryDto success(String step, String message) {
        return new ExecutionLogEntryDto(step, "Info", "success", message, null, LocalDateTime.now(), null, null);
    }

    public static ExecutionLogEntryDto fail(String step, String message, String selector) {
        return new ExecutionLogEntryDto(step, "Error", "fail", message, selector, LocalDateTime.now(), null, null);
    }
}
