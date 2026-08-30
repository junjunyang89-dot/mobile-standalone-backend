package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口测试环境：TEST / PROD 各一条，提供 baseUrl 与默认鉴权头。
 */
@Data
public class ApiTestEnv {
    /** TEST | PROD */
    private String environment;
    private String name;
    private String baseUrl;
    /** JSON object，拼到每个请求上 */
    private String defaultHeaders;
    /** 默认 token；请求头 token / Authorization 可引用 */
    private String defaultToken;
    /**
     * 数据库断言用的只读连接。跟着环境走，和 {@code baseUrl} 一个逻辑——
     * 接口打预发、断言却查测试库的话，结论是反的。
     */
    private String dbHost;
    private Integer dbPort;
    private String dbName;
    private String dbUser;
    private String dbPassword;

    private Boolean enabled;
    private LocalDateTime updatedAt;
}
