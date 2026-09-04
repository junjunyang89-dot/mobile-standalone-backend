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
public class EnvVariable {
    /** TEST | PROD */
    private String environment;

    /**
     * 归属项目；<b>空串＝该环境下全局</b>。
     * <p>
     * 用空串而不是 null：它是主键 (environment, project, name) 的一部分，
     * MySQL 主键列不允许 NULL。没有这一维的话，全局 token 和某项目专属 token
     * 会主键冲突，等于这个功能加了也用不了。
     */
    private String project;
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
