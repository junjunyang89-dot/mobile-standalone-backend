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
    private Boolean enabled;
    private LocalDateTime updatedAt;
}
