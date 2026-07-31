package com.mobile.standalone.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条测试结果：测项分类、测试项名称（检测规则）、测试结果、详细日志。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestResultItemDto {
    /** 测试项分类，如「登录」「OS兼容」「设备兼容」 */
    private String testItemCategory;
    /** 测试项名称（检测规则），如「输入手机号」「应用/元服务启动无兼容性问题」 */
    private String testItemName;
    /** 测试结果：通过、不通过、告警、设备依赖 等 */
    private String testResult;
    /** 详细日志链接或标识，用于「查看」；无则为 null */
    private String detailLogLink;
    /** 详细日志/变量展示文案（如脱敏手机号 138****8000、验证码 ****(6位)），用于检测规则表格「详细日志」列 */
    private String detailMessage;
}
