package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 环境变量：用例里写 <code>{{name}}</code>，执行时换成这里的值。
 * <p>
 * 与 {@link ApiTestEnv#getBaseUrl()} 分开放：baseUrl 只有一个、且每个环境必须有，
 * 是环境的固有属性；变量是可增删的一堆 kv。混在一起就会出现两个地方能改 baseUrl。
 */
@Data
public class ApiTestVariable {
    /** TEST | PROD */
    private String environment;
    /** 变量名，用例里写 {{name}} */
    private String name;
    private String value;
    /**
     * 敏感值。为 true 时列表接口返回掩码，真值只在执行时从库里取——
     * 密钥落到前端就等于泄露，浏览器里随手一看就有。
     */
    private Boolean secret;
    /** 关掉相当于没配这个变量，用来临时试「不传会怎样」而不用删掉重配 */
    private Boolean enabled;
    private String description;
    private Integer sortOrder;
    private LocalDateTime updatedAt;
}
