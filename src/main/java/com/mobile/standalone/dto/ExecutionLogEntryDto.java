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
    /**
     * 该步骤引用的元素 key（ui_elements.name_en），如 login_phone_input。
     * <p>
     * selector 只有定位串，而同一串可能被多个元素行共用，也可能因为随机下标展开过而对不上原值——
     * 事后要判断「是元素定位失效还是功能真坏了」，得靠人回去翻流程才知道说的是哪一行。
     * 记下 key 之后可以直接回表取定位与备用定位。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String elementKey;
    /** 时间（ISO 格式） */
    private LocalDateTime timestamp;
    /** 该步骤对应的截图文件路径（服务端保存用） */
    private String screenshotPath;
    /** 前端展示用：该步骤截图的请求地址（由接口层按 stepIndex 填充，不持久化） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String screenshotUrl;
    /** 扫码安装页 URL（QR_ASSIST），前端据此生成二维码 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String qrPayload;
    /**
     * 该截图是否为「页面稳定后」拍的最终态。
     * <p>
     * 定时连拍会拍到 loading 态和转场动画中间帧，人眼能一扫而过，但交给 AI 做结果比对时
     * 这些噪声图会造成误判。带此标记的才是这一步的确定结果，供人工/AI 复核时优先看。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean screenshotSettled;

    public static ExecutionLogEntryDto success(String step, String message) {
        return new ExecutionLogEntryDto(step, "Info", "success", message, null, null, LocalDateTime.now(),
                null, null, null, null);
    }

    /**
     * 中立事件：既不是通过也不是失败。
     * <p>
     * 用户点停止、重试前的那次失败尝试，这些都不该算进不通过——
     * 否则人为中断会拉低达标率，重试成功的步骤也会同时留下一条红。
     * 报告里归入「告警」，看得见但不计入失败。
     */
    public static ExecutionLogEntryDto neutral(String step, String message) {
        return new ExecutionLogEntryDto(step, "Warn", "neutral", message, null, null, LocalDateTime.now(),
                null, null, null, null);
    }

    public static ExecutionLogEntryDto fail(String step, String message, String selector) {
        return fail(step, message, selector, null);
    }

    public static ExecutionLogEntryDto fail(String step, String message, String selector, String elementKey) {
        return new ExecutionLogEntryDto(step, "Error", "fail", message, selector, elementKey, LocalDateTime.now(),
                null, null, null, null);
    }
}
