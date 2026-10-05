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
     * 老的「一个环境一个库」配置，只留着给启动时迁移读。
     * 现在数据库断言的连接在 {@link ApiDbConnection}（环境 × 项目），迁完这几列会被清空。
     */
    private String dbHost;
    private Integer dbPort;
    private String dbName;
    private String dbUser;
    private String dbPassword;

    private Boolean enabled;
    private LocalDateTime updatedAt;
}
